package com.ajx.botforge.term

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端会话。
 *
 * ## 为什么不用 PTY
 *
 * 早期实现走 `android.system.Os.openpty()`，这是 AOSP 的 hidden API。
 * Android 9 起对非 SDK 接口有限制，后续版本进一步收紧，实测在
 * Android 17 上反射调用被拦截，终端直接报「会话启动失败」。
 *
 * 手写 JNI 调 posix_openpt 需要额外编译原生库，且对 SELinux 策略敏感，
 * 成本与风险都不划算。因此改用管道：Process 的标准输入输出本身就是
 * 管道，能支持逐行命令、交互式提示符与非全屏程序。
 *
 * ## 明确的取舍
 *
 * 管道不是终端设备，isatty() 返回 false。因此 top、vim、less 这类
 * 依赖终端尺寸与光标控制的程序无法正常工作；Ctrl+C 也无法通过
 * 前台进程组投递 SIGINT，只能直接结束子进程。这两点在界面说明与
 * 文档中均如实标注，不做隐瞒。
 */
class TerminalSession(
    private val context: Context,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit
) {

    private var proc: Process? = null
    private var stdin: OutputStream? = null
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
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            // stderr 合并进 stdout：终端只有一条输出流，分开会让错误信息
            // 与正常输出交错乱序。
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["PROOT_TMP_DIR"] =
                TerminalEnvironment.tmpDir(context).absolutePath
            pb.environment()["TERM"] = "dumb"

            val p = pb.start()
            proc = p
            stdin = p.outputStream
            startReader(p.inputStream)
            true
        } catch (e: Throwable) {
            // 具体原因必须落到 logcat：界面只有一句通用提示，
            // 无法据此判断是 proot 无法执行、被 SELinux 拒绝，还是路径缺失。
            android.util.Log.e("BotForge/Term", "start() 失败", e)
            onOutput("\r\n启动失败: ${e.message ?: e.javaClass.simpleName}\r\n")
            close()
            false
        }
    }

    /** 读线程：阻塞读取，EOF 结束。 */
    private fun startReader(stream: InputStream) {
        reader = Thread {
            val buf = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val len = stream.read(buf)
                    if (len <= 0) break
                    // 跨块被截断的多字节序列会被替换成占位符，
                    // 在终端里表现为偶发乱码字符，不影响命令执行。
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
        exitCode = try {
            val p = proc
            if (p != null && p.waitFor(1, TimeUnit.SECONDS)) p.exitValue() else -1
        } catch (e: Throwable) {
            -1
        }
        runCatching { stdin?.close() }
        onExit(exitCode)
    }

    /** 向终端写入文本（键盘输入）。 */
    fun write(text: String) {
        if (closed.get()) return
        val out = stdin ?: return
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Throwable) {
            // 写入失败通常意味着会话已结束，无需额外处理。
        }
    }

    /** 发送控制字符，如 Ctrl+C 用 '\u0003'。 */
    fun sendControl(ch: Char) = write(ch.toString())

    /** 结束会话。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        runCatching { reader?.interrupt() }
        onExit(exitCode)
    }
}