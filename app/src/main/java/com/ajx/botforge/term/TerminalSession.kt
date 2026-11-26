package com.ajx.botforge.term

import android.content.Context
import android.system.Os
import android.system.StructPollfd
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 交互式终端会话。
 *
 * ## 为什么必须用 PTY
 *
 * `ProcessBuilder` 给出的是普通管道，不是终端设备。管道有几处根本性缺陷：
 * 一、程序会把自己判定为「非交互」，`top`、`less` 直接拒绝运行或退化成一
 * 次性输出；二、行缓冲模式下 Ctrl+C 不产生任何效果，因为没有前台进程组
 * 可以接收 SIGINT；三、输出被行缓冲，交互式提示符（如 `login:`）永远不会
 * 被刷新出来。所以这里用 `Os.openpty()` 拿一对主从描述符，把从端交给子
 * 进程作为标准流，主端留在应用侧读写。
 *
 * ## 关于窗口尺寸
 *
 * 设置终端尺寸需要 `TIOCSWINSZ` ioctl 与 `struct winsize`。Android 的
 * `android.system.Os` 没有暴露 `ioctlWinsize`，也没有 `StructWinsize`，
 * 而手写 JNI 或反射调用 `libcore` 内部符号都不稳妥。
 *
 * 因此本实现**不设置窗口尺寸**，内核使用默认的 80x24。对逐行文本渲染
 * 的终端视图来说这不构成问题：界面宽度与列数无关，输出按换行折行。
 * 需要精确尺寸的全屏程序（`vim`、`top`）本就不被支持，这一点在文档与
 * 界面说明里都写明，不做隐瞒。
 *
 * ## 退出判定
 *
 * 子进程退出后所有从端副本关闭，主端 `read` 返回 0（EOF），读线程据此
 * 结束并回调退出码。
 */
class TerminalSession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) {

    /** 主端文件描述符。Os.poll / Os.read / Os.write / Os.close 都要求 FileDescriptor 对象。 */
    private var master: FileDescriptor? = null
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
     * @return 是否启动成功
     */
    fun start(command: String?): Boolean {
        if (!TerminalEnvironment.isReady(context)) return false
        return try {
            // Os.openpty() 在 SDK 的 android.jar 里是 @hide，编译期不可见，
            // 因此走反射。它在运行时始终存在（API 21+），方法签名
            // 为 public static FileDescriptor[] openpty()。
            val pty = invokeOpenpty()
            val masterFdObj = pty[0]
            val slave = pty[1]
            master = masterFdObj
            val slaveFd = slave.fd

            // 拿到从端的真实设备路径（/dev/pts/N）。
            // 不能用 /proc/self/fd/N 直接当重定向目标：读完链接后拿到的是
            // 设备路径，语义明确，也不依赖 proc 链接的可访问性。
            val slavePath = Os.readlink("/proc/self/fd/$slaveFd")

            val argv = TerminalEnvironment.buildCommand(context, command)
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectInput(ProcessBuilder.Redirect.from(File(slavePath)))
            pb.redirectOutput(ProcessBuilder.Redirect.to(File(slavePath)))
            pb.redirectError(ProcessBuilder.Redirect.to(File(slavePath)))
            pb.environment().clear()
            pb.environment()["PROOT_TMP_DIR"] =
                TerminalEnvironment.tmpDir(context).absolutePath
            pb.environment()["TERM"] = "xterm-256color"

            proc = pb.start()
            // 子进程已打开从端副本。应用侧这份必须关掉，否则主端永远等不到
            // EOF——只要还有进程持有从端，主端 read 就不会返回 0。
            runCatching { Os.close(slave) }
            startReader()
            true
        } catch (e: Throwable) {
            onOutput("\r\n启动失败: ${e.message ?: e.javaClass.simpleName}\r\n")
            close()
            false
        }
    }

    /**
     * 反射调用 `android.system.Os.openpty()`。
     *
     * 该方法在 AOSP 中带 `@UnsupportedAppUsage` 且未进入 SDK 存根，
     * 直接引用编译不通过；运行时可正常访问。
     *
     * @return 长度为 2 的数组，[0] 为主端，[1] 为从端
     */
    private fun invokeOpenpty(): Array<FileDescriptor> {
        val m = Class.forName("android.system.Os")
            .getMethod("openpty")
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null) as Array<FileDescriptor>
    }

    /** 读线程：poll 等可读，read 取数据，EOF 结束。 */
    private fun startReader() {
        val fd: FileDescriptor = master ?: return
        reader = Thread {
            val buf = ByteArray(8192)
            val pfd = StructPollfd().apply {
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
                // 按 UTF-8 解码。跨块被截断的多字节序列会被替换成占位符，
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
        if (!closed.get()) {
            exitCode = try {
                val p = proc
                if (p != null && p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.exitValue()
                } else {
                    -1
                }
            } catch (e: Throwable) {
                -1
            }
        }
        if (!closed.compareAndSet(false, true) && exitCode == -1) {
            return
        }
        val fd = master
        master = null
        if (fd != null) runCatching { Os.close(fd) }
        onExit(exitCode)
    }

    /** 向终端写入文本（键盘输入）。 */
    fun write(text: String) {
        val fd = master ?: return
        if (closed.get()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            var off = 0
            while (off < bytes.size) {
                off += Os.write(fd, bytes, off, bytes.size - off)
            }
        } catch (e: Throwable) {
            // 写入失败通常意味着会话已结束，无需额外处理。
        }
    }

    /** 发送控制字符，如 Ctrl+C 用 '\u0003'。 */
    fun sendControl(ch: Char) = write(ch.toString())

    /** 结束会话。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { proc?.destroy() }
        val fd = master
        master = null
        if (fd != null) runCatching { Os.close(fd) }
        runCatching { reader?.interrupt() }
        onExit(exitCode)
    }
}