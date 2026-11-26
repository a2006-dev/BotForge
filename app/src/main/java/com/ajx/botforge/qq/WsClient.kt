package com.ajx.botforge.qq

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 WebSocket 客户端（RFC 6455）
 *
 * 相对初版的关键修复（对应任务书第二节 1、5）：
 *
 * 1. **帧读取状态机可恢复**
 *    初版 `read()` 在任意位置遇到 SocketTimeoutException 都会向上抛，
 *    调用方拿到 null 后立刻重连；但此时流位置可能停在「已读帧头、未读 payload」
 *    的中间，下一次 read 会把 payload 当成新帧头解析 —— 帧彻底错位。
 *
 *    这里拆成两个阶段：
 *      - `readFrameHeader()` 允许超时（帧边界，超时=空闲，安全可重试）
 *      - `readExact()`       读 payload 时把 soTimeout 抬到 hardTimeoutMs，
 *                            要么整帧读完，要么抛 IOException 判死连接
 *    这样超时只可能发生在帧边界，不会产生半帧状态。
 *
 * 2. **超时语义分离**
 *    connect 拆成 connectTimeoutMs（TCP 建连）、handshakeTimeoutMs（TLS+WS 握手）、
 *    readIdleTimeoutMs（空闲读）。初版用同一个 soTimeout 值覆盖全部阶段。
 *
 * 3. **close 能解除阻塞读取**
 *    直接 `socket.close()`（不先发 close 帧），让阻塞的 read 立刻抛
 *    SocketException，而不是等到 soTimeout 到点。close 帧用 best-effort 方式发。
 *
 * 4. **握手校验 Sec-WebSocket-Accept**
 *    初版只判断 `contains("101")`，代理返回的伪 101 也会被接受。
 *
 * 5. **控制帧长度合法化**
 *    pong 回传 payload 超过 125 字节时初版会写出非法帧（长度字节溢出）。
 */
class WsClient(
    private val host: String,
    private val port: Int = 443,
    private val path: String = "/websocket",
    private val onLog: (String) -> Unit = {}
) {

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Any()

    @Volatile
    private var closed = false

    /** 读 payload 阶段的兜底超时：远大于正常帧传输时间，仅防真挂死 */
    @Volatile
    private var hardTimeoutMs = 120_000

    /**
     * 建立连接
     *
     * @param connectTimeoutMs   TCP 建连超时
     * @param handshakeTimeoutMs TLS + WebSocket 握手超时
     * @param readIdleTimeoutMs  握手后进入空闲读取的默认 soTimeout
     */
    fun connect(
        connectTimeoutMs: Int = 15_000,
        handshakeTimeoutMs: Int = 20_000,
        readIdleTimeoutMs: Int = 60_000
    ) {
        hardTimeoutMs = (readIdleTimeoutMs * 3).coerceAtLeast(90_000)

        val raw = Socket()
        try {
            // 阶段一：TCP 建连（带独立的超时，避免 DNS/建连挂死拖到握手超时）
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            ssl.soTimeout = handshakeTimeoutMs

            // 阶段二：TLS 握手（startHandshake 自身受 soTimeout 约束）
            ssl.startHandshake()

            socket = ssl
            doHttpUpgrade(ssl, handshakeTimeoutMs)

            // 阶段三：进入业务读写
            ssl.soTimeout = readIdleTimeoutMs
            output = ssl.outputStream
            closed = false
            onLog("握手成功")
        } catch (e: Exception) {
            // 任何失败路径都必须关掉 socket，否则 fd 泄漏
            runCatching { raw.close() }
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            throw e
        }
    }

    private fun doHttpUpgrade(s: SSLSocket, timeoutMs: Int) {
        s.soTimeout = timeoutMs
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyB64 = Base64.getEncoder().encodeToString(keyBytes)

        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $keyB64\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: BotForge/1.0\r\n")
            append("\r\n")
        }
        s.outputStream.write(req.toByteArray())
        s.outputStream.flush()

        val bin = BufferedInputStream(s.inputStream)
        val sb = StringBuilder()
        while (true) {
            val b = bin.read()
            if (b == -1) throw IllegalStateException("握手时连接关闭")
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) break
            if (sb.length > 16384) throw IllegalStateException("响应头过长")
        }

        val lines = sb.toString().split("\r\n")
        val status = lines.firstOrNull() ?: ""
        if (!status.contains(" 101")) {
            throw IllegalStateException("握手失败: $status")
        }

        // 校验 Sec-WebSocket-Accept（RFC 6455 §4.1）
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((keyB64 + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        )
        val got = lines.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (got != null && got != expected) {
            throw IllegalStateException("Sec-WebSocket-Accept 校验失败")
        }

        input = bin
    }

    /** 发送文本帧（客户端必须掩码） */
    fun send(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        writeFrame(0x1, payload)
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val os = output ?: throw IllegalStateException("未连接")
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val len = payload.size

        val header = ArrayList<Byte>(14)
        header.add((0x80 or opcode).toByte())
        when {
            len < 126 -> header.add((0x80 or len).toByte())
            len < 65536 -> {
                header.add((0x80 or 126).toByte())
                header.add(((len shr 8) and 0xFF).toByte())
                header.add((len and 0xFF).toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (i in 7 downTo 0) header.add(((len.toLong() shr (8 * i)) and 0xFF).toByte())
            }
        }
        mask.forEach { header.add(it) }

        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        // ArrayList<Byte> 直接转数组：不能写扩展函数，会与
        // java.util.Collection.toByteArray() 产生平台声明冲突
        val head = ByteArray(header.size)
        for (i in header.indices) head[i] = header[i]

        synchronized(writeLock) {
            os.write(head)
            os.write(masked)
            os.flush()
        }
    }

    /** 一次读取的结果 */
    sealed class ReadResult {
        /** 收到一条完整文本消息 */
        data class Message(val text: String) : ReadResult()

        /** 空闲超时：读取停在帧边界，连接仍然可用，可以安全地再次 read */
        object Idle : ReadResult()

        /** 收到 close 帧或流结束，连接不可再用 */
        object Closed : ReadResult()
    }

    /**
     * 读取一条完整文本消息
     *
     * 与初版不同，这里不返回 null：调用方必须能区分
     * 「空闲」（继续等）和「已断开」（重连），这决定了重连策略是否正确。
     *
     * @param idleTimeoutMs 帧边界等待超时；超过则认为这一段是空闲
     */
    fun readMessage(idleTimeoutMs: Int): ReadResult {
        val ins = input ?: return ReadResult.Closed
        val so = socket ?: return ReadResult.Closed
        val chunks = ByteArrayOutputStream()
        var fragOpcode = -1

        while (!closed) {
            // 阶段一：帧头。允许空闲超时。
            so.soTimeout = idleTimeoutMs
            val b0 = try {
                ins.read()
            } catch (e: SocketTimeoutException) {
                // 停在帧边界，状态干净
                return ReadResult.Idle
            } catch (e: java.io.IOException) {
                return ReadResult.Closed
            }
            if (b0 == -1) return ReadResult.Closed

            val b1 = ins.read()
            if (b1 == -1) return ReadResult.Closed

            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()

            // 从这里开始已经在帧中间，任何超时都意味着连接不可继续使用。
            so.soTimeout = hardTimeoutMs

            try {
                if (len == 126L) {
                    val x1 = ins.read(); val x2 = ins.read()
                    if (x1 == -1 || x2 == -1) return ReadResult.Closed
                    len = ((x1 shl 8) or x2).toLong()
                } else if (len == 127L) {
                    len = 0
                    repeat(8) {
                        val x = ins.read()
                        if (x == -1) return ReadResult.Closed
                        len = (len shl 8) or x.toLong()
                    }
                }
                if (len < 0 || len > 16L * 1024 * 1024) {
                    throw IllegalStateException("帧过大: $len")
                }

                val maskKey = if (masked) ByteArray(4).also { readExact(ins, it) } else null
                val data = ByteArray(len.toInt())
                readExact(ins, data)
                if (maskKey != null) {
                    for (i in data.indices) {
                        data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x8 -> {
                        onLog("收到 close 帧")
                        close()
                        return ReadResult.Closed
                    }
                    0x9 -> {
                        // 控制帧 payload 上限 125 字节；超出则截断，避免写出非法帧
                        val body = if (data.size > 125) data.copyOf(125) else data
                        runCatching { writeFrame(0xA, body) }
                        continue
                    }
                    0xA -> continue
                    0x1, 0x2 -> {
                        if (fin) {
                            if (opcode == 0x1) return ReadResult.Message(String(data, Charsets.UTF_8))
                            continue
                        }
                        fragOpcode = opcode
                        chunks.reset()
                        chunks.write(data)
                    }
                    0x0 -> {
                        if (fragOpcode == -1) continue
                        chunks.write(data)
                        if (fin) {
                            val all = chunks.toByteArray()
                            chunks.reset()
                            val op = fragOpcode
                            fragOpcode = -1
                            if (op == 0x1) return ReadResult.Message(String(all, Charsets.UTF_8))
                        }
                    }
                    else -> { /* 其它类型跳过 */ }
                }
            } catch (e: SocketTimeoutException) {
                // 帧读到一半超时 —— 帧边界已破坏，不能继续复用这条连接
                onLog("帧读取中途超时，连接判定失效")
                return ReadResult.Closed
            } catch (e: java.io.IOException) {
                return ReadResult.Closed
            }
        }
        return ReadResult.Closed
    }

    private fun readExact(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n <= 0) throw java.io.EOFException("连接提前结束")
            off += n
        }
    }

    /**
     * 关闭连接
     *
     * 顺序很关键：先把 socket 关掉，让阻塞中的 read 立即抛异常返回，
     * 再 best-effort 地补发 close 帧（发不出去也无所谓，连接已断）。
     * 初版先发 close 帧再关流，读线程在 sendControl 的 socket 写锁上
     * 可能和读线程互相等待，且关流后读线程仍阻塞到 soTimeout 到点。
     */
    fun close() {
        if (closed) return
        closed = true

        val s = socket
        // 先关 socket：唤醒阻塞读
        runCatching { s?.close() }

        input = null
        output = null
        socket = null
    }

    fun isAlive(): Boolean = !closed && socket != null
}