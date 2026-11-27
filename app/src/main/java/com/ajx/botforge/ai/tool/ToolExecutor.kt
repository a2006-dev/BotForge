package com.ajx.botforge.ai.tool

import com.ajx.botforge.data.Logger
import org.json.JSONObject

/**
 * ASK 权限的拦截层。
 *
 * ## 为什么是「拦截」而不是「弹框」
 *
 * 原始设计里 ASK = 执行前让用户确认。但 BotForge 是常驻后台的 QQ 机器人：
 * 触发工具调用的是群里某个人发的一句话，用户本人可能根本不在看手机。
 * 这种场景下「弹框确认」有三种结局，每一种都比直接拒绝更糟：
 *
 *  1. 没人点 → 超时 → 模型以为工具卡住了，开始编原因
 *  2. 挂起等 → 整条消息处理链堵住，后面的消息全部排队
 *  3. 假装点了 → 静默执行，用户以为有确认其实没有，这是最坏的
 *
 * 所以这里的选择是：**ASK 档的工具不进模型视野，模型看不到也调不动；
 * 万一从别处漏进来（比如模型猜了一个名字），执行时明确拒绝并说明原因。**
 * 用户想让某个工具生效，去设置页把它的权限改成 ALLOW，这个动作本身就是确认，
 * 而且是有记录、可追溯、不依赖当时是否在看手机的确认。
 *
 * ## 为什么还要包一层
 *
 * [PackageRegistry.activeTools] 在 ASK 档不会把工具交给模型。
 * 这一层是纵深防御：注册表被绕过、缓存了旧的工具列表、或者插件路径塞进同名工具时，
 * 这里仍能挡住。成本是一次对象分配，收益是「权限失效」不会悄无声息地发生。
 */
object ToolExecutor {

    /**
     * 把 ASK 档的工具包成守卫。
     *
     * 包装后仍叫原名字、原描述、原 schema——如果它意外进了 schema，
     * 模型看到的调用方式和正常工具完全一样，不会因为名字变化而误判能力。
     */
    fun guard(pkg: ToolPackage, def: ToolDef): Tool = GuardedTool(pkg, def)

    private class GuardedTool(
        private val pkg: ToolPackage,
        private val def: ToolDef,
    ) : Tool {
        override val name: String get() = def.tool.name
        override val description: String get() = def.tool.description
        override val parameters: JSONObject get() = def.tool.parameters

        override fun run(args: JSONObject): ToolResult {
            val who = "${pkg.displayName} / ${def.tool.name}"
            Logger.w("ToolExec", "拒绝执行 ASK 档工具: $who")
            return ToolResult.fail(
                "工具「${def.tool.name}」未获授权，已被拦截。",
                buildString {
                    append("原因：该工具属于「${pkg.displayName}」包，权限档位是「需确认」，")
                    append("而当前没有可以为它确认的界面。\n")
                    append("要使用它，请让用户在 BotForge 设置 → 工具包 → ${pkg.displayName} ")
                    append("里，把「${def.tool.name}」改为「允许」。\n")
                    if (def.note.isNotBlank()) {
                        append("这个工具会做的事：${def.note}\n")
                    }
                    append("在用户授权之前，请如实告诉用户「需要先在设置里开启这个工具」，")
                    append("不要更换参数反复重试，也不要声称已经完成。")
                }
            )
        }
    }
}