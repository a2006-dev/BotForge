package com.ajx.botforge.ai

import com.ajx.botforge.data.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型服务端能力探测：拉取可选模型列表 + 连通性测试。
 *
 * 兼容三种服务端：
 *  - OpenAI 兼容（绝大多数中转、vLLM、Ollama、OneAPI、NewAPI）
 *      GET {base}/models  → {"data":[{"id":"gpt-4o"}, ...]}
 *  - Anthropic
 *      GET {base}/models  → {"data":[{"id":"claude-...","display_name":"..."}]}
 *      需要 x-api-key + anthropic-version
 *  - 纯 OpenAI 官方
 *      同上，Bearer 鉴权
 *
 * 结果全部包成 [ProbeResult]，UI 无需关心异常。
 */
object ModelProbe {

    /** 单个模型信息 */
    data class ModelInfo(val id: String, val display: String)

    /** 拉列表结果 */
    data class ListResult(
        val ok: Boolean,
        val models: List<ModelInfo>,
        val message: String
    )

    /** 连通性结果 */
    data class PingResult(
        val ok: Boolean,
        val latencyMs: Long,
        val message: String
    )

    private fun baseUrlOf(base: String): String {
        var b = base.trim().trimEnd('/')
        // 用户可能填了完整 chat 地址，剥回到 /v1 这一层
        b = b.removeSuffix("/chat/completions").removeSuffix("/messages")
        return b
    }

    private fun open(url: String, key: String, type: String, timeoutSec: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = timeoutSec * 1000
        c.readTimeout = timeoutSec * 1000
        c.setRequestProperty("Accept", "application/json")
        if (type == "anthropic") {
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            c.setRequestProperty("Authorization", "Bearer $key")
        }
        return c
    }

    private fun readAll(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    /**
     * 拉取服务端可选模型。
     *
     * 失败时把 HTTP 状态码与响应体前 200 字带回去，便于排查是鉴权错、
     * 地址错还是服务端没实现 /models。
     */
    fun listModels(model: Config.Model): ListResult {
        val base = baseUrlOf(model.baseUrl)
        if (base.isBlank()) return ListResult(false, emptyList(), "接口地址为空")
        val url = "$base/models"
        return try {
            val c = open(url, model.apiKey, model.type, minOf(model.timeout, 30))
            val body = readAll(c)
            val code = c.responseCode
            c.disconnect()
            if (code !in 200..299) {
                return ListResult(false, emptyList(), "HTTP $code：${body.take(200)}")
            }
            val arr: JSONArray = try {
                val o = JSONObject(body)
                when {
                    o.has("data") -> o.getJSONArray("data")
                    o.has("models") -> o.getJSONArray("models")
                    else -> JSONArray()
                }
            } catch (e: Exception) {
                // 少数服务端直接返回数组
                JSONArray(body)
            }
            val out = mutableListOf<ModelInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("name") }
                if (id.isBlank()) continue
                val disp = o.optString("display_name").ifBlank { id }
                out.add(ModelInfo(id, disp))
            }
            if (out.isEmpty()) {
                ListResult(false, emptyList(), "接口通了，但没有返回可用模型")
            } else {
                out.sortBy { it.id }
                ListResult(true, out, "获取到 ${out.size} 个模型")
            }
        } catch (e: Exception) {
            ListResult(false, emptyList(), "请求失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    /**
     * 连通性测试。
     *
     * 走一次极小的对话请求而不是只 ping /models：真正决定能不能用的
     * 是 chat 端点（鉴权、model 名、配额），/models 通了不代表能聊天。
     */
    fun ping(model: Config.Model): PingResult {
        val t0 = System.currentTimeMillis()
        return try {
            val reply = AiEngine.chat(
                model = model,
                system = "只回复一个字。",
                history = emptyList(),
                user = "回一个字：好",
                temperature = 0f,
                maxTokens = 8
            )
            val ms = System.currentTimeMillis() - t0
            if (reply.isNullOrBlank()) {
                PingResult(false, ms, "无响应（详见日志）")
            } else {
                PingResult(true, ms, "正常 · ${ms}ms · 回复「${reply.trim().take(16)}」")
            }
        } catch (e: Exception) {
            PingResult(false, System.currentTimeMillis() - t0, "异常：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
