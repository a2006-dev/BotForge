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

    /** 执行。args 为模型给出的参数对象，返回给模型看的文本 */
    fun run(args: JSONObject): String
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