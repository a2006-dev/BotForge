package com.ajx.botforge.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ajx.botforge.App
import com.ajx.botforge.R
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger

/**
 * 守护服务：跑在 :guard 独立进程
 * 职责：定期检查主服务是否存活，没了就拉起来
 * 与 BotService 互相拉起，形成闭环
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var checking = false

    private val tick = object : Runnable {
        override fun run() {
            if (!checking) return
            if (Config.keepAlive) {
                checkMain()
            }
            handler.postDelayed(this, INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                GUARD_NOTI_ID,
                buildGuardNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(GUARD_NOTI_ID, buildGuardNotification())
        }
        checking = true
        handler.postDelayed(tick, INTERVAL)
        Logger.i("Guard", "守护服务已启动（进程 :guard）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        checking = false
        handler.removeCallbacks(tick)
        Logger.w("Guard", "守护服务销毁")
        // 自己也尝试被拉起
        if (Config.keepAlive) {
            BotService.scheduleRestart(applicationContext, 3000L)
        }
        super.onDestroy()
    }

    private fun checkMain() {
        val alive = BotService.instance != null
        if (!alive) {
            Logger.w("Guard", "主服务不在，尝试拉起")
            // 第一层：常规拉起
            val normal = runCatching {
                val i = Intent(this, BotService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
                true
            }.getOrDefault(false)

            // 第二层：Shizuku 提权拉起（普通方式失败时）
            if (!normal) {
                val ok = ShizukuKeepAlive.startService(applicationContext)
                Logger.i("Guard", "提权拉起结果: $ok")
            }
        }
    }

    private fun buildGuardNotification(): Notification =
        NotificationCompat.Builder(this, App.CH_RUNNING)
            .setContentTitle("BotForge 守护进程")
            .setContentText("负责保持机器人服务不中断")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

    companion object {
        const val GUARD_NOTI_ID = 1002
        private const val INTERVAL = 30_000L // 每 30 秒检查一次
    }
}