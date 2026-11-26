package com.ajx.botforge.data

/**
 * 异常分类与日志脱敏
 *
 * 任务书第四节：
 *   - 禁止把所有网络异常统一记为「读取超时或断开」
 *   - 至少区分 12 类
 *   - 每条日志要能定位到 Bot 名 / 连接代次 / 状态 / 异常类型 / 消息 / 重连次数
 *   - 密钥必须脱敏
 *
 * 分类原则：宁可细一点，也不要让「读超时」和「TLS 失败」在日志里长得一样，
 * 因为这两种故障的排查方向完全相反。
 */
object ErrorTaxonomy {

    /** 12 类异常标签 */
    enum class Kind(val label: String, val hint: String) {
        DNS("DNS 解析失败", "检查网络 / 代理是否正常"),
        CONNECT("TCP 建连失败", "端口被墙或服务端不可达"),
        TLS("TLS 握手失败", "证书 / 系统时间 / SNI 问题"),
        HANDSHAKE("WebSocket 握手失败", "网关拒绝 upgrade，检查 token 与路径"),
        AUTH("鉴权失败", "AppID / Secret 错误或 token 失效"),
        READ_TIMEOUT("读取超时", "链路慢或服务端无响应"),
        FRAME_TIMEOUT("帧读取中途超时", "帧边界破坏，连接已判定失效"),
        EOF("对端提前关闭", "服务端主动断开，通常伴随 op=7/9"),
        SOCKET_CLOSED("连接已关闭", "本地 close() 或 socket 被系统回收"),
        NETWORK("网络不可达", "无网络 / 飞行模式 / VPN 中断"),
        IO("IO 异常", "底层读写错误，多为网络抖动"),
        PARSE("数据解析失败", "JSON 结构不符预期"),
        HEARTBEAT("心跳超时", "连续未收到 ACK，链路假死"),
        UNKNOWN("未知异常", "需要看堆栈定位")
    }

    /**
     * 把任意异常映射为分类
     *
     * 注意顺序：SocketTimeoutException 是 IOException 的子类，
     * ConnectException 也是，所以必须先判具体的再判父类。
     */
    fun classify(e: Throwable): Kind = when {
        e is java.net.UnknownHostException -> Kind.DNS
        e is java.net.NoRouteToHostException -> Kind.NETWORK
        e is java.net.ConnectException -> Kind.CONNECT
        e is javax.net.ssl.SSLException -> Kind.TLS
        e is java.net.SocketTimeoutException -> Kind.READ_TIMEOUT
        e is java.io.EOFException -> Kind.EOF
        e is java.net.SocketException -> {
            val m = (e.message ?: "").lowercase()
            if (m.contains("closed")) Kind.SOCKET_CLOSED else Kind.IO
        }
        e is IllegalStateException -> {
            val m = e.message ?: ""
            when {
                m.contains("握手") -> Kind.HANDSHAKE
                m.contains("token", ignoreCase = true) -> Kind.AUTH
                m.contains("帧") -> Kind.FRAME_TIMEOUT
                else -> Kind.UNKNOWN
            }
        }
        e is org.json.JSONException -> Kind.PARSE
        e is java.io.IOException -> Kind.IO
        else -> Kind.UNKNOWN
    }

    /**
     * 生成一条可定位的日志正文
     *
     * 格式：[bot名·g代次] 状态=<状态> 类型=<分类> 消息=<msg> 重连=<n> 时间=<ts>
     */
    fun describe(
        botName: String,
        generation: Long,
        state: String,
        kind: Kind,
        e: Throwable?,
        reconnectCount: Int = 0
    ): String {
        val msg = e?.let { brief(it) } ?: "—"
        return "[${botName}·g$generation] 状态=$state 类型=${kind.label} " +
            "消息=$msg 重连=$reconnectCount 提示=${kind.hint}"
    }

    /** 截断异常消息，避免日志被长堆栈塞满 */
    fun brief(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return mask(m).take(160).replace("\n", " ")
    }

    /** 密钥脱敏：任何看起来像 token / secret 的片段都打码 */
    fun mask(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        // QQBot xxxxxxxx / Bearer xxxxx
        out = Regex("(QQBot|Bearer)\\s+[A-Za-z0-9._\\-]{8,}")
            .replace(out) { m -> m.groupValues[1] + " ****" }
        // access_token / clientSecret / appSecret 的 JSON 形态
        out = Regex("(\"(?:access_token|clientSecret|appSecret|token)\"\\s*:\\s*\")[^\"]{6,}")
            .replace(out) { m -> m.groupValues[1] + "****" }
        // 裸的 32 位以上十六进制/字母数字串（AppSecret 就是这种形态）
        out = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{32,}(?![A-Za-z0-9])")
            .replace(out) { m ->
                val v = m.value
                if (v.all { it.isDigit() }) v else v.take(4) + "****"
            }
        return out
    }
}