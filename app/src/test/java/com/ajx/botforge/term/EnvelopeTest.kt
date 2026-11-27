package com.ajx.botforge.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envelope 协议单元测试。
 *
 * ## 为什么这组测试值得写
 *
 * 之前每次改终端相关代码，都要等 CI 跑完 5 分钟、装机、手动点，才能知道
 * 「信封拼得对不对、解析会不会把命令输出吃掉」。而这些问题里绝大多数是
 * **纯字符串逻辑**，根本不需要真机。把协议抽成 [Envelope] 之后，这里覆盖：
 *
 * - 生成的 shell 片段结构是否合法（heredoc 结束符、引号配平、顺序）
 * - token 必须精确隔离（A 命令不能吃掉 B 命令的结束标记）
 * - 解析能否完整剥掉 begin / pid / end / ready 四类回显
 * - 多行命令、含引号和 $ 的命令不被破坏
 * - 超长输出被截断
 * - 未完成（超时）时 finished=false
 */
class EnvelopeTest {

    private val token = "abc123"

    // ------------------------------------------------------------ build

    @Test
    fun `build 包含完整的信封四段`() {
        val e = Envelope.build("echo hi", token)
        assertTrue("缺 begin", e.contains("${Envelope.BEGIN_PREFIX}$token"))
        assertTrue("缺 pid", e.contains("${Envelope.PID_PREFIX}$token"))
        assertTrue("缺 end", e.contains("${Envelope.END_PREFIX}$token"))
        assertTrue("缺 heredoc", e.contains(Envelope.heredocTag(token)))
    }

    @Test
    fun `build 的 heredoc 结束符单独成行且出现两次`() {
        val e = Envelope.build("echo hi", token)
        val tag = Envelope.heredocTag(token)
        val lines = e.lines()
        val opens = lines.count { it.startsWith("cat >") && it.contains("<<'$tag'") }
        val closes = lines.count { it == tag }
        assertEquals("heredoc 开头应恰好一处", 1, opens)
        assertEquals("heredoc 结束符应恰好单独一行", 1, closes)
    }

    @Test
    fun `build 使用带引号的heredoc防止提前展开`() {
        // <<'TAG' 而不是 <<TAG，否则命令里的 $VAR 和 `` 会在写文件前就被
        // 外层 bash 展开，多行脚本会被改坏。
        val e = Envelope.build("echo \$HOME && echo `id`", token)
        assertTrue("heredoc 必须带引号", e.contains("<<'${Envelope.heredocTag(token)}'"))
    }

    @Test
    fun `build 的命令正文原样保留`() {
        val cmd = "for i in 1 2 3\ndo\n  echo \"line \$i\"\ndone"
        val e = Envelope.build(cmd, token)
        assertTrue("多行命令被破坏", e.contains(cmd))
    }

    @Test
    fun `build 不以换行结尾的命令会补换行`() {
        val e = Envelope.build("echo hi", token)
        val tag = Envelope.heredocTag(token)
        assertFalse(
            "命令正文与结束符之间必须有换行",
            e.contains("echo hi$tag")
        )
    }

    @Test
    fun `build 的CRLF会被归一化`() {
        val e = Envelope.build("echo a\r\necho b", token)
        assertFalse("CRLF 未归一化", e.contains("\r\n"))
    }

    @Test
    fun `build 的命令脱离stdin`() {
        // setsid + </dev/null 是这套协议能用的前提：
        // 否则 cat / python3 会抢走管道里后续的信封内容。
        val e = Envelope.build("cat", token)
        assertTrue("缺 setsid", e.contains("setsid "))
        assertTrue("缺 </dev/null", e.contains("</dev/null"))
    }

    @Test
    fun `build 结束码取自wait`() {
        val e = Envelope.build("false", token)
        assertTrue("缺 wait", e.contains("wait \"\$__bf_pid\""))
        assertTrue("缺 \$?", e.contains("__bf_rc=\$?"))
    }

    // ------------------------------------------------------------ parse

    @Test
    fun `parse 正常成功`() {
        val raw = "__BF_BEGIN__:$token\n" +
            "__BF_PID__:$token:1234\n" +
            "hello\n" +
            "__BF_END__:$token:0\n"
        val p = Envelope.parse(raw, token)
        assertTrue(p.finished)
        assertEquals(0, p.exitCode)
        assertEquals("hello", p.output)
    }

    @Test
    fun `parse 非零退出码`() {
        val raw = "boom\n__BF_END__:$token:127\n"
        val p = Envelope.parse(raw, token)
        assertEquals(127, p.exitCode)
        assertEquals("boom", p.output)
    }

    @Test
    fun `parse 剥掉就绪标记`() {
        val raw = "${Envelope.READY_MARKER}\npkg unpacking...\n" +
            "__BF_END__:$token:0\n"
        val p = Envelope.parse(raw, token)
        assertFalse("ready 标记残留", p.output.contains(Envelope.READY_MARKER))
        assertEquals("pkg unpacking", p.output)
    }

    @Test
    fun `parse 不吞掉别的token的结束标记`() {
        // A 命令的输出里如果恰好含 B 的 end 行，不能被当成自己的结束。
        val other = "zzz999"
        val raw = "__BF_BEGIN__:$token\n" +
            "some log __BF_END__:$other:0\n" +
            "__BF_END__:$token:0\n"
        val p = Envelope.parse(raw, token)
        assertEquals(0, p.exitCode)
        assertTrue("别的 token 的标记应保留在输出里", p.output.contains("__BF_END__:$other:0"))
    }

    @Test
    fun `parse 未完成时finished为false`() {
        val raw = "__BF_BEGIN__:$token\npartial output"
        val p = Envelope.parse(raw, token)
        assertFalse(p.finished)
        assertEquals(null, p.exitCode)
        assertTrue(p.output.contains("partial output"))
    }

    @Test
    fun `parse 裁剪首尾空行`() {
        val raw = "\n\n__BF_BEGIN__:$token\n\nout\n\n__BF_END__:$token:0\n\n"
        val p = Envelope.parse(raw, token)
        assertEquals("out", p.output)
    }

    @Test
    fun `parse 超长输出被截断`() {
        val big = "x".repeat(Envelope.MAX_OUTPUT_CHARS + 5000)
        val raw = "$big\n__BF_END__:$token:0\n"
        val p = Envelope.parse(raw, token)
        assertTrue("未截断", p.output.length < Envelope.MAX_OUTPUT_CHARS + 100)
        assertTrue("缺截断提示", p.output.contains("输出过长已截断"))
    }

    @Test
    fun `parse Windows换行被归一化`() {
        val raw = "__BF_BEGIN__:$token\r\na\r\nb\r\n__BF_END__:$token:0\r\n"
        val p = Envelope.parse(raw, token)
        assertEquals("a\nb", p.output)
    }

    @Test
    fun `parse 空输出`() {
        val raw = "__BF_BEGIN__:$token\n__BF_END__:$token:0\n"
        assertEquals("", Envelope.parse(raw, token).output)
    }

    // -------------------------------------------------------- withWorkdir

    @Test
    fun `withWorkdir 为空时不加前缀`() {
        assertEquals("ls", Envelope.withWorkdir(" ls ", null))
        assertEquals("ls", Envelope.withWorkdir("ls", ""))
    }

    @Test
    fun `withWorkdir 加cd前缀并在失败时退出`() {
        val r = Envelope.withWorkdir("ls", "/sdcard")
        assertEquals("cd '/sdcard' || exit 1\nls", r)
    }

    @Test
    fun `withWorkdir 转义单引号`() {
        val r = Envelope.withWorkdir("ls", "/a'b")
        assertTrue("单引号未转义: $r", r.startsWith("cd '/a'\\''b' || exit 1"))
    }

    @Test
    fun `shellQuote 转义`() {
        assertEquals("'plain'", Envelope.shellQuote("plain"))
        assertEquals("'a'\\''b'", Envelope.shellQuote("a'b"))
    }
}