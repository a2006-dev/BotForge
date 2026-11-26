package com.ajx.botforge.qq

import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * 单个 Bot 的网关连接
 *
 * 对应任务书第二节 2、3、4 的整改：
 *
 * 1. **连接代次隔离**
 *    每个连接有自己的 `Gen`（代次 + ws + 心跳线程）。旧代次的任何回调
 *    都先校验 `gen.id == currentGenId`，不相等直接丢弃。
 *    初版所有字段（ws / heartbeatThread / lastAckAt）都是实例级，
 *    旧连接的 `ws?.close()` 会误关新连接，旧心跳线程污染的 lastAckAt
 *    又会让新连接被误判假死 —— 这是重连打架的根因之一。
 *
 * 2. **退避只在连接稳定后重置**
 *    初版无论连接活了多久，`connectAndListen()` 一返回就把 backoff 复位成 3s，
 *    于是「连上 2 秒就断」的场景会以 3 秒间隔无限重连。
 *    现在要求连接成功维持 [STABLE_MS] 以上才重置退避，并加随机抖动。
 *
 * 3. **心跳间隔以服务端为准**
 *    初版把 heartbeat_interval 强压到 15000ms。现在按服务端下发值调度，
 *    仅做「不低于 5s」的下限保护（避免服务端异常给 0），不再无依据缩短。
 *
 * 4. **ACK 容错**
 *    连续 [MAX_MISSED_ACK] 次未收到 ACK 才判假死，而不是一次超时。
 *    同时记录 ACK 延迟，供 Dashboard 展示真实链路质量。
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

    /** 一次连接的完整上下文，代次隔离的载体 */
    private class Gen(
        val id: Long,
        val ws: WsClient
    ) {
        @Volatile var heartbeatThread: Thread? = null
        @Volatile var sessionId: String = ""
        @Volatile var seq: Int = 0
        @Volatile var interval: Long = 41_250L
        @Volatile var lastAckAt: Long = 0L
        @Volatile var lastSendAt: Long = 0L
        @Volatile var missedAck: Int = 0
        /** 本次连接是否已经进入 READY/RESUMED */
        @Volatile var ready: Boolean = false
        @Volatile var connectedAt: Long = 0L
        /** 由外部 stop() 置位，用于区分「主动停止」与「异常断开」 */
        val aborted = AtomicBoolean(false)
    }

    private val session = QqApi.Session(cfg.appId, cfg.appSecret)
    private val genSeq = AtomicLong(0L)

    @Volatile private var current: Gen? = null
    @Volatile private var runThread: Thread? = null
    @Volatile private var running = false

    /** 连接稳定多久后认为「这次连接是成功的」，从而重置退避 */
    private val stableMs = 60_000L

    private val maxBackoffMs = 60_000L
    private val baseBackoffMs = 3_000L

    private val seen = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 400
    }

    // ------------------------------------------------------------ 生命周期

    fun start() {
        if (running) return
        running = true
        publish(BotConnState.CONNECTING)
        runThread = Thread({ loop() }, "bot-${cfg.appId}").also { it.start() }
        Logger.i("Bot", "启动 ${cfg.name}")
    }

    fun stop() {
        running = false
        val g = current
        g?.aborted?.set(true)
        g?.heartbeatThread?.interrupt()
        // 关 socket 会让阻塞中的 readMessage 立刻返回
        runCatching { g?.ws?.close() }
        runCatching { runThread?.interrupt() }
        current = null
        publish(BotConnState.STOPPED)
        Logger.i("Bot", "${cfg.name} 已停止")
    }

    /** 仅供 UI 判断「主循环是否在跑」，不代表在线；在线看 BotStatusRegistry */
    fun isRunning() = running

    /** 是否真的在线（收到过 READY/RESUMED 且未断开） */
    fun isOnline(): Boolean = current?.ready == true

    fun displayName(): String = current?.let { botNameCache } ?: cfg.name

    @Volatile private var botNameCache: String = ""

    // ------------------------------------------------------------ 主循环

    private fun loop() {
        var backoff = baseBackoffMs
        var reconnectCount = 0

        while (running) {
            val startedAt = System.currentTimeMillis()
            var cleanExit = false
            try {
                connectAndListen()
                // connectAndListen 正常返回 = 连接已结束（收到 close / 流结束）
                cleanExit = true
            } catch (e: InterruptedException) {
                if (!running) break
                Logger.e("Bot", "${cfg.name} 连接被中断")
            } catch (e: Exception) {
                if (!running) break
                val kind = classify(e)
                publishError("${kind}: ${e.message ?: e.javaClass.simpleName}")
                Logger.e("Bot", "${cfg.name} 【$kind】${e.message ?: e.javaClass.simpleName}")
            }

            if (!running) break

            val lived = System.currentTimeMillis() - startedAt
            // 只有「真正稳定运行过」才重置退避。
            // 初版无条件重置，导致短连接陷入 3 秒风暴。
            if (cleanExit && lived >= stableMs) {
                backoff = baseBackoffMs
                reconnectCount = 0
            } else {
                reconnectCount++
            }

            // 指数退避 + 抖动，避免多机器人同时重连打在同一时刻
            val jitter = (0..(backoff / 4)).random()
            val wait = min(backoff, maxBackoffMs) + jitter
            publishState(BotConnState.RECONNECTING, reconnectCount = reconnectCount)
            Logger.w(
                "Bot",
                "${cfg.name} ${wait}ms 后重连（第 ${reconnectCount} 次，上次存活 ${lived}ms）"
            )
            sleepQuiet(wait)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }

        current = null
        if (!running) publish(BotConnState.STOPPED)
        Logger.i("Bot", "${cfg.name} 循环结束")
    }

    /** 把底层异常翻译成可诊断的分类，避免全部记成「读取超时或断开」 */
    private fun classify(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        return when {
            e is java.net.UnknownHostException -> "DNS 解析失败"
            e is java.net.ConnectException -> "TCP 建连失败"
            e is java.net.SocketTimeoutException ->
                if (msg.contains("handshake") || msg.contains("握手")) "握手超时" else "读取超时"
            e is javax.net.ssl.SSLException -> "TLS 握手失败"
            msg.contains("握手失败") -> "WebSocket 握手失败"
            msg.contains("未收到 hello") -> "HELLO 超时"
            msg.contains("首个包不是 hello") -> "HELLO 协议异常"
            msg.contains("socket closed") || msg.contains("socket is closed") -> "连接被关闭"
            e is java.io.EOFException -> "连接被对端关闭"
            e is java.io.IOException -> "网络 IO 异常"
            else -> "未知异常"
        }
    }

    /**
     * 统一异常分类入口（任务书第四节）。
     * 上面的 classify 只产出中文标签；这里额外带上 Bot 名、代次、状态、
     * 重连次数与脱敏后的消息，形成一条可定位日志。
     */
    private fun logError(gen: Long, state: String, e: Throwable, reconnect: Int = 0) {
        val kind = com.ajx.botforge.data.ErrorTaxonomy.classify(e)
        val text = com.ajx.botforge.data.ErrorTaxonomy.describe(
            botName = cfg.name,
            generation = gen,
            state = state,
            kind = kind,
            e = e,
            reconnectCount = reconnect
        )
        Logger.e("Bot", text)
    }

    private fun connectAndListen() {
        val gatewayUrl = QqApi.gateway(session)
        val uri = URI(gatewayUrl)
        val host = uri.host ?: "api.sgroup.qq.com"
        val path = uri.path.ifEmpty { "/websocket" }
        val port = if (uri.port > 0) uri.port else 443

        publishState(BotConnState.CONNECTING)
        Logger.i("Bot", "连接网关 $host:$port$path")

        val id = genSeq.incrementAndGet()
        val ws = WsClient(host, port, path) { Logger.i("Bot", "[g$id] $it") }

        val g = Gen(id, ws)
        current = g

        try {
            // 建连 / 握手 / 空闲读三段超时分开，不再一个值通吃
            ws.connect(
                connectTimeoutMs = 15_000,
                handshakeTimeoutMs = 20_000,
                readIdleTimeoutMs = 60_000
            )

            // 1. 读 HELLO
            val hello = when (val r = ws.readMessage(30_000)) {
                is WsClient.ReadResult.Message -> r.text
                WsClient.ReadResult.Idle -> throw IllegalStateException("未收到 HELLO")
                WsClient.ReadResult.Closed -> throw IllegalStateException("未收到 HELLO，连接已断")
            }
            val helloObj = JSONObject(hello)
            val op = helloObj.optInt("op", -1)
            if (op != 10) throw IllegalStateException("首个包不是 HELLO: op=$op")

            // 心跳间隔以服务端下发为准，仅做下限保护（不再强压到 15s）
            val raw = helloObj.optJSONObject("d")?.optLong("heartbeat_interval", 41_250L) ?: 41_250L
            g.interval = raw.coerceIn(5_000L, 300_000L)
            Logger.i("Bot", "心跳间隔 ${g.interval}ms（服务端下发 ${raw}ms）")
            publishState(BotConnState.AUTHENTICATING, heartbeatInterval = g.interval)

            // 2. 鉴权（续连时沿用旧 session）
            identify(g)

            // 3. 心跳线程
            startHeartbeat(g)

            // 4. 收包循环
            while (running && !g.aborted.get() && isCurrent(g)) {
                when (val r = ws.readMessage(idleTimeoutFor(g))) {
                    is WsClient.ReadResult.Message -> dispatch(g, r.text)
                    WsClient.ReadResult.Idle -> {
                        // 帧边界空闲：连接仍然健康，继续等。
                        // 初版把超时一律当断开，是重连风暴的另一个来源。
                        if (!ws.isAlive()) throw IllegalStateException("连接已关闭")
                    }
                    WsClient.ReadResult.Closed -> {
                        Logger.w("Bot", "${cfg.name} 连接关闭（代次 $id）")
                        return
                    }
                }
            }
        } finally {
            g.heartbeatThread?.interrupt()
            runCatching { ws.close() }
            if (isCurrent(g)) {
                current = null
                publishState(BotConnState.RECONNECTING)
            }
        }
    }

    /** 空闲读超时 = 心跳间隔 * 2，至少 30s。超过这个时间没有任何帧就认为链路可疑 */
    private fun idleTimeoutFor(g: Gen): Int =
        (g.interval * 2).coerceIn(30_000L, 180_000L).toInt()

    private fun isCurrent(g: Gen): Boolean = current?.id == g.id

    private fun identify(g: Gen) {
        QqApi.token(session)
        if (g.sessionId.isNotEmpty() && g.seq > 0) {
            val p = JSONObject().apply {
                put("op", 6)
                put("d", JSONObject().apply {
                    put("token", "QQBot " + session.token)
                    put("session_id", g.sessionId)
                    put("seq", g.seq)
                })
            }
            g.ws.send(p.toString())
            Logger.i("Bot", "发送续连包 seq=${g.seq}")
        } else {
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
            g.ws.send(p.toString())
            Logger.i("Bot", "发送鉴权包 intents=${cfg.intents}")
        }
    }

    private fun startHeartbeat(g: Gen) {
        g.heartbeatThread = Thread({
            var first = true
            g.lastAckAt = System.currentTimeMillis()
            while (running && !g.aborted.get() && isCurrent(g) && g.ws.isAlive()) {
                if (!first) sleepQuiet(g.interval)
                first = false
                if (!running || g.aborted.get() || !isCurrent(g) || !g.ws.isAlive()) break

                // 连续未收到 ACK 达到阈值才判假死（初版一次超时就断）
                if (g.missedAck >= maxMissedAck) {
                    Logger.w(
                        "Bot",
                        "${cfg.name} 连续 ${g.missedAck} 次心跳无 ACK，" +
                                "距上次 ACK ${System.currentTimeMillis() - g.lastAckAt}ms，主动重连"
                    )
                    runCatching { g.ws.close() }
                    break
                }

                // 超过 2 个周期没 ACK，先记一次 miss，不立刻断
                val sinceAck = System.currentTimeMillis() - g.lastAckAt
                if (sinceAck > g.interval * 2 + 3_000L) {
                    g.missedAck++
                    Logger.w("Bot", "${cfg.name} 心跳 ACK 延迟 ${sinceAck}ms（miss=${g.missedAck}）")
                }

                val ok = runCatching {
                    val p = JSONObject().apply {
                        put("op", 1)
                        if (g.seq > 0) put("d", g.seq)
                    }
                    g.lastSendAt = System.currentTimeMillis()
                    g.ws.send(p.toString())
                }.isSuccess
                if (!ok) break
            }
        }, "heartbeat-${cfg.appId}-${g.id}").also { it.isDaemon = true; it.start() }
    }

    private val maxMissedAck = 2

    private fun dispatch(g: Gen, text: String) {
        // 代次隔离：旧连接的迟到帧不得影响新连接的状态
        if (!isCurrent(g)) return

        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        val op = obj.optInt("op", -1)
        if (obj.has("s")) g.seq = obj.optInt("s", g.seq)

        when (op) {
            0 -> {
                val t = obj.optString("t", "")
                when (t) {
                    "READY" -> {
                        val d = obj.optJSONObject("d")
                        g.sessionId = d?.optString("session_id") ?: ""
                        val user = d?.optJSONObject("user")
                        botNameCache = user?.optString("username") ?: cfg.name
                        g.ready = true
                        g.connectedAt = System.currentTimeMillis()
                        Logger.i("Bot", "★ 已上线: $botNameCache (session=${g.sessionId.take(8)})")
                        publishState(BotConnState.CONNECTED, connectedAt = g.connectedAt)
                        reportStatus("$botNameCache 已上线")
                    }
                    "RESUMED" -> {
                        g.ready = true
                        g.connectedAt = System.currentTimeMillis()
                        Logger.i("Bot", "会话已恢复")
                        publishState(BotConnState.CONNECTED, connectedAt = g.connectedAt)
                    }
                    "GROUP_AT_MESSAGE_CREATE",
                    "GROUP_MESSAGE_CREATE" -> onGroupMessage(obj)
                    "C2C_MESSAGE_CREATE" -> onC2cMessage(obj)
                    "GROUP_ADD_ROBOT" -> onGroupAdd(obj, "机器人被拉入群")
                    "GROUP_DEL_ROBOT" -> onGroupAdd(obj, "机器人被移出群")
                    "FRIEND_ADD" -> onFriendAdd(obj)
                    "FRIEND_DEL" -> Logger.i(
                        "Bot",
                        "好友删除：${obj.optJSONObject("d")?.optString("openid") ?: "?"}"
                    )
                    else -> if (t.isNotEmpty()) Logger.i("Bot", "事件 $t（未处理）")
                }
            }
            11 -> {
                val now = System.currentTimeMillis()
                val latency = if (g.lastSendAt > 0) now - g.lastSendAt else -1L
                g.lastAckAt = now
                g.missedAck = 0
                publishState(BotConnState.CONNECTED, lastAckAt = now, ackLatency = latency)
            }
            7 -> {
                Logger.w("Bot", "${cfg.name} 收到服务端重连指令")
                g.ready = false
                runCatching { g.ws.close() }
            }
            9 -> {
                Logger.w("Bot", "${cfg.name} 会话失效，清空 session 重新鉴权")
                g.sessionId = ""
                g.seq = 0
                g.ready = false
                QqApi.token(session, force = true)
                runCatching { g.ws.close() }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ 状态上报

    private fun publish(state: BotConnState) {
        val cur = BotStatusRegistry.get(cfg.id)
        BotStatusRegistry.publish(
            (cur ?: BotConnectionState(cfg.id, cfg.name)).copy(
                botId = cfg.id,
                botName = cfg.name,
                state = state,
                generation = current?.id ?: (cur?.generation ?: 0L)
            )
        )
    }

    private fun publishState(
        state: BotConnState,
        connectedAt: Long = 0L,
        heartbeatInterval: Long = 0L,
        lastAckAt: Long = 0L,
        ackLatency: Long = -1L,
        reconnectCount: Int = -1
    ) {
        val cur = BotStatusRegistry.get(cfg.id)
            ?: BotConnectionState(cfg.id, cfg.name)
        BotStatusRegistry.publish(
            cur.copy(
                botId = cfg.id,
                botName = cfg.name,
                state = state,
                generation = current?.id ?: cur.generation,
                connectedAt = if (connectedAt > 0) connectedAt else cur.connectedAt,
                heartbeatInterval = if (heartbeatInterval > 0) heartbeatInterval else cur.heartbeatInterval,
                lastAckAt = if (lastAckAt > 0) lastAckAt else cur.lastAckAt,
                lastHeartbeatAt = current?.lastSendAt ?: cur.lastHeartbeatAt,
                ackLatency = if (ackLatency >= 0) ackLatency else cur.ackLatency,
                reconnectCount = if (reconnectCount >= 0) reconnectCount else cur.reconnectCount,
                lastDisconnectAt = if (state == BotConnState.RECONNECTING)
                    System.currentTimeMillis() else cur.lastDisconnectAt,
                lastError = if (state == BotConnState.CONNECTED) null else cur.lastError
            )
        )
    }

    private fun publishError(msg: String) {
        val cur = BotStatusRegistry.get(cfg.id)
            ?: BotConnectionState(cfg.id, cfg.name)
        BotStatusRegistry.publish(
            cur.copy(
                botId = cfg.id,
                botName = cfg.name,
                state = BotConnState.ERROR,
                generation = current?.id ?: cur.generation,
                lastError = msg,
                lastDisconnectAt = System.currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------ 消息处理

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

    private fun onGroupMessage(obj: JSONObject) {
        val d = obj.optJSONObject("d") ?: return
        val groupOpenId = d.optString("group_openid")
        val msgId = d.optString("id")
        val content = d.optString("content").trim()
        val author = d.optJSONObject("author")?.optString("member_openid") ?: ""
        val images = parseImages(d)

        if (!accept(groupOpenId, msgId, content, images.isNotEmpty())) return

        val ev = Event("group", groupOpenId, author, msgId, content, d, images)
        Logger.i(
            "Bot",
            "→ 群消息 ${content.take(40)}${if (images.isNotEmpty()) " [图${images.size}]" else ""}"
        )
        runCatching { handler(ev) }.onFailure {
            Logger.e("Bot", "处理异常: ${it.message}")
        }
    }

    private fun onGroupAdd(obj: JSONObject, action: String) {
        val d = obj.optJSONObject("d") ?: return
        val gid = d.optString("group_openid").ifEmpty { d.optString("op_member_openid") }
        Logger.i("Bot", "★ $action：group_openid = $gid")
        if (gid.isNotEmpty()) reportStatus("$action，群 openid: $gid")
    }

    private fun onFriendAdd(obj: JSONObject) {
        val d = obj.optJSONObject("d") ?: return
        val oid = d.optString("openid").ifEmpty { d.optString("user_openid") }
        Logger.i("Bot", "★ 有新好友添加机器人：openid = $oid")
        if (oid.isNotEmpty()) reportStatus("新好友：$oid")
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
        Logger.i(
            "Bot",
            "→ 私聊消息 ${content.take(40)}${if (images.isNotEmpty()) " [图${images.size}]" else ""}"
        )
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
            Logger.i("Bot", "未配置的群 $key，忽略")
            return false
        }
        return true
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