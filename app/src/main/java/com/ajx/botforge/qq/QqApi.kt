package com.ajx.botforge.qq

import com.ajx.botforge.data.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ 官方开放平台 API
 * 已验证的端点：
 *   token:  POST https://bots.qq.com/app/getAppAccessToken
 *   gateway:GET  https://api.sgroup.qq.com/gateway
 *   发群消息: POST https://api.sgroup.qq.com/v2/groups/{group_openid}/messages
 *   发私聊:   POST https://api.sgroup.qq.com/v2/users/{user_openid}/messages
 */
object QqApi {

    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
    private const val BASE = "https://api.sgroup.qq.com"

    class Session(val appId: String, val appSecret: String) {
        @Volatile var token: String = ""
        @Volatile var expiresAt: Long = 0
    }

    /** 获取或刷新 token，提前 60 秒 */
    fun token(s: Session, force: Boolean = false): String {
        if (!force && s.token.isNotEmpty() && System.currentTimeMillis() < s.expiresAt - 60_000) {
            return s.token
        }
        val body = JSONObject().apply {
            put("appId", s.appId)
            put("clientSecret", s.appSecret)
        }.toString()

        val resp = httpJson(TOKEN_URL, "POST", null, body)
            ?: throw IllegalStateException("获取 token 失败：空响应")
        val j = JSONObject(resp)
        if (!j.has("access_token")) {
            throw IllegalStateException("获取 token 失败：${resp.take(200)}")
        }
        s.token = j.getString("access_token")
        val exp = j.optInt("expires_in", 7200)
        s.expiresAt = System.currentTimeMillis() + exp * 1000L
        Logger.i("Api", "AccessToken 已刷新，有效期 ${exp}s")
        return s.token
    }

    /** 网关地址 */
    fun gateway(s: Session): String {
        val resp = httpGet("$BASE/gateway", s)
            ?: return "wss://api.sgroup.qq.com/websocket"
        return runCatching { JSONObject(resp).optString("url") }
            .getOrDefault("")
            .ifEmpty { "wss://api.sgroup.qq.com/websocket" }
    }

    /** 发群消息 */
    fun sendGroup(s: Session, groupOpenId: String, content: String, msgId: String? = null, seq: Int = 1): Boolean {
        val body = JSONObject().apply {
            put("content", content)
            put("msg_type", 0)
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", seq)
            }
        }.toString()
        val resp = httpJson("$BASE/v2/groups/$groupOpenId/messages", "POST", s, body)
            ?: return false
        val ok = resp.contains("\"id\"")
        if (!ok) Logger.w("Api", "群消息发送失败: ${resp.take(240)}")
        return ok
    }

    /** 发私聊消息 */
    fun sendC2C(s: Session, userOpenId: String, content: String, msgId: String? = null, seq: Int = 1): Boolean {
        val body = JSONObject().apply {
            put("content", content)
            put("msg_type", 0)
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", seq)
            }
        }.toString()
        val resp = httpJson("$BASE/v2/users/$userOpenId/messages", "POST", s, body)
            ?: return false
        val ok = resp.contains("\"id\"")
        if (!ok) Logger.w("Api", "私聊发送失败: ${resp.take(240)}")
        return ok
    }

    // ---------------------------------------------------------------- Bot 便捷重载
    private val botSessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    private fun sessionOf(bot: com.ajx.botforge.data.Config.Bot): Session =
        botSessions.getOrPut(bot.id) { Session(bot.appId, bot.appSecret) }

    fun sendGroup(bot: com.ajx.botforge.data.Config.Bot, groupOpenId: String, content: String): Boolean =
        sendGroup(sessionOf(bot), groupOpenId, content)

    // 被动回复带 msgId + 递增 seq 的重载（F10 之后 reply() 使用）
    fun sendGroup(bot: com.ajx.botforge.data.Config.Bot, groupOpenId: String, content: String, msgId: String?, seq: Int): Boolean =
        sendGroup(sessionOf(bot), groupOpenId, content, msgId, seq)

    fun sendC2C(bot: com.ajx.botforge.data.Config.Bot, userOpenId: String, content: String): Boolean =
        sendC2C(sessionOf(bot), userOpenId, content)

    fun sendC2C(bot: com.ajx.botforge.data.Config.Bot, userOpenId: String, content: String, msgId: String?, seq: Int): Boolean =
        sendC2C(sessionOf(bot), userOpenId, content, msgId, seq)

    // ---------------------------------------------------------------- HTTP
    private fun httpGet(url: String, s: Session, timeoutMs: Int = 20000): String? =
        request(url, "GET", s, null, timeoutMs)

    private fun httpJson(url: String, method: String, s: Session?, body: String?, timeoutMs: Int = 30000): String? =
        request(url, method, s, body, timeoutMs)

    private fun request(
        url: String, method: String, s: Session?, body: String?, timeoutMs: Int
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/json")
                if (s != null) {
                    setRequestProperty("Authorization", "QQBot " + token(s))
                    setRequestProperty("X-Union-Appid", s.appId)
                }
                if (body != null) doOutput = true
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Logger.w("Api", "$method ${url.substringAfter("sgroup.qq.com")} -> $code ${text.take(200)}")
            }
            text
        } catch (e: Exception) {
            Logger.e("Api", "请求异常 ${url.takeLast(50)}: ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}