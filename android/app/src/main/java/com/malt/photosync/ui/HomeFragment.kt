package com.malt.photosync.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.malt.photosync.R
import com.malt.photosync.data.ConfigStore
import com.malt.photosync.data.SyncDatabase
import com.malt.photosync.databinding.FragmentHomeBinding
import com.malt.photosync.net.PcClient
import com.malt.photosync.service.SyncService
import com.malt.photosync.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            continueStart()
        } else {
            b.statusText.text = "需要相册读取权限才能同步，请重新点击开始并授权"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnStart.setOnClickListener {
            if (SyncState.flow.value.running) SyncService.stop(requireContext())
            else onStartClicked()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { SyncState.flow.collect { render(it) } }
                launch {
                    SyncDatabase.get(requireContext()).dao().stats().collect { list ->
                        val m = list.associate { it.kind to it.count }
                        b.photoCount.text = (m[0] ?: 0).toString()
                        b.videoCount.text = (m[1] ?: 0).toString()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    /** 开始前依次检查：配置、权限、WiFi、电脑在线（PRD F2） */
    private fun onStartClicked() {
        val cfg = ConfigStore(requireContext())
        if (cfg.host.isBlank() || cfg.token.isBlank()) {
            b.statusText.text = "请先在「设置」中填写电脑地址和配对令牌"
            return
        }
        if (cfg.bucketIds.isEmpty()) {
            b.statusText.text = "请先在「设置」中选择要同步的相册目录"
            return
        }
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.all {
                ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            continueStart()
        } else {
            permLauncher.launch(needed)
        }
    }

    private fun continueStart() {
        if (!onWifi()) {
            b.statusText.text = "当前未连接 WiFi"
            return
        }
        b.statusText.text = "正在连接电脑…"
        val cfg = ConfigStore(requireContext())
        val pc = PcClient(cfg.host, cfg.port, cfg.token)
        viewLifecycleOwner.lifecycleScope.launch {
            when (withContext(Dispatchers.IO) { pc.ping() }) {
                PcClient.PingResult.UNREACHABLE ->
                    b.statusText.text = "找不到电脑，请确认电脑已开机并运行接收软件"
                PcClient.PingResult.BAD_TOKEN ->
                    b.statusText.text = "配对令牌不正确，请核对电脑端显示的令牌"
                PcClient.PingResult.OK -> {
                    b.statusText.text = ""
                    SyncService.start(requireContext())
                }
            }
        }
    }

    private fun onWifi(): Boolean {
        val cm = requireContext().getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun render(state: com.malt.photosync.sync.UiState) {
        b.btnStart.text = if (state.running) "停止同步" else "开始同步"
        b.runningCard.visibility = if (state.running) View.VISIBLE else View.GONE
        b.statusText.text = state.message ?: ""
        if (state.running) {
            b.phaseText.text = buildString {
                append(state.phase)
                if (state.pendingTotal > 0) {
                    append("  ${state.doneInRun}/${state.pendingTotal}")
                }
            }
            b.currentFileText.text = state.currentName ?: ""
            b.fileProgress.progress = if (state.fileTotal > 0) {
                ((state.fileBytes * 100) / state.fileTotal).toInt()
            } else 0
            b.fileProgressText.text =
                "${formatSize(state.fileBytes)} / ${formatSize(state.fileTotal)}"
            b.speedText.text = if (state.speedBps > 0) formatSpeed(state.speedBps) else ""
        }
    }

    private fun formatSize(n: Long): String {
        if (n < 0) return "-"
        val kb = n / 1024.0
        return when {
            kb >= 1024 * 1024 -> "%.2fGB".format(kb / 1024 / 1024)
            kb >= 1024 -> "%.1fMB".format(kb / 1024)
            kb >= 1 -> "%.0fKB".format(kb)
            else -> "${n}B"
        }
    }

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        return if (kb >= 1024) "%.1fMB/s".format(kb / 1024) else "%.0fKB/s".format(kb)
    }
}
