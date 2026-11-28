package com.ajx.botforge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾约束注入守卫（任务书 Milestone 1 第三项）。
 *
 * ## 为什么用源码断言而不是调用真实逻辑
 *
 * `AgentLoop` 的收费段要发 HTTP，单测里跑不动。但这里要防的不是算法错，
 * 而是**某个字段被构造出来后没送进对话**——正是这个 bug 当初的形态：
 *
 *     val tail = buildString { ... }   约束编好了
 *     （此处原本什么都没有）            从未 msgs.put(...)
 *     val finalMsg = callOnce(...)     收尾请求拿的还是旧上下文
 *
 * 后果很隐蔽：模型不知道轮数已耗尽，于是继续回答「已完成」，
 * 用户在界面上看到的是一句自信的假话。编译通过、单测不报、只有跑起来才露。
 *
 * 这类「构造了但没使用」的问题，用源码断言拦最划算：
 * 成本是一次字符串匹配，收益是把整类问题挡在 CI 之前。
 * 和 term/SourceGuardTest 是同一个思路，只是守的对象不同。
 */
class AgentLoopTailGuardTest {

    private fun readAgentLoop(): String {
        val candidates = listOf(
            File("src/main/java/com/ajx/botforge/ai/tool/AgentLoop.kt"),
            File("app/src/main/java/com/ajx/botforge/ai/tool/AgentLoop.kt")
        )
        val f = candidates.firstOrNull { it.exists() }
        assertTrue(
            "找不到 AgentLoop.kt，尝试过：${candidates.joinToString { it.absolutePath }}",
            f != null
        )
        return f!!.readText()
    }

    /** 取 `val tail = buildString {` 之后到函数结束的那段代码。 */
    private fun sectionAfterTail(src: String): String {
        val marker = "val tail = buildString {"
        val at = src.indexOf(marker)
        assertTrue("AgentLoop 里找不到 `$marker`，收尾约束可能被改名或删除", at >= 0)
        return src.substring(at)
    }

    @Test
    fun `收尾约束必须真的送进对话`() {
        val body = sectionAfterTail(readAgentLoop())

        val injected = body.contains("\"role\", \"system\"") ||
            body.contains("\"role\",\"system\"")
        assertTrue(
            "tail 构造后必须作为 system 消息 put 进 msgs，否则收尾轮看不到约束",
            injected
        )

        // 更严格：tail 这个变量本身必须被使用，不能只留个 JSONObject
        assertTrue(
            "system 消息的 content 必须是 tail 本身，不能换个拼凑字符串糊过去",
            body.contains("\"content\", tail")
        )
    }

    @Test
    fun `收尾约束必须包含禁止谎报完成`() {
        val src = readAgentLoop()
        val body = sectionAfterTail(src)

        // 有效约束的三段话，缺任何一段都会让模型有解释空间
        assertTrue("缺少轮数上限说明", body.contains("轮数已达上限"))
        assertTrue("缺少如实汇报要求", body.contains("如实汇报"))
        assertTrue("缺少禁止谎报完成的硬性要求", body.contains("禁止声称"))
    }

    @Test
    fun `收尾轮失败时必须提示模型如实说明失败原因`() {
        val body = sectionAfterTail(readAgentLoop())
        assertTrue(
            "lastFailed 分支应要求模型交代失败原因，否则用户只看到一句「已完成」",
            body.contains("lastFailed") && body.contains("失败原因")
        )
    }

    @Test
    fun `收尾轮仍然带着工具定义`() {
        // 这条不是防 bug，是防「优化」：曾有人为了省 token 把收尾轮的 tools 去掉，
        // 结果部分反代直接 400。写在测试里，改动的人就会看到原因。
        val src = readAgentLoop()
        val idx = src.indexOf("private fun callOnce(")
        assertTrue("找不到 callOnce", idx >= 0)
        val seg = src.substring(idx)
        assertTrue(
            "callOnce 不应无条件剥离 tools：部分反代在末尾才给工具",
            seg.contains("if (tools != null && tools.length() > 0)")
        )
    }

    // ---------------- 主题迁移守卫 ----------------

    /**
     * 主题 token 守卫：页面不得再 import 已删除的编译期色值常量。
     *
     * Color.kt 已经被删掉，但这类回归的复发方式不是「文件被加回来」，
     * 而是有人把常量重新定义到别处、再 import 进来。所以这里不检查文件，
     * 只检查有没有人绕过 botColors 去取固定色值。
     */
    @Test
    fun `主题必须经由 botColors 取色`() {
        val themeDir = listOf(
            File("src/main/java/com/ajx/botforge/ui"),
            File("app/src/main/java/com/ajx/botforge/ui")
        ).firstOrNull { it.exists() }
        assertTrue("找不到 ui 源码目录", themeDir != null)

        val oldConsts = listOf(
            "Bg0", "Bg1", "Bg2", "Bg3", "Bg4",
            "TextHigh", "TextMid", "TextLow", "TextInverse",
            "BrandPressed", "BrandDisabled", "BrandGhost",
            "LineStrong", "Scrim"
        )
        val offenders = mutableListOf<String>()

        themeDir!!.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { f ->
                // theme 包自身要定义这些名字，跳过
                if (f.parentFile?.name == "theme") return@forEach
                val text = f.readText()
                oldConsts.forEach { c ->
                    // 不用 raw string：正则结尾是 '\s*$'，在 raw string 里
                    // 后接三个引号会让 Kotlin 把末尾的 $ 当成模板起始而编译失败。
                    val re = Regex(
                        "\\bimport\\s+\\S+\\.ui\\.theme\\." +
                            Regex.escape(c) + "\\s*$",
                        RegexOption.MULTILINE
                    )
                    if (re.containsMatchIn(text)) {
                        offenders += f.name + " -> " + c
                    }
                }
            }

        assertEquals(
            "以下文件仍在 import 编译期色值常量，浅色主题下不会变色：" +
                offenders.joinToString(", "),
            0,
            offenders.size
        )
    }

    @Test
    fun `已删除的 Color_kt 不应被重新加回来`() {
        val candidates = listOf(
            File("src/main/java/com/ajx/botforge/ui/theme/Color.kt"),
            File("app/src/main/java/com/ajx/botforge/ui/theme/Color.kt")
        )
        assertEquals(
            "Color.kt 已并入 Tokens.kt 的 Dark/Light 两套。" +
                "重新加回来会让「页面直接吃编译期色值」的老毛病立刻复发。",
            false,
            candidates.any { it.exists() }
        )
    }
}