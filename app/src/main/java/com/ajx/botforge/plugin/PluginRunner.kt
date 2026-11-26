package com.ajx.botforge.plugin

import com.ajx.botforge.data.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件脚本执行器
 *
 * 只依赖 Android 自带的 sh（toybox），不需要 rootfs / proot。
 * 这样插件层可以独立于 Term 层工作，proot 留给「需要 python 的插件」作可选依赖。
 */
object PluginRunner {

    /** QQ 单条消息保守上限 */
    private const val MAX_OUTPUT = 1800

    data class Result(val ok: Boolean, val output: String)

    /**
     * 执行插件指令。
     *
     * @param args 指令后面的参数，已按空格切分（会作为 $1、$2… 传给脚本）
     */
    fun run(cmd: PluginCommand, args: List<String>): Result {
        val manifest = PluginRegistry.manifestOf(cmd) ?: return Result(false, "插件未加载")
        val script = File(manifest.dir, cmd.entry)

        if (!script.exists()) return Result(false, "入口脚本不存在：${cmd.entry}")
        if (!script.isFile) return Result(false, "入口不是文件：${cmd.entry}")

        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(listOf("/system/bin/sh", script.absolutePath) + args)
            pb.directory(manifest.dir)
            pb.redirectErrorStream(true)
            // 工作目录限定在插件自己的目录，减少误伤范围
            pb.environment()["BF_PLUGIN_DIR"] = manifest.dir.absolutePath
            pb.environment()["BF_PLUGIN_ID"] = manifest.id
            pb.environment()["BF_ARGS"] = args.joinToString(" ")

            proc = pb.start()
            proc.outputStream.close()   // 插件不需要 stdin

            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            // 超过上限只保留前段，避免 OOM 与刷屏
                            if (sb.length < MAX_OUTPUT * 4) {
                                synchronized(sb) { sb.appendRange(buf, 0, n) }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = proc.waitFor(cmd.timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                return Result(false, "执行超时（${cmd.timeout}s），已终止")
            }

            reader.join(1000)
            val exit = proc.exitValue()
            val raw = synchronized(sb) { sb.toString() }.trim()

            val body = when {
                raw.isEmpty() -> "（无输出）"
                raw.length > MAX_OUTPUT -> raw.take(MAX_OUTPUT) + "\n…（已截断）"
                else -> raw
            }
            if (exit == 0) Result(true, body) else Result(false, "退出码 $exit\n$body")
        } catch (e: Exception) {
            Logger.w("Plugin", "执行失败 ${cmd.command}: ${e.message}")
            Result(false, "执行出错：${e.message}")
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
        }
    }
}