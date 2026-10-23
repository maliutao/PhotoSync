package com.malt.photosync.net

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/** 局域网自动发现：监听电脑端接收软件的 UDP 广播（docs/PROTOCOL.md） */
object PcDiscovery {

    private const val PORT = 52000
    private const val DEFAULT_TIMEOUT_MS = 3500L

    data class PcInfo(
        val ip: String,
        val hostname: String,
        val port: Int,
        val devices: Map<String, String>      // 设备名 → 令牌
    )

    /**
     * 阻塞监听广播约 [timeoutMs] 毫秒，返回发现的电脑列表（按 IP 去重）。
     * 必须在 IO 线程调用。
     */
    fun discover(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<PcInfo> {
        // 持有 MulticastLock，防止 WiFi 驱动过滤广播/组播包（失败则降级为直接监听）
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("photosync:discover")?.run {
            try {
                setReferenceCounted(false)
                acquire()
                this
            } catch (e: SecurityException) {
                null
            }
        }
        val found = LinkedHashMap<String, PcInfo>()
        try {
            DatagramSocket(PORT).use { s ->
                s.soTimeout = 500
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    try {
                        val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        if (o.optString("app") != "photo-sync-receiver") continue
                        val ip = p.address.hostAddress ?: continue
                        val devices = mutableMapOf<String, String>()
                        val dev = o.optJSONObject("devices")
                        dev?.keys()?.forEach { k -> devices[k] = dev.optString(k) }
                        found[ip] = PcInfo(
                            ip, o.optString("hostname", ip), o.optInt("port", 8765), devices
                        )
                    } catch (e: Exception) {
                        // 忽略无法解析的包
                    }
                }
            }
        } catch (e: Exception) {
            // 端口被占用等异常：返回已发现的部分
        } finally {
            lock?.release()
        }
        return found.values.toList()
    }
}
