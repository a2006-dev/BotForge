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

    /**
     * 消息处理线程池。
     * 关键：BotClient 的读取线程必须只做「收包 + 分发」，不能同步执行业务逻辑。
     * 否则调用大模型（最长 120s）、下载图片期间，读取循环完全停摆，
     * 心跳 ACK 和其它事件都收不到，连接会被判定超时。
     */
    private val worker = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "msg-worker").also { it.isDaemon = true }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val bots = Config.loadBots().filter { it.enabled && it.appId.isNotBlank() && it.appSecret.isNotBlank() }
        if (bots.isEmpty()) {
            Logger.w("Manager", "没有可用的 Bot 配置，请先在界面添加")
            return
        }
        bots.forEach { cfg ->
            if (clients.containsKey(cfg.id)) return@forEach
            val c = BotClient(cfg) { ev ->
                // 立即丢进线程池，读取线程不等待业务处理
                val ctx0 = appCtx
                if (ctx0 == null) {
                    Logger.w("Manager", "appCtx 为空，丢弃消息")
                } else {
                    worker.execute {
                        runCatching { MessageHandler.handle(ctx0, cfg, ev) }
                            .onFailure { Logger.e("Manager", "处理异常: ${it.message}") }
                    }
                }
            }
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