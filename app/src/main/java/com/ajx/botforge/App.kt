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
        val prefs = getSharedPreferences("botforge", MODE_PRIVATE)
        com.ajx.botforge.plugin.PluginGuard.init(prefs)
        // 主人/管理员授权体系。复用同一个 prefs 文件，
        // 这样导出配置时不会漏掉授权状态（换机恢复后不用重新绑定）。
        com.ajx.botforge.data.AuthStore.init(prefs)
        // 已发现群登记簿：被动收集 group_openid。
        // 官方没有「列出全部群」的接口，只能靠收消息时顺手记一笔。
        com.ajx.botforge.data.GroupRegistry.init(prefs)
        // 工具包的启用状态与单工具权限。故意用独立 prefs 文件：
        // 这是「用户对工具的管理决定」，寿命和模型/机器人配置不同，
        // 分开存以后清配置、导出配置都不用牵连。
        com.ajx.botforge.ai.tool.PackagePrefs.init(this)
        // 把内置包装进注册表。放在启动时做而不是首次调用时懒加载，
        // 是为了让「哪些包被关了」在日志里一开机就能看到，
        // 排查「为什么模型看不到某个工具」时不用先问它一句话。
        com.ajx.botforge.ai.tool.BuiltinTools.ensureRegistered()
        // 主题偏好灌入内存，供根 Composition 读取
        com.ajx.botforge.ui.data.UiPrefs.load()
        com.ajx.botforge.data.Logger.i("App", "BotForge 启动 v${BuildConfig.VERSION_NAME}")
        // 启动后台跑一次终端环境自检，结果落到
        // /sdcard/Download/bf_terminal_diag.txt。
        //
        // 为什么放在启动时做：release 包的 logcat 被剥离，App 私有目录
        // 又被 SELinux 挡住，外部完全看不到 proot/loader 的真实状态，
        // 排查只能靠猜。每次启动自动落一份，装完机就能直接取证。
        val appCtx = this
        Thread {
            runCatching {
                if (com.ajx.botforge.term.TerminalEnvironment.isReady(appCtx)) {
                    com.ajx.botforge.term.TerminalEnvironment.dumpDiagnostics(appCtx)
                }
            }
        }.apply { isDaemon = true }.start()
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