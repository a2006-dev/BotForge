package com.ajx.botforge.ai.tool

import com.ajx.botforge.data.Logger

/**
 * 工具权限三档。
 *
 * 跟 Operit 的确认机制对齐，但语义落到「LLM 无人值守」这个真实场景上：
 *  - [ALLOW] 直接执行。
 *  - [ASK]   默认**不交给模型**。这一档的真实含义是「用户还没表态」，
 *            而不是「弹个框等人点」——QQ 机器人跑在后台，没有可以点确认的界面，
 *            假装有确认框然后静默执行，比直接拒绝更坏。
 *            用户可以在设置页把它改成 ALLOW 或 DENY 来表态。
 *  - [DENY]  禁用。不进 schema，模型看不见，也就调不动。
 *
 * 默认值由包声明给出，用户可按单个工具覆盖，覆盖存在 [PackagePrefs]。
 */
enum class ToolPermission {
    ALLOW,
    ASK,
    DENY;

    companion object {
        fun parse(raw: String?, fallback: ToolPermission): ToolPermission {
            if (raw == null) return fallback
            return runCatching { valueOf(raw) }.getOrDefault(fallback)
        }
    }
}

/**
 * 包内一个工具的声明。
 *
 * [note] 是给人看的：设置页拿它解释「这个开关为什么值得关掉」。
 */
data class ToolDef(
    val tool: Tool,
    val permission: ToolPermission = ToolPermission.ALLOW,
    val note: String = "",
)

/**
 * 一个工具包。
 *
 * 包是「用户能整体开关的一组工具」，粒度比单个工具粗，比全部内置细。
 * 内置包 [builtin] = true，不会因为插件目录变动而消失。
 */
data class ToolPackage(
    val id: String,
    val displayName: String,
    val description: String,
    val defs: List<ToolDef>,
    val builtin: Boolean = true,
    val category: String = "",
)

/**
 * 包注册表。AgentLoop 只认 [activeTools]，这是唯一的工具来源。
 *
 * 三层过滤，顺序固定：
 *  1. 包没启用  → 整包工具消失（模型看不到）
 *  2. 单工具 DENY → 该工具消失
 *  3. 单工具 ASK  → 仍包装进去，但执行前由 [ToolExecutor] 挡下
 *
 * 过滤放在这里而不是 AgentLoop 里，是因为只有这里同时知道「包」和「用户偏好」。
 */
object PackageRegistry {

    private val packages = LinkedHashMap<String, ToolPackage>()

    /** 永远不能关掉的包。核心工具没了，机器人就是个纯聊天壳。 */
    val ALWAYS_ON = setOf("core")

    fun install(pkg: ToolPackage) {
        packages[pkg.id] = pkg
    }

    fun installAll(list: List<ToolPackage>) {
        list.forEach { install(it) }
    }

    fun all(): List<ToolPackage> = packages.values.toList()

    fun find(id: String): ToolPackage? = packages[id]

    /** 包是否启用。ALWAYS_ON 里的包恒为启用，用户设了 false 也不认。 */
    fun isEnabled(pkg: ToolPackage): Boolean {
        if (pkg.id in ALWAYS_ON) return true
        val default = pkg.builtin
        return PackagePrefs.packageEnabled(pkg.id, default)
    }

    fun setEnabled(pkgId: String, on: Boolean) {
        if (pkgId in ALWAYS_ON) {
            Logger.w("Pkg", "包 $pkgId 属于 ALWAYS_ON，忽略关闭请求")
            return
        }
        PackagePrefs.setPackageEnabled(pkgId, on)
    }

    /** 某工具当前生效的权限：用户覆盖优先，否则取包声明。 */
    fun permissionOf(pkg: ToolPackage, def: ToolDef): ToolPermission =
        ToolPermission.parse(PackagePrefs.toolPermission(pkg.id, def.tool.name), def.permission)

    fun setPermission(pkgId: String, toolName: String, perm: ToolPermission) {
        PackagePrefs.setToolPermission(pkgId, toolName, perm.name)
    }

    fun resetPermission(pkgId: String, toolName: String) {
        PackagePrefs.clearToolPermission(pkgId, toolName)
    }

    /**
     * 交给 AgentLoop 的工具列表。已按权限过滤，且 ASK 档已被 [ToolExecutor] 包好。
     *
     * 注意返回值里的 Tool 可能不是注册时那个对象——ASK 档会被替换成守卫包装，
     * 所以调用方不要拿它做 `===` 比较。
     */
    fun activeTools(): List<Tool> {
        val out = ArrayList<Tool>()
        packages.values.forEach { pkg ->
            if (!isEnabled(pkg)) {
                Logger.i("Pkg", "包 ${pkg.id} 已关闭，跳过 ${pkg.defs.size} 个工具")
                return@forEach
            }
            pkg.defs.forEach { def ->
                when (permissionOf(pkg, def)) {
                    ToolPermission.DENY -> Unit
                    ToolPermission.ALLOW -> out.add(def.tool)
                    ToolPermission.ASK -> out.add(ToolExecutor.guard(pkg, def))
                }
            }
        }
        return out
    }

    /** 统计：给设置页显示「N 个包 / M 个工具可用」 */
    fun stats(): Pair<Int, Int> {
        val enabled = packages.values.count { isEnabled(it) }
        return enabled to activeTools().size
    }

    /** 仅供测试与热重载：清空注册表。正常流程不会调用。 */
    fun reset() {
        packages.clear()
    }
}