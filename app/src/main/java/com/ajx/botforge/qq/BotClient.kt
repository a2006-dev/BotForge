package com.ajx.botforge.qq

import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import org.json.JSONObject
import java.net.URI

/**
 * 单个 Bot 的网关连接
 * 负责：鉴权 → 心跳 → 收消息 → 交给处理器 → 发回复
 *
 * 移植自已验证可用的 bot.py，opcode 语义：
 *   10 HELLO → 发 op2 鉴权 / op6 续连
 *   0  事件  → READY / RESUMED / 消息
 *   11 ACK   → 忽略
 *   7  重连  → 关闭重连
 *   9  无效会话 → 清 session 后重新鉴权
 */
class BotClient(
    private val cfg: Config.Bot,
    private val handler: (Event) -> Unit
) {

    data class Event(
        val scene: String,
        val groupOpenId: String,
        val userOpenId: String,
        val messageId: String,
        val content: String,
        val raw: JSONObject,
        /** 图片附件 URL 列表（可能为空） */
        val images: List<String> = emptyList()
    )

    /** 从事件 d 里解析图片附件 */
    private fun parseImages(d: JSONObject): List<String> {
        val arr = d.optJSONArray("attachments") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val type = a.optString("content_type", "")
            if (!type.startsWith("image/")) continue
            val url = a.optString("url", "")
            if (url.isNotEmpty()) out.add(url)
        }
        return out
    }

    private val session = QqApi.Session(cfg.appId, cfg.appSecret)
    private var ws: WsClient? = null
    private var heartbeatThread: Thread? = null
    private var runThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var seq: Int = 0
    @Volatile private var interval: Long = 41250
    @Volatile private var sessionId: String = ""
    @Volatile private var botName: String = ""

    /** 消息去重：msgId -> 处理时间（BotClient 与 MessageHandler 各有一份，双重保险） */
    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 400
    }

    fun start() {
        if (running) return
        running = true
        runThread = Thread({ loop() }, "bot-${cfg.appId}").also { it.start() }
        Logger.i("Bot", "启动 ${cfg.name}")
    }

    fun stop() {
        running = false
        heartbeatThread?.interrupt()
        runCatching { ws?.close() }
        runCatching { runThread?.interrupt() }
        Logger.i("Bot", "${cfg.name} 已停止")
    }

    fun isRunning() = running

    fun displayName() = botName.ifEmpty { cfg.name }

    // ------------------------------------------------------------ 主循环
    private fun loop() {
        var backoff = 3000L
        while (running) {
            try {
                connectAndListen()
                backoff = 3000L
            } catch (e: Exception) {
                if (!running) break
                Logger.e("Bot", "${cfg.name} 连接异常: ${e.message}")
            }
            if (!running) break
            Logger.w("Bot", "${cfg.name} ${backoff}ms 后重连")
            sleepQuiet(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
        Logger.i("Bot", "${cfg.name} 循环结束")
    }

    private fun connectAndListen() {
        val gatewayUrl = QqApi.gateway(session)
        val uri = URI(gatewayUrl)
        val host = uri.host ?: "api.sgroup.qq.com"
        val path = uri.path.ifEmpty { "/websocket" }

        Logger.i("Bot", "连接网关 $host$path")
        val client = WsClient(host, 443, path) { Logger.i("Bot", it) }
        client.connect()
        ws = client

        // 1. 读 HELLO
        val hello = client.read(30000) ?: throw IllegalStateException("未收到 HELLO")
        val helloObj = JSONObject(hello)
        val op = helloObj.optInt("op", -1)
        if (op != 10) throw IllegalStateException("首个包不是 HELLO: op=$op")
        interval = helloObj.optJSONObject("d")?.optLong("heartbeat_interval", 41250) ?: 41250
        Logger.i("Bot", "心跳间隔 ${interval}ms")

        // 2. 鉴权
        identify(client)

        // 3. 心跳线程
        startHeartbeat(client)

        // 4. 收包循环
        while (running && client.isAlive()) {
            val text = try {
                client.read((interval + 30000).toInt())
            } catch (e: Exception) {
                if (!running) break
                null
            }
            if (text == null) {
                Logger.w("Bot", "${cfg.name} 读取超时或断开")
                break
            }
            dispatch(text)
        }
        heartbeatThread?.interrupt()
        runCatching { client.close() }
        ws = null
    }

    private fun identify(client: WsClient) {
        QqApi.token(session)
        if (sessionId.isNotEmpty() && seq > 0) {
            // 续连
            val p = JSONObject().apply {
                put("op", 6)
                put("d", JSONObject().apply {
                    put("token", "QQBot " + session.token)
                    put("session_id", sessionId)
                    put("seq", seq)
                })
            }
            client.send(p.toString())
            Logger.i("Bot", "发送续连包 seq=$seq")
        } else {
            // 全新鉴权
            val p = JSONObject().apply {
                put("op", 2)
                put("d", JSONObject().apply {
                    put("token", "QQBot " + session.token)
                    put("intents", cfg.intents)
                    put("shard", org.json.JSONArray().put(0).put(1))
                    put("properties", JSONObject().apply {
                        put("\$os", "Android")
                        put("\$browser", "BotForge")
                        put("\$device", "android")
                    })
                })
            }
            client.send(p.toString())
            Logger.i("Bot", "发送鉴权包 intents=${cfg.intents}")
        }
    }

    private fun startHeartbeat(client: WsClient) {
        heartbeatThread = Thread({
            var alive = true
            while (alive && running && client.isAlive()) {
                sleepQuiet(interval)
                if (!running || !client.isAlive()) break
                runCatching {
                    val p = JSONObject().apply {
                        put("op", 1)
                        if (seq > 0) put("d", seq)
                    }
                    client.send(p.toString())
                }.onFailure { alive = false }
            }
        }, "heartbeat-${cfg.appId}").also { it.isDaemon = true; it.start() }
    }

    private fun dispatch(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val op = obj.optInt("op", -1)
        if (obj.has("s")) seq = obj.optInt("s", seq)

        when (op) {
            0 -> {
                val t = obj.optString("t", "")
                when (t) {
                    "READY" -> {
                        val d = obj.optJSONObject("d")
                        sessionId = d?.optString("session_id") ?: ""
                        val user = d?.optJSONObject("user")
                        botName = user?.optString("username") ?: cfg.name
                        Logger.i("Bot", "★ 已上线: $botName (session=${sessionId.take(8)})")
                        reportStatus("$botName 已上线")
                    }
                    "RESUMED" -> Logger.i("Bot", "会话已恢复")
                    "GROUP_AT_MESSAGE_CREATE",
                    "GROUP_MESSAGE_CREATE" -> onGroupMessage(obj)
                    "C2C_MESSAGE_CREATE" -> onC2cMessage(obj)
                    else -> if (t.isNotEmpty()) Logger.i("Bot", "事件 $t（未处理）")
                }
            }
            11 -> { /* 心跳 ACK */ }
            7 -> {
                Logger.w("Bot", "收到重连指令")
                runCatching { ws?.close() }
            }
            9 -> {
                Logger.w("Bot", "会话失效，清空 session 重新鉴权")
                sessionId = ""
                seq = 0
                QqApi.token(session, force = true)
                runCatching { ws?.close() }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ 消息处理
    private fun onGroupMessage(obj: JSONObject) {
        val d = obj.optJSONObject("d") ?: return
        val groupOpenId = d.optString("group_openid")
        val msgId = d.optString("id")
        val content = d.optString("content").trim()
        val author = d.optJSONObject("author")?.optString("member_openid") ?: ""
        val images = parseImages(d)

        if (!accept(groupOpenId, msgId, content, images.isNotEmpty())) return

        val ev = Event("group", groupOpenId, author, msgId, content, d, images)
        Logger.i("Bot", "→ 群消息 ${content.take(40)}${if (images.isNotEmpty()) " [图${images.size}]" else ""}")
        runCatching { handler(ev) }.onFailure {
            Logger.e("Bot", "处理异常: ${it.message}")
        }
    }

    private fun onC2cMessage(obj: JSONObject) {
        val d = obj.optJSONObject("d") ?: return
        val userOpenId = d.optJSONObject("author")?.optString("user_openid")
            ?: d.optString("user_openid")
        val msgId = d.optString("id")
        val content = d.optString("content").trim()
        val images = parseImages(d)

        if (!accept("c2c:$userOpenId", msgId, content, images.isNotEmpty())) return

        val ev = Event("c2c", "", userOpenId, msgId, content, d, images)
        Logger.i("Bot", "→ 私聊消息 ${content.take(40)}${if (images.isNotEmpty()) " [图${images.size}]" else ""}")
        runCatching { handler(ev) }.onFailure {
            Logger.e("Bot", "处理异常: ${it.message}")
        }
    }

    /** 统一过滤：去重 + 群开关 + 空内容（有图时不算空） */
    private fun accept(key: String, msgId: String, content: String, hasImage: Boolean = false): Boolean {
        if (msgId.isEmpty()) return false
        synchronized(seen) {
            if (seen.containsKey(msgId)) {
                Logger.i("Bot", "重复消息，跳过")
                return false
            }
            seen[msgId] = System.currentTimeMillis()
        }
        if (content.isEmpty() && !hasImage) return false

        if (key.startsWith("c2c:")) return true

        // 群配置检查
        val gc = cfg.groups.firstOrNull { it.groupOpenId == key }
        if (gc != null) {
            if (!gc.enabled) {
                Logger.i("Bot", "该群已禁用")
                return false
            }
            if (gc.requireAt) {
                val atMe = content.contains("<@") || content.contains("@")
                if (!atMe) return false
            }
        } else {
            // 未配置的群：默认不响应，避免陌生群炸群
            Logger.i("Bot", "未配置的群 $key，忽略")
            return false
        }
        return true
    }

    /** 长消息分片，QQ 单条限约 2000 字，这里保守取 1200 */
    private fun splitMessage(text: String): List<String> {
        val max = 1200
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= max) {
                out.add(rest)
                break
            }
            var cut = rest.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', ' '), max)
            if (cut < max / 2) cut = max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        return out
    }

    private fun reportStatus(s: String) {
        runCatching { com.ajx.botforge.service.BotService.instance?.updateStatus(s) }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}