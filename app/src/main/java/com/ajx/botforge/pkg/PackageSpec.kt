package com.ajx.botforge.pkg

import java.io.File

/**
 * 包的运行时。
 *
 * 任务书 Milestone 2.1 要求把原先割裂的 plugin（QQ 斜杠指令）与 tools（App 内表单）
 * 收敛成同一个包模型，第一步就是把「这份脚本靠什么跑」显式建模。
 *
 *  - [ANDROID_SH] 交给系统的 sh 直接执行，不用起容器，冷启动几十毫秒。
 *  - [PROOT]      在 Alpine proot 里执行，能装 apk 包，但要摊开 rootfs，重得多。
 *
 * 默认取 [ANDROID_SH]：绝大多数指令只是拼一段文本回消息，不值得为它拉起容器。
 * 用户没显式要 proot，就不该付这份代价。
 *
 * ## 现状：这一栏目前只是「声明」，还没有「分发」
 *
 * 当前版本读到 [PROOT] 之后只会把它记进 [PackageSpec.runtime]，全仓库没有任何
 * 执行路径按它分流；真正跑脚本时仍然一律走 android_sh。也就是说清单里写
 * `runtime: "proot"` 在今天等于什么都没发生——它不会报错，也不会生效，
 * 是个沉默的陷阱。
 *
 * 让执行层支持按包切换运行环境需要改执行分支，不在本模型的职责范围内；
 * 在此之前至少要做到「不静默」：读取层遇到 [PROOT] 会追加一条警告
 * （见 [runtimeWarnings]），把「你写的这行暂时无效」明确摆到用户面前。
 */
enum class Runtime {
    ANDROID_SH,
    PROOT;

    companion object {
        /**
         * runtime 声明为 [PROOT] 时要显示给用户的提示。
         *
         * 措辞刻意点明「实际仍以 android_sh 方式运行」：只说「暂不支持」，
         * 用户会以为包根本跑不起来；说清真相他才知道该按 android_sh 的
         * 可用环境去排查 command not found，而不是白等一个永远不来的容器。
         */
        const val WARNING_PROOT_NOT_APPLIED =
            "该包声明 runtime=proot，当前版本尚未实现按包切换运行环境，实际仍以 android_sh 方式运行。"

        /**
         * 宽容解析。
         *
         * 清单是给人手写的，写法一定会飘：`proot`、`PROOT`、`proot `、`android-sh`。
         * 解析失败不能报错——一个字段拼错就让整个包装不上，对用户是纯粹的挫折。
         * 未知值一律回落到 [fallback]，默认 [ANDROID_SH]。
         */
        fun parse(raw: String?, fallback: Runtime = ANDROID_SH): Runtime {
            if (raw == null) return fallback
            return when (raw.trim().lowercase()) {
                "proot" -> PROOT
                "android_sh", "android-sh", "android", "sh" -> ANDROID_SH
                else -> fallback
            }
        }
    }
}

/**
 * 声明某个 [Runtime] 时该一并告知用户的话；没有需要说明的就返回空列表。
 *
 * 为什么抽成顶层纯函数，而不是把文案直接写在读取器里：
 * [PackageManifestReader] 要碰 org.json，在 JVM 单测里一碰就炸 not mocked，
 * 落在里面的文案永远测不到。文案放这里、读取器只负责追加，测试就能直接
 * 断言「声明 proot 会产出哪句话」，也就锁住了「改执行层之前不许删这条警告」。
 */
fun runtimeWarnings(runtime: Runtime): List<String> = when (runtime) {
    Runtime.ANDROID_SH -> emptyList()
    Runtime.PROOT -> listOf(Runtime.WARNING_PROOT_NOT_APPLIED)
}

/**
 * 来源格式。
 *
 * 迁移完成之后仍然保留它，是因为设置页需要如实告诉用户
 *「这个包是老 plugin.json 自动转过来的」，而不是假装一切都是原生 v2。
 */
enum class Origin {
    V1_PLUGIN,
    V1_TOOL,
    V2
}

/**
 * 权限标签。
 *
 * [risk] 取 0..3：
 *  - 0 无副作用（纯计算）
 *  - 1 只读外界（联网、读文件）
 *  - 2 会改变用户设备状态（写文件、借 Shizuku 执行）
 *  - 3 能拿到 root 级能力或读取敏感数据
 *
 * 用排序值而不是布尔值，是为了让设置页能把「最该看的」排在前面，
 * 也让「有任意一项 risk 大于等于 2 就默认不启用」这类策略有依据。
 */
enum class PermissionTag(val tag: String, val risk: Int) {
    NETWORK("network", 1),
    STORAGE_READ("storage_read", 1),
    STORAGE_WRITE("storage_write", 2),
    SHIZUKU("shizuku", 2),
    LINUX_ROOT_CMD("linux_root_cmd", 3),
    SENSITIVE_DATA("sensitive_data", 3);

    companion object {
        /**
         * 未知标签返回 null，不报错。
         *
         * 清单里出现不认识的标签只有两种可能：用户装的是更新版本写出来的包，
         * 或者清单写错了。两种情况下「跳过这一条、把包其余部分读出来」都比
         * 「整包拒绝」好：权限是加法，漏掉一条按「未知即风险」保守处理即可，
         * 但整包装不上会让用户连本来能用的功能都拿不到。
         */
        fun parse(raw: String?): PermissionTag? {
            if (raw == null) return null
            val key = raw.trim().lowercase()
            if (key.isEmpty()) return null
            return PermissionTag.values().firstOrNull { it.tag == key }
        }
    }
}

/**
 * 一条 QQ 斜杠指令。
 *
 * [command] 不带斜杠、全小写：`/Help` 与 `help` 在 QQ 里是同一个入口，
 * 存两种形态只会让查找分支变多。
 *
 * 超时：构造时传入的 [timeout] 保留原值（清单里写了什么就存什么，便于排查），
 * 真正生效的是 [timeoutSec]，它钳在 1..120。数据类没法在构造里改自己的 val，
 * 所以用「存原值 + 计算属性」表达约束：既不丢原始声明，也不会把 0 秒或
 * 9999 秒这种清单笔误带到执行层去。
 */
data class PackageCommand(
    val command: String,
    val description: String = "",
    val usage: String = "",
    val entry: String = "main.sh",
    val timeout: Int = 15,
    val scope: List<String> = listOf("c2c"),
    val confirm: Boolean = false
) {
    /** 生效的超时秒数，恒在 1..120 */
    val timeoutSec: Int get() = timeout.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    /** 该场景是否允许触发。scene 取 c2c / group，大小写与首尾空白宽容 */
    fun allows(scene: String): Boolean {
        val key = scene.trim().lowercase()
        if (key.isEmpty()) return false
        return scope.any { it.trim().lowercase() == key }
    }

    companion object {
        const val MIN_TIMEOUT_SEC = 1
        const val MAX_TIMEOUT_SEC = 120
        const val DEFAULT_TIMEOUT_SEC = 15

        /** 默认只私聊：群里触发「撤回所有人的消息」这类动作，破坏力比私聊大一个量级 */
        val DEFAULT_SCOPE: List<String> = listOf("c2c")
    }
}

/**
 * 一个暴露给模型的 AI 工具。
 *
 * [parametersJson] 存 JSON Schema 原文，不在这里解析。
 * 原因很硬：本文件必须能在 JVM 单测里跑，而 Android 运行时桩不提供 JSON 实现，
 * 一旦在这里引用它，纯逻辑单测就会挂在 not mocked 上。
 * 需要解析时由 PackageManifestReader 负责，那里可以依赖平台自带的 JSON 类。
 */
data class PackageAiTool(
    val name: String,
    val description: String = "",
    val parametersJson: String = "{}",
    val permission: String = PERMISSION_ALLOW,
    val entry: String = "run.sh"
) {
    companion object {
        const val PERMISSION_ALLOW = "ALLOW"
        const val PERMISSION_ASK = "ASK"
        const val PERMISSION_DENY = "DENY"

        /**
         * 归一到三档。解析前先 trim + uppercase。
         *
         * 先归一化是因为清单是手写的：`"ask"`、`" Ask "`、`"deny"` 这类写法几乎
         * 必然出现，不该因为大小写或一个空格就被当成「认不出的档位」。
         * 对齐 com.ajx.botforge.ai.tool.ToolPermission 的三档语义。
         *
         * ## 为什么未知值回落 ASK，而不是 ALLOW
         *
         * 这一栏的取值要分两类情况，混在一起处理就会出错：
         *
         *  - **空值或缺失**：作者根本没写，等于没表态。此时用调用方给的
         *    [fallback]，默认 [PERMISSION_ALLOW]——这与本次修正之前的行为
         *    一致，不算语义变更，只是把「没写」和「写错」分开。
         *  - **非空但认不出**：作者确实写了，只是拼错（`"deny"`、`"ASK "`）
         *    或者写了更高版本才有的档位。这一类才是危险来源。
         *
         * 第二类如果回落到 ALLOW，后果是「拼错一个字母 → 这个工具从需要用户
         * 表态变成无条件直连执行」，错误方向指向更危险的一侧，而且出错的
         * 概率完全不可控：漏个大写、多个空格、未来加第四档，任何一次手误都会
         * 把作者本意想加的限制悄悄打开，且不留任何痕迹。
         *
         * 回落到 ASK 则相反。ASK 的语义是「用户还没表态」：工具不进 schema、
         * 或者走人工确认，既不执行也不静默放行，用户可以在设置页看到它、
         * 再把它改成 ALLOW 或 DENY 来表态。也就是说错的时候代价是
         * 「少一个工具能用」，而不是「多一个工具擅自跑了」。
         *
         * 安全默认必须站在代价更小的那一侧：这里选 ASK，宁可保守地拦住，
         * 也不替作者做「允许执行」的决定。
         */
        fun normalizePermission(
            raw: String?,
            fallback: String = PERMISSION_ALLOW
        ): String {
            val key = raw?.trim()?.uppercase() ?: return fallback
            if (key.isEmpty()) return fallback
            return when (key) {
                PERMISSION_ALLOW -> PERMISSION_ALLOW
                PERMISSION_ASK -> PERMISSION_ASK
                PERMISSION_DENY -> PERMISSION_DENY
                else -> PERMISSION_ASK
            }
        }
    }
}

/**
 * 一个 UI 参数声明。
 *
 * 字段刻意与 tools/ToolManifest.kt 里的 ToolParam 一一对齐（name/label/type/
 * default/required/hint/options），这样 v1 tool.json 迁移不会缺字段，
 * 界面层也只需要一套渲染逻辑。
 */
data class PackageParam(
    val name: String,
    val label: String = "",
    val type: String = TYPE_TEXT,
    val default: String = "",
    val required: Boolean = false,
    val hint: String = "",
    val options: List<String> = emptyList()
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_SELECT = "select"
    }
}

/**
 * App 内面板上的一个动作（v1 tool.json 迁移后就落成一条这个）。
 *
 * [timeoutSec] 是从 v1 `tool.json` 的 `timeoutSec`（默认 60）原样带过来的，
 * v2 的 `ui_actions` 也可以显式声明它。这一栏必须留着：v1 里的 `timeoutSec`
 * 在旧执行层有真实消费者（直接拿它当进程超时），迁移时把它丢掉，等于把作者
 * 调好的超时悄悄改回默认值——一个声明 20 秒的动作会被当成 60 秒跑，用户只
 * 会看到「这个动作卡很久」，而且查不出原因，因为清单里的数字根本没进模型。
 *
 * 这里存的是**声明原值**，不做钳制。钳制是执行层的策略，模型层擅自改数会让
 * 排查时看到的数字与清单对不上；需要安全边界时由调用方自行 coerce。
 */
data class PackageUiAction(
    val id: String,
    val label: String = "",
    val description: String = "",
    val entry: String = "run.sh",
    val params: List<PackageParam> = emptyList(),
    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC
) {
    companion object {
        /** 与 v1 tools/ToolManifest.kt 的 ToolManifest.timeoutSec 默认值保持一致，迁移时不产生数值漂移 */
        const val DEFAULT_TIMEOUT_SEC = 60
    }
}

/**
 * 包声明的一项权限及理由。
 *
 * [reason] 会原样显示给用户，所以要写人话；为空时由读取层补默认文案，
 * 不在模型层硬塞一句话——模型层不该编造作者没说过的话。
 */
data class PermissionDecl(
    val tag: PermissionTag,
    val reason: String = ""
)

/**
 * 统一包规格（.bfpack v2）。
 *
 * 三类能力并存：commands（QQ 斜杠指令）、aiTools（给模型的工具）、
 * uiActions（App 内表单动作）。
 *
 * 关键点：三者都允许为空这一类情况，模型层不强制 commands 非空。
 * 旧 v1 的解析器里有一句「指令为空即判非法」，那是 v1 的历史限制；
 * v2 必须支持纯 ai_tools 包，否则「一个包只提供 AI 工具」这种最自然的
 * 形态根本装不上。是否「三类全空」由读取层在校验时拒绝，而不是靠模型构造器拦。
 */
data class PackageSpec(
    val id: String,
    val name: String = "",
    val version: String = "0.0.0",
    val api: Int = CURRENT_API,
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val license: String = "",
    val runtime: Runtime = Runtime.ANDROID_SH,
    val origin: Origin = Origin.V2,
    val commands: List<PackageCommand> = emptyList(),
    val aiTools: List<PackageAiTool> = emptyList(),
    val uiActions: List<PackageUiAction> = emptyList(),
    val permissions: List<PermissionDecl> = emptyList(),
    val dir: File = File(".")
) {
    /**
     * 人类可读的能力标签，给包列表页显示。
     *
     * 返回 List 而不是拼好的 String：界面可以自己决定用标签还是逗号连接。
     * 顺序固定（指令、AI工具、面板），避免每次渲染顺序抖动导致界面跳动。
     */
    val capabilities: List<String>
        get() {
            val out = ArrayList<String>(3)
            if (commands.isNotEmpty()) out.add(CAP_COMMANDS)
            if (aiTools.isNotEmpty()) out.add(CAP_AI_TOOLS)
            if (uiActions.isNotEmpty()) out.add(CAP_UI_ACTIONS)
            return out
        }

    companion object {
        /**
         * 本模型对应的协议版本。
         *
         * 刻意不引用 plugin/PluginManifest.kt 的 CURRENT_API（那个仍然是 1）：
         * 本文件不得依赖平台框架类型，也不能让「谁先改」决定版本号。
         * 两个常量并存的那段时间里，BfpackImporter 读 v1、本 Reader 读 v2，
         * 等 Reader 接管导入路径后单一来源才有意义。
         */
        const val CURRENT_API = 2

        const val CAP_COMMANDS = "指令"
        const val CAP_AI_TOOLS = "AI工具"
        const val CAP_UI_ACTIONS = "面板"
    }
}

/**
 * 包 id 是否可安全地拼进路径。
 *
 * 与 plugin/BfpackImporter.kt 里那份私有实现同规则，但在这里公开出来复用：
 * 解压、读取、卸载三处都要判断同一件事，各写一份迟早漂移。
 * 漂移的后果不是报错而是静默放行——装的时候拦住、卸的时候放过去，最难查。
 */
fun isSafePackageId(id: String): Boolean = packageIdProblem(id) == null

/**
 * 返回包 id 不合法的中文原因；合法返回 null。
 *
 * 用「返回原因」而不是「返回 Boolean」，是因为这条消息要直接显示给用户，
 * 让用户知道是清单里哪个字符写坏了，而不是只看到「安装失败」。
 */
fun packageIdProblem(id: String): String? {
    if (id.isBlank()) return "包 id 不能为空"
    if (id != id.trim()) return "包 id 首尾不能有空白字符"
    if (id.contains('/')) return "包 id 不能包含斜杠，否则会被拼成越界路径"
    if (id.contains('\\')) return "包 id 不能包含反斜杠"
    if (id.contains("..")) return "包 id 不能包含两个连续的点"
    if (id.startsWith(".")) return "包 id 不能以点开头，会与隐藏目录混淆"
    return null
}

/**
 * api 版本比对结果。
 *
 * 用密封类而不是 Boolean 或 Int 差值，是因为三种情况的处置完全不同：
 * 一致放行、太新必须拒绝并提示升级 App、太旧则按兼容路径读并提示作者。
 * 用布尔值表达会立刻退化成「if 里再判断一次」，判断逻辑就散到调用方去了。
 */
sealed class ApiCheck {
    /** 与本机一致，正常读取 */
    object OK : ApiCheck()

    /** 包要求的版本比本机新，装上也跑不对，只能拒绝 */
    object TOO_NEW : ApiCheck()

    /** 包用的是旧协议，通常仍可按兼容路径读，只需提示 */
    object LEGACY : ApiCheck()
}

/**
 * 比对清单声明的 api 与本机支持的 api。
 *
 * 不做「容差」这类模糊处理：协议版本是本项目唯一能保证兼容性的握手机制，
 * 模糊化之后「包能装进去但解析不出来」就会变成常态。
 */
fun checkApi(declared: Int, current: Int = PackageSpec.CURRENT_API): ApiCheck = when {
    declared > current -> ApiCheck.TOO_NEW
    declared < current -> ApiCheck.LEGACY
    else -> ApiCheck.OK
}
