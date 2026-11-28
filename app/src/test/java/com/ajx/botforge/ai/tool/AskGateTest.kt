package com.ajx.botforge.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AskGate 的纯逻辑回归测试。
 *
 * ## 为什么这段逻辑必须被测
 *
 * AskGate 是 ASK 档工具唯一的放行判据。它一旦安静地错，后果分两种：
 *
 *  1. 该拦没拦：未确认的工具直接执行，用户以为有确认其实没有。
 *  2. 该放没放：用户已经确认过，工具仍被拒，模型反复重试，配额白烧。
 *
 * 两者都不抛异常，只会让线上行为看起来怪，所以必须用断言把边界钉住。
 *
 * ## 为什么用假时钟
 *
 * TTL 是本类唯一依赖时间的分支。如果直接读系统时钟，测过期就得真 sleep 61 秒，
 * 这种测试没人愿意跑，等于没测。所以这里用可拨动的假时钟，毫秒级覆盖过期路径。
 *
 * ## 约束
 *
 * 本文件不引入 org.json：JVM 单测里 android 的 org.json 是 not mocked，
 * 碰一下就是运行时异常。AskGate 本身也不依赖 org.json，正好可以完全离线测。
 */
class AskGateTest {

    /** 假时钟当前读数。每个用例自己拨。 */
    private var fakeNow: Long = 1000000L

    private val pkg = "demo.pkg"
    private val tool = "demo_tool"

    @Before
    fun setUp() {
        AskGate.reset()
        fakeNow = 1000000L
        AskGate.useClock { fakeNow }
    }

    private fun requestDefault(): String = AskGate.request(pkg, tool, "digest")

    @Test
    fun requestReturnsNonEmptyCode() {
        val code = requestDefault()
        assertTrue("确认码不能为空", code.isNotEmpty())
        assertEquals("确认码长度应为 6", AskGate.CODE_LENGTH, code.length)
    }

    @Test
    fun twoRequestsReturnDifferentCodes() {
        val a = requestDefault()
        val b = requestDefault()
        assertNotEquals("相邻请求不能拿到同一个码", a, b)
    }

    @Test
    fun codeOnlyUsesSafeAlphabet() {
        val code = requestDefault()
        code.forEach { c ->
            val ok = (c >= 'A' && c <= 'Z') || (c >= '2' && c <= '7')
            assertTrue("出现了字母表之外的字符: " + c, ok)
        }
    }

    @Test
    fun newRequestInvalidatesOldCodeOfSameTool() {
        val old = requestDefault()
        val fresh = requestDefault()
        assertFalse("旧码应该已失效", AskGate.confirm(old))
        assertTrue("新码应该可用", AskGate.confirm(fresh))
    }

    @Test
    fun pendingCarriesPackageToolAndRemainingTtl() {
        val code = requestDefault()
        val p = AskGate.pending(code)
        assertNotNull("刚生成的码应该能查到待确认项", p)
        if (p != null) {
            assertEquals(pkg, p.pkgId)
            assertEquals(tool, p.toolName)
            assertEquals(1, p.attempts)
            assertEquals("初始剩余时长应等于 TTL", AskGate.TTL_MS, p.remainingMs(fakeNow))
        }
    }

    @Test
    fun confirmCorrectCodeGrantsTool() {
        val code = requestDefault()
        assertFalse("确认前不应授权", AskGate.isGranted(pkg, tool))
        assertTrue("正确码应该被接受", AskGate.confirm(code))
        assertTrue("确认后应该放行", AskGate.isGranted(pkg, tool))
    }

    @Test
    fun confirmWrongCodeReturnsFalseWithoutGrant() {
        requestDefault()
        assertFalse("不存在的码必须拒绝", AskGate.confirm("ZZZZZZ"))
        assertFalse("错误码不能产生授权", AskGate.isGranted(pkg, tool))
    }

    @Test
    fun secondConfirmOfSameCodeReturnsFalse() {
        val code = requestDefault()
        assertTrue("第一次应该成功", AskGate.confirm(code))
        assertFalse("一次性，第二次必须失败", AskGate.confirm(code))
    }

    @Test
    fun confirmToleratesCaseSpaceAndHyphen() {
        val code = requestDefault()
        val messy = "  " + code.lowercase() + "-"
        assertTrue("用户手打应该有容错", AskGate.confirm(messy))
        assertTrue(AskGate.isGranted(pkg, tool))
    }

    @Test
    fun denyCorrectCodeReturnsTrueWithoutGrant() {
        val code = requestDefault()
        assertTrue("正确码应该被拒绝受理", AskGate.deny(code))
        assertFalse("拒绝不能变成授权", AskGate.isGranted(pkg, tool))
        assertTrue("拒绝应留痕", AskGate.wasDenied(pkg, tool))
    }

    @Test
    fun denyRevokesExistingGrant() {
        val first = requestDefault()
        assertTrue(AskGate.confirm(first))
        assertTrue("先假设已授权", AskGate.isGranted(pkg, tool))
        val second = requestDefault()
        assertTrue(AskGate.deny(second))
        assertFalse("明确拒绝后不能再放行", AskGate.isGranted(pkg, tool))
    }

    @Test
    fun confirmAfterTtlReturnsFalse() {
        val code = requestDefault()
        fakeNow += AskGate.TTL_MS + 1000L
        assertFalse("过期码必须拒绝", AskGate.confirm(code))
        assertFalse(AskGate.isGranted(pkg, tool))
    }

    @Test
    fun expiredToolStaysUngrantedAndPendingCleared() {
        requestDefault()
        fakeNow += AskGate.TTL_MS + 1000L
        assertFalse("未确认的工具始终不该放行", AskGate.isGranted(pkg, tool))
        assertEquals("过期项应被清理，不能虚高", 0, AskGate.pendingCount())
    }

    @Test
    fun confirmJustBeforeTtlStillWorks() {
        val code = requestDefault()
        fakeNow += AskGate.TTL_MS - 1L
        assertTrue("TTL 边界内不该提前失效", AskGate.confirm(code))
        assertTrue(AskGate.isGranted(pkg, tool))
    }

    @Test
    fun resetClearsGrantsAndPending() {
        assertTrue(AskGate.confirm(requestDefault()))
        assertTrue(AskGate.isGranted(pkg, tool))
        val old = AskGate.request(pkg, "other_tool", "d")
        AskGate.reset()
        assertFalse("reset 后必须回到未授权", AskGate.isGranted(pkg, tool))
        assertEquals(0, AskGate.pendingCount())
        assertNull("reset 后旧码应查不到", AskGate.pending(old))
    }

    @Test
    fun differentToolNamesAreIsolated() {
        val a = AskGate.request(pkg, "tool_a", "d")
        val b = AskGate.request(pkg, "tool_b", "d")
        assertTrue(AskGate.confirm(a))
        assertTrue("tool_a 已授权", AskGate.isGranted(pkg, "tool_a"))
        assertFalse("tool_b 不能跟着放行", AskGate.isGranted(pkg, "tool_b"))
        assertFalse("已消费的码不能再用", AskGate.confirm(a))
        assertTrue("tool_b 自己的码仍然有效", AskGate.confirm(b))
    }

    @Test
    fun sameToolNameInDifferentPackagesAreIsolated() {
        val a = AskGate.request("pkg.a", tool, "d")
        val b = AskGate.request("pkg.b", tool, "d")
        assertTrue(AskGate.confirm(a))
        assertTrue(AskGate.isGranted("pkg.a", tool))
        assertFalse("同名不同包不能串授权", AskGate.isGranted("pkg.b", tool))
        assertTrue(AskGate.confirm(b))
        assertTrue(AskGate.isGranted("pkg.b", tool))
    }

    @Test
    fun revokeOnlyAffectsGivenTool() {
        assertTrue(AskGate.confirm(AskGate.request(pkg, "tool_a", "d")))
        AskGate.revoke(pkg, "tool_a")
        assertFalse("撤销后必须重新确认", AskGate.isGranted(pkg, "tool_a"))
    }

    @Test
    fun blankCodesAreAlwaysRejected() {
        assertFalse(AskGate.confirm(""))
        assertFalse(AskGate.confirm("   "))
        assertFalse(AskGate.deny(""))
        assertFalse(AskGate.isGranted(pkg, tool))
    }
}
