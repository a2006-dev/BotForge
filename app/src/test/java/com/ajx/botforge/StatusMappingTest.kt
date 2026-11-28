package com.ajx.botforge

import com.ajx.botforge.qq.BotConnState
import com.ajx.botforge.ui.components.BotStatus
import com.ajx.botforge.ui.components.resolveBotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态映射的回归测试（任务书 Milestone 1 第三项）。
 *
 * ## 为什么这组断言值得写下来
 *
 * `resolveBotStatus` 的判定是**有序的 when 分支**，顺序本身承载业务语义：
 * 先看服务在不在跑，再看连接好不好。这个顺序曾经被写反过，
 * 结果是服务已经停了、但 `connected` 还残留 true，首页照样显示「在线」。
 * 用户在界面上看到的是一台没开机却自称在线的机器。
 *
 * 纯 Kotlin 逻辑，不碰 Android 也不碰 Compose，所以能在 JVM 单测里跑。
 */
class StatusMappingTest {

    // ---------------- 服务状态优先于连接状态 ----------------

    @Test
    fun `服务未运行且用户主动停止时是暂停`() {
        assertEquals(
            BotStatus.PAUSED,
            resolveBotStatus(running = false, connected = false, userStopped = true)
        )
    }

    @Test
    fun `服务未运行且非用户停止时是离线`() {
        assertEquals(
            BotStatus.OFFLINE,
            resolveBotStatus(running = false, connected = false, userStopped = false)
        )
    }

    /**
     * 关键回归：服务已停但连接标记残留 true。
     *
     * 旧实现在这里返回 ONLINE，因为 `connected` 分支写在了 `!running` 之前。
     * 断言 PAUSED/OFFLINE 就是在锁死「服务没跑就不许说在线」。
     */
    @Test
    fun `服务未运行时残留的 connected 不得渲染为在线`() {
        val paused = resolveBotStatus(running = false, connected = true, userStopped = true)
        val offline = resolveBotStatus(running = false, connected = true, userStopped = false)

        assertEquals(BotStatus.PAUSED, paused)
        assertEquals(BotStatus.OFFLINE, offline)
        assertFalse("服务已停却给出 ONLINE，说明判定顺序被改坏了", paused == BotStatus.ONLINE)
        assertFalse("服务已停却给出 ONLINE，说明判定顺序被改坏了", offline == BotStatus.ONLINE)
    }

    // ---------------- 运行中的四种状态 ----------------

    @Test
    fun `运行且连接正常是在线`() {
        assertEquals(
            BotStatus.ONLINE,
            resolveBotStatus(running = true, connected = true)
        )
    }

    /**
     * connected 优先于失败计数：网关确实好了就该显示在线，
     * 不能因为历史失败次数还没清零就继续报警。
     */
    @Test
    fun `连接正常时忽略历史失败次数`() {
        assertEquals(
            BotStatus.ONLINE,
            resolveBotStatus(
                running = true, connected = true,
                generation = 5, failCount = 99
            )
        )
    }

    @Test
    fun `运行但连续失败三次进入错误`() {
        assertEquals(
            BotStatus.ERROR,
            resolveBotStatus(running = true, connected = false, failCount = 3)
        )
    }

    @Test
    fun `运行且失败不足三次时按代次判定重连或连接中`() {
        assertEquals(
            BotStatus.RECONNECTING,
            resolveBotStatus(running = true, connected = false, generation = 2)
        )
        assertEquals(
            BotStatus.CONNECTING,
            resolveBotStatus(running = true, connected = false, generation = 1)
        )
    }

    /**
     * ERROR 优先于 RECONNECTING：连续失败三次以上说明重连也没用，
     * 继续显示「重连中」会让用户以为再等等就好。
     */
    @Test
    fun `连续失败优先于重连代次`() {
        assertEquals(
            BotStatus.ERROR,
            resolveBotStatus(
                running = true, connected = false,
                generation = 9, failCount = 3
            )
        )
    }

    // ---------------- 派生布尔量 ----------------

    @Test
    fun `只有在线算健康`() {
        assertTrue(BotStatus.ONLINE.isHealthy)
        BotStatus.entries
            .filter { it != BotStatus.ONLINE }
            .forEach { assertFalse("${it.name} 不该算健康", it.isHealthy) }
    }

    @Test
    fun `只有错误与重连需要警示`() {
        assertEquals(
            setOf(BotStatus.ERROR, BotStatus.RECONNECTING),
            BotStatus.entries.filter { it.needsAttention }.toSet()
        )
    }

    /**
     * 连接层与展示层的契约：只有 CONNECTED 可以对外宣称在线。
     * 两处各自维护一份语义，这条断言防止它们漂移。
     */
    @Test
    fun `连接状态里只有 CONNECTED 算在线`() {
        assertTrue(BotConnState.CONNECTED.isOnline)
        BotConnState.entries
            .filter { it != BotConnState.CONNECTED }
            .forEach { assertFalse("${it.name} 不该算在线", it.isOnline) }
    }
}
