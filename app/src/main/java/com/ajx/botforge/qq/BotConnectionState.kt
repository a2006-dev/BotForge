package com.ajx.botforge.qq

/**
 * 连接状态机
 *
 * 任务书第二节要求区分「客户端运行」与「WebSocket 真正在线」，
 * 此前 `running = true` 同时代表这两件事，导致重连等待期
 * Dashboard 也显示「在线」——这是虚假状态。
 *
 * 这里用显式状态取代布尔量：
 *   STOPPED        用户停止 / 未启动
 *   CONNECTING     DNS + TCP + TLS + WebSocket 握手阶段
 *   AUTHENTICATING 已连上，但尚未收到 READY / RESUMED
 *   CONNECTED      收到 READY 或 RESUMED，这在业务上才叫「在线」
 *   RECONNECTING   断线后等待退避
 *   ERROR          因不可恢复错误停机（与 STOPPED 区分：这个带 lastError）
 */
enum class BotConnState {
    STOPPED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    ERROR;

    /** 只有 CONNECTED 才允许对外宣称在线。 */
    val isOnline: Boolean get() = this == CONNECTED

    val label: String
        get() = when (this) {
            STOPPED -> "已停止"
            CONNECTING -> "连接中"
            AUTHENTICATING -> "鉴权中"
            CONNECTED -> "在线"
            RECONNECTING -> "重连中"
            ERROR -> "异常"
        }
}

/**
 * 单个机器人的连接快照
 *
 * 任务书第三节列出的字段全部落在这里。所有时间戳单位为毫秒；
 * 0 表示「从未发生」，UI 层据此区分「从未连接」与「曾经连接」。
 */
data class BotConnectionState(
    val botId: String,
    val botName: String,
    val state: BotConnState = BotConnState.STOPPED,
    /** 当前连接代次，每次新建连接 +1，用于隔离旧线程的回调 */
    val generation: Long = 0L,
    val connectedAt: Long = 0L,
    val lastHeartbeatAt: Long = 0L,
    val lastAckAt: Long = 0L,
    val lastDisconnectAt: Long = 0L,
    val reconnectCount: Int = 0,
    val lastError: String? = null,
    /** 服务端下发的原始心跳间隔（未截断） */
    val heartbeatInterval: Long = 0L,
    /** 最近一次心跳的 ACK 延迟；-1 表示尚无样本 */
    val ackLatency: Long = -1L
) {
    /** 仅用于 UI 展示的 ACK 延迟文本 */
    fun ackLatencyText(): String = when {
        ackLatency < 0 -> "—"
        ackLatency < 1000 -> "${ackLatency}ms"
        else -> "${ackLatency / 1000}s"
    }
}

/**
 * 连接状态观测器
 *
 * BotManager 持有唯一实例，BotClient 每次状态变化就往外推。
 * 用监听器而非 StateFlow，是因为连接层在 qq 包内不应依赖 UI 层的
 * kotlinx-coroutines-flow；UI 侧再包一层 StateFlow。
 */
object BotStatusRegistry {

    private val states = java.util.concurrent.ConcurrentHashMap<String, BotConnectionState>()

    @Volatile
    var listener: ((BotConnectionState) -> Unit)? = null

    fun publish(s: BotConnectionState) {
        states[s.botId] = s
        listener?.invoke(s)
    }

    fun update(botId: String, block: (BotConnectionState) -> BotConnectionState) {
        val cur = states[botId] ?: return
        publish(block(cur))
    }

    fun get(botId: String): BotConnectionState? = states[botId]

    /** 快照，按名称排序，给 Dashboard 用 */
    fun snapshot(): List<BotConnectionState> =
        states.values.sortedBy { it.botName }

    /** 真正在线的数量（不是 running 的数量） */
    fun onlineCount(): Int = states.values.count { it.state.isOnline }

    fun clear() = states.clear()

    fun remove(botId: String) {
        states.remove(botId)
    }
}
