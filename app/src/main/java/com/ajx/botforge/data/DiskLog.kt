package com.ajx.botforge.data

import com.ajx.botforge.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志落盘：按天切分，最多保留 7 个文件，单文件超 512KB 轮转。
 */
object DiskLog {

    private val dir: File by lazy { File(App.instance.filesDir, "logs").apply { mkdirs() } }
    private val nameFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_DAYS = 7

    @Synchronized
    fun write(line: Logger.Line) {
        // App 未初始化前（例如 Application 构造阶段）直接跳过，避免 lateinit 崩溃
        if (!runCatching { App.instance }.isSuccess) return
        runCatching {
            val f = File(dir, nameFmt.format(Date()) + ".log")
        if (f.exists() && f.length() > MAX_BYTES) {
            val idx = File(dir, nameFmt.format(Date()) + "_1.log")
            if (idx.exists()) idx.delete()
            f.renameTo(idx)
        }
        val tag = when (line.level) {
            2 -> "E"
            1 -> "W"
            else -> "I"
        }
        f.appendText("${timeFmt.format(Date())} $tag ${line.text}\n")
        }
    }

    fun currentFile(): File = File(dir, nameFmt.format(Date()) + ".log")

    fun allContent(): String {
        val sb = StringBuilder()
        list().forEach { f ->
            sb.append("══════ ${f.name} ══════\n")
            sb.append(f.readText())
            sb.append("\n")
        }
        return sb.toString()
    }

    fun list(): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.name }
            ?: emptyList()

    fun prune() {
        val files = list()
        files.drop(KEEP_DAYS).forEach { runCatching { it.delete() } }
    }

    fun clearAll() {
        list().forEach { runCatching { it.delete() } }
    }
}