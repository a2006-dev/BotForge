package com.ajx.botforge.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.bfpack` 导入器的安全边界测试。
 *
 * ## 为什么只测这些
 *
 * 完整的导入流程要经过 [PluginManifest.parse]，而它依赖 `org.json`。
 * JVM 单测里 `android.jar` 是桩，`JSONObject` 的方法会抛
 * `RuntimeException: Method ... not mocked`，所以凡是会走到 parse 的用例
 * 都跑不了。
 *
 * 这里只测**不经过 parse** 的部分，也就是最容易出安全事故的那一层：
 * 解压时的路径校验、体积限制、临时目录清理、卸载的 id 校验。
 * 这些逻辑全是纯文件系统操作，不碰 JSON，能在 JVM 上稳定运行。
 *
 * manifest 语义（api 版本、指令必填、入口缺失）的验证放到真机或用
 * Robolectric 的集成测试里，不在这一层硬凑。
 */
class BfpackImporterTest {

    private fun tmpRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "bfpack_test_${System.nanoTime()}").apply { mkdirs() }

    /** 造一个 zip，entries 是 (路径, 内容) */
    private fun makeZip(target: File, vararg entries: Pair<String, String>): File {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return target
    }

    // ---------------- 路径穿越：最关键的三个 ----------------

    /**
     * 上层目录穿越。
     *
     * 断言两件事：必须抛异常，且文件不能真的落到目标目录之外。
     * 只断言抛异常是不够的——有可能先写了文件再抛。
     */
    @Test
    fun `拒绝上层目录穿越`() {
        val root = tmpRoot()
        val outside = File(root.parentFile, "bfpack_escaped_${System.nanoTime()}.txt")
        val pack = makeZip(
            File(root, "evil.bfpack"),
            "../${outside.name}" to "leaked"
        )
        var threw = false
        try {
            BfpackImporter.preview(pack, root)
        } catch (e: SecurityException) {
            threw = true
        }
        assertTrue("含 ../ 的条目必须抛 SecurityException", threw)
        assertFalse("绝不能把文件写到上级目录", outside.exists())
    }

    /**
     * 嵌套形式的穿越。
     *
     * `a/b/../../../x` 在字符串上不以 `../` 开头，
     * 用 startsWith("../") 做校验的实现会漏掉这种。这里钉住逐段判断的行为。
     */
    @Test
    fun `拒绝嵌套形式穿越`() {
        val root = tmpRoot()
        val pack = makeZip(
            File(root, "evil2.bfpack"),
            "a/b/../../../bfpack_escaped2_${System.nanoTime()}.txt" to "x"
        )
        var threw = false
        try {
            BfpackImporter.preview(pack, root)
        } catch (e: SecurityException) {
            threw = true
        }
        assertTrue("a/b/../.. 也是穿越，必须拒绝", threw)
    }

    @Test
    fun `拒绝绝对路径条目`() {
        val root = tmpRoot()
        val pack = makeZip(File(root, "abs.bfpack"), "/data/local/tmp/bf_abs_test.txt" to "x")
        var threw = false
        try {
            BfpackImporter.preview(pack, root)
        } catch (e: SecurityException) {
            threw = true
        }
        assertTrue("以 / 开头的条目必须拒绝", threw)
    }

    // ---------------- 临时目录清理 ----------------

    /**
     * preview 是「点开看看」的动作，用户可能根本不装。
     * 如果每次都留一份解压目录，用几次就攒出一堆垃圾。
     */
    @Test
    fun `preview 不留下临时目录`() {
        val root = tmpRoot()
        val pack = makeZip(
            File(root, "p.bfpack"),
            "plugin.json" to "{}",
            "main.sh" to "echo hi"
        )
        // 这里不关心 preview 返回什么（会因缺 commands 判失败），
        // 只关心它有没有把 .staging_ 目录留下。
        runCatching { BfpackImporter.preview(pack, root) }
        val leftovers = root.listFiles { f -> f.name.startsWith(".staging_") } ?: emptyArray()
        assertEquals("preview 必须清理临时目录", 0, leftovers.size)
    }

    // ---------------- 卸载 ----------------

    @Test
    fun `卸载会删掉包目录`() {
        val root = tmpRoot()
        val pkg = File(root, "com.demo.rm").apply { mkdirs() }
        File(pkg, "plugin.json").writeText("{}")
        File(pkg, "main.sh").writeText("echo hi")
        assertNull("正常卸载应返回 null", BfpackImporter.uninstall(root, "com.demo.rm"))
        assertFalse("目录应被删除", pkg.exists())
    }

    /**
     * 卸载的 id 来自界面，但界面也可能传进 URL 解码过的怪东西。
     * 这几个都是能穿越出插件目录的输入，必须挡住。
     */
    @Test
    fun `卸载拒绝非法 id`() {
        val root = tmpRoot()
        assertNotNull(".. 必须拒绝", BfpackImporter.uninstall(root, ".."))
        assertNotNull("带斜杠必须拒绝", BfpackImporter.uninstall(root, "a/b"))
        assertNotNull("空串必须拒绝", BfpackImporter.uninstall(root, " "))
        assertNotNull("反斜杠路径必须拒绝", BfpackImporter.uninstall(root, "a\\b"))
    }

    @Test
    fun `卸载不存在的包给出明确原因`() {
        val root = tmpRoot()
        val e = BfpackImporter.uninstall(root, "com.not.exists")
        assertNotNull("应返回错误", e)
        assertTrue("应说明没找到，而不是静默成功", e!!.contains("没有找到"))
    }
}