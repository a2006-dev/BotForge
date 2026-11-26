package com.ajx.botforge.term

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 最小 tar 解析器（ustar + GNU longname + pax path）。
 *
 * ## 为什么自己写
 *
 * 只为解包 rootfs 引入 commons-compress 会多一个依赖。tar 的结构足够简单：
 * 每 512 字节一个头块，文件名在前 100 字节，大小是 12 字节八进制，
 * 类型标志在第 156 字节，链接名在第 157 字节。这里只覆盖 rootfs 里实际出现
 * 的类型：普通文件、目录、符号链接。
 *
 * ## 状态机
 *
 * 当前条目的状态只有两个量：`bodyLeft`（还没读的正文）与 `currentSize`
 * （声明长度）。[nextEntry] 入口先调 [finishEntry] 把上一条彻底消费干净，
 * 因此调用方无需关心 512 对齐。
 */
internal class TarReader(private val input: InputStream) {

    private companion object {
        const val BLOCK = 512
        const val TYPE_FILE = '0'.code.toByte()
        const val TYPE_FILE_OLD = 0.toByte()
        const val TYPE_SYMLINK = '2'.code.toByte()
        const val TYPE_DIR = '5'.code.toByte()
        const val TYPE_LONGNAME = 'L'.code.toByte()
        const val TYPE_PAX = 'x'.code.toByte()
        const val TYPE_PAX_GLOBAL = 'g'.code.toByte()
    }

    class Entry(
        val name: String,
        val size: Long,
        val type: Byte,
        val linkName: String,
        val mode: Int
    ) {
        val isDirectory: Boolean get() = type == TYPE_DIR || name.endsWith("/")
        val isSymlink: Boolean get() = type == TYPE_SYMLINK
        val isFile: Boolean get() = type == TYPE_FILE || type == TYPE_FILE_OLD
    }

    private var bodyLeft = 0L
    private var currentSize = 0L
    private var pendingLongName: String? = null

    fun nextEntry(): Entry? {
        finishEntry()
        while (true) {
            val header = readBlock() ?: return null
            if (header.all { it == 0.toByte() }) return null

            val size = parseOctal(header, 124, 12)
            val type = header[156]
            val rawName = readString(header, 0, 100)
            val mode = parseOctal(header, 100, 8).toInt()
            val linkName = readString(header, 157, 100)

            when (type) {
                TYPE_LONGNAME -> {
                    pendingLongName = readAllBody(size).trimEnd('\u0000')
                    continue
                }
                TYPE_PAX, TYPE_PAX_GLOBAL -> {
                    readAllBody(size).lineSequence()
                        .firstOrNull { it.startsWith("path=") }
                        ?.let { pendingLongName = it.removePrefix("path=").trim() }
                    continue
                }
                else -> {
                    val name = (pendingLongName ?: rawName).removePrefix("./")
                    pendingLongName = null
                    currentSize = size
                    bodyLeft = if (type == TYPE_DIR) 0 else size
                    return Entry(name, size, type, linkName, mode)
                }
            }
        }
    }

    /** 把当前条目正文写入输出。目录与链接无正文，返回 0。 */
    fun copyBody(out: OutputStream): Long {
        var written = 0L
        val buf = ByteArray(32 * 1024)
        while (bodyLeft > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), bodyLeft).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            bodyLeft -= n
            written += n
        }
        return written
    }

    /** 消费当前条目剩余正文与对齐填充。 */
    private fun finishEntry() {
        if (currentSize <= 0 && bodyLeft <= 0) return
        skipFully(bodyLeft)
        bodyLeft = 0
        // 正文物理占用向上取整到 512，剩余部分为填充
        val consumed = currentSize
        val pad = (BLOCK - (consumed % BLOCK)) % BLOCK
        skipFully(pad)
        currentSize = 0
    }

    /** 读取整段正文（用于扩展头）并立即消费填充。 */
    private fun readAllBody(size: Long): String {
        val out = ByteArrayOutputStream()
        var left = size
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        skipFully((BLOCK - (size % BLOCK)) % BLOCK)
        return out.toString("UTF-8")
    }

    private fun readBlock(): ByteArray? {
        val buf = ByteArray(BLOCK)
        var read = 0
        while (read < BLOCK) {
            val n = input.read(buf, read, BLOCK - read)
            if (n < 0) return if (read == 0) null else buf
            read += n
        }
        return buf
    }

    private fun skipFully(n: Long) {
        if (n <= 0) return
        var left = n
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val k = input.read(buf, 0, minOf(BLOCK.toLong(), left).toInt())
            if (k < 0) return
            left -= k
        }
    }

    private fun readString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        // GNU 对大文件使用 base-256 编码，首字节最高位为 1
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) continue
            if (c < '0'.code || c > '7'.code) continue
            v = v * 8 + (c - '0'.code)
        }
        return v
    }
}