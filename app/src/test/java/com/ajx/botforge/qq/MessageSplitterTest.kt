package com.ajx.botforge.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessageSplitter] 的回归测试。
 *
 * ## 为什么这段逻辑值得单独钉住
 *
 * 它是「模型输出 → 真正发到聊天窗」之间的最后一道整形：分片点算错，
 * 用户看到的就是半句话；分片数算错，一次回复会刷屏；内容丢字符则是静默的，
 * 线上只表现为「机器人说话缺字」，从日志里根本看不出来。
 *
 * 因此这里按「边界 / 断句 / 硬切 / 退化输入 / 内容等价」五组分别钉住。
 *
 * 本文件只依赖 JUnit：MessageSplitter 是纯函数，不 import android 与 org.json，
 * 一旦引入它们，JVM 单测会直接抛 not mocked。
 */
class MessageSplitterTest {

    // ------------------------------------------------------------ 边界

    /** 短文本不该被切开，且要原样返回（不去空白、不加包装） */
    @Test
    fun shortText_isNotSplit() {
        val text = "  你好，世界  "
        val out = MessageSplitter.split(text, 1200)
        assertEquals(1, out.size)
        assertEquals(text, out[0])
    }

    /** 长度恰好等于上限时不分片：判据是 <= limit，不是 < limit */
    @Test
    fun lengthExactlyAtLimit_isSingleChunk() {
        val text = "0123456789"
        val out = MessageSplitter.split(text, 10)
        assertEquals(1, out.size)
        assertEquals(text, out[0])
    }

    /** 比上限多一个字就要分片，不能因为「差不多」而放过 */
    @Test
    fun lengthJustOverLimit_isSplit() {
        val text = "a".repeat(1201)
        assertEquals(1, MessageSplitter.split("a".repeat(1200)).size)
        val out = MessageSplitter.split(text)
        assertEquals(2, out.size)
        assertEquals(1200, out[0].length)
        assertEquals(1, out[1].length)
    }

    /** 空串是合法输入：返回一个空分片，不能返回空列表（否则调用方 forEach 会漏发） */
    @Test
    fun emptyString_returnsSingleEmptyChunk() {
        val out = MessageSplitter.split("")
        assertEquals(1, out.size)
        assertEquals("", out[0])
    }

    // ------------------------------------------------------------ 断句

    /** 超长文本要优先在句号处断开，而不是在句子中间硬切 */
    @Test
    fun longText_breaksAtPunctuation() {
        val text = "你好世界。你好世界。你好世界。"
        val out = MessageSplitter.split(text, 10)
        assertEquals(2, out.size)
        assertTrue("首片应以句号收尾，实际=" + out[0], out[0].endsWith("。"))
        assertEquals("你好世界。", out[1])
    }

    /** 断点必须落在后 2/3 区间：太靠前（i <= limit/3）的标点要被忽略 */
    @Test
    fun breakPoint_tooEarly_isIgnored() {
        // 句号在下标 1，limit=9，limit/3=3，1 <= 3 应被忽略；
        // 后面没有别的标点，于是硬切在 9。
        val text = "a。bbbbbbbbbbbb"
        val out = MessageSplitter.split(text, 9)
        assertEquals(2, out.size)
        assertEquals("a。bbbbbbb", out[0])
        assertEquals(9, out[0].length)
        assertEquals("bbbbb", out[1])
    }

    /** 连续换行要被合并掉，不能产出空分片 */
    @Test
    fun consecutiveNewlines_produceNoBlankChunk() {
        val text = "aaaa\n\n\n\nbbbbbbbbbbbb"
        val out = MessageSplitter.split(text, 10)
        assertEquals(3, out.size)
        assertEquals("aaaa", out[0])
        assertEquals("bbbbbbbbbb", out[1])
        assertEquals("bb", out[2])
        out.forEach { assertTrue("不应出现空白分片", it.isNotEmpty()) }
    }

    // ------------------------------------------------------------ 硬切

    /** 完全无标点、无空格、无换行时必须按上限硬切，且不丢字符 */
    @Test
    fun noPunctuation_hardCutsAtLimit() {
        val text = "0123456789".repeat(4)
        val out = MessageSplitter.split(text, 10)
        assertEquals(4, out.size)
        out.forEach { assertEquals("0123456789", it) }
        assertEquals(text, out.joinToString(""))
    }

    // ------------------------------------------------------------ 内容等价

    /** 多片按顺序拼接后必须与原文逐字相等（这是最能兜住「丢字」的一条） */
    @Test
    fun chunks_concatenateBackToOriginal() {
        val text = "句子一内容。句子二内容。句子三内容。"
        val out = MessageSplitter.split(text, 12)
        assertEquals(2, out.size)
        assertEquals(text, out.joinToString(""))
    }

    /** limit 非默认值时行为一致：小 limit 也应正确分段且内容等价 */
    @Test
    fun nonDefaultLimit_behavesConsistently() {
        val text = "abcdefghij"
        val out = MessageSplitter.split(text, 5)
        assertEquals(2, out.size)
        assertEquals("abcde", out[0])
        assertEquals("fghij", out[1])
        assertEquals(text, out.joinToString(""))
        // 同一段文本用默认上限时不该被切开
        assertEquals(1, MessageSplitter.split(text).size)
    }

    /** 默认上限必须仍是 1200，改了会直接影响 QQ 侧的单条拒收率 */
    @Test
    fun defaultLimit_isStill1200() {
        assertEquals(1200, MessageSplitter.DEFAULT_LIMIT)
    }
}
