package com.ajx.botforge.term

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 交互式终端会话。
 *
 * ## 为什么必须用 PTY
 *
 * `ProcessBuilder` 给出的是普通管道，不是终端设备。管道有几处根本性缺陷：
 * 一、程序会把自己判定为「非交互」，`top`、`vim`、`less` 直接拒绝运行或退化成
 * 一次性输出；二、行缓冲模式下 Ctrl+C 不会产生任何效果，因为没有前台进程组
 * 可以接收 SIGINT；三、窗口尺寸无从告知（`TIOCGWINSZ` 拿不到值），全屏程序
 * 排版错乱。所以这里用 `Os.openpty()` 拿一对主从描述符，把从端交给子进程
 * 作为 stdin / stdout / stderr，主端留在应用侧读写。
 *
 * ## 数据流
 *
 * 主端 fd 是非阻塞的，读线程用 `poll` 等待可读再 `read`，读到多少就往回调丢
 * 多少。写方向直接把字节写进主端，由内核转交给子进程。
 *
 * ## 退出判定
 *
 * 子进程退出后主端会读到 EOF（0 字节），此时结束读线程并回调退出码。
 * 关闭会话时先发 SIGKILL，再关主端，避免阻塞在 `read` 上。
 */
class TerminalSession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) {

    private var masterFd: Int = -1
    private var proc: Process? = null
    private var reader: Thread? = null
    private val closed = AtomicBoolean(false)
    private var exitCode = -1

    /** 会话是否已结束。 */
    val isFinished: Boolean get() = closed.get()

    /**
     * 启动会话。
     *
     * @param command 初始命令；null 表示交互式登录 shell
     * @param cols 列数
     * @param rows 行数
     * @return 是否启动成功
     */
    fun start(command: String?, cols: Int = 80, rows: Int = 24): Boolean {
        if (!TerminalEnvironment.isReady(context)) return false
        return try {
            val pty = Os.openpty()
            val masterPfd = pty[0]
            val slavePfd = pty[1]
            masterFd = masterPfd.fd
            val slaveFd = slavePfd.fd

            // 从端的真实设备路径（/dev/pts/N）。
            //
            // 不能用 /proc/self/fd/N：ProcessBuilder 打开重定向目标时，
            // /proc/self 指向 JVM 自身，而且 SELinux 下访问该链接
            // 可能被拒。读链接拿到 /dev/pts/N 后直接用，语义明确。
            val slavePath = Os.readlink("/proc/self/fd/$slaveFd")
            val slave = File(slavePath)
            val argv = TerminalEnvironment.buildCommand(context, command)
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectInput(ProcessBuilder.Redirect.from(slave))
            pb.redirectOutput(ProcessBuilder.Redirect.to(slave))
            pb.redirectError(ProcessBuilder.Redirect.to(slave))
            pb.environment().clear()
            pb.environment()["PROOT_TMP_DIR"] =
                TerminalEnvironment.tmpDir(context).absolutePath
            pb.environment()["TERM"] = "xterm-256color"

            proc = pb.start()
            // 子进程已打开从端拷贝，应用侧必须关掉自己这份，
            // 否则主端永远等不到 EOF（没有进程持有从端时才会产生 EOF）。
            slavePfd.close()
            setWindowSize(cols, rows)
            startReader()
            true
        } catch (e: Throwable) {
            onOutput("\r\n启动失败: ${e.message ?: e.javaClass.simpleName}\r\n")
            close()
            false
        }
    }

    /** 读线程：poll 等可读，read 取数据，EOF 结束。 */
    private fun startReader() {
        val fd = masterFd
        reader = Thread {
            val buf = ByteArray(8192)
            val pfd = android.system.StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            while (!closed.get()) {
                val n = try {
                    Os.poll(arrayOf(pfd), 300)
                } catch (e: Throwable) {
                    break
                }
                if (n <= 0) continue
                val len = try {
                    Os.read(fd, buf, 0, buf.size)
                } catch (e: Throwable) {
                    break
                }
                if (len <= 0) break
                // 按 UTF-8 解码；跨块被截断的多字节序列会被替换成占位符，
                // 在终端里表现为偶发乱码字符，不影响命令执行。
                onOutput(String(buf, 0, len, Charsets.UTF_8))
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
        val p = proc
        exitCode = try {
            if (p != null && p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                p.exitValue()
            } else {
                -1
            }
        } catch (e: Throwable) {
            -1
        }
        runCatching { if (masterFd >= 0) Os.close(masterFd) }
        masterFd = -1
        onExit(exitCode)
    }

    /** 向终端写入文本（键盘输入）。 */
    fun write(text: String) {
        if (closed.get() || masterFd < 0) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            var off = 0
            while (off < bytes.size) {
                off += Os.write(masterFd, bytes, off, bytes.size - off)
            }
        } catch (e: Throwable) {
            // 写入失败一般意味着会话已结束，无需额外处理。
        }
    }

    /**
     * 通知终端窗口尺寸。
     *
     * 全屏程序依赖这个值做排版，不设置时会按默认 80x24 输出，
     * 在手机上表现为内容溢出或留白异常。
     */
    fun setWindowSize(cols: Int, rows: Int) {
        if (masterFd < 0) return
        runCatching {
            val ws = android.system.StructWinsize()
            ws.ws_row = rows.toShort()
            ws.ws_col = cols.toShort()
            ws.ws_xpixel = 0
            ws.ws_ypixel = 0
            Os.ioctlWinsize(masterFd, ws)
        }
    }

    /** 发送控制字符。 */
    fun sendControl(ch: Char) = write(ch.toString())

    /** 结束会话。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { proc?.destroy() }
        runCatching { if (masterFd >= 0) Os.close(masterFd) }
        masterFd = -1
        runCatching { reader?.interrupt() }
        onExit(exitCode)
    }
}
