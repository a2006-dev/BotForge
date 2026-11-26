package com.ajx.botforge.ui.data

import com.ajx.botforge.data.Logger
import com.ajx.botforge.qq.BotConnectionState
import com.ajx.botforge.qq.BotManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dashboard 的唯一状态源。
 *
 * 任务书第一节要求「所有指标必须来自真实业务数据」「使用 StateFlow 实现合理的
 * 状态同步」「增加最近运行事件和错误信息」。
 *
 * 之前 Dashboard 的问题：
 *  - 指标通过 LaunchedEffect(Unit) 读一次，之后靠 3 秒轮询补，本质是"伪响应式"；
 *  - 首页只有一个 `logCount` 数字，看不到任何具体事件 —— 出了异常用户无从判断；
 *  - 服务运行状态与 WebSocket 连接状态混在一起显示。
 *
 * 这里把三者拆开集中管理：
 *  - service：前台服务是否存活（进程级）
 *  - summary：连接层聚合（在线数 / 各状态计数）
 *  - bots：每个机器人的真实连接快照（含 lastError / ackLatency / reconnectCount）
 *  - events：最近的运行事件（来自 Logger 环形缓冲的真实日志，不是编造的数据）
 */
data class DashboardState(
    /** 前台服务是否存活 */
    val serviceRunning: Boolean = false,
    /** BotManager 聚合摘要 */
    val summary: BotManager.Summary = BotManager.Summary(
        loaded = 0, online = 0, connecting = 0, reconnecting = 0, error = 0, stopped = 0
    ),
    /** 每个机器人的真实连接快照 */
    val bots: List<BotConnectionState> = emptyList(),
    /** 最近运行事件（最新的在前） */
    val events: List<Logger.Line> = emptyList(),
    /** 配置数量 */
    val personaCount: Int = 0,
    val modelCount: Int = 0,
    /** 已加载日志总条数 */
    val logTotal: Int = 0
) {
    /** 有异常需要用户关注的机器人 */
    val abnormalBots: List<BotConnectionState>
        get() = bots.filter {
            it.state == com.ajx.botforge.qq.BotConnState.ERROR ||
                    it.state == com.ajx.botforge.qq.BotConnState.RECONNECTING
        }

    /** 是否存在需要提示的异常 */
    val hasAbnormal: Boolean get() = abnormalBots.isNotEmpty() || summary.error > 0

    /** 错误级事件（用于首页"运行异常"区块） */
    val errorEvents: List<Logger.Line>
        get() = events.filter { it.level >= 2 }.take(3)
}

/**
 * Dashboard 状态仓库。
 *
 * 刻意做成进程内单例：连接层的 BotStatusRegistry 也是单例，
 * 二者生命周期一致，页面重建（旋转/返回）不该丢掉已经积累的事件。
 */
object DashboardStore {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val repo = BotForgeRepository()

    @Volatile private var installed = false

    /**
     * 订阅连接层推送。
     *
     * BotStatusRegistry 用回调而非 Flow（连接层不依赖 UI 的协程库），
     * 这里转成 StateFlow 供 Compose 消费。重复调用是幂等的。
     */
    fun install() {
        if (installed) return
        installed = true
        com.ajx.botforge.qq.BotStatusRegistry.listener = { _ -> refreshBots() }
        refreshBots()
    }

    fun uninstall() {
        com.ajx.botforge.qq.BotStatusRegistry.listener = null
        installed = false
    }

    /** 从连接层拉一次真实快照。任何状态变化都走这里，保证 UI 与连接层同源。 */
    fun refreshBots() {
        _state.value = _state.value.copy(
            summary = BotManager.summary(),
            bots = com.ajx.botforge.qq.BotStatusRegistry.snapshot()
        )
    }

    /** 刷新服务存活、配置数量、日志事件。由页面的轻量轮询调用。 */
    suspend fun refreshAll() {
        val cur = _state.value
        val logs = repo.logs()
        _state.value = cur.copy(
            serviceRunning = repo.isServiceRunning(),
            summary = BotManager.summary(),
            bots = com.ajx.botforge.qq.BotStatusRegistry.snapshot(),
            events = logs.takeLast(6).reversed(),
            logTotal = logs.size,
            personaCount = repo.personas().size,
            modelCount = repo.models().size
        )
    }

    /** 服务启停后立即同步一次，不必等轮询 */
    fun syncService(running: Boolean) {
        _state.value = _state.value.copy(
            serviceRunning = running,
            summary = BotManager.summary()
        )
    }
}