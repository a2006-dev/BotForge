package com.ajx.botforge.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存日志环 + 可选落盘。界面实时订阅。
 */
object Logger {

    data class Line(val time: String, val level: Int, val text: String)

    private const val MAX = 600
    /** 日志级别：信息。 */
    const val LEVEL_INFO = 0
    /** 日志级别：警告。 */
    const val LEVEL_WARN = 1
    /** 日志级别：错误。 */
    const val LEVEL_ERROR = 2
    private val buf = ArrayDeque<Line>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val dayFmt = SimpleDateFormat("MM-dd", Locale.CHINA)

    @Volatile
    var listener: ((Line) -> Unit)? = null

    fun i(tag: String, msg: String) = add(LEVEL_INFO, tag, msg)
    fun w(tag: String, msg: String) = add(LEVEL_WARN, tag, msg)
    fun e(tag: String, msg: String) = add(LEVEL_ERROR, tag, msg)

    private fun add(level: Int, tag: String, msg: String) {
        val line = Line(fmt.format(Date()), level, "[$tag] $msg")
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX) buf.removeFirst()
        }
        listener?.invoke(line)
        if (Config.logEnabled) {
            try {
                DiskLog.write(line)
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): List<Line> = synchronized(buf) { buf.toList() }

    fun clear() = synchronized(buf) { buf.clear() }

    internal fun stamp(): String = dayFmt.format(Date())
}