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
 */
enum class Runtime {
    ANDROID_SH,
    PROOT;

    companion object {
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
         * 归一到三档，大小写宽容。
         * 对齐 com.ajx.botforge.ai.tool.ToolPermission；未知写法按 ALLOW 处理，
         * 因为清单里这一栏的默认语义就是「包作者认为它可以直接跑」。
         */
        fun normalizePermission(raw: String?): String = when (raw?.trim()?.uppercase()) {
            PERMISSION_ASK -> PERMISSION_ASK
            PERMISSION_DENY -> PERMISSION_DENY
            else -> PERMISSION_ALLOW
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

/** App 内面板上的一个动作（v1 tool.json 迁移后就落成一条这个）。 */
data class PackageUiAction(
    val id: String,
    val label: String = "",
    val description: String = "",
    val entry: String = "run.sh",
    val params: List<PackageParam> = emptyList()
)

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
