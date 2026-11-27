package com.ajx.botforge.term

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端会话。
 *
 * ## 用真正的伪终端
 *
 * 早期实现走 ProcessBuilder + 管道。管道不是终端设备，子进程里
 * isatty() 返回 false，bash 一启动就发现 stdin 非 tty 并立刻读到 EOF，
 * 打印完提示符就 logout。界面上表现为会话「闪一下就没了」、
 * 输入框发什么都没反应 —— 因为进程早就死透了。
 *
 * 期间试过在管道里喂命令（能出提示符，但有数据可读掩盖了问题）、
 * 也试过用 rootfs 里的 script 命令包一层（它内部调 posix_openpt，
 * 但自己同样要接一个 stdin，在管道上下文里撑不住），都不算解决。
 *
 * 现在改用 forkpty：子进程挂真终端，父进程持有 master fd 做读写。
 * 见 Pty.kt 与 src/main/cpp/pty.c。这样 isatty() 为 true，bash 正常进入
 * 交互模式，有作业控制，Ctrl+C 也能通过 VINTR 投递 SIGINT。
 */
class TerminalSession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) {

    @Volatile
    private var pty: Pty? = null

    private var reader: Thread? = null
    private val closed = AtomicBoolean(false)
    private var exitCode = -1

    /** 会话是否已结束。 */
    val isFinished: Boolean get() = closed.get()

    /**
     * 启动会话。
     *
     * @param command 初始命令；null 表示交互式登录 shell
     * @return 是否启动成功
     */
    fun start(command: String?): Boolean {
        if (!TerminalEnvironment.isReady(context)) return false
        return try {
            val argv = TerminalEnvironment.buildCommand(context, command)
            val env = TerminalEnvironment.buildPtyEnvironment(context)
            val workDir = context.filesDir

            val p = Pty.start(argv.toTypedArray(), env, workDir)
            pty = p

            // 让终端尺寸和界面大致匹配，避免 readline 认为只有 80x24。
            runCatching { p.setWindowSize(40, 100) }

            startReader(p)
            true
        } catch (e: Throwable) {
            // 具体原因必须落到 logcat：界面只有一句通用提示，无法据此判断
            // 是 forkpty 失败、execve 失败，还是 fd 反射被拦。
            android.util.Log.e("BotForge/Term", "start() 失败", e)
            onOutput("\r\n启动失败: ${e.message ?: e.javaClass.simpleName}\r\n")
            close()
            false
        }
    }

    /** 读线程：阻塞读取 master fd，EOF 结束。 */
    private fun startReader(p: Pty) {
        reader = Thread {
            val buf = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val len = p.stdout.read(buf)
                    if (len <= 0) break
                    onOutput(String(buf, 0, len, Charsets.UTF_8))
                }
            } catch (e: Throwable) {
                // 流关闭属正常结束路径，无需上报。
            }
            finish()
        }.apply {
            name = "bf-term-reader"
            isDaemon = true
            start()
        }
    }

    /** 子进程退出后的收尾。 */
    private fun finish() {
        if (!closed.compareAndSet(false, true)) return
        val p = pty
        exitCode = try {
            p?.waitFor() ?: -1
        } catch (e: Throwable) {
            -1
        }
        onOutput("\r\n[会话已结束，退出码 $exitCode]\r\n")
        onExit(exitCode)
    }

    /** 向终端写入文本（键盘输入）。 */
    fun write(text: String) {
        if (closed.get()) return
        val out = pty?.stdin ?: return
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Throwable) {
            // 写入失败通常意味着会话已结束，无需额外处理。
        }
    }

    /** 发送控制字符，如 Ctrl+C 用 '\u0003'。PTY 会把它翻译成 SIGINT。 */
    fun sendControl(ch: Char) = write(ch.toString())

    /** 结束会话。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { pty?.destroy() }
        runCatching { reader?.interrupt() }
        onExit(exitCode)
    }
}