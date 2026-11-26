package com.ajx.botforge.service

import android.app.Notification
import android.app.Service
import android.content.Context
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
        // 同样要尊重「自动拉起」开关与用户的主动停止意图
        if (Config.keepAlive && Config.canAutoRestart()) {
            BotService.scheduleRestart(applicationContext, 3000L)
        }
        super.onDestroy()
    }

    private fun checkMain() {
        // 注意：GuardService 跑在 :guard 进程，BotService.instance 是主进程的静态字段，
        // 在这里永远为 null，不能用来判活。必须跨进程查询服务状态。
        val alive = isBotServiceRunning()
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

    /** 跨进程判断 BotService 是否存活 */
    private fun isBotServiceRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            services.any { it.service.className == BotService::class.java.name }
        } catch (e: Exception) {
            // 查询失败时保守认为存活，避免无谓重启造成循环
            Logger.w("Guard", "查询服务状态失败: ${e.message}")
            true
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