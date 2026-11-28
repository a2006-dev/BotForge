package com.ajx.botforge.ai.tool

import com.ajx.botforge.data.AuditStore
import com.ajx.botforge.data.Logger
import org.json.JSONObject

/**
 * ASK 权限的拦截层。
 *
 * ## 设计变化：从「直接拒绝」到「两阶段确认」
 *
 * 旧版对 ASK 档工具一律 fail，真实含义是「没有可用于确认的界面，所以干脆不许」。
 * 这对后台常驻的 QQ 机器人是安全的，但把所有 ASK 工具变成了永久不可用。
 * 现在改成两阶段：
 *
 *  阶段一（本文件）：工具被调用且未获授权时，立刻返回失败，
 *            同时向 AskGate 登记一个 6 位确认码（TTL 60 秒）。
 *            失败内容会告诉模型：确认码、有效期、让用户发 /确认 的指令，以及禁止重试。
 *            此阶段不向 QQ 发任何消息，因为工具层拿不到会话上下文，也不知道该发给谁。
 *
 *  阶段二（MessageHandler 的 /确认 分支，不在本文件范围）：
 *            用户发 /确认 <码> 时，由 AskGate.confirm 消费该码，
 *            并把（包, 工具）记为已授权。
 *            下一次同工具调用就会看到 isGranted 为 true，直接执行真实逻辑。
 *
 * ## 为什么不能阻塞等待
 *
 * 执行链是纯阻塞的多线程：BotManager 用固定 4 线程池，MessageHandler →
 * AgentLoop.run → Tool.run 全程同步调用，且全仓没有任何 suspend / withTimeout。
 * 如果在 Tool.run 里停下来等确认，一个待确认的调用就会占住 4 个线程中的一个；
 * 连续几个 ASK 调用进来，线程池被占满，连用户用来确认的那条 QQ 消息都排不上队，
 * 自己把自己锁死。所以这里只登记、不等待，把时间权重还给线程池。
 *
 * ## 为什么还要包一层
 *
 * [PackageRegistry.activeTools] 在 ASK 档仍会把工具交给模型（包成这个守卫），
 * 这一层就是唯一哨卡：注册表被绕过、缓存了旧列表、或插件路径塞入同名工具时，
 * 这里仍能拦住。成本是一次对象分配，收益是「权限失效不会悄无声息地发生」。
 */
object ToolExecutor {

    /**
     * 把 ASK 档的工具包成守卫。
     *
     * 包装后仍叫原名字、原描述、原 schema，模型看到的调用方式和普通工具完全一致，
     * 不会因为名字变化而误判能力。
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
            val digest = AuditStore.digestArgs(args.toString())
            return if (AskGate.isGranted(pkg.id, def.tool.name)) {
                runGranted(args, digest)
            } else {
                raisePending(digest)
            }
        }

        /** 已获授权：执行真实工具，并把 ASK_OK / 失败都记入审计。 */
        private fun runGranted(args: JSONObject, digest: String): ToolResult {
            Logger.i("ToolExec", "ASK 已授权，执行: ${pkg.displayName} / ${def.tool.name}")
            val started = System.currentTimeMillis()
            val outcome = runCatching { def.tool.run(args) }
            val ms = System.currentTimeMillis() - started
            return outcome.fold(
                onSuccess = { result ->
                    AuditStore.record(
                        record(
                            digest = digest,
                            decision = AuditStore.Decision.ASK_OK,
                            durationMs = ms,
                            error = result.error
                        )
                    )
                    result
                },
                onFailure = { e ->
                    val reason = e::class.java.simpleName + ": " + (e.message ?: "")
                    Logger.w("ToolExec", "ASK 已授权但执行抛错: $reason")
                    AuditStore.record(
                        record(
                            digest = digest,
                            decision = AuditStore.Decision.ASK_DENY,
                            durationMs = ms,
                            error = reason
                        )
                    )
                    ToolResult.fail(
                        "工具「${def.tool.name}」已获授权但执行时出错：$reason",
                        "这次是真实执行失败，不是权限问题。请如实告诉用户失败了，不要重试同一参数。"
                    )
                }
            )
        }

        /**
         * 未获授权：登记确认码并立即返回失败。
         *
         * 审计写成 ASK_DENY，因为本次调用的结论就是「不放行」。
         * 但 error 字段区分两种情形：待用户确认（本次），或用户已明确拒绝。
         */
        private fun raisePending(digest: String): ToolResult {
            val code = AskGate.request(pkg.id, def.tool.name, digest)
            val alreadyDenied = AskGate.wasDenied(pkg.id, def.tool.name)
            val who = "${pkg.displayName} / ${def.tool.name}"
            Logger.w("ToolExec", "ASK 待确认，已发码: $who code=$code attempts=${AskGate.pending(code)?.attempts ?: 0}")
            AuditStore.record(
                record(
                    digest = digest,
                    decision = AuditStore.Decision.ASK_DENY,
                    durationMs = 0,
                    error = if (alreadyDenied) "用户已拒绝" else "ASK 待用户确认，已发确认码"
                )
            )
            val seconds = AskGate.TTL_MS / 1000L
            val head = if (alreadyDenied) {
                "用户刚才已经拒绝过工具「${def.tool.name}」。本次没有执行，并已生成新的确认码。"
            } else {
                "工具「${def.tool.name}」需要用户确认后才能执行。本次没有执行。"
            }
            return ToolResult.fail(
                head,
                buildString {
                    append("确认码：$code\n")
                    append("有效期：$seconds 秒（过期作废，需重新发起调用才能拿到新码）\n")
                    append("接下来只能做一件事：把这个确认码告诉用户，请用户发送「/确认 $code」；")
                    append("如果用户不想执行，请用户发送「/拒绝 $code」。\n")
                    append("用户确认后，再调用一次本工具即可真实执行，参数请保持一致。\n")
                    append("在用户回复之前，不要重复调用本工具，不要更换参数反复重试，也不要声称已经完成。\n")
                    append("工具属于「${pkg.displayName}」包。")
                    if (def.note.isNotBlank()) {
                        append("这个工具会做的事：${def.note}")
                    }
                }
            )
        }

        private fun record(
            digest: String,
            decision: AuditStore.Decision,
            durationMs: Long,
            error: String?,
        ): AuditStore.AuditRecord {
            // 审计归属：能从 AgentLoop 的桥里取到上下文，就按真实的 botId / scene /
            // userOpenId 记；取不到（工具被直接调用、调用方不在 AgentLoop.run 里面）
            // 就退回改造前的默认值，行为不比以前差。
            // 每次现取、绝不缓存：桥是按线程存活期清空的，缓存会把上一条消息的归属
            // 粘到下一条消息的审计上，那比空归属更难查。
            val ctx = AgentLoop.AuditBridge.get() ?: AgentLoop.AuditContext()
            return AuditStore.AuditRecord(
                timestamp = System.currentTimeMillis(),
                botId = ctx.botId,
                scene = ctx.scene,
                userOpenId = ctx.userOpenId,
                packageId = pkg.id,
                toolOrCmd = def.tool.name,
                argsDigest = digest,
                decision = decision,
                exitCode = null,
                durationMs = durationMs,
                error = error
            )
        }
    }
}
