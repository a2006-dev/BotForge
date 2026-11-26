package com.ajx.botforge.qq

import android.content.Context
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.service.BotService

/**
 * 管理多个 BotClient 的启停
 */
object BotManager {

    private val clients = mutableMapOf<String, BotClient>()
    private var appCtx: Context? = null

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val bots = Config.loadBots().filter { it.enabled && it.appId.isNotBlank() && it.appSecret.isNotBlank() }
        if (bots.isEmpty()) {
            Logger.w("Manager", "没有可用的 Bot 配置，请先在界面添加")
            return
        }
        bots.forEach { cfg ->
            if (clients.containsKey(cfg.id)) return@forEach
            val c = BotClient(cfg) { ev -> MessageHandler.handle(appCtx!!, cfg, ev) }
            // handler 内部自行发送回复，BotClient 只负责分发
            clients[cfg.id] = c
            c.start()
            Logger.i("Manager", "启动 Bot: ${cfg.name} (${cfg.appId})")
        }
        BotService.instance?.updateStatus("已启动 ${clients.size} 个机器人")
    }

    fun stop() {
        clients.values.forEach { it.stop() }
        clients.clear()
        BotService.instance?.updateStatus("已停止")
    }

    fun reload(ctx: Context) {
        Logger.i("Manager", "重新加载所有 Bot")
        stop()
        start(ctx)
    }

    fun status(): String = when {
        clients.isEmpty() -> "未运行"
        clients.values.all { it.isRunning() } -> "运行中 (${clients.size})"
        else -> "部分运行"
    }

    fun count(): Int = clients.size
}