package com.ajx.botforge.term

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 隐藏执行器：给模型用、不在界面上露出来的终端。
 *
 * ## 为什么不再用交互式 PTY
 *
 * 1.5.x / 1.6.x 一直在和「会话存活」较劲：ProcessBuilder 给的是匿名管道，
 * `isatty()` 为 false，bash 打印提示符后立刻读到 EOF 就 logout；改用
 * `forkpty` 又要处理 soname 补名、SIGWINCH、作业控制等等，代码量和失败面
 * 都大，而且**对模型执行命令这个场景毫无收益**。
 *
 * 模型要的是「丢一条命令进去，拿回输出和退出码」，不需要光标、不需要
 * 行编辑、不需要 Ctrl+C 之外的交互。所以这里换掉 PTY，改用 Operit 的做法：
 * 起一个**常驻的 bash 进程**（管道即可），用**标记信封**把命令包起来，
 * 靠结束标记判断这条命令跑完没跑完。
 *
 * ## 标记信封协议
 *
 * 一次性启动：
 * ```
 * proot ... /bin/bash --noprofile --norc -i
 * ```
 * （加 -i 让 bash 不因为 stdin 暂时没数据就退出，它只在读到 EOF 时退出，
 *   而我们全程只关进程、不关 stdin。）
 *
 * 每条命令写进去的是：
 * ```sh
 * printf '%s\n' '__BF_BEGIN__:<token>'
 * cat > "$TMPDIR/bf_<token>.sh" <<'__BF_CMD_<token>__'
 * <用户命令>
 * __BF_CMD_<token>__
 * setsid /bin/bash --noprofile --norc "$TMPDIR/bf_<token>.sh" </dev/null &
 * __bf_pid=$!
 * printf '%s:%s\n' '__BF_PID__:<token>' "$__bf_pid"
 * wait "$__bf_pid"
 * __bf_rc=$?
 * rm -f "$TMPDIR/bf_<token>.sh"
 * printf '%s:%s\n' '__BF_END__:<token>' "$__bf_rc"
 * ```
 *
 * 解析端只认 `__BF_END__:<token>:` 之后的数字，即退出码。
 * 用 `setsid` + `</dev/null` 是为了让命令**脱离 bash 的 stdin**：
 * 否则 `cat`、`python3` 这类会读 stdin 的程序会跟外层 bash 抢管道，
 * 把后续信封内容吞掉，表现为「命令跑完了但拿不到结束标记」。
 *
 * 这套做法不需要 PTY、不需要 NDK、不依赖 soname 补名，纯 Java 层可控。
 */
object HiddenExec {

    /** 启动就绪标记。协议其余部分在 [Envelope] 里，那里有单元测试覆盖。 */
    private const val READY_MARKER = Envelope.READY_MARKER

    /** 就绪等待上限。首次启动要解包 rootfs，给足时间。 */
    private const val READY_TIMEOUT_MS = 180_000L

    /** 常驻 shell。按 key 复用，key 变化（比如切了工作目录方案）就重建。 */
    @Volatile
    private var shell: Shell? = null

    private class Shell(
        val key: String,
        val process: Process,
        val writer: java.io.BufferedWriter,
        val reader: Thread,
        val chunks: java.util.concurrent.LinkedBlockingQueue<String>,
        @Volatile var closed: Boolean = false
    )

    data class Result(
        val ok: Boolean,
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
        val error: String? = null
    )

    /** 关掉常驻 shell。App 退出或环境重建时调用。 */
    @Synchronized
    fun close() {
        val s = shell ?: return
        shell = null
        s.closed = true
        runCatching { s.writer.close() }
        runCatching { s.process.destroy() }
        runCatching { s.reader.interrupt() }
    }

    /**
     * 在容器内执行一条命令，等它跑完并返回输出。
     *
     * @param command 用户命令，可以多行、可以带管道重定向
     * @param timeoutSec 超时秒数
     */
    fun run(
        context: Context,
        command: String,
        timeoutSec: Long = 120L,
        workdir: String? = null
    ): Result {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return Result(false, "", -1, error = "命令为空")
        }

        // 工作目录用 cd 前缀表达，容器内的路径由 proot 负责解析。
        val body = Envelope.withWorkdir(trimmed, workdir)

        val s = try {
            ensureShell(context)
        } catch (e: Throwable) {
            return Result(false, "", -1, error = "启动 shell 失败: " + (e.message ?: e.javaClass.simpleName))
        }

        val token = UUID.randomUUID().toString().replace("-", "")
        val envelope = Envelope.build(body, token)

        return try {
            s.writer.write(envelope)
            s.writer.flush()
        } catch (e: Throwable) {
            close()
            return Result(false, "", -1, error = "写入命令失败: " + (e.message ?: e.javaClass.simpleName))
        }.let {
            collect(s, token, timeoutSec)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    @Synchronized
    private fun ensureShell(context: Context): Shell {
        val key = "proot-bash-v1"
        shell?.let { existing ->
            if (existing.key == key && !existing.closed && existing.process.isAlive) {
                return existing
            }
            close()
        }
        val created = createShell(context, key)
        shell = created
        return created
    }

    private fun createShell(context: Context, key: String): Shell {
        // 复用 TerminalEnvironment 的 argv 构造：proot 本体、绑定、
        // ld.config 等等全部沿用，唯一区别是最后执行的程序是 bash -i。
        // rootfs 的解包在 Kotlin 侧（ensureReady），不在 proot 里：
        // 1.7.0 之后终端页不再是必经之路，QQ 工具调用必须自己保证环境就绪。
        // 已就绪时这里只是几次文件检查。
        if (!TerminalEnvironment.ensureReady(context)) {
            throw IllegalStateException(
                "Linux 环境未就绪：" + (TerminalEnvironment.lastError() ?: "rootfs 解包失败")
            )
        }
        val argv = TerminalEnvironment.buildHiddenExecCommand(context, READY_MARKER)
        val env = TerminalEnvironment.buildPtyEnvironment(context)

        val pb = ProcessBuilder(argv)
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        val proc = pb.start()

        val chunks = java.util.concurrent.LinkedBlockingQueue<String>()
        val reader = Thread {
            try {
                val input = proc.inputStream
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) chunks.offer(String(buffer, 0, n, Charsets.UTF_8))
                }
            } catch (_: Throwable) {
                // 进程被杀或流关闭，正常路径
            } finally {
                chunks.offer("\u0000EOF\u0000")
            }
        }
        reader.isDaemon = true
        reader.name = "bf-hidden-exec-reader"
        reader.start()

        val writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        val s = Shell(key, proc, writer, reader, chunks)

        // 等就绪标记。解包 rootfs 时这里会等比较久，但只在第一次。
        val ready = awaitReady(s)
        if (!ready.ok) {
            // 不能调 close()：此时 shell 字段还没赋值，close() 会直接 return，
            // 刚起的 proot 进程和读线程就泄漏了。直接清理这个新建的实例。
            s.closed = true
            runCatching { s.writer.close() }
            runCatching { s.process.destroyForcibly() }
            runCatching { s.reader.interrupt() }
            throw IllegalStateException(ready.error ?: "shell 未能就绪")
        }
        return s
    }

    private fun awaitReady(s: Shell): Result {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val chunk = s.chunks.poll(200, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                if (!s.process.isAlive) {
                    return Result(
                        false, "", -1,
                        error = "shell 在就绪前退出。输出尾部：\n" + tail(sb.toString())
                    )
                }
                continue
            }
            if (chunk.startsWith("\u0000EOF")) {
                return Result(
                    false, "", -1,
                    error = "shell 输出流结束（就绪前退出）。输出尾部：\n" + tail(sb.toString())
                )
            }
            sb.append(chunk)
            if (sb.indexOf(READY_MARKER) >= 0) {
                // 就绪前的解包日志对模型没意义，直接丢弃。
                return Result(true, "", 0)
            }
        }
        return Result(
            false, "", -1,
            error = "等待 shell 就绪超时。输出尾部：\n" + tail(sb.toString())
        )
    }

    private fun collect(s: Shell, token: String, timeoutSec: Long): Result {
        val endPrefix = Envelope.END_PREFIX + token + ":"
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        val sb = StringBuilder()
        var sawEof = false

        while (System.currentTimeMillis() < deadline) {
            val chunk = s.chunks.poll(200, TimeUnit.MILLISECONDS)
            if (chunk == null) {
                if (!s.process.isAlive) break
                continue
            }
            if (chunk.startsWith("\u0000EOF")) {
                sawEof = true
                break
            }
            // 收集时就限长，避免超长输出把内存吃掉。
            if (sb.length < Envelope.MAX_OUTPUT_CHARS * 2) sb.append(chunk)
            if (sb.indexOf(endPrefix) >= 0) break
        }

        val parsed = Envelope.parse(sb.toString(), token)
        if (!parsed.finished) {
            val alive = s.process.isAlive
            if (!alive || sawEof) close()
            return Result(
                ok = false,
                output = parsed.output,
                exitCode = -1,
                timedOut = alive,
                error = if (alive) "命令超时（${timeoutSec}s），已放弃等待；shell 仍在运行"
                else "命令执行中 shell 意外退出"
            )
        }
        val code = parsed.exitCode ?: -1
        return Result(code == 0, parsed.output, code)
    }

    private fun tail(s: String): String {
        val t = s.takeLast(1500).trim()
        return if (t.isEmpty()) "（无输出）" else t
    }
}