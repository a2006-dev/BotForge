package com.ajx.botforge.qq

import com.ajx.botforge.data.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ 官方开放平台 API
 *
 * 已验证端点：
 *   token:  POST https://bots.qq.com/app/getAppAccessToken
 *   gateway:GET  https://api.sgroup.qq.com/gateway
 *   发群消息: POST https://api.sgroup.qq.com/v2/groups/{group_openid}/messages
 *   发私聊:   POST https://api.sgroup.qq.com/v2/users/{user_openid}/messages
 *
 * 任务书第五节整改点：
 *
 * 1. **超时与 WebSocket 彻底分离**
 *    HTTP 请求是「一问一答」的短连接，超时只影响单次调用；
 *    WebSocket 是长连接，超时代表链路可能已死。两者混谈会让
 *    HTTP 的慢请求被误判成连接问题。
 *    这里连接超时固定 10s（TCP+TLS 建连），读超时按接口类型区分：
 *    token 15s、gateway 10s、发消息 20s。
 *
 * 2. **不是所有请求都能安全重试**
 *    GET（token 除外）幂等，可重试。
 *    发消息的 POST **不重试** —— 一旦服务端已收到但响应超时，
 *    重试会导致重复发消息（用户会看到机器人复读）。
 *    token 的 POST 幂等（只是换新令牌），可重试一次。
 *    重试仅针对「网络层抖动」（IOException / 5xx），4xx 一律不重试。
 *
 * 3. **token 失效自动重取一次**
 *    QQ 网关在 token 过期时返回 401，此时强制刷新 token 重发，
 *    但同样只对幂等请求做，避免消息重复。
 */
object QqApi {

    private const val TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken"
    private const val BASE = "https://api.sgroup.qq.com"

    /** 连接超时：TCP + TLS 建连，与读超时分开 */
    private const val CONNECT_TIMEOUT = 10_000

    class Session(val appId: String, val appSecret: String) {
        @Volatile var token: String = ""
        @Volatile var expiresAt: Long = 0

        /** 供日志脱敏 */
        fun describe(): String = "appId=$appId"
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

        // token 请求幂等（重复请求只是换新令牌），允许一次重试
        val resp = httpJson(TOKEN_URL, "POST", null, body, readTimeout = 15_000, retry = 1)
            ?: throw IllegalStateException("获取 token 失败：空响应")
        val j = JSONObject(resp)
        if (!j.has("access_token")) {
            throw IllegalStateException("获取 token 失败：${resp.take(200)}")
        }
        s.token = j.getString("access_token")
        val exp = j.optInt("expires_in", 7200)
        s.expiresAt = System.currentTimeMillis() + exp * 1000L
        Logger.i("Api", "AccessToken 已刷新（${s.describe()}），有效期 ${exp}s")
        return s.token
    }

    /** 网关地址 */
    fun gateway(s: Session): String {
        // GET 幂等，可重试一次
        val resp = httpGet("$BASE/gateway", s, readTimeout = 10_000, retry = 1)
        val url = resp?.let { runCatching { JSONObject(it).optString("url") }.getOrDefault("") } ?: ""
        return url.ifEmpty { "wss://api.sgroup.qq.com/websocket" }
    }

    /** 发群消息 */
    fun sendGroup(
        s: Session,
        groupOpenId: String,
        content: String,
        msgId: String? = null,
        seq: Int = 1
    ): Boolean {
        val body = JSONObject().apply {
            put("content", content)
            put("msg_type", 0)
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", seq)
            }
        }.toString()
        // 发消息不重试：重复投递会让用户看到机器人复读
        val resp = httpJson(
            "$BASE/v2/groups/$groupOpenId/messages", "POST", s, body,
            readTimeout = 20_000, retry = 0
        ) ?: return false
        val ok = resp.contains("\"id\"")
        if (!ok) Logger.w("Api", "群消息发送失败: ${resp.take(240)}")
        return ok
    }

    /** 发私聊消息 */
    fun sendC2C(
        s: Session,
        userOpenId: String,
        content: String,
        msgId: String? = null,
        seq: Int = 1
    ): Boolean {
        val body = JSONObject().apply {
            put("content", content)
            put("msg_type", 0)
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", seq)
            }
        }.toString()
        val resp = httpJson(
            "$BASE/v2/users/$userOpenId/messages", "POST", s, body,
            readTimeout = 20_000, retry = 0
        ) ?: return false
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

    fun sendGroup(
        bot: com.ajx.botforge.data.Config.Bot,
        groupOpenId: String,
        content: String,
        msgId: String?,
        seq: Int
    ): Boolean = sendGroup(sessionOf(bot), groupOpenId, content, msgId, seq)

    fun sendC2C(bot: com.ajx.botforge.data.Config.Bot, userOpenId: String, content: String): Boolean =
        sendC2C(sessionOf(bot), userOpenId, content)

    fun sendC2C(
        bot: com.ajx.botforge.data.Config.Bot,
        userOpenId: String,
        content: String,
        msgId: String?,
        seq: Int
    ): Boolean = sendC2C(sessionOf(bot), userOpenId, content, msgId, seq)

    // ---------------------------------------------------------------- HTTP

    private fun httpGet(
        url: String,
        s: Session,
        readTimeout: Int,
        retry: Int
    ): String? = request(url, "GET", s, null, readTimeout, retry)

    private fun httpJson(
        url: String,
        method: String,
        s: Session?,
        body: String?,
        readTimeout: Int,
        retry: Int
    ): String? = request(url, method, s, body, readTimeout, retry)

    /**
     * 执行一次 HTTP 请求
     *
     * @param retry 允许的重试次数（仅网络异常与 5xx 生效，4xx 直接返回）
     */
    private fun request(
        url: String,
        method: String,
        s: Session?,
        body: String?,
        readTimeout: Int,
        retry: Int
    ): String? {
        var attempt = 0
        var tokenRefreshed = false

        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    // 连接与读取分离，连接阶段不拖长读超时
                    connectTimeout = CONNECT_TIMEOUT
                    this.readTimeout = readTimeout
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

                // 401：token 失效，强制刷新后重试一次（仅幂等请求，避免消息重复）
                if (code == 401 && s != null && !tokenRefreshed && retry > 0) {
                    tokenRefreshed = true
                    Logger.w("Api", "401 token 失效，强制刷新后重试")
                    runCatching { token(s, force = true) }
                    attempt++
                    continue
                }

                if (code !in 200..299) {
                    Logger.w(
                        "Api",
                        "$method ${url.substringAfter("sgroup.qq.com")} -> $code ${text.take(200)}"
                    )
                    // 5xx 可重试，4xx 不重试
                    if (code >= 500 && attempt < retry) {
                        attempt++
                        sleepBackoff(attempt)
                        continue
                    }
                }
                return text
            } catch (e: Exception) {
                val kind = when (e) {
                    is java.net.SocketTimeoutException -> "读超时"
                    is java.net.ConnectException -> "建连失败"
                    is java.net.UnknownHostException -> "DNS 失败"
                    is javax.net.ssl.SSLException -> "TLS 失败"
                    is java.io.IOException -> "IO 异常"
                    else -> e.javaClass.simpleName
                }
                Logger.e("Api", "请求异常[$kind] ${url.takeLast(50)}: ${e.message}")
                // 网络层抖动重试，且只对允许重试的调用生效
                if (attempt < retry) {
                    attempt++
                    sleepBackoff(attempt)
                    continue
                }
                return null
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun sleepBackoff(attempt: Int) {
        runCatching { Thread.sleep(500L * attempt) }
    }
}