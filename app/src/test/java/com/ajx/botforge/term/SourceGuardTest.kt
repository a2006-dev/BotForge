package com.ajx.botforge.term

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码结构守卫。
 *
 * ## 为什么要用测试来检查源文件
 *
 * 这个仓库在 1.4.7 / 1.4.8 / 1.4.9 / 1.7.0 上连续踩过同一类问题：
 *
 * 1. 编辑脚本吃掉 `fun xxx` 前的四格缩进，函数掉出 `object`，编译器报出
 *    一堆 `Unresolved reference`，还附带一个完全误导的 `Missing }`；
 * 2. 1.7.0 行尾多了一个引号，CI 报 `Unexpected tokens`，等 5 分钟才知道。
 *
 * 这两类问题的共同点是：**不需要运行 App 就能判断**。放进单元测试后，
 * `gradle :app:testDebugUnitTest` 会先于打包把它们拦下来。
 *
 * 测试通过相对路径定位源文件。Gradle 跑单测时工作目录是 app 模块，
 * 所以从 `src/main/java` 直接找即可；同时兼容从仓库根目录运行的情况。
 */
class SourceGuardTest {

    private fun read(rel: String): String {
        val candidates = listOf(
            File("src/main/java/com/ajx/botforge/$rel"),
            File("app/src/main/java/com/ajx/botforge/$rel")
        )
        val f = candidates.firstOrNull { it.exists() }
        assertTrue(
            "找不到源文件 $rel，尝试过：${candidates.joinToString { it.absolutePath }}",
            f != null
        )
        return f!!.readText()
    }

    private val kotlinFiles = listOf(
        "term/Envelope.kt",
        "term/HiddenExec.kt",
        "term/TerminalEnvironment.kt",
        "term/TerminalSession.kt",
        "term/LinuxShell.kt",
        "ai/tool/BuiltinTools.kt"
    )

    @Test
    fun `花括号必须配平`() {
        kotlinFiles.forEach { rel ->
            val s = read(rel)
            // 粗略但有效：Kotlin 字符串里出现不成对花括号的概率极低，
            // 而这里要抓的是「编辑脚本把函数挪出类」这类结构性破坏。
            assertEquals(
                "$rel 花括号不配平",
                s.count { it == '{' },
                s.count { it == '}' }
            )
        }
    }

    @Test
    fun `顶层只能有 package import 声明与少量顶层声明`() {
        kotlinFiles.forEach { rel ->
            val s = read(rel)
            s.lines().forEachIndexed { idx, raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) return@forEachIndexed
                if (line.startsWith("package ")) return@forEachIndexed
                if (line.startsWith("import ")) return@forEachIndexed
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                if (line.startsWith("/*") || line.startsWith("*/")) return@forEachIndexed
                // 两个空格及以上的缩进属于类体内部，跳过
                if (line.startsWith(" ")) return@forEachIndexed
                // 允许的顶层形态：object / class / fun / val / const / @
                // 类/函数的续行（如 `class Foo(\n  ...\n) {`）也合法
                if (line == ") {" || line.startsWith(") :") || line.startsWith("){")) {
                    return@forEachIndexed
                }
                val ok = line == "}" ||
                    line.startsWith("object ") ||
                    line.startsWith("class ") ||
                    line.startsWith("data class ") ||
                    line.startsWith("fun ") ||
                    line.startsWith("val ") ||
                    line.startsWith("internal ") ||
                    line.startsWith("@")
                assertTrue(
                    "$rel:${idx + 1} 出现可疑的顶层行（很可能是缩进被吃掉，函数掉出了 object）：\n  $line",
                    ok
                )
            }
        }
    }

    @Test
    fun `object 声明后必须有配对的收尾`() {
        val special = listOf("term/Envelope.kt", "term/HiddenExec.kt", "term/TerminalEnvironment.kt")
        special.forEach { rel ->
            val lines = read(rel).lines().map { it.trimEnd() }
            val start = lines.indexOfFirst { it.startsWith("object ") }
            assertTrue("$rel 找不到顶层 object", start >= 0)
            val last = lines.lastOrNull { it.isNotBlank() }
            assertEquals("$rel 最后一行应该是 object 的收尾括号", "}", last)
        }
    }

    @Test
    fun `行尾不允许出现连续两个引号`() {
        // 1.7.0 的编译失败就是 joinToString(" ")" 末尾多了个引号。
        // Kotlin 里 `= "x""` 这种结尾一定是笔误。
        kotlinFiles.forEach { rel ->
            read(rel).lines().forEachIndexed { idx, raw ->
                val line = raw.trimEnd()
                if (line.endsWith("\"\"")) {
                    // 允许的是在字符串里显式写空串，形如 .equals("") 或以 ", \"\"" 结尾，
                    // 这些都不会以裸引号结尾。这里只抓真正可疑的收尾。
                    val suspicious = !line.contains("(") || !line.contains(")")
                    assertTrue(
                        "$rel:${idx + 1} 行尾连续引号，疑似笔误：\n  $line",
                        !suspicious
                    )
                }
            }
        }
    }

    @Test
    fun `Envelope 与 HiddenExec 的协议常量保持一致`() {
        val hidden = read("term/HiddenExec.kt")
        assertTrue(
            "HiddenExec 的就绪标记应从 Envelope 取，避免两处硬编码漂移",
            hidden.contains("Envelope.READY_MARKER")
        )
        assertTrue(
            "HiddenExec 不应再自带一份 end 标记常量",
            !hidden.contains("const val END_PREFIX")
        )
    }

    /**
     * Compose 注解守卫。
     *
     * 这个坑在 1.8.8 上连续踩了两次：往 SettingsScreen 里插新 Composable 时，
     * 插入点带着 `@Composable` 前缀，结果出现「注解 + 注释 + 注解」，
     * 编译器报 `This annotation is not repeatable`；把多的那个删掉，
     * 又连带删掉了原有函数的注解，报 `@Composable invocations can only
     * happen from the context of a @Composable function`。
     *
     * 两次都只影响编译、不影响逻辑，但每次要等几分钟 CI 才知道。
     * 这里用源码断言把它提前到单测阶段。
     *
     * 判定刻意保守：只认「大写的 Composable 函数名 + 左括号」这种确定形态，
     * 加上 Column/Row/Box/Spacer 这类容器。
     * 不要收 Text( —— 它在字符串字面量里太常见（"断线"/"断线文本" 等），
     * 也不要用裸词匹配（HeroMetric 这种数据类会被误判）。
     */
    @Test
    fun `使用 Composable 的函数必须带 @Composable 注解`() {
        val files = listOf(
            "ui/screens/SettingsScreen.kt",
            "ui/screens/DashboardScreen.kt",
            "ui/components/Buttons.kt",
            "ui/components/Layout.kt"
        )
        // 只匹配「函数调用」形态：大写开头标识符紧跟左括号
        val composableCall = Regex(
            "\\b(Column|Row|Box|Spacer|Surface|LazyColumn|LazyRow|" +
            "SettingGroup|ThemeRow|SwitchRow|ActionRow|TrackSwitch|" +
            "PageTopBar|SettingDivider|SectionHeader|SmallButton|" +
            "PrimaryButton|DangerButton|StatusHero|AttentionCard)\\s*\\("
        )
        files.forEach { rel ->
            val lines = read(rel).lines()
            lines.forEachIndexed { i, raw ->
                val m = Regex("^(?:private\\s+)?fun\\s+(\\w+)").find(raw) ?: return@forEachIndexed
                val name = m.groupValues[1]
                // 粗扫函数体
                var depth = 0
                var started = false
                var j = i + 1
                while (j < lines.size) {
                    depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                    if (lines[j].contains('{')) started = true
                    if (started && depth <= 0) break
                    j++
                }
                val body = lines.subList(i + 1, minOf(j + 1, lines.size)).joinToString("\n")
                // 去掉行注释与字符串字面量，避免 "断线" 之类被当成调用
                val cleaned = body
                    .replace(Regex("//[^\\n]*"), "")
                    .replace(Regex("\"([^\"\\\\]|\\\\.)*\""), "\"\"")
                if (!composableCall.containsMatchIn(cleaned)) return@forEachIndexed

                var k = i - 1
                var ann: String? = null
                while (k >= 0) {
                    val t = lines[k].trim()
                    if (t.isEmpty()) { k--; continue }
                    ann = t
                    break
                }
                assertEquals(
                    "$rel:${i + 1} 函数 $name 调用了 Composable 却没有 @Composable 注解" +
                        "（上一行是 ${ann ?: "文件开头"}）",
                    "@Composable",
                    ann
                )
            }
        }
    }
}
