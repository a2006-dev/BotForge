package com.ajx.botforge.qq

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 WebSocket 客户端（RFC 6455）
 * 移植自已验证可用的 ws.py，只依赖 JDK
 */
class WsClient(
    private val host: String,
    private val port: Int = 443,
    private val path: String = "/websocket",
    private val onLog: (String) -> Unit = {}
) {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var closed = false

    fun connect(timeoutMs: Int = 20000) {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val s = factory.createSocket(host, port) as SSLSocket
        s.soTimeout = timeoutMs
        s.startHandshake()

        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyB64 = Base64.getEncoder().encodeToString(key)

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
        val headers = StringBuilder()
        while (true) {
            val b = bin.read()
            if (b == -1) throw IllegalStateException("握手时连接关闭")
            headers.append(b.toChar())
            if (headers.length >= 4 && headers.endsWith("\r\n\r\n")) break
            if (headers.length > 16384) throw IllegalStateException("响应头过长")
        }
        val first = headers.lineSequence().firstOrNull() ?: ""
        if (!first.contains("101")) {
            throw IllegalStateException("握手失败: $first")
        }
        onLog("握手成功")

        socket = s
        input = bin
        output = s.outputStream
        closed = false
    }

    /** 发送文本帧（客户端必须掩码） */
    @Synchronized
    fun send(text: String) {
        val os = output ?: throw IllegalStateException("未连接")
        val payload = text.toByteArray(Charsets.UTF_8)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }

        val header = ArrayList<Byte>()
        header.add(0x81.toByte()) // FIN + text
        val len = payload.size
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

        os.write(header.toByteArray())
        os.write(masked)
        os.flush()
    }

    /** 读取一条消息文本；自动回应 ping/pong、跳过二进制 */
    fun read(timeoutMs: Int = 90000): String? {
        val ins = input ?: return null
        (socket as? Socket)?.soTimeout = timeoutMs

        while (!closed) {
            val b0 = ins.read()
            if (b0 == -1) return null
            val b1 = ins.read()
            if (b1 == -1) return null

            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()

            if (len == 126L) {
                len = ((ins.read() shl 8) or ins.read()).toLong()
            } else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or ins.read().toLong() }
            }
            if (len > 8 * 1024 * 1024) throw IllegalStateException("帧过大: $len")

            val maskKey = if (masked) ByteArray(4).also { ins.read(it) } else null
            val data = ByteArray(len.toInt())
            var read = 0
            while (read < data.size) {
                val n = ins.read(data, read, data.size - read)
                if (n <= 0) return null
                read += n
            }
            if (maskKey != null) {
                for (i in data.indices) {
                    data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                0x1 -> return String(data, Charsets.UTF_8)          // text
                0x8 -> { onLog("收到 close 帧"); close(); return null } // close
                0x9 -> sendControl(0xA, data)                       // ping -> pong
                0xA -> { /* pong 忽略 */ }
                0x0 -> return String(data, Charsets.UTF_8)          // 续帧
                else -> { /* 二进制等，跳过 */ }
            }
        }
        return null
    }

    @Synchronized
    private fun sendControl(opcode: Int, data: ByteArray) {
        val os = output ?: return
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = byteArrayOf(
            (0x80 or opcode).toByte(),
            (0x80 or data.size).toByte(),
            mask[0], mask[1], mask[2], mask[3]
        )
        val masked = ByteArray(data.size) { i ->
            (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        runCatching {
            os.write(header)
            os.write(masked)
            os.flush()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { sendControl(0x8, byteArrayOf()) }
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    fun isAlive(): Boolean = !closed && socket != null
}