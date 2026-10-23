package com.malt.photosync.sync

import android.content.Context
import com.malt.photosync.data.ConfigStore
import com.malt.photosync.data.SyncDatabase
import com.malt.photosync.data.SyncRecord
import com.malt.photosync.data.SyncRecordDao
import com.malt.photosync.net.PcClient
import com.malt.photosync.scan.KeyBuilder
import com.malt.photosync.scan.MediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 结束原因（决定通知与界面的终态文案） */
enum class FinishReason { DONE, STOPPED, NET, SPACE, TOKEN, OFFLINE, ERROR }

/**
 * 差集驱动的顺序上传状态机（PRD §5.3）。
 * 运行在 SyncService 的工作协程里；stopRequested 由服务置位。
 */
class UploadEngine(
    context: Context,
    private val pc: PcClient,
    private val dao: SyncRecordDao,
    private val stopRequested: AtomicBoolean
) {
    var finishReason: FinishReason = FinishReason.ERROR
        private set
    var finishMessage: String = ""
        private set

    private val resolver = context.contentResolver
    private val cfg = ConfigStore(context)
    private var terminated = false

    suspend fun run() {
        SyncState.update {
            it.copy(running = true, message = null, phase = "准备中…",
                doneInRun = 0, currentName = null, fileBytes = 0, fileTotal = 0)
        }
        try {
            doRun()
        } catch (e: SecurityException) {
            // 存储权限被撤销（PRD §7）：提示，不崩溃
            finish(FinishReason.ERROR, "相册读取权限被撤销，请重新授权后再同步")
        } catch (e: Exception) {
            finish(
                if (stopRequested.get()) FinishReason.STOPPED else FinishReason.ERROR,
                if (stopRequested.get()) "已停止，已完成的文件已保存"
                else "同步异常结束：${e.message ?: e.javaClass.simpleName}"
            )
        } finally {
            SyncState.update { it.copy(running = false, phase = "", currentName = null) }
        }
    }

    private suspend fun doRun() {
        val bucketIds = cfg.bucketIds
        if (bucketIds.isEmpty()) {
            finish(FinishReason.ERROR, "未选择相册目录，请到「设置」中选择")
            return
        }

        // 电脑在线检查（PRD F2）
        SyncState.update { it.copy(phase = "检查电脑…") }
        when (pc.ping()) {
            PcClient.PingResult.UNREACHABLE -> {
                finish(FinishReason.OFFLINE, "找不到电脑，请确认电脑已开机并运行接收软件")
                return
            }
            PcClient.PingResult.BAD_TOKEN -> {
                finish(FinishReason.TOKEN, "配对令牌不正确，请核对电脑端显示的令牌")
                return
            }
            PcClient.PingResult.OK -> Unit
        }

        // 扫描 + 差集（PRD §5.3）
        SyncState.update { it.copy(phase = "扫描相册…") }
        val scanned = withContext(Dispatchers.IO) { MediaScanner(resolver).scan(bucketIds) }
        val records = dao.all().associateBy { it.mediaId }
        val pending = ArrayList<MediaScanner.MediaItem>(scanned.size)
        for (item in scanned) {
            val r = records[item.mediaId]
            val done = r != null && r.status == SyncRecord.DONE &&
                r.size == item.size && r.dateModified == item.dateModified
            if (!done) {
                pending += item
                dao.upsert(item.toRecord(SyncRecord.PENDING, null, KeyBuilder.build(item)))
            }
        }
        SyncState.update { it.copy(pendingTotal = pending.size, phase = "上传中") }

        var doneInRun = 0
        for (item in pending) {
            if (stopRequested.get()) {
                finish(FinishReason.STOPPED, "已停止，已完成的文件已保存")
                return
            }
            SyncState.update { it.copy(currentName = item.name, fileBytes = 0, fileTotal = item.size) }

            val key = KeyBuilder.build(item)
            val st = pc.status(key, item.size, item.mediaId)
            if (st == null) {
                finish(FinishReason.NET, "连接中断，已完成的文件已保存")
                return
            }
            if (st.exists) {
                // 电脑端已有同名同大小完整文件：补记状态并跳过（PRD §5.5）
                markDone(item, key)
                doneInRun++
                SyncState.update { it.copy(doneInRun = doneInRun) }
                continue
            }
            if (st.free >= 0 && st.free < item.size + st.reserve) {
                finish(FinishReason.SPACE, "电脑磁盘空间不足，同步已停止")
                return
            }

            var lastT = System.nanoTime()
            var lastBytes = 0L
            var ema = 0.0
            val result = pc.upload(key, item.size, item.mediaId, item.uri(), resolver) { written ->
                SyncState.update { it.copy(fileBytes = written, fileTotal = item.size) }
                val now = System.nanoTime()
                if (now - lastT >= 500_000_000L) {
                    val spd = (written - lastBytes) * 1e9 / (now - lastT)
                    ema = if (ema == 0.0) spd else ema * 0.6 + spd * 0.4
                    SyncState.update { it.copy(speedBps = ema.toLong()) }
                    lastT = now
                    lastBytes = written
                }
                if (stopRequested.get()) throw IOException("cancelled")
            }
            when (result) {
                PcClient.UploadResult.OK -> {
                    markDone(item, key)
                    doneInRun++
                    SyncState.update { it.copy(doneInRun = doneInRun) }
                }
                PcClient.UploadResult.NO_SPACE -> {
                    finish(FinishReason.SPACE, "电脑磁盘空间不足，同步已停止")
                    return
                }
                PcClient.UploadResult.BAD_TOKEN -> {
                    finish(FinishReason.TOKEN, "配对令牌不正确，请核对电脑端显示的令牌")
                    return
                }
                PcClient.UploadResult.NET_ERROR -> {
                    if (stopRequested.get()) {
                        finish(FinishReason.STOPPED, "已停止，已完成的文件已保存")
                    } else {
                        finish(FinishReason.NET, "连接中断，已完成的文件已保存")
                    }
                    return
                }
                is PcClient.UploadResult.ERROR -> {
                    finish(FinishReason.ERROR, "上传失败（${item.name}）：${result.msg}")
                    return
                }
            }
        }
        finish(FinishReason.DONE, "同步完成")
    }

    /** 上传成功立即落库（PRD §5.3 步骤 4） */
    private suspend fun markDone(item: MediaScanner.MediaItem, key: String) {
        val now = System.currentTimeMillis()
        val n = dao.markDone(item.mediaId, item.size, item.dateModified, now, key)
        if (n == 0) {
            dao.upsert(item.toRecord(SyncRecord.DONE, now, key))
        }
    }

    private fun finish(reason: FinishReason, msg: String) {
        terminated = true
        finishReason = reason
        finishMessage = msg
        SyncState.update { it.copy(message = msg) }
    }

    private fun MediaScanner.MediaItem.toRecord(
        status: Int, syncedAt: Long?, key: String
    ): SyncRecord = SyncRecord(mediaId, kind, size, dateModified, status, syncedAt, key)
}
