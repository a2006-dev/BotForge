package com.ajx.botforge.ai.tool

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
 */
object AgentLoop {


    data class RunResult(
        val text: String,
        /** 本次实际调用过的工具名，用于审计 */
        val usedTools: List<String>
    )

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
        onToolCall: ((String, JSONObject) -> Unit)? = null
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
        repeat(maxRounds) { round ->
            val msg = callOnce(model, msgs, toolSchemas, temperature, maxTokens) ?: return null
            val calls = msg.optJSONArray("tool_calls")

            if (calls == null || calls.length() == 0) {
                val text = msg.optString("content").trim()
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
                val output = if (tool == null) {
                    "错误：没有名为 $name 的工具"
                } else {
                    used.add(name)
                    onToolCall?.invoke(name, argsObj)
                    Logger.i("Agent", "调用工具 $name(${rawArgs.take(120)})")
                    try {
                        tool.run(argsObj)
                    } catch (e: Exception) {
                        Logger.w("Agent", "工具 $name 抛异常: ${e.message}")
                        "执行出错：${e.message}"
                    }
                }

                msgs.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", output.take(4000))
                )
            }
        }

        Logger.w("Agent", "达到轮数上限 $maxRounds，强制收尾")
        val finalMsg = callOnce(model, msgs, null, temperature, maxTokens) ?: return null
        val text = finalMsg.optString("content").trim()
        return RunResult(text.ifEmpty { "（处理步骤过多，已中止）" }, used)
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