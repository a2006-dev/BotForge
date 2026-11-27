package com.ajx.botforge.term

import android.content.Context
import com.ajx.botforge.data.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 给模型用的非交互 shell。
 *
 * ## 和 TerminalSession 的分工
 *
 * [TerminalSession] 是「给人看的交互终端」：一条长驻管道，持续吐字，
 * 靠写 stdin 输入下一条命令。
 *
 * 本类相反：每次调用都是独立的一次 exec，命令跑完、进程退出、收全部输出。
 * 大模型调工具就是这个模型——它要的是一问一答，不是流。
 *
 * 每次新起进程还有一个副作用是好事：workingDir / cd 不会跨调用泄漏，
 * 模型看到的永远是干净的初始状态，行为可预测。
 *
 * ## 为什么直接吃 sh -c 而不是自己拆参数
 *
 * 模型给的是整条命令字符串（含管道、重定向、&&），只有 shell 能解释。
 * 拆开 argv 反而会破坏这些语义。
 *
 * ## 环境没就绪时的降级
 *
 * rootfs 没解包完、或运行库缺失时，不能直接把错误甩给模型（它无法自行修复）。
 * 这里退回 Android 自带 sh，让 degraded 为 true，由调用方在输出前面加一句
 * 提示，模型就能知道「现在没有 apt/python3」，而不是以为命令写错了。
 */
object LinuxShell {

    /** 单次执行默认超时。apt install 之类可能很慢，给到 2 分钟。 */
    private const val DEFAULT_TIMEOUT_SEC = 120L

    /** 输出上限。超了截断，避免把模型上下文撑爆。 */
    private const val MAX_OUTPUT = 6000

    data class Result(
        val code: Int,
        val output: String,
        val error: String?,
        /** 是否退化成了 Android 自带 sh（Linux 环境不可用） */
        val degraded: Boolean
    )

    /**
     * 执行一条命令。
     *
     * @param workingDir 容器内的工作目录（如 /root、/sdcard/Download），
     *        空则用 /root。只有 Linux 环境下才生效。
     */
    fun run(
        context: Context,
        command: String,
        workingDir: String? = null,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC
    ): Result {
        if (command.isBlank()) return Result(-1, "", "命令为空", false)

        // 首次调用时 rootfs 可能还没解包（QQ 后台收到消息不会经过终端页 UI）。
        // 这里主动补一次，否则模型永远只能拿到降级的 Android sh。
        var ready = runCatching { TerminalEnvironment.isReady(context) }.getOrDefault(false)
        if (!ready) {
            ready = runCatching {
                Logger.i("LinuxShell", "首次使用，开始解包 rootfs")
                TerminalEnvironment.ensureReady(context)
            }.getOrDefault(false)
        }
        if (!ready) {
            Logger.w("LinuxShell", "Linux 环境未就绪，退化到 Android sh")
            return runAndroidSh(command, timeoutSec)
        }

        val cd = workingDir?.trim()?.takeIf { it.isNotEmpty() && it.startsWith("/") }
        val full = if (cd != null) "cd '$cd' 2>/dev/null || cd /root; $command" else command

        val argv = try {
            TerminalEnvironment.buildCommand(context, full)
        } catch (e: Throwable) {
            Logger.w("LinuxShell", "buildCommand 失败: " + e.message)
            return Result(-1, "", e.message ?: e.javaClass.simpleName, false)
        }

        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            // 与 TerminalSession 同因：proot 自身 DT_NEEDED 里写的是
            // libtalloc.so.2，只有补好 soname 的私有目录里才有这个文件，
            // 库搜索路径必须显式带上，否则 proot 连 exec 都过不去。
            TerminalEnvironment.applyProotEnv(pb.environment(), context)

            val proc = pb.start()
            proc.outputStream.close()

            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            synchronized(sb) {
                                // 只留前 MAX_OUTPUT*2 字符，后面不再追加，
                                // 但继续读完，避免管道写满把子进程卡死。
                                if (sb.length < MAX_OUTPUT * 2) sb.appendRange(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                name = "bf-linuxshell-reader"
                isDaemon = true
                start()
            }

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                val partial = synchronized(sb) { sb.toString() }.trim()
                return Result(
                    -1,
                    clip(partial),
                    "执行超时（" + timeoutSec + "s），进程已终止。若是 apt install 这类耗时命令，" +
                        "重试时把 timeout 调大，或改用 nohup 后台跑。",
                    false
                )
            }
            reader.join(1500)
            val out = synchronized(sb) { sb.toString() }.trim()
            Result(proc.exitValue(), clip(out), null, false)
        } catch (e: Throwable) {
            Logger.w("LinuxShell", "执行异常: " + e.javaClass.simpleName + " " + e.message)
            Result(-1, "", e.message ?: e.javaClass.simpleName, false)
        }
    }

    /**
     * 环境自检。
     *
     * proot 启动失败时对外只有一句 "execve failed"，看不出到底是哪一环断了：
     * 是 proot 本体没解出来、libtalloc.so.2 没补上、rootfs 里的 /usr/bin/env
     * 缺失，还是 SELinux 拦了 exec。这里逐项检查并把结果拼成可读报告，
     * 直接给用户和模型看，不用再靠猜。
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        val root = TerminalEnvironment.rootfsDir(context)
        val lib = TerminalEnvironment.libDir(context)
        val runLib = TerminalEnvironment.runLibDir(context)

        sb.append("=== 原生库目录 ===\n").append(lib.absolutePath).append("\n")
        listOf("libbfproot.so", "libtalloc.so", "libandroid-shmem.so").forEach { n ->
            val f = File(lib, n)
            sb.append(if (f.exists()) "  OK  " else "  MISS ")
                .append(n).append("  ").append(if (f.exists()) "${f.length()}B" else "-")
                .append("\n")
        }

        sb.append("\n=== 运行期库 ===\n").append(runLib.absolutePath).append("\n")
        listOf("libtalloc.so.2", "libandroid-shmem.so").forEach { n ->
            val f = File(runLib, n)
            sb.append(if (f.exists()) "  OK  " else "  MISS ")
                .append(n).append("  ").append(if (f.exists()) "${f.length()}B" else "-")
                .append("\n")
        }

        sb.append("\n=== rootfs ===\n").append(root.absolutePath).append("\n")
        sb.append("  存在: ").append(root.exists()).append("\n")
        sb.append("  MARKER: ").append(
            runCatching { File(root, ".bf_ready").readText().trim() }.getOrNull() ?: "（无）"
        ).append("\n")
        // 容器内的绝对路径，拼到 rootfs 下检查；这是启动链第一环
        listOf("usr/bin/env", "bin/sh", "bin/busybox").forEach { p ->
            val f = File(root, p)
            sb.append(if (f.exists()) "  OK  " else "  MISS ")
                .append("/").append(p)
            if (f.exists()) {
                sb.append("  ").append(f.length()).append("B")
                // 链接解析目标也报一下，方便判断是不是断链
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    sb.append("  -> ").append(
                        runCatching {
                            java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                        }.getOrNull() ?: "?"
                    )
                }
            }
            sb.append("\n")
        }

        // 真跑一次最小命令，把原始错误原文带出来
        sb.append("\n=== 试跑 ===\n")
        val argv = runCatching { TerminalEnvironment.buildCommand(context, "echo BF_OK") }
            .getOrElse {
                sb.append("buildCommand 抛异常: ").append(it.message).append("\n")
                return@diagnose sb.toString()
            }
        val r = runRaw(context, argv)
        sb.append("退出码: ").append(r.code).append("\n")
        sb.append("输出: ").append(r.output.ifBlank { "（空）" }).append("\n")
        r.error?.let { sb.append("错误: ").append(it).append("\n") }
        return sb.toString()
    }

    /** 不带任何降级逻辑地跑一次，专供 diagnose 取原始报错。 */
    private fun runRaw(context: Context, argv: List<String>): Result {
        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            TerminalEnvironment.applyProotEnv(pb.environment(), context)
            val proc = pb.start()
            proc.outputStream.close()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.waitFor(20, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                return Result(-1, out.take(3000), "超时", false)
            }
            Result(proc.exitValue(), out.take(3000), null, false)
        } catch (e: Throwable) {
            Result(-1, "", e.javaClass.simpleName + ": " + e.message, false)
        }
    }

    /**
     * 退化路径：Android 自带 sh。
     *
     * 没有 apt / python3 / git，但 cat / ls / df / getprop 这些还能用，
     * 至少让模型能查点东西，而不是全线报错。
     */
    private fun runAndroidSh(command: String, timeoutSec: Long): Result {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.close()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = br.read(buf)
                            if (n <= 0) break
                            synchronized(sb) {
                                if (sb.length < MAX_OUTPUT * 2) sb.appendRange(buf, 0, n)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                name = "bf-androidsh-reader"
                isDaemon = true
                start()
            }
            val t = minOf(timeoutSec, 30L)
            val finished = proc.waitFor(t, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                return Result(
                    -1,
                    clip(synchronized(sb) { sb.toString() }.trim()),
                    "执行超时（" + t + "s）",
                    true
                )
            }
            reader.join(1000)
            Result(
                proc.exitValue(),
                clip(synchronized(sb) { sb.toString() }.trim()),
                null,
                true
            )
        } catch (e: Throwable) {
            Result(-1, "", "执行出错：" + e.message, true)
        }
    }

    private fun clip(s: String): String =
        if (s.length <= MAX_OUTPUT) s else s.take(MAX_OUTPUT) + "\n…（输出过长，已截断）"

    /** 环境是否可用，供状态查询入口使用。 */
    fun available(context: Context): Boolean =
        runCatching { TerminalEnvironment.isReady(context) }.getOrDefault(false)

    /** rootfs 目录，供工具描述里展示给模型（让它知道自己在哪个容器里）。 */
    fun rootPath(context: Context): File = TerminalEnvironment.rootfsDir(context)
}
