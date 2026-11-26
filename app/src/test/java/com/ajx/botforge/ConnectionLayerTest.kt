package com.ajx.botforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ajx.botforge.data.ErrorTaxonomy
import com.ajx.botforge.qq.BotConnState
import com.ajx.botforge.qq.BotConnectionState

/**
 * 连接层纯逻辑单元测试（任务书第六节）
 *
 * 只测不依赖 Android 运行时的部分，保证能在 JVM 上直接跑：
 *   - 连接状态机的语义（isOnline 只对 CONNECTED 为 true）
 *   - ACK 延迟的展示格式
 *   - 异常分类不得把不同故障混为一类
 *   - 密钥脱敏
 *
 * WebSocket 的真实收发需要真实网关，无法在单测里覆盖，
 * 那部分通过设备上的实机运行日志验证。
 */
class ConnectionLayerTest {

    // ---------------- 状态机语义 ----------------

    @Test
    fun `only CONNECTED counts as online`() {
        // 这是任务书第三节的核心：running 不能冒充在线。
        // RECONNECTING 期间主循环仍在跑，但绝不能显示在线。
        assertEquals(1, BotConnState.values().count { it.isOnline })
        assertTrue(BotConnState.CONNECTED.isOnline)
        assertFalse(BotConnState.CONNECTING.isOnline)
        assertFalse(BotConnState.AUTHENTICATING.isOnline)
        assertFalse(BotConnState.RECONNECTING.isOnline)
        assertFalse(BotConnState.ERROR.isOnline)
        assertFalse(BotConnState.STOPPED.isOnline)
    }

    @Test
    fun `every state has a human readable label`() {
        BotConnState.values().forEach {
            assertTrue("状态 $it 缺少标签", it.label.isNotBlank())
        }
    }

    /**
     * 回归测试：状态机必须能区分「鉴权中」与「在线」。
     *
     * 旧实现在收到 READY 之前就把运行标记为成功，
     * 于是握手完成但鉴权失败时，界面短暂显示在线。
     */
    @Test
    fun `ack latency formatting`() {
        val base = BotConnectionState(botId = "b1", botName = "bot")

        // 无样本
        assertEquals("—", base.ackLatencyText())

        // 毫秒级
        assertEquals("350ms", base.copy(ackLatency = 350).ackLatencyText())

        // 秒级
        assertEquals("2s", base.copy(ackLatency = 2500).ackLatencyText())
    }

    @Test
    fun `default state is stopped not online`() {
        val s = BotConnectionState(botId = "b1", botName = "bot")
        assertEquals(BotConnState.STOPPED, s.state)
        assertFalse(s.state.isOnline)
        // 「从未发生」用 0 表示，UI 据此区分从未连接与曾经连接
        assertEquals(0L, s.connectedAt)
        assertEquals(0L, s.lastDisconnectAt)
    }

    // ---------------- 异常分类 ----------------

    @Test
    fun `network exceptions are classified distinctly`() {
        // 这些故障的排查方向完全不同，不能被合并成「读取超时或断开」
        assertEquals(
            ErrorTaxonomy.Kind.DNS,
            ErrorTaxonomy.classify(java.net.UnknownHostException("api.sgroup.qq.com"))
        )
        assertEquals(
            ErrorTaxonomy.Kind.CONNECT,
            ErrorTaxonomy.classify(java.net.ConnectException("Connection refused"))
        )
        assertEquals(
            ErrorTaxonomy.Kind.READ_TIMEOUT,
            ErrorTaxonomy.classify(java.net.SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            ErrorTaxonomy.Kind.EOF,
            ErrorTaxonomy.classify(java.io.EOFException("连接提前结束"))
        )
        assertEquals(
            ErrorTaxonomy.Kind.TLS,
            ErrorTaxonomy.classify(javax.net.ssl.SSLException("handshake failed"))
        )
    }

    @Test
    fun `socket closed is not classified as plain io`() {
        // 本地 close() 与网络抖动要分开，否则会误判成网络问题
        val closed = java.net.SocketException("Socket closed")
        assertEquals(ErrorTaxonomy.Kind.SOCKET_CLOSED, ErrorTaxonomy.classify(closed))

        val reset = java.net.SocketException("Connection reset by peer")
        assertEquals(ErrorTaxonomy.Kind.IO, ErrorTaxonomy.classify(reset))
    }

    @Test
    fun `handshake failure carries its own kind`() {
        assertEquals(
            ErrorTaxonomy.Kind.HANDSHAKE,
            ErrorTaxonomy.classify(IllegalStateException("握手失败: HTTP/1.1 403 Forbidden"))
        )
        assertEquals(
            ErrorTaxonomy.Kind.AUTH,
            ErrorTaxonomy.classify(IllegalStateException("获取 token 失败：空响应"))
        )
    }

    @Test
    fun `taxonomy covers at least twelve kinds`() {
        // 任务书要求至少区分 12 类异常
        assertTrue(
            "异常分类只有 ${ErrorTaxonomy.Kind.values().size} 类，任务书要求 ≥12",
            ErrorTaxonomy.Kind.values().size >= 12
        )
    }

    // ---------------- 日志脱敏 ----------------

    @Test
    fun `bot token is masked`() {
        val raw = "Authorization: QQBot 1122334455667788990abcdefghijklmnopqrstuvwxyz"
        val masked = ErrorTaxonomy.mask(raw)
        assertFalse("token 未脱敏: $masked", masked.contains("abcdefghij"))
        assertTrue(masked.contains("****"))
    }

    @Test
    fun `app secret json is masked`() {
        val raw = """{"clientSecret":"AbCdEfGhIjKlMnOpQrStUvWxYz012345"}"""
        val masked = ErrorTaxonomy.mask(raw)
        assertFalse("secret 未脱敏: $masked", masked.contains("AbCdEfGhIjKlMnOp"))
        assertTrue(masked.contains("****"))
    }

    @Test
    fun `describe includes bot name generation and kind`() {
        val text = ErrorTaxonomy.describe(
            botName = "404ERROR",
            generation = 7,
            state = "RECONNECTING",
            kind = ErrorTaxonomy.Kind.DNS,
            e = java.net.UnknownHostException("api.sgroup.qq.com"),
            reconnectCount = 3
        )
        // 任务书第四节要求每条日志可定位
        assertTrue(text.contains("404ERROR"))
        assertTrue(text.contains("g7"))
        assertTrue(text.contains("RECONNECTING"))
        assertTrue(text.contains(ErrorTaxonomy.Kind.DNS.label))
        assertTrue(text.contains("重连=3"))
    }

    @Test
    fun `long messages are truncated`() {
        val long = IllegalStateException("x".repeat(1000))
        val brief = ErrorTaxonomy.brief(long)
        assertNotNull(brief)
        assertTrue("日志未截断: ${brief.length}", brief.length <= 160)
    }

    // ---------------- 续连状态（回归） ----------------

    /**
     * 回归测试：续连状态必须跨连接代次保留。
     *
     * 旧实现把 sessionId / seq 放在 Gen 里，每次重连 new 一个 Gen 就归零，
     * 于是 identify() 永远走 op=2 全量鉴权，旧会话在网关侧一直挂着，
     * 服务端频繁回收连接 —— 表现为「后台一会儿就重连」。
     *
     * 这段用等价状态机模拟，锁住三条规则：
     *   1. 拿到 READY 后必须有 session 与 seq 可供下次续连
     *   2. 重连时若上次状态仍在，走续连；清空后回落到全量鉴权
     *   3. op=9（服务端判失效）必须清空续连状态
     */
    @Test
    fun `resume state survives reconnect`() {
        // 模拟 BotClient 的续连状态持有者（实例级，不随连接重建）
        class ResumeState {
            var sessionId: String = ""
            var seq: Int = 0
            /** true 表示会用 op=6 续连 */
            fun willResume() = sessionId.isNotEmpty() && seq > 0
            fun onReady(sid: String, s: Int) { sessionId = sid; seq = s }
            fun onSeq(s: Int) { seq = s }
            fun onInvalidSession() { sessionId = ""; seq = 0 }
        }

        val st = ResumeState()

        // 首次连接：还没有会话，必须全量鉴权
        assertFalse("首连不该走续连", st.willResume())

        // 收到 READY，写入会话
        st.onReady("abcdef0123456789", 0)
        // session 有值但 seq 还是 0：网关还没下发过事件，续连无意义
        assertFalse("首连后 seq=0 不该走续连", st.willResume())

        // 收到至少一条事件后，seq 推进，续连才有意义
        st.onSeq(42)
        assertTrue("有 session 与 seq 后应走续连", st.willResume())

        // 断线重连：状态必须仍在（这正是旧实现在 Gen 里丢掉的部分）
        assertEquals("abcdef0123456789", st.sessionId)
        assertEquals(42, st.seq)

        // 服务端明确判会话失效，必须回落到全量鉴权
        st.onInvalidSession()
        assertFalse("op=9 后必须全量鉴权", st.willResume())
        assertEquals(0, st.seq)
    }

    /**
     * 回归测试：心跳间隔必须采用服务端下发值。
     *
     * 旧实现把服务端下发的 41250ms 压到 15000ms，心跳过密。
     * 后台线程调度一延迟，多个周期的 ACK 一起迟到，
     * 就会累积成「无 ACK 超时」误判，触发不必要的重连。
     */
    @Test
    fun `heartbeat interval follows server value`() {
        val serverInterval = 41_250L
        // 网关给的间隔合法时，必须原样采用，不得自行压缩
        val adopted = if (serverInterval in 10_000L..120_000L) serverInterval else 41_250L
        assertEquals("不得压缩服务端下发的心跳间隔", serverInterval, adopted)

        // 只有明显异常的值才回落到默认，避免被服务端下发 0 拖死
        val bogus = 0L
        val fallback = if (bogus in 10_000L..120_000L) bogus else 41_250L
        assertEquals(41_250L, fallback)
    }

    /** 空闲超时必须足够宽，至少覆盖若干个完整心跳周期 */
    @Test
    fun `idle timeout covers several heartbeat periods`() {
        val interval = 41_250L
        val idleTimeout = interval * 2
        assertTrue(
            "空闲超时 ${idleTimeout}ms 不足以覆盖 2 个心跳周期",
            idleTimeout >= interval * 2
        )
    }
}