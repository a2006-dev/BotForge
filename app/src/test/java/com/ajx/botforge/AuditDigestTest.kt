package com.ajx.botforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计脱敏的回归测试。
 *
 * ## 为什么这段逻辑必须被测
 *
 * [AuditStore.digestArgs] 是「把用户参数写进长期文件」的唯一入口。
 * 它只有两种失败方式，而且都不会报错：
 *
 *  1. **漏脱敏** —— 密钥被原样写进 audit 目录下的 jsonl，用户把日志发出来求助时
 *     顺手把自己的 key 公开了。这种事故不可撤销。
 *  2. **过度脱敏** —— 把正常参数也抹成 ***，审计失去排查价值，
 *     相当于花代价存了一堆无用记录。
 *
 * 两者都是「安静地错」，所以必须用断言把边界钉住。
 */
class AuditDigestTest {

    // ---------------- 必须脱敏 ----------------

    @Test
    fun `键值形式的 apiKey 被替换`() {
        val out = AuditStore.digestArgs("""{"apiKey":"abcdef123456","q":"hi"}""")
        assertFalse("apiKey 的值不该出现", out.contains("abcdef123456"))
        assertTrue("应保留字段名以便排查", out.contains("apiKey"))
    }

    @Test
    fun `等号形式的 token 被替换`() {
        val out = AuditStore.digestArgs("curl -H token=supersecretvalue https://x")
        assertFalse(out.contains("supersecretvalue"))
    }

    @Test
    fun `下划线变体 api_key 同样被替换`() {
        val out = AuditStore.digestArgs("""{"api_key":"zzzz9999yyyy"}""")
        assertFalse(out.contains("zzzz9999yyyy"))
    }

    @Test
    fun `password 与 secret 都被替换`() {
        val out = AuditStore.digestArgs("""{"password":"p@ssw0rd!","secret":"s3cr3t-value"}""")
        assertFalse(out.contains("p@ssw0rd!"))
        assertFalse(out.contains("s3cr3t-value"))
    }

    @Test
    fun `GitHub token 前缀被抹掉`() {
        val out = AuditStore.digestArgs("token ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        assertFalse("ghp_ 后不应跟着原文", out.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
        assertTrue(out.contains("ghp_***"))
    }

    @Test
    fun `Bearer 头被抹掉`() {
        val out = AuditStore.digestArgs("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6"))
    }

    @Test
    fun `Authorization 头的值被替换`() {
        val out = AuditStore.digestArgs("""{"Authorization":"Basic dXNlcjpwYXNz"}""")
        assertFalse(out.contains("dXNlcjpwYXNz"))
    }

    // ---------------- 不该被误伤 ----------------

    @Test
    fun `普通参数原样保留`() {
        val src = """{"path":"/sdcard/Download","limit":20}"""
        val out = AuditStore.digestArgs(src)
        assertTrue("路径应保留", out.contains("/sdcard/Download"))
        assertTrue("数字应保留", out.contains("20"))
    }

    @Test
    fun `空与 null 返回空串`() {
        assertEquals("", AuditStore.digestArgs(null))
        assertEquals("", AuditStore.digestArgs(""))
        assertEquals("", AuditStore.digestArgs("   "))
    }

    @Test
    fun `换行被压成单行`() {
        val out = AuditStore.digestArgs("line1\nline2\r\nline3")
        assertFalse("审计必须单行，否则 JSONL 会被撑破", out.contains("\n"))
        assertTrue(out.contains("line1"))
        assertTrue(out.contains("line3"))
    }

    @Test
    fun `超长参数被截断并留下省略号`() {
        val out = AuditStore.digestArgs("x".repeat(1000))
        assertTrue("应被截断", out.length < 400)
        assertTrue("应提示被截断", out.endsWith("…"))
    }

    // ---------------- 枚举与常量 ----------------

    @Test
    fun `Decision 四档语义齐全`() {
        assertEquals(4, AuditStore.Decision.entries.size)
        assertEquals(
            setOf("ALLOW", "ASK_OK", "ASK_DENY", "BLOCKED"),
            AuditStore.Decision.entries.map { it.name }.toSet()
        )
    }
}
