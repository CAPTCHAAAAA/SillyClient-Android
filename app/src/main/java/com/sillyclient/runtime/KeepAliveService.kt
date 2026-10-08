package com.sillyclient.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps long instance operations alive while the app is backgrounded.
 *
 * The target device freezes an app's process subtree (including spawned
 * `tar`/`rm` children) as soon as the screen goes off or the user switches
 * away: extraction and deletion then stall mid-flight and look "slow" or
 * stuck. A foreground service with a data-sync type plus a partial wake lock
 * is the platform-sanctioned exemption, so every multi-minute operation and
 * background purge runs under [KeepAlive] until it finishes.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Fulfil the startForegroundService() promise as early as possible: the
        // system kills the app ("did not then call Service.startForeground()")
        // when the main thread is busy or delayed longer than the deadline, so
        // the window between start and this call must stay as short as possible.
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        KeepAlive.onServiceReady(applicationContext)
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Exception) {
            // Never leave a pending foreground-service promise unfulfilled: if
            // this promotion is rejected, drop the service so release() can
            // safely start a fresh one later.
            runCatching { stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "实例任务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "实例准备、迁移与清理进行中的提示" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("SillyClient")
            .setContentText("正在处理实例任务，请保持应用在后台运行")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sillyclient-keepalive"
        private const val NOTIFICATION_ID = 0x5C01
    }
}

/**
 * Ref-counted keep-alive: any number of foreground operations and background
 * purges can hold it; the service and wake lock live until the last one lets
 * go. All calls must be balanced.
 */
object KeepAlive {
    private var leases = 0

    /** True once the service actually ran startForeground(); a pending start must
     *  never be stopped before that or the system kills the app. */
    private var serviceStarted = false
    private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        leases++
        if (leases > 1) return
        stopRequested = false
        serviceStarted = false
        runCatching {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyClient:instance-task")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_MILLIS) }
        }
    }

    @Synchronized
    fun release(context: Context) {
        if (leases == 0) return
        leases--
        if (leases > 0) return
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopServiceWhenSafe(context)
    }

    /** Called by the service right after startForeground() succeeded. */
    @Synchronized
    fun onServiceReady(context: Context) {
        serviceStarted = true
        if (stopRequested) stopServiceWhenSafe(context)
    }

    private fun stopServiceWhenSafe(context: Context) {
        if (!serviceStarted) {
            // startForegroundService() was issued but the service has not
            // confirmed the promotion yet. Stopping now would leave the platform
            // promise unfulfilled (ForegroundServiceDidNotStartInTimeException,
            // which kills the whole app), so defer the stop instead.
            stopRequested = true
            return
        }
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        serviceStarted = false
        stopRequested = false
    }

    private const val MAX_WAKE_MILLIS = 60L * 60 * 1000
}
