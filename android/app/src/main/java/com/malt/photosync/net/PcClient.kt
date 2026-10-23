package com.malt.photosync.net

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 与电脑端接收软件的 HTTP 通信（docs/PROTOCOL.md） */
class PcClient(host: String, port: Int, private val token: String) {

    enum class PingResult { OK, BAD_TOKEN, UNREACHABLE }

    data class StatusResult(val exists: Boolean, val free: Long, val reserve: Long)

    sealed class UploadResult {
        object OK : UploadResult()
        object NO_SPACE : UploadResult()
        object BAD_TOKEN : UploadResult()
        object NET_ERROR : UploadResult()
        data class ERROR(val msg: String) : UploadResult()
    }

    private val base: HttpUrl = HttpUrl.Builder()
        .scheme("http").host(host).port(port).build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)      // > 服务端 30s 接收超时
        .callTimeout(0, TimeUnit.MILLISECONDS)   // 大文件总耗时不可限
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    private var currentCall: Call? = null

    /** 停止时立即打断当前上传（对端会因此删除 .part） */
    fun cancelCurrent() {
        currentCall?.cancel()
    }

    private fun url(path: String, params: Map<String, String>): HttpUrl =
        base.newBuilder().addPathSegments(path)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    suspend fun ping(): PingResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url("ping", emptyMap()))
                .header("X-Token", token).build()
            client.newCall(req).execute().use {
                when (it.code) {
                    200 -> PingResult.OK
                    401 -> PingResult.BAD_TOKEN
                    else -> PingResult.UNREACHABLE
                }
            }
        } catch (e: IOException) {
            PingResult.UNREACHABLE
        }
    }

    suspend fun status(key: String, size: Long, mid: Long): StatusResult? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url("status", mapOf(
                        "key" to key, "size" to size.toString(), "mid" to mid.toString()
                    )))
                    .header("X-Token", token).build()
                client.newCall(req).execute().use { r ->
                    if (r.code != 200) return@use null
                    val o = JSONObject(r.body?.string() ?: "{}")
                    StatusResult(
                        o.optBoolean("exists", false),
                        o.optLong("free", -1),
                        o.optLong("reserve", 0)
                    )
                }
            } catch (e: IOException) {
                null
            }
        }

    /**
     * 流式上传：512KB 分块从 ContentResolver 读一块发一块，不把整个文件读入内存。
     * onProgress 在 OkHttp 写出线程回调；抛 IOException 可中止本次上传。
     */
    suspend fun upload(
        key: String,
        total: Long,
        mid: Long,
        uri: Uri,
        resolver: ContentResolver,
        onProgress: (written: Long) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = total

            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { ins ->
                    val buf = ByteArray(512 * 1024)
                    var written = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                    if (written != total) {
                        // 文件在上传过程中被修改（PRD §7）：放弃本次
                        throw IOException("size changed during upload: $written != $total")
                    }
                } ?: throw IOException("cannot open $uri")
            }
        }
        val req = Request.Builder()
            .url(url("upload", mapOf(
                "key" to key, "total" to total.toString(), "mid" to mid.toString()
            )))
            .header("X-Token", token).put(body).build()
        val call = client.newCall(req)
        currentCall = call
        try {
            call.execute().use { r ->
                when (r.code) {
                    200 -> UploadResult.OK
                    507 -> UploadResult.NO_SPACE
                    401 -> UploadResult.BAD_TOKEN
                    else -> UploadResult.ERROR("HTTP ${r.code}")
                }
            }
        } catch (e: IOException) {
            UploadResult.NET_ERROR
        } finally {
            currentCall = null
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
