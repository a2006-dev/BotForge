package com.ajx.botforge.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * AgentLoop.AuditBridge 的回归测试。
 *
 * ## 为什么这段逻辑值得单独测
 *
 * 审计的三个归属字段（botId / scene / userOpenId）以前是写死在 ToolExecutor 里的
 * 空值，结果所有审计记录都无归属。改成从桥里取之后，判据变成一句话：
 * 「取得就记真实的，取不到就退回旧默认值」。这句话错在任何一边都会出事：
 *
 *  1. 取得却没用上：字段还是空的，改了等于没改（这次要修的正是这个）。
 *  2. 取不到却记住了上一条：审计把 A 群 A 用户的命令记到 B 群 B 用户名下，
 *     这比空归属更糟，因为它是「看起来很可信的错数据」，排查时会被当成真的。
 *
 * 所以这里不测 AgentLoop 的整条循环，只测桥的读写和清空语义。
 * 桥是纯内存对象，不碰 org.json、不碰 android，可以在 JVM 单测里直接跑。
 *
 * ## 注意：桥是进程级静态状态
 *
 * 测试方法之间共享同一个桥，跑完一个用例不清就会污染下一个。
 * 因此每个用例执行前统一清空（见 [resetBridge]），不依赖执行顺序。
 */
class AuditContextTest {

    /**
     * 每个用例前把桥清空。
     *
     * 桥的状态是 object 里的静态字段，生命周期比测试实例长；
     * 不做这一步，用例顺序一换就可能出现「单独跑过、一起跑挂」的假失败。
     */
    @Before
    fun resetBridge() {
        AgentLoop.AuditBridge.clear()
    }

    /** 全新状态下桥里不该有任何上下文，否则 ToolExecutor 会拿到脏数据。 */
    @Test
    fun bridgeIsNullByDefault() {
        assertNull(AgentLoop.AuditBridge.get())
    }

    /** 放进去再取出来，三个字段必须原样返回，不能被改动或丢失。 */
    @Test
    fun setThenGetReturnsSameFields() {
        val ctx = AgentLoop.AuditContext(
            botId = "bot-1001",
            scene = "group",
            userOpenId = "openid-abc"
        )
        AgentLoop.AuditBridge.set(ctx)

        val got = AgentLoop.AuditBridge.get()
        assertNotNull(got)
        assertEquals("bot-1001", got!!.botId)
        assertEquals("group", got.scene)
        assertEquals("openid-abc", got.userOpenId)
    }

    /** 取出来的应该是放进去的同一个对象，不能是复制品，否则字段将来变化会对不上。 */
    @Test
    fun getReturnsExactInstanceThatWasSet() {
        val ctx = AgentLoop.AuditContext(botId = "bot-2", scene = "c2c", userOpenId = null)
        AgentLoop.AuditBridge.set(ctx)
        assertSame(ctx, AgentLoop.AuditBridge.get())
    }

    /** run 退出时清空桥，之后必须取不到：这是「不把归属串给下一条消息」的前提。 */
    @Test
    fun clearMakesGetReturnNull() {
        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-3", scene = "group", userOpenId = "u3")
        )
        assertNotNull(AgentLoop.AuditBridge.get())

        AgentLoop.AuditBridge.clear()

        assertNull(AgentLoop.AuditBridge.get())
    }

    /** 连续两条消息先后进入 run，后一条的上下文必须完整覆盖前一条。 */
    @Test
    fun secondSetOverridesFirstCompletely() {
        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-A", scene = "group", userOpenId = "user-A")
        )
        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-B", scene = "c2c", userOpenId = null)
        )

        val got = AgentLoop.AuditBridge.get()
        assertNotNull(got)
        assertEquals("bot-B", got!!.botId)
        assertEquals("c2c", got.scene)
        // 从有值覆盖到 null 也要生效，否则上一条的用户会被记到这一条上
        assertNull(got.userOpenId)
    }

    /** 同一次工具调用里可能连取两次（granted 与 pending 两条路径），两次结果必须一致。 */
    @Test
    fun repeatedGetReturnsConsistentResult() {
        val ctx = AgentLoop.AuditContext(botId = "bot-5", scene = "group", userOpenId = "u5")
        AgentLoop.AuditBridge.set(ctx)

        val first = AgentLoop.AuditBridge.get()
        val second = AgentLoop.AuditBridge.get()

        assertSame(first, second)
        assertEquals(first, second)
        assertEquals("u5", second!!.userOpenId)
    }

    /** 清空之后还能再次设置，不能出现「清过一次就再也写不进去」。 */
    @Test
    fun setWorksAgainAfterClear() {
        AgentLoop.AuditBridge.set(AgentLoop.AuditContext(botId = "bot-6"))
        AgentLoop.AuditBridge.clear()
        assertNull(AgentLoop.AuditBridge.get())

        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-7", scene = "group", userOpenId = "u7")
        )

        assertEquals("bot-7", AgentLoop.AuditBridge.get()!!.botId)
    }

    /** 显式 set(null) 与 clear() 等价：取不到时 ToolExecutor 才会走回落分支。 */
    @Test
    fun setNullBehavesLikeClear() {
        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-8", scene = "group", userOpenId = "u8")
        )

        AgentLoop.AuditBridge.set(null)

        assertNull(AgentLoop.AuditBridge.get())
    }

    /**
     * 回落值就是改造前的硬编码值：空 botId、scene 为 internal、userOpenId 为 null。
     *
     * ToolExecutor 取不到上下文时用的是 `AuditBridge.get() ?: AuditContext()`，
     * 也就是这里的默认实例。把这个约定钉在测试里，
     * 以后谁改了 data class 的默认值，这里会先亮红，而不是等线上审计出现空归属才发现。
     */
    @Test
    fun defaultAuditContextMatchesLegacyFallbackValues() {
        val fallback = AgentLoop.AuditContext()

        assertEquals("", fallback.botId)
        assertEquals("internal", fallback.scene)
        assertNull(fallback.userOpenId)
    }

    /** 桥里的上下文与默认实例必须能区分开，否则回落分支形同虚设。 */
    @Test
    fun bridgeContextIsDistinguishableFromFallback() {
        AgentLoop.AuditBridge.set(
            AgentLoop.AuditContext(botId = "bot-9", scene = "group", userOpenId = "u9")
        )

        val ctx = AgentLoop.AuditBridge.get() ?: AgentLoop.AuditContext()

        assertEquals("bot-9", ctx.botId)
        assertEquals("group", ctx.scene)
        assertEquals("u9", ctx.userOpenId)
    }
}
