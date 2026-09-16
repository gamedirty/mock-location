package com.sideproject.mocklocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 前台服务：让模拟位置在应用退到后台／锁屏后继续生效，并在通知栏显示当前坐标。
 * 心跳循环本身由 MockEngine 持有，这里只负责"活着"+ 通知。
 */
class MockService : Service() {

    private var listener: ((MockState) -> Unit)? = null

    private val nm: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val ok = try {
            startForeground(NOTI_ID, buildNotification(MockEngine.state))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败", t)
            false
        }

        if (!ok) {
            MockEngine.stopTicking()
            stopSelf()
            return START_NOT_STICKY
        }

        if (listener == null) {
            val l: (MockState) -> Unit = { st -> updateNotification(st) }
            listener = l
            MockEngine.addListener(l)
        }
        MockEngine.startTicking()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listener?.let { MockEngine.removeListener(it) }
        listener = null
        MockEngine.stopTicking()
        MockEngine.stopMock(this)
        super.onDestroy()
    }

    private fun shutdown() {
        MockEngine.stopTicking()
        MockEngine.stopMock(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun updateNotification(st: MockState) {
        try {
            nm.notify(NOTI_ID, buildNotification(st))
        } catch (t: Throwable) {
            Log.w(TAG, "更新通知失败", t)
        }
    }

    private fun buildNotification(st: MockState): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, MockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildString {
            append(Geo.fmt(st.lat)).append(", ").append(Geo.fmt(st.lng))
            append("   ±").append(st.accuracy.toInt()).append("m")
            if (st.routeActive) {
                append("   巡航 ")
                append(String.format(Locale.US, "%.1f", st.speedMps))
                append(" m/s")
                append("  ").append((st.routeProgress * 100).toInt()).append("%")
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(getString(R.string.noti_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.noti_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        ch.setShowBadge(false)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "MockService"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTI_ID = 0x10C

        const val ACTION_START = "com.sideproject.mocklocation.action.START"
        const val ACTION_STOP = "com.sideproject.mocklocation.action.STOP"

        /** Android 14 起 location 类型前台服务要求已授予定位权限，失败时返回 false 由调用方降级 */
        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, MockService::class.java).setAction(ACTION_START),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "启动前台服务失败", t)
            false
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, MockService::class.java)) }
        }
    }
}
