package com.ajx.botforge.term

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 伪终端。
 *
 * ## 为什么不能用 ProcessBuilder
 *
 * ProcessBuilder 只能通过管道连子进程。管道不是终端设备，子进程里
 * isatty() 返回 false，bash 一启动就发现 stdin 非 tty 并立刻读到 EOF，
 * 打印完提示符就 logout 退出。界面上表现为会话「闪一下就没了」、
 * 输入框发什么都没反应 —— 因为进程早就死透了。
 *
 * 试过用 rootfs 里的 script 命令包一层（它内部也调 posix_openpt），
 * 但 script 自己同样要接一个 stdin，在 App 的管道上下文里撑不住。
 *
 * ## 现在的做法
 *
 * 直接用 forkpty 建一个真终端：子进程挂到 slave 端，父进程持有 master
 * 端的 fd，用 FileInputStream / FileOutputStream 读写这个 fd 即可。
 * 子进程看到的 isatty() 为 true，bash 会正常进入交互模式、有作业控制、
 * Ctrl+C 也能通过 VINTR 投递 SIGINT。
 *
 * 原生层见 app/src/main/cpp/pty.c。
 */
class Pty private constructor(
    val pid: Int,
    val masterFd: Int,
    private val masterFileDescriptor: FileDescriptor
) {
    /** 读 PTY 输出。 */
    val stdout: InputStream = FileInputStream(masterFileDescriptor)

    /** 写 PTY 输入。 */
    val stdin: OutputStream = FileOutputStream(masterFileDescriptor)

    /** 阻塞等待子进程退出，返回退出码。 */
    fun waitFor(): Int = waitForPid(pid)

    /** 调整终端窗口大小，让全屏程序知道该画多大。 */
    fun setWindowSize(rows: Int, cols: Int): Boolean =
        setPtyWindowSize(masterFd, rows, cols) == 0

    /** 结束会话：先发 SIGHUP 让子进程自行收尾，再关 fd 触发 EOF。 */
    fun destroy() {
        runCatching { android.os.Process.sendSignal(pid, 1) }   // SIGHUP
        runCatching { android.os.Process.sendSignal(pid, 9) }   // SIGKILL
        runCatching { stdout.close() }
        runCatching { stdin.close() }
    }

    companion object {
        private const val TAG = "BfPty"

        init {
            System.loadLibrary("bfpty")
        }

        /**
         * 启动一个挂在伪终端上的子进程。
         *
         * @param command 完整命令行，第一个元素是可执行文件绝对路径
         * @param environment 环境变量，会被完整替换掉父进程的环境
         * @param workingDir 子进程的工作目录
         */
        @Throws(IOException::class)
        fun start(
            command: Array<String>,
            environment: Map<String, String>,
            workingDir: File
        ): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()
            val info = createSubprocess(command, envArray, workingDir.absolutePath)
                ?: throw IOException("forkpty 失败，详见 logcat 的 BfPty 标签")

            val pid = info[0]
            val fd = info[1]
            if (pid <= 0 || fd < 0) {
                throw IOException("forkpty 返回异常值: pid=$pid fd=$fd")
            }

            Log.d(TAG, "PTY 已建立: pid=$pid fd=$fd cmd=${command.firstOrNull()}")
            return Pty(pid, fd, reflectFileDescriptor(fd))
        }

        /**
         * 把一个 int fd 包成 FileDescriptor。
         *
         * FileDescriptor 没有公开的构造方式，只能反射写私有字段 descriptor。
         * 这在 Android 上一直是可用的（不同于 Os.openpty 那类被 hidden API
         * 策略拦截的方法，这里只是取用一个已存在的 fd）。
         */
        private fun reflectFileDescriptor(fd: Int): FileDescriptor {
            val descriptor = FileDescriptor()
            try {
                val field = FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.set(descriptor, fd)
            } catch (e: Throwable) {
                throw IOException("无法把 fd $fd 转成 FileDescriptor", e)
            }
            return descriptor
        }

        @JvmStatic
        private external fun createSubprocess(
            cmdArray: Array<String>,
            envArray: Array<String>,
            workingDir: String
        ): IntArray?

        @JvmStatic
        private external fun waitForPid(pid: Int): Int

        @JvmStatic
        private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
    }
}