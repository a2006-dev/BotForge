package com.ajx.botforge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI 配置守卫（任务书 Milestone 1 第三项）。
 *
 * ## 为什么这一项必须用测试来守
 *
 * 打包流水线里有一步 `Build debug` 被设成了 `continue-on-error: true`。
 * 于是编译失败时整个 run 依然显示 ✓，绿得像没事。
 *
 * 这个设置的代价在 1.8.x 时暴露过：连续几个提交的「CI 全绿」里，
 * 有的是真绿，有的是 debug 编译其实挂了但被吞掉，最后靠装包才发现。
 * 一个永久为真的信号，等于没有信号。
 *
 * 用 `gradle :app:testDebugUnitTest` 来守它还有一个附带好处：
 * 这一步本身在 workflow 里排在 Build 之前，一旦配置被改坏，
 * 会在「还没开始打包」的阶段就报出来。
 */
class CiWorkflowGuardTest {

    private fun readWorkflow(): String {
        val candidates = listOf(
            File("../.github/workflows/build.yml"),
            File(".github/workflows/build.yml")
        )
        val f = candidates.firstOrNull { it.exists() }
        assertTrue(
            "找不到 build.yml，尝试过：${candidates.joinToString { it.absolutePath }}",
            f != null
        )
        return f!!.readText()
    }

    @Test
    fun `debug 与 release 编译失败不得被 continue-on-error 吞掉`() {
        val src = readWorkflow()

        // 逐个 step 检查：从 "- name: Xxx" 到下一条 "- name:" 之间算一段
        val blocks = src.split(Regex("""(?m)^\s*-\s*name:\s*""")).drop(1)
        val offenders = blocks.filter { b ->
            val title = b.lineSequence().first().trim()
            val isBuild = title.contains("Build debug", ignoreCase = true) ||
                title.contains("Build release", ignoreCase = true)
            isBuild && b.contains("continue-on-error: true")
        }.map { it.lineSequence().first().trim() }

        assertEquals(
            "以下编译步骤设置了 continue-on-error，失败也显示成功：" +
                offenders.joinToString(", ") +
                "。要「失败但产物仍可用」请用 `|| true` 之类写进 run，" +
                "不要用 continue-on-error 让整步失真。",
            0,
            offenders.size
        )
    }

    @Test
    fun `单元测试必须排在打包之前`() {
        val src = readWorkflow()
        val testAt = src.indexOf("testDebugUnitTest")
        val debugAt = src.indexOf("assembleDebug")
        assertTrue("workflow 里找不到 testDebugUnitTest", testAt >= 0)
        assertTrue("workflow 里找不到 assembleDebug", debugAt >= 0)
        assertTrue(
            "单测必须先于打包执行，否则编译类问题要等几分钟打包后才暴露",
            testAt < debugAt
        )
    }

    @Test
    fun `CI 必须校验运行时真正使用的 rootfs`() {
        val src = readWorkflow()
        // 运行时绑定的是 termux-rootfs.tar（见 TerminalEnvironment.TERMUX_PREFIX）。
        // 校验却查 alpine 的话，装完才会发现「缺少运行环境组件」。
        assertTrue(
            "APK 校验必须检查 assets/rootfs/termux-rootfs.tar",
            src.contains("assets/rootfs/termux-rootfs.tar")
        )
        assertTrue(
            "不应再校验 alpine-rootfs.tar：运行时不用它，校验它只会给出虚假安全感",
            !src.contains("alpine-rootfs.tar")
        )
    }

    @Test
    fun `密钥不得出现在构建脚本里`() {
        val src = readWorkflow()
        // 只允许从 secrets 读，硬编码的 key 会被 git 历史永久记住
        val hardcoded = Regex("""ghp_[A-Za-z0-9]{20,}""")
        assertEquals(
            "工作流里出现了疑似硬编码的 GitHub token",
            false,
            hardcoded.containsMatchIn(src)
        )
    }
}