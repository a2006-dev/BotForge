package com.ajx.botforge.ai.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具（L2 Tool 层）
 *
 * 契约：工具只暴露名字、描述、JSON Schema 参数、执行函数。
 * 不暴露任何内部对象，与插件层同一原则。
 */
interface Tool {
    /** 工具名，模型据此调用，建议蛇形小写 */
    val name: String

    /** 描述，模型据此判断何时调用，写得越清楚调用越准 */
    val description: String

    /** 参数 JSON Schema，形如 {"type":"object","properties":{...},"required":[...]} */
    val parameters: JSONObject

    /**
     * 执行。args 为模型给出的参数对象。
     *
     * 返回 [ToolResult] 而不是裸 String：框架需要知道这次调用到底成不成，
     * 才能拦住「没执行却声称执行」的情况，也才能把失败原因准确喂回模型。
     * 仍然只返回 String 的老工具会被包成 ToolResult（视为成功）。
     */
    fun run(args: JSONObject): ToolResult = ToolResult.ok(legacyRun(args))

    /**
     * 兼容旧实现：老工具只返回文本。
     * 新工具请直接覆盖 [run] 并给出明确的 success 标记。
     */
    fun legacyRun(args: JSONObject): String =
        throw UnsupportedOperationException("$name 未实现 run()")
}

/**
 * 工具执行结果。
 *
 * [success] 是框架侧的判定依据，不交给模型猜：
 *  - 为 false 时，模型若声称「已完成/已成功」会被判定为编造并拦截
 *  - [error] 会原样喂回模型，让它知道失败在哪，而不是自己编一个原因
 */
data class ToolResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
) {
    /** 给模型看的内容：失败时把错误原因明确写在最前面 */
    fun forModel(): String = if (success) {
        content
    } else {
        val e = error?.takeIf { it.isNotBlank() } ?: "未提供原因"
        "【执行失败】$e\n${content.takeIf { it.isNotBlank() } ?: ""}".trim()
    }

    companion object {
        fun ok(content: String) = ToolResult(true, content)
        fun fail(error: String, content: String = "") = ToolResult(false, content, error)
    }
}

/** 把工具转成 OpenAI function calling 的 tools 数组元素 */
fun Tool.toOpenAiSchema(): JSONObject = JSONObject()
    .put("type", "function")
    .put(
        "function",
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
    )

/** 空参数工具的便捷 schema */
fun noParams(): JSONObject = JSONObject()
    .put("type", "object")
    .put("properties", JSONObject())
    .put("required", JSONArray())
