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

    // ---------------------------------------------------------------- 富媒体（图片 / 视频 / 文件）

    /**
     * 富媒体消息类型。
     *
     * 依据等级 A：botpy 的 post_group_message / post_c2c_message 注释写着
     * 「0 文本、1 图文混排、2 markdown、3 ark、4 embed、7 media 富媒体」；
     * botgo 的 MessageType 同样把 7 定义为 RichMediaMsg。
     */
    private const val MSG_TYPE_MEDIA = 7

    /** 上传读超时：素材比一行 JSON 重得多，给足 60s */
    private const val UPLOAD_TIMEOUT = 60_000

    /**
     * 拼 /files 上传端点。
     *
     * 依据等级 A：botpy 的 post_group_file 打 `/v2/groups/{group_openid}/files`，
     * post_c2c_file 打 `/v2/users/{openid}/files`；botgo 一致。任务书写的两个端点与此吻合。
     *
     * @param scene "group" 或 "c2c"（大小写不敏感，另容忍 user / users / friend）
     * @return 未知场景返回 null
     */
    private fun filesUrl(scene: String, targetOpenId: String): String? = when (scene.lowercase()) {
        "group", "groups" -> "$BASE/v2/groups/$targetOpenId/files"
        "c2c", "user", "users", "friend" -> "$BASE/v2/users/$targetOpenId/files"
        else -> null
    }

    /**
     * 拼发消息端点，与 [filesUrl] 的 scene 规则保持一致。
     *
     * 依据等级 A：与上面 sendGroup / sendC2C 已经在用的端点完全相同。
     */
    private fun messagesUrl(scene: String, targetOpenId: String): String? = when (scene.lowercase()) {
        "group", "groups" -> "$BASE/v2/groups/$targetOpenId/messages"
        "c2c", "user", "users", "friend" -> "$BASE/v2/users/$targetOpenId/messages"
        else -> null
    }

    /**
     * 上传一份富媒体素材，返回可以用于发消息的 file_info（拿不到时退回 file_uuid）。
     *
     * ## 与任务书的一处冲突（如实记录，不按任务书假设去编字段）
     *
     * 任务书说「先调 /files 拿 upload_url + file_uuid，再 PUT 二进制到 upload_url」。
     * 但在官方 SDK 源码里，/files 的**入参是 url**（一条公网可访问的 HTTP/HTTPS 链接），
     * **响应才是** file_uuid / file_info / ttl：
     *
     *  - botpy post_group_file / post_c2c_file：payload 只有 file_type、url、srv_send_msg
     *    三个字段（依据等级 A，源码实测）；docstring 写「file_type: 1 图片png/jpg，
     *    2 视频mp4，3 语音silk，4 文件（暂不开放）」。
     *  - botgo RichMediaMessage：字段为 file_type、url、srv_send_msg、content、msg_seq，
     *    注释「需发送的富媒体文件，HTTP或者HTTPS链接」（依据等级 A）。
     *  - botpy types/message.py 的 Media：file_uuid、file_info、ttl（依据等级 A）。
     *
     * 结论：**官方公开 SDK 里不存在 upload_url 这个返回值，也没有把本地字节
     * 直传上去的公开途径**。任务书那段「两步上传」我没能证实，因此不敢照着写死。
     *
     * ## 所以这里按「能证实多少做多少」实现
     *
     *  1. POST /files，body 用 file_type + file_name + file_data。
     *     file_type 是 A 级；file_name 与 file_data（base64 直传）是 **D 级**，
     *     官方 SDK 里没有，属社区常见写法，**待真机验证**。服务端若按未知名拒绝，
     *     这里记日志返回 null，不会静默当成功。
     *  2. 响应里若有 upload_url，就按任务书走第二步：PUT 原始字节到 upload_url
     *     （**B 级**：任务书描述 + 社区做法，官方 SDK 未证实，**待真机验证**）。
     *  3. 响应里若有 file_info 直接返回（A 级字段名）；否则退回 file_uuid（A 级字段名）。
     *
     * 失败一律返回 null 并记日志，日志带 HTTP 状态与响应片段，便于真机验证时定位。
     */
    fun uploadMedia(
        s: Session,
        scene: String,
        targetOpenId: String,
        fileType: FileType,
        fileName: String,
        bytes: ByteArray
    ): String? {
        val url = filesUrl(scene, targetOpenId)
        if (url == null) {
            Logger.w("Api", "uploadMedia: 未知场景 $scene")
            return null
        }
        // 本地预检：体积不合规就别浪费一次网络往返
        val reason = MediaUpload.checkSize(fileType, bytes.size.toLong())
        if (reason != null) {
            Logger.w("Api", "uploadMedia 被本地预检拒绝：$reason")
            return null
        }

        val name = MediaUpload.sanitizeFileName(fileName)
        val body = JSONObject().apply {
            put("file_type", fileType.code)
            put("file_name", name)
            // base64 直传：字段名未经官方文档确认（D 级，待真机验证）
            put("file_data", java.util.Base64.getEncoder().encodeToString(bytes))
        }.toString()

        // 上传是 POST，允许重试一次：重复上传只会多出一份素材，不会让用户看到复读
        val resp = httpJson(url, "POST", s, body, readTimeout = UPLOAD_TIMEOUT, retry = 1)
            ?: return null
        val j = runCatching { JSONObject(resp) }.getOrNull()
        if (j == null) {
            Logger.w("Api", "uploadMedia 响应不是 JSON: ${resp.take(200)}")
            return null
        }

        // 第二步（仅当服务端真的给了 upload_url）：PUT 原始字节
        val uploadUrl = j.optString("upload_url")
        if (uploadUrl.isNotEmpty()) {
            val ok = putBinary(uploadUrl, MediaUpload.inferMime(name), bytes, UPLOAD_TIMEOUT, 1)
            if (!ok) {
                Logger.w("Api", "uploadMedia: PUT upload_url 失败")
                return null
            }
        }

        val fileInfo = j.optString("file_info")
        if (fileInfo.isNotEmpty()) return fileInfo
        val fileUuid = j.optString("file_uuid")
        if (fileUuid.isNotEmpty()) {
            // file_uuid 只是兜底：发消息的 media 要的是 file_info，这里多半会失败
            Logger.w("Api", "uploadMedia 只拿到 file_uuid，发消息大概率失败")
            return fileUuid
        }
        Logger.w("Api", "uploadMedia 响应缺少 file_info / file_uuid: ${resp.take(200)}")
        return null
    }

    /**
     * 发一张图片。
     *
     * @param scene 同 [uploadMedia]："group" 或 "c2c"
     * @param msgId 非空表示被动回复（5 分钟有效期），为空是主动消息
     */
    fun sendImage(
        bot: com.ajx.botforge.data.Config.Bot,
        scene: String,
        targetOpenId: String,
        fileName: String,
        bytes: ByteArray,
        msgId: String? = null
    ): Boolean = sendMedia(sessionOf(bot), scene, targetOpenId, FileType.IMAGE, fileName, bytes, msgId)

    /**
     * 发一个文件。
     *
     * 注意 file_type=4（文件）在官方 docstring 里标着「暂不开放」（A 级），
     * 真机很可能直接拒绝，这一步的可用性**待真机验证**。
     */
    fun sendFile(
        bot: com.ajx.botforge.data.Config.Bot,
        scene: String,
        targetOpenId: String,
        fileName: String,
        bytes: ByteArray,
        msgId: String? = null
    ): Boolean = sendMedia(sessionOf(bot), scene, targetOpenId, FileType.FILE, fileName, bytes, msgId)

    /** 上传 + 发送的串联：任一步失败即整体失败 */
    private fun sendMedia(
        s: Session,
        scene: String,
        targetOpenId: String,
        fileType: FileType,
        fileName: String,
        bytes: ByteArray,
        msgId: String?
    ): Boolean {
        val fileInfo = uploadMedia(s, scene, targetOpenId, fileType, fileName, bytes) ?: return false
        return sendMediaMessage(s, scene, targetOpenId, fileInfo, msgId)
    }

    /**
     * 用 file_info 发一条富媒体消息。
     *
     * msg_type=7 + media.file_info 是 A 级依据：
     * botgo 的 MessageToCreate 有 Media *MediaInfo 且 MediaInfo 只有 file_info 字段；
     * botpy 的 post_group_message 有 media 参数、Media 里写着 file_info。
     *
     * msg_seq 固定 1：主动消息不需要 seq，被动回复场景目前传一个固定值即可，
     * 与现有 sendGroup / sendC2C 的默认值保持一致。
     */
    private fun sendMediaMessage(
        s: Session,
        scene: String,
        targetOpenId: String,
        fileInfo: String,
        msgId: String?
    ): Boolean {
        val url = messagesUrl(scene, targetOpenId)
        if (url == null) {
            Logger.w("Api", "富媒体消息: 未知场景 $scene")
            return false
        }
        val body = JSONObject().apply {
            put("msg_type", MSG_TYPE_MEDIA)
            put("media", JSONObject().put("file_info", fileInfo))
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", 1)
            }
        }.toString()
        // 发消息不重试：与 sendGroup / sendC2C 同一理由，重复投递会让用户看到机器人复读
        val resp = httpJson(url, "POST", s, body, readTimeout = 20_000, retry = 0) ?: return false
        val ok = resp.contains("\"id\"")
        if (!ok) Logger.w("Api", "富媒体消息发送失败: ${resp.take(240)}")
        return ok
    }

    /**
     * 把二进制原样 PUT 到预签名 upload_url。
     *
     * ## 为什么不复用 request()
     *
     * [request] 的 body 参数是 String，内部用 `body.toByteArray(Charsets.UTF_8)` 写出，
     * 并且硬编码了 `Content-Type: application/json`。这两点在二进制上传上都是错的：
     *
     *  1. 先 ByteArray -> String -> ByteArray 往返一趟，0x80..0xFF 区间会被 UTF-8
     *     编码替换成 EF BF BD，图片与压缩包会直接损坏（不是概率问题，是必然）。
     *  2. 预签名 URL 通常把 Content-Type 纳入签名，发成 application/json 会签名不匹配。
     *
     * 所以这里单开一个「只写原始字节」的最小实现。超时策略、重试退避、
     * 异常分类与 [request] 保持一致，避免两套 HTTP 行为分叉。
     *
     * 另外刻意**不带 Authorization**：预签名 URL 自带鉴权，多带一个头反而可能
     * 让签名校验失败（依据等级 C，常见对象存储行为，待真机验证）。
     */
    private fun putBinary(
        url: String,
        contentType: String,
        bytes: ByteArray,
        readTimeout: Int,
        retry: Int
    ): Boolean {
        var attempt = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = CONNECT_TIMEOUT
                    this.readTimeout = readTimeout
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                    // 已知长度：避免 HttpURLConnection 为算长度把整个 body 缓进内存
                    setFixedLengthStreamingMode(bytes.size)
                }
                conn.outputStream.use { it.write(bytes) }

                val code = conn.responseCode
                if (code in 200..299) return true
                val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Logger.w("Api", "PUT upload_url -> $code ${text.take(200)}")
                if (code >= 500 && attempt < retry) {
                    attempt++
                    sleepBackoff(attempt)
                    continue
                }
                return false
            } catch (e: Exception) {
                Logger.e("Api", "PUT upload_url 异常: ${e.message}")
                if (attempt < retry) {
                    attempt++
                    sleepBackoff(attempt)
                    continue
                }
                return false
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }
}
