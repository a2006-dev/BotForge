package com.ajx.botforge.ai

import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 模型调用层：统一 OpenAI 兼容 /anthropic 两种协议
 * 三路来源（反代 / 官方 / 本地）只是 baseUrl+key+model 不同，走同一套代码
 */
object AiEngine {

    data class Msg(val role: String, val content: String)

    private fun normalizeBase(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/chat/completions")) return b
        if (!b.endsWith("/v1")) b = "$b/v1"
        return "$b/chat/completions"
    }

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

    private fun chatAnthropic(
        model: Config.Model,
        system: String,
        history: List<Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList()
    ): String? {
        var base = model.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/messages")) base else "$base/v1/messages"
        val msgs = JSONArray()
        history.forEach {
            msgs.put(
                JSONObject()
                    .put("role", if (it.role == "assistant") "assistant" else "user")
                    .put("content", it.content)
            )
        }

        // Anthropic 的图片用 source.base64 而不是 data URL
        val userBlocks = JSONArray()
        userBlocks.put(
            JSONObject().put("type", "text")
                .put("text", user.ifBlank { if (images.isEmpty()) "" else "看看这几张图，说说你的想法。" })
        )
        images.forEach { dataUrl ->
            val idx = dataUrl.indexOf("base64,")
            if (idx < 0) return@forEach
            val b64 = dataUrl.substring(idx + 7)
            userBlocks.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source", JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64)
                    )
            )
        }
        msgs.put(JSONObject().put("role", "user").put("content", userBlocks))

        val body = JSONObject()
            .put("model", model.model)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature.toDouble())
            .put("messages", msgs)
        if (system.isNotBlank()) body.put("system", system)

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
            val arr = JSONObject(text).optJSONArray("content") ?: return null
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            return sb.toString().trim().ifBlank { null }
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
