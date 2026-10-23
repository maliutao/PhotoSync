package com.malt.photosync.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 主界面与前台服务共享的同步状态（单例） */
data class UiState(
    val running: Boolean = false,
    val phase: String = "",                    // 扫描相册 / 检查电脑 / 上传中
    val pendingTotal: Int = 0,
    val doneInRun: Int = 0,
    val currentName: String? = null,
    val fileBytes: Long = 0,
    val fileTotal: Long = 0,
    val speedBps: Long = 0,
    val message: String? = null                // 最近一次结束原因（终态文案）
)

object SyncState {
    private val _flow = MutableStateFlow(UiState())
    val flow: StateFlow<UiState> = _flow

    fun update(transform: (UiState) -> UiState) {
        _flow.update(transform)
    }

    fun reset() {
        _flow.value = UiState()
    }
}
