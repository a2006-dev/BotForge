package com.ajx.botforge.ai

/**
 * Anthropic Messages 协议的纯逻辑编解码。
 *
 * ## 为什么这个文件不碰 JSON、也不碰网络
 *
 * 这里最容易出错的地方全在字段名和嵌套形状上：工具 schema 该叫 `input_schema`
 * 而不是 `parameters`、`tool_choice` 是对象而不是字符串、工具结果要写成
 * user 消息里的 `tool_result` 块而不是 OpenAI 的 `role:"tool"`。
 * 这些一旦写错，编译能过、单测若依赖 `org.json` 又跑不起来（Android 单测里
 * `org.json` 是 not mocked），只能靠真机试错，代价很高。
 *
 * 所以本项目把「形状」和「序列化」拆开：
 *
 * - 本文件只处理普通 Kotlin 数据结构（[Map]、[List]、data class），
 *   输入输出都是纯 Kotlin，JVM 单测可直接断言形状；
 * - 真正转成 `JSONObject` 的动作由调用方 [AiEngine] 完成。
 *
 * 这个文件因此不得 import `android.*`、`androidx.*` 或 `org.json`。
 */
object AnthropicCodec {

    /** 官方 stop_reason 中表示「本轮要求调用工具」的那一个 */
    const val STOP_TOOL_USE = "tool_use"

    /** 有图但用户没写文字时的兜底提示，与改造前 AiEngine 的行为保持一致 */
    const val DEFAULT_IMAGE_USER_TEXT = "看看这几张图，说说你的想法。"

    /** data URL 里缺失 media_type 时的兜底 */
    const val DEFAULT_MEDIA_TYPE = "image/jpeg"

    /** data URL 中 base64 段的起始标记 */
    private const val BASE64_MARK = "base64,"

    /** 一个工具在 Anthropic 侧的定义：注意第三个字段是 inputSchema，不是 parameters */
    data class ToolSpec(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any?>
    )

    /** Anthropic 的图片只接受 base64 source，不接受 http URL，也不接受 data URL 原样透传 */
    data class Base64Image(val mediaType: String, val data: String)

    /** 历史消息：Anthropic 只有 user / assistant 两种角色，system 是顶层字段 */
    data class HistoryTurn(val role: String, val content: String)

    /** 模型要求的工具调用。input 是参数对象（官方返回 object，不是 JSON 字符串） */
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: Map<String, Any?>,
        /**
         * input 是否是 JSON 对象。官方恒为对象；
         * 若某个中转把它写成字符串，这里会是 false，调用方可据此报错而不是静默按空参数执行。
         */
        val inputIsObject: Boolean = true
    )

    /** 回填给模型的工具结果 */
    data class ToolResultBlock(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    )

    /**
     * 解析后的一次模型回复。
     *
     * [text] 可能为空：当模型只返回 tool_use 时，text 是空串而 [toolUses] 非空。
     * 调用方必须先看 [isToolUse] / [toolUses]，不能只看 [text] 是否为空就当成失败。
     */
    data class Reply(
        val id: String,
        val model: String,
        val stopReason: String,
        val texts: List<String>,
        val toolUses: List<ToolUseBlock>,
        val unknownTypes: List<String>
    ) {
        val text: String get() = texts.joinToString("")
        val isToolUse: Boolean get() = stopReason == STOP_TOOL_USE
        val isEmpty: Boolean get() = text.isBlank() && toolUses.isEmpty()
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 工具定义。外层是 `{name, description, input_schema}`，没有 `type:"function"` 包裹，
     * 参数键名是 `input_schema` 而不是 `parameters`。
     */
    fun toolSchema(spec: ToolSpec): Map<String, Any?> = linkedMapOf(
        "name" to spec.name,
        "description" to spec.description,
        "input_schema" to spec.inputSchema
    )

    /** `tool_choice` 是对象。当前只支持 `auto` / `any`，按工具名指定待真机验证 */
    fun toolChoiceAuto(): Map<String, Any?> = linkedMapOf("type" to "auto")

    /** 图片块：`{type:"image", source:{type:"base64", media_type, data}}` */
    fun imageBlock(img: Base64Image): Map<String, Any?> = linkedMapOf(
        "type" to "image",
        "source" to linkedMapOf(
            "type" to "base64",
            "media_type" to img.mediaType,
            "data" to img.data
        )
    )

    /**
     * 从 data URL 解析出图片。解析不出返回 null（调用方直接丢弃，与改造前行为一致）。
     *
     * media_type 从 data URL 前缀取，拿不到就用 [DEFAULT_MEDIA_TYPE]。
     * 这一点比改造前硬编码 `image/jpeg` 更安全：png 数据配 jpeg 声明会被服务端拒绝。
     */
    fun imageFromDataUrl(dataUrl: String): Base64Image? {
        val idx = dataUrl.indexOf(BASE64_MARK)
        if (idx < 0) return null
        val data = dataUrl.substring(idx + BASE64_MARK.length).trim()
        if (data.isEmpty()) return null
        val head = dataUrl.substring(0, idx)
        val mediaType = head.removePrefix("data:").substringBefore(';').trim()
        return Base64Image(mediaType.ifBlank { DEFAULT_MEDIA_TYPE }, data)
    }

    /**
     * 构造 `/v1/messages` 的请求体。
     *
     * 关键差异（与 OpenAI 对照）：
     * - `system` 是顶层字段，不进 messages；空白时不发送该键；
     * - messages 只有 user / assistant；
     * - `tools` 为空时**不发送 tools 键**（部分中转对空数组报错）；
     * - `tool_choice` 只在有工具时发送，且是 `{"type":"auto"}` 对象。
     *
     * 行为说明：user 的 content 一律用块数组（与改造前 AiEngine 一致），
     * 文字放在第一个 text 块里，图片块跟在其后。官方允许 string 或块数组两种写法，
     * 这里只保留一种，是为了让「有无图片」不改变消息形状，减少中转兼容性差异。
     */
    fun buildRequestBody(
        model: String,
        system: String,
        history: List<HistoryTurn>,
        user: String,
        temperature: Double,
        maxTokens: Int,
        images: List<Base64Image> = emptyList(),
        tools: List<ToolSpec> = emptyList(),
        toolChoice: String? = "auto"
    ): Map<String, Any?> {
        val msgs = ArrayList<Any?>()
        history.forEach { turn ->
            msgs.add(
                linkedMapOf<String, Any?>(
                    "role" to if (turn.role == "assistant") "assistant" else "user",
                    "content" to turn.content
                )
            )
        }

        val text = if (user.isBlank() && images.isNotEmpty()) DEFAULT_IMAGE_USER_TEXT else user
        val blocks = ArrayList<Any?>()
        blocks.add(linkedMapOf<String, Any?>("type" to "text", "text" to text))
        images.forEach { blocks.add(imageBlock(it)) }
        msgs.add(linkedMapOf<String, Any?>("role" to "user", "content" to blocks))

        val body = linkedMapOf<String, Any?>(
            "model" to model,
            "max_tokens" to maxTokens,
            "temperature" to temperature,
            "messages" to msgs
        )
        if (system.isNotBlank()) body["system"] = system
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map { toolSchema(it) }
            toolChoice?.takeIf { it.isNotBlank() }?.let { body["tool_choice"] = linkedMapOf("type" to it) }
        }
        return body
    }

    /**
     * 把 assistant 那一轮的原始内容回放进下一轮请求。
     *
     * 必须原样带回 `tool_use` 块（id / name / input），否则下一轮模型看到的是
     * 「凭空出现的工具结果」，上下文不一致，容易报错或胡编。
     * 文字为空时不塞空 text 块，避免部分服务端拒绝空文本块。
     */
    fun assistantTurn(uses: List<ToolUseBlock>, text: String = ""): Map<String, Any?> {
        val blocks = ArrayList<Any?>()
        if (text.isNotBlank()) blocks.add(linkedMapOf<String, Any?>("type" to "text", "text" to text))
        uses.forEach { use ->
            blocks.add(
                linkedMapOf<String, Any?>(
                    "type" to "tool_use",
                    "id" to use.id,
                    "name" to use.name,
                    "input" to use.input
                )
            )
        }
        return linkedMapOf("role" to "assistant", "content" to blocks)
    }

    /**
     * 工具结果的回填形状：**user 消息里嵌 `tool_result` 块**。
     *
     * 这是与 OpenAI 最大的形状差异：OpenAI 是独立一条 `{role:"tool", tool_call_id, content}`，
     * Anthropic 不行，它只允许 user / assistant 两种角色，结果必须作为 user 的内容块出现。
     */
    fun toolResultTurn(results: List<ToolResultBlock>): Map<String, Any?> {
        val blocks = ArrayList<Any?>()
        results.forEach { r ->
            val block = linkedMapOf<String, Any?>(
                "type" to "tool_result",
                "tool_use_id" to r.toolUseId,
                "content" to r.content
            )
            // is_error 只在失败时出现，避免给成功结果加一个 false 干扰模型判断
            if (r.isError) block["is_error"] = true
            blocks.add(block)
        }
        return linkedMapOf("role" to "user", "content" to blocks)
    }

    // ------------------------------------------------------------------ 响应

    /**
     * 解析 `/v1/messages` 的响应体（已由调用方转成普通 Kotlin 结构）。
     *
     * 传入 null 返回 null，表示调用方认为这次调用无效。
     * content 不是数组时按纯文本容错（个别中转会这么干，待真机验证）。
     */
    fun parseResponse(json: Map<String, Any?>?): Reply? {
        if (json == null) return null

        val texts = ArrayList<String>()
        val uses = ArrayList<ToolUseBlock>()
        val unknown = ArrayList<String>()

        when (val content = json["content"]) {
            null -> Unit
            is String -> if (content.isNotEmpty()) texts.add(content)
            is List<*> -> content.forEach { item ->
                val block = item as? Map<*, *> ?: return@forEach
                when (val type = block["type"] as? String) {
                    "text" -> (block["text"] as? String)?.let { texts.add(it) }
                    "tool_use" -> {
                        val raw = block["input"]
                        val obj = raw as? Map<*, *>
                        val input = LinkedHashMap<String, Any?>()
                        obj?.forEach { (k, v) -> input[k.toString()] = v }
                        uses.add(
                            ToolUseBlock(
                                id = block["id"] as? String ?: "",
                                name = block["name"] as? String ?: "",
                                input = input,
                                inputIsObject = obj != null
                            )
                        )
                    }
                    else -> if (type != null) unknown.add(type)
                }
            }
            else -> Unit
        }

        return Reply(
            id = json["id"] as? String ?: "",
            model = json["model"] as? String ?: "",
            stopReason = json["stop_reason"] as? String ?: "",
            texts = texts,
            toolUses = uses,
            unknownTypes = unknown
        )
    }
}
