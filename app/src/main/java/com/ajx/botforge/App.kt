package com.ajx.botforge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ajx.botforge.data.Config

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Config.init(this)
        com.ajx.botforge.plugin.PluginRegistry.init(this)
        com.ajx.botforge.plugin.PluginGuard.init(
            getSharedPreferences("botforge", MODE_PRIVATE)
        )
        com.ajx.botforge.data.Logger.i("App", "BotForge 启动 v${BuildConfig.VERSION_NAME}")
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 常驻通知：低优先级、静音、不打扰
        val running = NotificationChannel(
            CH_RUNNING, "机器人运行状态", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示机器人连接状态，关闭此通知会导致服务被系统回收"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(running)

        // 事件通知
        val events = NotificationChannel(
            CH_EVENTS, "重要事件", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "断线、异常等需要你知道的情况"
        }
        nm.createNotificationChannel(events)
    }

    companion object {
        const val CH_RUNNING = "bot_running"
        const val CH_EVENTS = "bot_events"

        lateinit var instance: App
            private set
    }
}