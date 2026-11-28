package com.ajx.botforge.ai.tool

import com.ajx.botforge.data.AuditStore
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent Loop（L2 Tool 层核心）
 *
 * 流程：
 *   用户消息 → 模型（带 tools）→ 若返回 tool_calls 则执行 → 结果回灌 → 再问 → 直到 stop
 *
 * 约束（来自规划书与实测的被动回复限额）：
 *  - 工具调用轮数上限由设置页配置（Config.toolMaxRounds），默认 4，防止死循环刷屏
 *  - 每轮工具输出已由工具自己截断，这里再兜一层
 *  - 全程只在最后返回一条最终文本，中间过程不外发
 *
 * 防编造（这次新加的三道闸）：
 *  1. 工具返回 [ToolResult]，框架自己知道这次调用成不成，不把判断权交给模型
 *  2. 本轮一次工具都没调，回复却在声称「已执行/已成功」，判定为编造：
 *     先静默重问一次；重问后仍编造，就把这句换成实话，不许原样发出去
 *  3. 轮数耗尽时明确告诉模型「任务未完成」，避免它把「没做完」说成「做完了」
 */
object AgentLoop {

    data class RunResult(
        val text: String,
        /** 本次实际调用过的工具名，用于审计 */
        val usedTools: List<String>
    )

    /**
     * 审计上下文。
     *
     * AgentLoop 不关心「谁在什么场景下问的」，那是上一层的事。
     * 但审计必须带上这些字段才有意义：只记「调了 shell」而不知道
     * 是哪个群哪个人触发的，等于没记。
     * 所以由调用方传入，这里只负责透传给 [AuditStore]。
     */
    data class AuditContext(
        val botId: String = "",
        val scene: String = "internal",
        val userOpenId: String? = null
    )

    /**
     * 审计上下文的进程内桥。
     *
     * ## 为什么需要它
     *
     * 审计要记 botId / scene / userOpenId，这三个字段只有上层知道，
     * AgentLoop 只是转发者；而真正写审计记录的地方在 ToolExecutor 的守卫工具里。
     * 中间隔着的是 Tool.run(args) 这个接口，全仓十几个工具实现、几个包、多个插件
     * 都按它写。为了多带三个字段去改接口签名，等于把整条工具链重写一遍，
     * 收益是三条审计字段，代价是一次大面积回归，不划算。
     *
     * 折中办法就是这个桥：AgentLoop 进入 run 时把上下文放进来，退出时（try/finally）
     * 清掉；ToolExecutor 写审计时到这里取，取不到就沿用改造前的默认值
     * （空 botId / internal / null），行为只会变好，不会变差。
     *
     * ## 局限（必须知道，别当成完整方案）
     *
     * 这是单线程池语义下的简化方案。BotManager 用固定线程池处理消息，
     * 一个线程同一时刻只服务一条消息，所以「set -> 跑工具 -> clear」不会互相踩。
     * 但一旦出现下面任一情况，审计归属就可能串到别人头上：
     *
     *  - 同一条线程上重入（工具内部再次调用 AgentLoop.run）：内层的 clear 会把外层的清掉
     *  - 换成协程或线程切换，set 和 clear 不在同一个线程
     *  - 工具自己起子线程再调用别的工具
     *
     * 完整方案应当把上下文沿着 Tool.run 链路显式往下传（或跟随协程上下文传递），
     * 那需要改动接口和所有实现，本轮先不动，等工具接口本身要动的时候一并做。
     */
    object AuditBridge {

        @Volatile
        private var current: AuditContext? = null

        fun set(ctx: AuditContext?) {
            current = ctx
        }

        fun get(): AuditContext? = current

        fun clear() {
            current = null
        }
    }

    // ------------------------------------------------------------ 防编造模式
    /**
     * 「声称已经干完活」的表述。刻意收窄，只匹配明确的完成语气，
     * 不匹配「如果执行 ls 会输出…」这类假设/讲道理的句式，避免误伤。
     */
    private val CLAIM_PATTERNS = listOf(
        Regex("""我已经?(?:执行|运行|调用|跑|操作)(?:了|过)"""),
        Regex("""已(?:经)?(?:成功)?(?:执行|运行|完成|调用)(?:完毕|了|成功)"""),
        Regex("""(?:执行|运行|命令|操作)(?:的)?(?:结果|输出)(?:如下|是|为)"""),
        Regex("""(?:输出|返回)(?:内容|结果)?(?:如下|是|为)[:：]"""),
        Regex("""我(?:帮你|已经帮你|已经)(?:打开|关闭|安装|卸载|发送|设置|删除|创建)了"""),
    )

    /** 只有出现这些「命令输出痕迹」才作为补强信号 */
    private val OUTPUT_TRACES = listOf(
        Regex("""uid=\d+"""),
        Regex("""package:[\w.]+"""),
        Regex("""^total \d+""", RegexOption.MULTILINE),
        Regex("""drwx[rwx-]{7}"""),
    )

    /** 判断一句话是否在声称「我干完了」 */
    internal fun claimsExecution(text: String): Boolean {
        if (text.isBlank()) return false
        val hitClaim = CLAIM_PATTERNS.any { it.containsMatchIn(text) }
        if (!hitClaim) return false
        // 再补一道：如果整段话明显是在讲道理（含「如果」「假设」「将会」）且没有任何输出痕迹，放过
        val hypothetical = Regex("""如果|假设|假如|若是|将会|会输出|应该会""").containsMatchIn(text)
        val hasTrace = OUTPUT_TRACES.any { it.containsMatchIn(text) }
        return hasTrace || !hypothetical
    }

    private const val HONEST_FALLBACK =
        "（这一轮我并没有实际执行任何工具，所以没有真实结果。需要我动手的话，请再说一次。）"

    private const val NO_ACTION_SYSTEM = """

---
【系统约束 · 必须遵守】
你在上一轮回复中声称已经执行了操作，但系统记录显示：
你这一轮一次工具都没有调用，因此不存在任何真实执行结果。

现在请重新回答用户：要么真正调用合适的工具去做，要么如实说明你没有执行。
禁止在没有调用工具的情况下声称「已执行 / 已成功 / 输出是…」。
"""

    private fun normalizeBase(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        if (!b.endsWith("/v1")) b = "$b/v1"
        return "$b/chat/completions"
    }

    /**
     * 执行一轮带工具的对话。
     *
     * @param tools 本次允许使用的工具（已按权限过滤好）
     * @param onToolCall 每调用一次工具回调一次，可用于审计
     */
    fun run(
        model: Config.Model,
        system: String,
        history: List<com.ajx.botforge.ai.AiEngine.Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        tools: List<Tool>,
        onToolCall: ((String, JSONObject) -> Unit)? = null,
        auditCtx: AuditContext = AuditContext()
    ): RunResult? {
        // 进入本轮之前把审计归属放进桥，退出时必须清掉（finally 保证异常路径也清）：
        // 线程池会复用线程，残留的上下文会把下一条消息的审计记到上一个人头上。
        // 桥的局限见 AuditBridge 的注释，这里只是最小改动方案。
        AuditBridge.set(auditCtx)
        try {
            return runInner(
                model, system, history, user, temperature, maxTokens, tools, onToolCall, auditCtx
            )
        } finally {
            AuditBridge.clear()
        }
    }

    /**
     * 真正的循环实现。拆出来是为了让 run 能用 try/finally 包住整段逻辑：
     * 原逻辑里有多个提前 return（协议不支持、请求失败、编造重问后给实话、轮数耗尽），
     * 逐处补 clear 迟早会漏一处，包一层才是一眼能看完的正确做法。
     */
    private fun runInner(
        model: Config.Model,
        system: String,
        history: List<com.ajx.botforge.ai.AiEngine.Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        tools: List<Tool>,
        onToolCall: ((String, JSONObject) -> Unit)?,
        auditCtx: AuditContext
    ): RunResult? {
        // anthropic 协议暂不支持工具，退回纯对话
        if (model.type == "anthropic" || tools.isEmpty()) {
            val t = com.ajx.botforge.ai.ApiPool.chat(
                model, system, history, user, temperature, maxTokens
            )
            return t?.let { RunResult(it, emptyList()) }
        }

        val toolMap = tools.associateBy { it.name }
        val toolSchemas = JSONArray()
        tools.forEach { toolSchemas.put(it.toOpenAiSchema()) }

        val msgs = JSONArray()
        if (system.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        history.forEach {
            msgs.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        msgs.put(JSONObject().put("role", "user").put("content", user))

        val used = mutableListOf<String>()
        val maxRounds = Config.toolMaxRounds
        var guardUsed = false          // 编造重问是否已用过（只给一次机会）
        var lastFailed = false         // 最近一次工具调用是否失败

        repeat(maxRounds) { round ->
            val msg = callOnce(model, msgs, toolSchemas, temperature, maxTokens) ?: return null
            val calls = msg.optJSONArray("tool_calls")

            if (calls == null || calls.length() == 0) {
                val text = msg.optString("content").trim()

                // ——— 第二道闸：本轮没调工具却声称执行了 ———
                if (used.isEmpty() && claimsExecution(text)) {
                    Logger.w("Agent", "第 ${round + 1} 轮检测到编造：未调用工具却声称已执行")
                    if (!guardUsed) {
                        guardUsed = true
                        // 静默重问：不把编造这句发给用户
                        msgs.put(JSONObject().put("role", "assistant").put("content", text))
                        msgs.put(JSONObject().put("role", "system").put("content", NO_ACTION_SYSTEM))
                        return@repeat
                    }
                    // 重问后仍编造：换成实话，不许原样发出去
                    Logger.w("Agent", "重问后仍然编造，替换为如实说明")
                    return RunResult(HONEST_FALLBACK, used)
                }

                Logger.i("Agent", "第 ${round + 1} 轮收敛，工具调用 ${used.size} 次")
                return RunResult(text.ifEmpty { "（模型没有返回内容）" }, used)
            }

            // 把 assistant 的 tool_calls 消息原样放回
            msgs.put(msg)

            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val id = c.optString("id")
                val fn = c.optJSONObject("function")
                val name = fn?.optString("name") ?: ""
                val rawArgs = fn?.optString("arguments") ?: "{}"

                val argsObj = try {
                    JSONObject(rawArgs)
                } catch (e: Exception) {
                    JSONObject()
                }

                val tool = toolMap[name]
                val result: ToolResult = if (tool == null) {
                    ToolResult.fail("没有名为 $name 的工具，可用工具：${toolMap.keys.joinToString("、")}")
                } else {
                    used.add(name)
                    onToolCall?.invoke(name, argsObj)
                    Logger.i("Agent", "调用工具 $name(${rawArgs.take(120)})")
                    val t0 = System.currentTimeMillis()
                    var res: ToolResult? = null
                    try {
                        res = tool.run(argsObj)
                        res
                    } catch (e: Throwable) {
                        Logger.w("Agent", "工具 $name 抛异常: ${e.message}")
                        ToolResult.fail("${e.javaClass.simpleName}: ${e.message ?: "无详情"}")
                    } finally {
                        // 无论成功、失败还是抛异常，都落一条审计。
                        // 「失败了但没记录」是排查时最难受的情况：
                        // 用户记得模型试过某个工具，日志里却什么都没有。
                        AuditStore.record(
                            AuditStore.AuditRecord(
                                timestamp = t0,
                                botId = auditCtx.botId,
                                scene = auditCtx.scene,
                                userOpenId = auditCtx.userOpenId,
                                packageId = null,
                                toolOrCmd = name,
                                argsDigest = AuditStore.digestArgs(rawArgs),
                                decision = AuditStore.Decision.ALLOW,
                                exitCode = null,
                                durationMs = System.currentTimeMillis() - t0,
                                error = res?.error?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                lastFailed = !result.success
                if (!result.success) {
                    Logger.w("Agent", "工具 $name 失败: ${result.error}")
                }

                msgs.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result.forModel().take(4000))
                )
            }
        }

        // ——— 第三道闸：轮数耗尽，明确告知未完成 ———
        Logger.w("Agent", "达到轮数上限 $maxRounds，强制收尾")

        val tail = buildString {
            append("\n\n---\n【系统约束 · 必须遵守】\n")
            append("工具调用轮数已达上限（$maxRounds 轮），任务可能还没有完成。\n")
            append("请如实汇报现在的真实进度：已经成功做了什么、还差什么没做。\n")
            append("禁止声称「已完成 / 全部做好 / 任务结束」，除非确实已经全部完成。")
            if (lastFailed) {
                append("\n注意：最近一次工具调用是失败的，请把失败原因如实告诉用户。")
            }
        }

        // 把 tail 真正送进对话。之前这里构造完就丢掉了：
        // 收尾请求拿到的还是「任务进行中」的上下文，模型不知道轮数已耗尽，
        // 于是继续声称「已完成」。追加成一条 system 消息才是有效约束。
        msgs.put(
            org.json.JSONObject()
                .put("role", "system")
                .put("content", tail)
        )

        val finalMsg = callOnce(model, msgs, toolSchemas, temperature, maxTokens) ?: return null
        var text = finalMsg.optString("content").trim()

        // 收尾这一轮如果还在编（一次成功工具都没调过），直接给实话
        if (used.isEmpty() && claimsExecution(text)) {
            Logger.w("Agent", "收尾轮编造，替换为如实说明")
            text = HONEST_FALLBACK
        }
        return RunResult(text.ifEmpty { "（处理步骤过多，已中止，任务可能未完成）" }, used)
    }

    // ---------------------------------------------------------------- HTTP
    private fun callOnce(
        model: Config.Model,
        msgs: JSONArray,
        tools: JSONArray?,
        temperature: Float,
        maxTokens: Int
    ): JSONObject? {
        val url = normalizeBase(model.baseUrl)
        val body = JSONObject()
            .put("model", model.model)
            .put("messages", msgs)
            .put("temperature", temperature.toDouble())
            .put("max_tokens", maxTokens)
            .put("stream", false)
        // 收尾轮也保留工具定义：部分反代在末尾才给工具，去掉反而更容易被拒
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = model.timeout * 1000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            if (code !in 200..299) {
                Logger.w("Agent", "HTTP $code: ${text.take(300)}")
                return null
            }
            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            choices.getJSONObject(0).optJSONObject("message")
        } catch (e: Exception) {
            Logger.w("Agent", "请求失败: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
