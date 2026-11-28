package com.ajx.botforge.ai

import com.ajx.botforge.ai.tool.Tool
import com.ajx.botforge.ai.tool.toAnthropicSchema
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型调用层：统一 OpenAI 兼容 /anthropic 两种协议
 * 三路来源（反代 / 官方 / 本地）只是 baseUrl+key+model 不同，走同一套代码
 *
 * ## Anthropic 工具调用
 *
 * Anthropic 的请求/响应形状由 [AnthropicCodec] 负责（纯 Kotlin 结构，可单测），
 * 本文件只做两件事：把 Kotlin 结构转成 [JSONObject] 发出去，把收到的东西转回
 * Kotlin 结构交给 codec 解析。
 *
 * 对外暴露两条入口，互不干扰：
 *
 * - [chat] 返回 `String?`，语义与改造前完全一致，现有调用点一行都不用改；
 * - [chatWithTools] 返回 [AiReply]，能带回 tool_use 块，供后续带工具循环使用。
 */
object AiEngine {

    data class Msg(val role: String, val content: String)

    /**
     * 一次模型回复的完整结果。
     *
     * [text] 为 null 表示模型没给正文（只调工具时是正常情况，不是失败）。
     * 判定成功与否请看 [AiReply] 是否非 null；判定是否有工具调用请看 [toolUses] 或 [isToolUse]。
     */
    data class AiReply(
        val text: String?,
        val stopReason: String,
        val toolUses: List<AnthropicCodec.ToolUseBlock>,
        val rawAssistantTurn: Map<String, Any?>?
    ) {
        val isToolUse: Boolean get() = stopReason == AnthropicCodec.STOP_TOOL_USE

        /** 兼容只关心文本的老用法 */
        fun textOrEmpty(): String = text ?: ""
    }

    private fun normalizeBase(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        if (!b.endsWith("/v1")) b = "$b/v1"
        return "$b/chat/completions"
    }

    /**
     * 有没有可用工具。
     *
     * 抽出来是为了让 [chat] 与 [chatWithTools] 对「纯对话」走同一条最短路径：
     * 没有工具时没必要走 Anthropic 的块数组逻辑，直接沿用老行为，减少回归面。
     */
    private fun noTools(tools: List<Tool>): Boolean = tools.isEmpty()

    /** 非流式调用，返回纯文本；失败返回 null 并记录原因 */
    fun chat(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList()
    ): String? {
        return if (model.type == "anthropic") {
            chatAnthropic(model, system, history, user, temperature, maxTokens, images)
        } else {
            chatOpenAi(model, system, history, user, temperature, maxTokens, images)
        }
    }

    /**
     * 带工具的非流式调用。
     *
     * 与 [chat] 的区别只有两点：能带工具定义，返回值能带回 tool_use。
     * 目前 Anthropic 走完整实现；OpenAI 分支只把工具定义发出去、不解析 tool_calls，
     * 因为 OpenAI 的循环已经在 `ai/tool/AgentLoop` 里跑，这里不重复造。
     *
     * @return 失败返回 null；成功但只有工具调用时 [AiReply.text] 为 null，这是正常结果
     */
    fun chatWithTools(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList(),
        tools: List<Tool> = emptyList(),
        toolChoice: String? = "auto"
    ): AiReply? {
        if (noTools(tools)) {
            return chat(model, system, history, user, temperature, maxTokens, images)?.let {
                AiReply(it, "end_turn", emptyList(), null)
            }
        }
        return if (model.type == "anthropic") {
            anthropicComplete(model, system, history, user, temperature, maxTokens, images, tools, toolChoice)
        } else {
            chat(model, system, history, user, temperature, maxTokens, images)?.let {
                AiReply(it, "end_turn", emptyList(), null)
            }
        }
    }

    // ------------------------------------------------------------ JSON 互转

    /**
     * Kotlin 结构转 JSON。
     *
     * 为什么不用 `JSONObject(Map)` 构造器：它会把 `Map` 里的 null 变成字符串 "null"，
     * 而且嵌套 List 的处理依赖实现细节。这里自己做递归，行为可控。
     * 对象里的 null 直接跳过（org.json 里 put(key, null) 是删除该键），
     * 数组里的 null 用 [JSONObject.NULL] 占位，避免数组塌陷。
     */
    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val obj = JSONObject()
            value.forEach { (k, v) ->
                val jv = toJson(v)
                if (jv !== JSONObject.NULL) obj.put(k.toString(), jv)
            }
            obj
        }
        is List<*> -> {
            val arr = JSONArray()
            value.forEach { arr.put(toJson(it)) }
            arr
        }
        else -> value
    }

    /** JSON 值转 Kotlin 结构，[JSONObject.NULL] 归一成 null */
    private fun toKotlin(value: Any?): Any? = when {
        // 用 === 而非 ==：JSONObject.NULL.equals(null) 也为 true，
        // 相等比较会把真正的 null 与占位符混为一谈，这里要的是「就是这个占位对象」。
        value == null -> null
        value === JSONObject.NULL -> null
        value is JSONObject -> {
            val out = LinkedHashMap<String, Any?>()
            val it = value.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = toKotlin(value.opt(k))
            }
            out
        }
        value is JSONArray -> {
            val out = ArrayList<Any?>()
            for (i in 0 until value.length()) out.add(toKotlin(value.opt(i)))
            out
        }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun asStringMap(value: Any?): Map<String, Any?> =
        (toKotlin(value) as? Map<String, Any?>) ?: emptyMap()

    /**
     * 工具转成 codec 能吃的规格。
     *
     * 这里刻意复用 [toAnthropicSchema]（新增函数），再拆回 Kotlin 结构交给 codec，
     * 保证「写进请求体的键」和「单测断言的键」是同一份逻辑，不会两处漂移。
     */
    private fun specOf(tool: Tool): AnthropicCodec.ToolSpec {
        val schema = tool.toAnthropicSchema()
        return AnthropicCodec.ToolSpec(
            name = schema.optString("name"),
            description = schema.optString("description"),
            inputSchema = asStringMap(schema.opt("input_schema"))
        )
    }

    /** 构造 user 内容：有图时用多模态数组，无图时用纯字符串 */
    private fun userContent(user: String, images: List<String>): Any {
        if (images.isEmpty()) return user
        val arr = JSONArray()
        arr.put(
            JSONObject()
                .put("type", "text")
                .put("text", user.ifBlank { "看看这几张图，说说你的想法。" })
        )
        images.forEach { url ->
            arr.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
        }
        return arr
    }

    private fun chatOpenAi(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList()
    ): String? {
        val url = normalizeBase(model.baseUrl)
        val msgs = JSONArray()
        if (system.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", system))
        }
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        msgs.put(JSONObject().put("role", "user").put("content", userContent(user, images)))

        val body = JSONObject()
            .put("model", model.model)
            .put("messages", msgs)
            .put("temperature", temperature.toDouble())
            .put("max_tokens", maxTokens)
            .put("stream", false)

        var conn: HttpURLConnection? = null
        try {
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
                Logger.w("AI", "HTTP $code: ${text.take(300)}")
                return null
            }
            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val m = choices.getJSONObject(0).optJSONObject("message") ?: return null
            return m.optString("content").trim().ifBlank { null }
        } catch (e: Exception) {
            Logger.w("AI", "调用失败: ${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Anthropic 纯文本调用。
     *
     * 保留这个函数是为了让 [chat] 的语义和调用签名完全不破：新增的 tools 参数带默认值，
     * 老调用点（含 `chat(...)` 内部那一处）不用改一个字符。
     */
    private fun chatAnthropic(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList(),
        tools: List<Tool> = emptyList()
    ): String? {
        val reply = anthropicComplete(model, system, history, user, temperature, maxTokens, images, tools, "auto")
        // 与改造前完全一致：空白也算失败，返回 null 让 ApiPool 换下一条候选。
        // anthropicComplete 的 AiReply.text 已做过 trim + ifBlank{null}，这里再兜一次空串。
        return reply?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Anthropic `/v1/messages` 完整调用，返回结构化结果。
     *
     * 请求体与响应解析全部委托 [AnthropicCodec]，本函数只负责网络与类型转换。
     * 工具相关的差异（input_schema、tool_choice 是对象、tool_use 块）都收在 codec 里，
     * 这里不再重复判断字段名，避免两处不一致。
     */
    private fun anthropicComplete(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String>,
        tools: List<Tool>,
        toolChoice: String?
    ): AiReply? {
        val base = model.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/messages")) base else "$base/v1/messages"

        val turns = history.map { AnthropicCodec.HistoryTurn(it.role, it.content) }
        val imageList = images.mapNotNull { AnthropicCodec.imageFromDataUrl(it) }
        val specs = tools.map { specOf(it) }

        val bodyMap = AnthropicCodec.buildRequestBody(
            model = model.model,
            system = system,
            history = turns,
            user = user,
            temperature = temperature.toDouble(),
            maxTokens = maxTokens,
            images = imageList,
            tools = specs,
            toolChoice = toolChoice
        )
        val body = toJson(bodyMap) as JSONObject

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = model.timeout * 1000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", model.apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            if (code !in 200..299) {
                Logger.w("AI", "anthropic HTTP $code: ${text.take(300)}")
                return null
            }

            val reply = AnthropicCodec.parseResponse(asStringMap(JSONObject(text))) ?: return null
            if (reply.unknownTypes.isNotEmpty()) {
                Logger.i("AI", "anthropic 返回了未处理的块类型: ${reply.unknownTypes.joinToString(",")}")
            }
            // 只调工具时 text 为空是正常情况，这里不能用 ifBlank 判失败
            val plain = reply.text.trim().ifBlank { null }
            val assistantTurn = if (reply.toolUses.isEmpty()) {
                null
            } else {
                AnthropicCodec.assistantTurn(reply.toolUses, reply.text)
            }
            return AiReply(plain, reply.stopReason, reply.toolUses, assistantTurn)
        } catch (e: Exception) {
            Logger.w("AI", "anthropic 失败: ${e.message}")
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 连通性测试 */
    fun ping(model: Config.Model): String {
        val r = chat(model, "你是测试助手，只回一个字。", emptyList(), "回一个字：好", 0f, 8)
        return r ?: "失败（详见日志）"
    }
}
