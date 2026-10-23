package com.malt.photosync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.malt.photosync.MainActivity
import com.malt.photosync.R
import com.malt.photosync.data.ConfigStore
import com.malt.photosync.data.SyncDatabase
import com.malt.photosync.net.PcClient
import com.malt.photosync.sync.FinishReason
import com.malt.photosync.sync.SyncState
import com.malt.photosync.sync.UploadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dataSync 前台服务：持有 WiFi 锁 + Wake 锁，通知栏显示进度并提供「停止」。
 * Android 14 起 dataSync 有每日时长限额，被系统叫停时等同一次停止（进度已
 * 逐文件落库，再次点击开始即续传）。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var syncJob: kotlinx.coroutines.Job? = null
    private var engine: UploadEngine? = null
    private var pc: PcClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifAt = 0L

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "相册同步", NotificationManager.IMPORTANCE_LOW)
        )
        // 观察进度 → 通知栏（节流 500ms）
        scope.launch {
            SyncState.flow.collect { st ->
                if (st.running) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifAt >= 500) {
                        lastNotifAt = now
                        notifyProgress(st.phase, st.doneInRun, st.pendingTotal,
                            st.currentName, st.speedBps)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithType()
                acquireLocks()
                if (syncJob?.isActive != true) startSync()
            }
            ACTION_STOP -> stopSync()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithType() {
        val notif = buildBaseNotification("准备同步…").build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    /** 传输期间持有 WiFi 锁（防锁屏降速/断开）+ 部分 Wake 锁 */
    private fun acquireLocks() {
        if (wifiLock?.isHeld == true) return
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "photosync:wifi")
            .apply { setReferenceCounted(false); acquire() }
        wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photosync:cpu")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun startSync() {
        stopRequested.set(false)
        val cfg = ConfigStore(this)
        val client = PcClient(cfg.host, cfg.port, cfg.token)
        pc = client
        val dao = SyncDatabase.get(this).dao()
        val e = UploadEngine(this, client, dao, stopRequested)
        engine = e
        syncJob = scope.launch {
            e.run()
            onFinished(e.finishReason, e.finishMessage)
        }
    }

    /** 立即结束：置停止标志 + 取消当前上传（<2s 内停止，PRD 验收 3） */
    private fun stopSync() {
        stopRequested.set(true)
        pc?.cancelCurrent()
    }

    private fun onFinished(reason: FinishReason, msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTIF_ID, buildBaseNotification(msg)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build())
        // 终态通知 15 秒后自动撤下
        mainHandler.postDelayed({ nm.cancel(NOTIF_ID) }, 15_000)
        releaseLocks()
        stopSelf()
    }

    private fun buildBaseNotification(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("相册同步")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stopPendingIntent())

    private fun notifyProgress(
        phase: String, done: Int, total: Int, currentName: String?, speedBps: Long
    ) {
        val text = buildString {
            append(phase)
            if (total > 0) append("  $done/$total")
            currentName?.let { append("  ·  $it") }
            if (speedBps > 0) append("  ·  ${formatSpeed(speedBps)}")
        }
        val b = buildBaseNotification(text)
            .setProgress(total, done, total <= 0)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, SyncService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun formatSpeed(bps: Long): String {
        val kb = bps / 1024.0
        return if (kb >= 1024) "%.1fMB/s".format(kb / 1024) else "%.0fKB/s".format(kb)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        pc?.cancelCurrent()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.malt.photosync.action.START"
        const val ACTION_STOP = "com.malt.photosync.action.STOP"
        private const val CHANNEL_ID = "sync"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            val i = Intent(context, SyncService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SyncService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
