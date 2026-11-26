package com.ajx.botforge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ajx.botforge.App
import com.ajx.botforge.R
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.ui.MainActivity

/**
 * 常驻前台服务：保活核心
 * 参考 GKD 思路：前台通知 + START_STICKY + 与 GuardService 互拉
 */
class BotService : Service() {

    private var running = false

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTI_ID,
                buildNotification("正在启动…"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTI_ID, buildNotification("正在启动…"))
        }
        Logger.i("Service", "前台服务已创建")
        if (Config.keepAlive) KeepAliveOverlay.acquire(applicationContext)
        startGuard()
        BotManager.start(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i("Service", "收到停止指令")
                BotManager.stop()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_RESTART_BOTS -> {
                Logger.i("Service", "重新加载配置")
                BotManager.reload(applicationContext)
            }
        }
        // 服务被系统杀掉后自动重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "任务被移除，调度重启")
        scheduleRestart(applicationContext, 1000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        Logger.w("Service", "前台服务销毁")
        KeepAliveOverlay.release()
        // 若用户没主动停止，尽量拉起
        if (Config.keepAlive) {
            scheduleRestart(applicationContext, 2000L)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startGuard() {
        if (!Config.keepAlive) return
        runCatching {
            val i = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        }.onFailure { Logger.w("Service", "守护服务启动失败: ${it.message}") }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    fun updateStatus(text: String) {
        if (!running) return
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTI_ID, buildNotification(text))
        }
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CH_RUNNING)
            .setContentTitle("BotForge 运行中")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        const val NOTI_ID = 1001
        const val ACTION_STOP = "com.ajx.botforge.STOP"
        const val ACTION_RESTART_BOTS = "com.ajx.botforge.RELOAD"

        @Volatile
        var instance: BotService? = null
            internal set

        fun start(ctx: Context) {
            val i = Intent(ctx, BotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BotService::class.java).apply { action = ACTION_STOP })
        }

        fun scheduleRestart(ctx: Context, delayMs: Long) {
            val i = Intent(ctx, RestartReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 42, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMs, pi)
                } else {
                    am.set(android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMs, pi)
                }
                Logger.i("Service", "已调度 ${delayMs}ms 后重启")
            }.onFailure { Logger.w("Service", "调度重启失败: ${it.message}") }
        }
    }
}