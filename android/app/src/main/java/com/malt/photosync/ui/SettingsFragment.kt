package com.malt.photosync.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.malt.photosync.MainActivity
import com.malt.photosync.data.ConfigStore
import com.malt.photosync.databinding.FragmentSettingsBinding
import com.malt.photosync.net.PcClient
import com.malt.photosync.net.PcDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private lateinit var cfg: ConfigStore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cfg = ConfigStore(requireContext())
        b.hostInput.setText(cfg.host)
        b.portInput.setText(if (cfg.port == 0) "8765" else cfg.port.toString())
        b.tokenInput.setText(cfg.token)

        b.btnSave.setOnClickListener { saveInputs() }
        b.btnTest.setOnClickListener { testConnection() }
        b.btnScan.setOnClickListener { scanForPcs() }
        b.btnAlbums.setOnClickListener {
            saveInputs()
            (activity as? MainActivity)?.openAlbums()
        }
        renderBucketSummary()
    }

    override fun onResume() {
        super.onResume()
        renderBucketSummary()      // 从相册选择页返回后刷新
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun saveInputs() {
        cfg.host = b.hostInput.text.toString()
        cfg.token = b.tokenInput.text.toString()
        cfg.port = b.portInput.text.toString().toIntOrNull() ?: 8765
        Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun currentInputs(): Triple<String, Int, String> {
        val host = b.hostInput.text.toString().trim()
        val port = b.portInput.text.toString().toIntOrNull() ?: cfg.port
        val token = b.tokenInput.text.toString().trim()
        return Triple(host, port, token)
    }

    private fun testConnection() {
        val (host, port, token) = currentInputs()
        if (host.isBlank() || token.isBlank()) {
            b.testResultText.text = "请先填写电脑地址和令牌"
            return
        }
        b.testResultText.text = "测试中…"
        val pc = PcClient(host, port, token)
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { pc.ping() }
            if (!isAdded) return@launch
            b.testResultText.text = when (r) {
                PcClient.PingResult.OK -> "连接成功"
                PcClient.PingResult.BAD_TOKEN -> "配对令牌不正确"
                PcClient.PingResult.UNREACHABLE ->
                    "找不到电脑，请确认电脑已开机并运行接收软件"
            }
        }
    }

    private fun renderBucketSummary() {
        val names = cfg.bucketNames
        val text = if (cfg.bucketIds.isEmpty()) {
            "未选择相册目录"
        } else {
            cfg.bucketIds.joinToString("、") { names[it] ?: it }
        }
        b.bucketSummary.text = "已选目录：$text"
    }

    // ---------- 自动发现配对 ----------

    /** 搜索局域网内正在运行接收软件的电脑（PRD 后续版本：自动发现） */
    private fun scanForPcs() {
        b.testResultText.text = "正在搜索电脑…（约 3 秒）"
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val pcs = withContext(Dispatchers.IO) { PcDiscovery.discover(ctx) }
            if (!isAdded) return@launch
            if (pcs.isEmpty()) {
                b.testResultText.text =
                    "未发现电脑，请确认电脑已运行接收软件，且手机与电脑在同一 WiFi"
                return@launch
            }
            b.testResultText.text = ""
            val labels = pcs.map { "${it.hostname}（${it.ip}）" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择电脑")
                .setItems(labels) { _, which -> pickIdentity(pcs[which]) }
                .show()
        }
    }

    /** 选完电脑后选择本机身份（手机A/手机B），自动填入全部配置 */
    private fun pickIdentity(pc: PcDiscovery.PcInfo) {
        val names = pc.devices.keys.toList()
        if (names.isEmpty()) {
            b.testResultText.text = "该电脑未配置任何设备令牌"
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("这台手机是哪一部？")
            .setItems(names.toTypedArray()) { _, i ->
                b.hostInput.setText(pc.ip)
                b.portInput.setText(pc.port.toString())
                b.tokenInput.setText(pc.devices[names[i]] ?: "")
                saveInputs()
                b.testResultText.text = "已配对 ${pc.hostname}（${pc.ip}）"
            }
            .show()
    }
}
