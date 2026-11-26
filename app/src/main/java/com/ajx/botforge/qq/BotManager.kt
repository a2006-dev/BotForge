package com.ajx.botforge.qq

import android.content.Context
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.service.BotService

/**
 * 管理多个 BotClient 的启停与聚合状态
 *
 * 任务书第三节「状态真实性」的整改点：
 *
 * 1. `count()` 此前返回 `clients.size`（配置数量），被 Dashboard 当成「在线数」显示，
 *    于是即使 QQ 网关早已断开、客户端正在等重连，界面依然显示在线。
 *    现在 `count()` 语义明确为「已加载的客户端数」，另提供 `onlineCount()`
 *    从 [BotStatusRegistry] 取真正 CONNECTED 的数量。
 *
 * 2. `status()` 此前只看 `clients.all { it.isRunning() }`，running 只代表主循环还在跑，
 *    重连等待期同样为 true。现在返回结构化 [Summary]，把
 *    「运行中 / 重连中 / 异常 / 已停止」分别计数，UI 可如实展示。
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

    /** 聚合状态，供 Dashboard 展示 */
    data class Summary(
        val loaded: Int,
        val online: Int,
        val connecting: Int,
        val reconnecting: Int,
        val error: Int,
        val stopped: Int
    ) {
        val hasAny: Boolean get() = loaded > 0

        /** 一句话概括，用于首页副标题 */
        val text: String
            get() = when {
                loaded == 0 -> "未运行"
                online == loaded -> "全部在线 · $online"
                online > 0 -> "部分在线 · $online/$loaded"
                reconnecting > 0 -> "重连中 · 0/$loaded 在线"
                connecting > 0 -> "连接中 · 0/$loaded 在线"
                error > 0 -> "异常 · 0/$loaded 在线"
                else -> "已停止"
            }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val bots = Config.loadBots()
            .filter { it.enabled && it.appId.isNotBlank() && it.appSecret.isNotBlank() }
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
        BotService.instance?.updateStatus(summary().text)
    }

    fun stop() {
        clients.values.forEach { it.stop() }
        clients.clear()
        BotStatusRegistry.clear()
        BotService.instance?.updateStatus("已停止")
    }

    fun reload(ctx: Context) {
        Logger.i("Manager", "重新加载所有 Bot")
        stop()
        start(ctx)
    }

    /** 已加载的客户端数量（配置层面，不代表在线） */
    fun count(): Int = clients.size

    /** 真正在线（CONNECTED）的数量 */
    fun onlineCount(): Int = clients.keys.count { id ->
        BotStatusRegistry.get(id)?.state?.isOnline == true
    }

    fun summary(): Summary {
        val states = clients.keys.mapNotNull { BotStatusRegistry.get(it)?.state }
        return Summary(
            loaded = clients.size,
            online = states.count { it == BotConnState.CONNECTED },
            connecting = states.count {
                it == BotConnState.CONNECTING || it == BotConnState.AUTHENTICATING
            },
            reconnecting = states.count { it == BotConnState.RECONNECTING },
            error = states.count { it == BotConnState.ERROR },
            stopped = states.count { it == BotConnState.STOPPED } +
                (clients.size - states.size)
        )
    }

    /**
     * 兼容旧调用点的字符串状态
     *
     * 注意：这里不再用 running 冒充在线，文案里明确写出在线数量。
     */
    fun status(): String {
        val s = summary()
        return when {
            s.loaded == 0 -> "未运行"
            s.online == s.loaded -> "运行中 (${s.online} 在线)"
            s.online > 0 -> "部分在线 (${s.online}/${s.loaded})"
            s.reconnecting > 0 -> "重连中 (0/${s.loaded} 在线)"
            s.connecting > 0 -> "连接中 (0/${s.loaded} 在线)"
            s.error > 0 -> "异常 (0/${s.loaded} 在线)"
            else -> "已停止"
        }
    }

    /** 每个 Bot 的详细状态，Dashboard 列表用 */
    fun states(): List<BotConnectionState> =
        clients.keys.mapNotNull { BotStatusRegistry.get(it) }
}