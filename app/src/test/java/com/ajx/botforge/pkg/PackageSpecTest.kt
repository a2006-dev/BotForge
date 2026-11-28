package com.ajx.botforge.pkg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一包模型（.bfpack v2）的回归测试。
 *
 * ## 为什么这些断言值得写
 *
 * [PackageSpec] 里全是「宽容解析 + 钳制 + 归一」的纯函数。这类逻辑有个共同点：
 * 出错时不抛异常，只是安静地给出一个「看起来合理」的值。例如
 * Runtime 解析退化成默认值、权限 risk 写错档位、timeout 钳制边界偏一格。
 * 都不会崩，只会让用户在设置页看到错误的权限排序、或者指令在 15 秒而不是
 * 120 秒时被掐断，然后来报「偶发失败」。所以边界必须被钉住。
 *
 * ## 为什么不 import 任何 JSON 库
 *
 * 本测试跑在 Android 单元测试环境（android.jar 桩）上，该桩里的 JSON 类
 * 任何方法都会抛 not mocked。一旦在测试路径上碰到它，整个用例会以环境错误失败，
 * 而不是以断言失败失败——那样的红是假红，会掩盖真正的缺陷。
 * 所以模型层与本测试都保持零 JSON 依赖，JSON 触点全部集中在
 * PackageManifestReader（它不在本测试覆盖范围内，只能在设备上验证）。
 */
class PackageSpecTest {

    // ---------------- 1. Runtime 解析 ----------------

    @Test
    fun `runtime 解析 proot`() {
        assertEquals(Runtime.PROOT, Runtime.parse("proot"))
        assertEquals("大小写与首尾空白都应宽容", Runtime.PROOT, Runtime.parse("  PROOT  "))
    }

    @Test
    fun `runtime 解析 android_sh`() {
        assertEquals(Runtime.ANDROID_SH, Runtime.parse("android_sh"))
        assertEquals("连字符写法同样接受", Runtime.ANDROID_SH, Runtime.parse("android-sh"))
    }

    @Test
    fun `runtime 未知值回落 android_sh`() {
        assertEquals("null 应回落默认", Runtime.ANDROID_SH, Runtime.parse(null))
        assertEquals("乱写应回落默认", Runtime.ANDROID_SH, Runtime.parse("wasm"))
        assertEquals("空串应回落默认", Runtime.ANDROID_SH, Runtime.parse("   "))
        assertEquals(
            "显式指定 fallback 时应尊重它",
            Runtime.PROOT,
            Runtime.parse("nonsense", Runtime.PROOT)
        )
    }

    // ---------------- 2. PermissionTag 往返 ----------------

    @Test
    fun `权限标签字符串往返一致`() {
        PermissionTag.values().forEach { tag ->
            assertEquals(
                "标签 ${tag.name} 的字符串应能解析回自身",
                tag,
                PermissionTag.parse(tag.tag)
            )
            assertEquals(
                "标签字符串应大小写无关",
                tag,
                PermissionTag.parse("  " + tag.tag.uppercase() + " ")
            )
        }
    }

    @Test
    fun `未知权限标签返回 null 而不抛异常`() {
        assertNull(PermissionTag.parse("teleport"))
        assertNull(PermissionTag.parse(""))
        assertNull(PermissionTag.parse("   "))
        assertNull(PermissionTag.parse(null))
    }

    // ---------------- 3. PermissionTag 风险值 ----------------

    @Test
    fun `权限风险值都在 0 到 3 之间`() {
        PermissionTag.values().forEach { tag ->
            assertTrue(
                "标签 ${tag.name} 的 risk=${tag.risk} 越界",
                tag.risk in 0..3
            )
        }
    }

    @Test
    fun `高危权限的风险值不低于 2`() {
        listOf(
            PermissionTag.STORAGE_WRITE,
            PermissionTag.LINUX_ROOT_CMD,
            PermissionTag.SENSITIVE_DATA
        ).forEach { tag ->
            assertTrue(
                "标签 ${tag.name} 的 risk 应大于等于 2，实际 ${tag.risk}",
                tag.risk >= 2
            )
        }
        assertTrue(
            "linux_root_cmd 与 sensitive_data 应是最高的 3 档",
            PermissionTag.LINUX_ROOT_CMD.risk == 3 && PermissionTag.SENSITIVE_DATA.risk == 3
        )
        assertTrue(
            "只读类权限应低于写类",
            PermissionTag.STORAGE_READ.risk < PermissionTag.STORAGE_WRITE.risk
        )
    }

    // ---------------- 4~6. 包 id 安全校验 ----------------

    @Test
    fun `反向域名风格的包 id 合法`() {
        assertTrue(isSafePackageId("com.example.hello"))
        assertTrue("短 id 也应合法", isSafePackageId("a"))
        assertTrue("带短横线应合法", isSafePackageId("com.example.hello-world"))
        assertTrue("中文 id 不在禁止列表内", isSafePackageId("示例.包"))
        assertNull(packageIdProblem("com.example.hello"))
    }

    @Test
    fun `含上级目录引用的包 id 非法`() {
        assertFalse(isSafePackageId("../evil"))
        assertFalse("尾部带 .. 同样非法", isSafePackageId("evil/.."))
        assertFalse(isSafePackageId(".."))
    }

    @Test
    fun `含路径分隔符或空白的包 id 非法`() {
        assertFalse("斜杠", isSafePackageId("a/b"))
        assertFalse("反斜杠", isSafePackageId("a\\b"))
        assertFalse("空串", isSafePackageId(""))
        assertFalse("纯空白", isSafePackageId("   "))
        assertFalse("以点开头的隐藏名", isSafePackageId(".hidden"))
        assertFalse("首尾空白", isSafePackageId(" hello"))
    }

    // ---------------- 7. packageIdProblem 给出中文原因 ----------------

    @Test
    fun `非法包 id 给出非空中文原因`() {
        val bads = listOf("", "   ", "a/b", "a\\b", "../evil", ".hidden", " hello", "evil..x")
        bads.forEach { bad ->
            val reason = packageIdProblem(bad)
            assertNotNull("id=[$bad] 应给出失败原因", reason)
            val text = reason ?: ""
            assertTrue("id=[$bad] 的原因不能是空白", text.isNotBlank())
            assertTrue(
                "id=[$bad] 的原因应是中文，便于直接显示给用户：$text",
                text.any { it.code > 127 }
            )
        }
    }

    // ---------------- 8. 超时钳制 ----------------

    @Test
    fun `指令超时被钳到 1 到 120 秒`() {
        assertEquals(1, PackageCommand(command = "a", timeout = 0).timeoutSec)
        assertEquals(1, PackageCommand(command = "a", timeout = -30).timeoutSec)
        assertEquals(120, PackageCommand(command = "a", timeout = 999).timeoutSec)
        assertEquals(45, PackageCommand(command = "a", timeout = 45).timeoutSec)
        assertEquals(
            "边界值本身不能被改",
            120,
            PackageCommand(command = "a", timeout = 120).timeoutSec
        )
        assertEquals(
            "默认值应落在合法区间",
            PackageCommand.DEFAULT_TIMEOUT_SEC,
            PackageCommand(command = "a").timeoutSec
        )
    }

    // ---------------- 9. scope 判定 ----------------

    @Test
    fun `指令只允许群聊时不响应私聊`() {
        val onlyGroup = PackageCommand(command = "ping", scope = listOf("group"))
        assertTrue("群聊应允许", onlyGroup.allows("group"))
        assertFalse("私聊不应允许", onlyGroup.allows("c2c"))

        val both = PackageCommand(command = "ping", scope = listOf("c2c", "group"))
        assertTrue(both.allows("c2c"))
        assertTrue(both.allows("group"))
        assertTrue("大小写宽容", both.allows("GROUP"))

        assertFalse("空场景不应命中", both.allows(""))
        assertFalse("未知场景不应命中", both.allows("channel"))

        val defaultScope = PackageCommand(command = "ping")
        assertTrue("默认只私聊", defaultScope.allows("c2c"))
        assertFalse("默认不响应群聊", defaultScope.allows("group"))
    }

    // ---------------- 10. api 比对 ----------------

    @Test
    fun `api 版本比对分出三种结局`() {
        assertEquals(ApiCheck.OK, checkApi(2, 2))
        assertEquals(ApiCheck.TOO_NEW, checkApi(3, 2))
        assertEquals(ApiCheck.LEGACY, checkApi(1, 2))
        assertEquals(
            "默认与本模型当前版本比对",
            ApiCheck.OK,
            checkApi(PackageSpec.CURRENT_API)
        )
        assertEquals(
            "比当前模型新一档必须判 TOO_NEW",
            ApiCheck.TOO_NEW,
            checkApi(PackageSpec.CURRENT_API + 1)
        )
    }

    // ---------------- 11. 能力标签 ----------------

    @Test
    fun `能力标签随三类能力的有无变化`() {
        val onlyCommands = PackageSpec(
            id = "com.example.cmd",
            commands = listOf(PackageCommand(command = "hello"))
        )
        assertTrue(onlyCommands.capabilities.contains(PackageSpec.CAP_COMMANDS))
        assertFalse(onlyCommands.capabilities.contains(PackageSpec.CAP_AI_TOOLS))
        assertFalse(onlyCommands.capabilities.contains(PackageSpec.CAP_UI_ACTIONS))

        val onlyTools = PackageSpec(
            id = "com.example.tools",
            aiTools = listOf(PackageAiTool(name = "lookup"))
        )
        assertFalse(onlyTools.capabilities.contains(PackageSpec.CAP_COMMANDS))
        assertTrue(onlyTools.capabilities.contains(PackageSpec.CAP_AI_TOOLS))
        assertFalse(onlyTools.capabilities.contains(PackageSpec.CAP_UI_ACTIONS))

        val onlyPanels = PackageSpec(
            id = "com.example.panel",
            uiActions = listOf(PackageUiAction(id = "run"))
        )
        assertEquals(
            "只有面板时应只报面板",
            listOf(PackageSpec.CAP_UI_ACTIONS),
            onlyPanels.capabilities
        )

        val everything = PackageSpec(
            id = "com.example.all",
            commands = listOf(PackageCommand(command = "hello")),
            aiTools = listOf(PackageAiTool(name = "lookup")),
            uiActions = listOf(PackageUiAction(id = "run"))
        )
        assertEquals(
            "三类齐备时顺序固定为 指令、AI工具、面板",
            listOf(
                PackageSpec.CAP_COMMANDS,
                PackageSpec.CAP_AI_TOOLS,
                PackageSpec.CAP_UI_ACTIONS
            ),
            everything.capabilities
        )

        val empty = PackageSpec(id = "com.example.empty")
        assertTrue("三类全空时不应有任何能力标签", empty.capabilities.isEmpty())
    }

    // ---------------- 12. 纯 ai_tools 包在模型层被允许 ----------------

    @Test
    fun `commands 为空的纯 ai 工具包可以构造`() {
        val spec = PackageSpec(
            id = "com.example.aitools",
            name = "纯 AI 工具包",
            aiTools = listOf(
                PackageAiTool(
                    name = "weather",
                    description = "查天气",
                    parametersJson = "{\"type\":\"object\"}",
                    permission = PackageAiTool.PERMISSION_ASK
                )
            )
        )

        assertTrue("v2 不强制 commands 非空，这是任务书 2.1 的核心要求", spec.commands.isEmpty())
        assertEquals(1, spec.aiTools.size)
        assertEquals("weather", spec.aiTools[0].name)
        assertEquals(PackageAiTool.PERMISSION_ASK, spec.aiTools[0].permission)
        assertEquals(
            "没有 commands 也应有能力标签，否则包列表页会显示成空壳",
            listOf(PackageSpec.CAP_AI_TOOLS),
            spec.capabilities
        )
        assertEquals("默认原产地是 v2", Origin.V2, spec.origin)
    }

    // ---------------- 附：包 id 与路径的既有契约 ----------------

    @Test
    fun `包 id 通过校验后才可拼进目录`() {
        val root = File("/tmp/bf_pkg_root")
        val ok = File(root, "com.example.hello")
        assertTrue("合法 id 拼出的路径应是它的直接子目录", ok.parentFile == root)

        val bad = packageIdProblem("../evil")
        assertNotNull("非法 id 必须在拼路径之前被拦下", bad)
        assertTrue("拒绝理由应提到连续的点", (bad ?: "").contains(".."))
    }

    // ---------------- 附：权限排序依据 ----------------

    @Test
    fun `权限按风险降序排列可行`() {
        val decls = listOf(
            PermissionDecl(PermissionTag.NETWORK, "联网取数据"),
            PermissionDecl(PermissionTag.LINUX_ROOT_CMD, "执行 root 命令"),
            PermissionDecl(PermissionTag.STORAGE_WRITE, "写入下载目录")
        )
        val sorted = decls.sortedByDescending { it.tag.risk }
        assertEquals(PermissionTag.LINUX_ROOT_CMD, sorted[0].tag)
        assertEquals(PermissionTag.STORAGE_WRITE, sorted[1].tag)
        assertEquals(PermissionTag.NETWORK, sorted[2].tag)
        assertEquals("最低风险项应等于 network 的档位", 1, sorted[2].tag.risk)
    }
}
