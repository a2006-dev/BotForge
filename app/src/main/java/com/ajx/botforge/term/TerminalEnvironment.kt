package com.ajx.botforge.term

import android.content.Context
import android.system.Os
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内置 Linux 环境。
 *
 * ## 为什么可执行文件要叫 .so
 *
 * Android 10 起应用私有目录被挂载为 noexec，SELinux 也禁止从 data 分区执行
 * 文件。唯一可执行的位置是 APK 的原生库目录（安装时由系统解压到
 * /data/app/.../lib/arm64）。因此 proot 本体以 `libbfproot.so` 打包进 jniLibs，
 * 运行时通过 [prootPath] 直接执行。
 *
 * ## rootfs 从哪来
 *
 * Alpine minirootfs 以 tar.gz 放在 assets/rootfs/，首次启动解包到
 * filesDir/rootfs。构建时已对 `.gz` 关闭压缩，因此运行时拿到的是原始 gzip 流。
 *
 * ## 为什么只绑定必要路径
 *
 * proot 的 -b 决定宿主哪些目录对容器可见。全部挂载会把整个外部存储暴露给
 * 容器内任意命令，这里只绑定 /sdcard、/dev、/proc、/sys 与应用私有目录。
 */
object TerminalEnvironment {

    const val ASSET_ROOTFS = "rootfs/alpine-rootfs.tar.gz"
    private const val MARKER = ".bf_ready"
    private const val ROOTFS_VERSION = "alpine-3.19.0"

    /** 解包状态，界面据此决定展示进度还是终端。 */
    sealed class State {
        data object Idle : State()
        data class Unpacking(val percent: Int, val detail: String) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    fun libDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun prootPath(context: Context): File = File(libDir(context), "libbfproot.so")

    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    fun tmpDir(context: Context): File = File(context.filesDir, "tmp").apply { mkdirs() }

    /** proot 本体与依赖是否随包存在。 */
    fun hasBinaries(context: Context): Boolean {
        val dir = libDir(context)
        return File(dir, "libbfproot.so").exists() && File(dir, "libtalloc.so.2").exists()
    }

    fun isReady(context: Context): Boolean {
        val marker = File(rootfsDir(context), MARKER)
        if (!marker.exists()) return false
        return runCatching { marker.readText().trim() == ROOTFS_VERSION }.getOrDefault(false)
    }

    /**
     * 确保 rootfs 已解包。可重复调用，已就绪时立即返回 true。
     *
     * @param onProgress 百分比与当前条目名，在调用线程触发
     */
    fun ensureReady(context: Context, onProgress: (Int, String) -> Unit = { _, _ -> }): Boolean {
        if (isReady(context)) {
            state = State.Ready
            return true
        }
        val dest = rootfsDir(context)
        return try {
            if (!hasBinaries(context)) {
                state = State.Failed("原生组件缺失")
                return false
            }
            // 上次中断留下的半成品会与新解包混在一起，先清掉
            if (dest.exists() && !File(dest, MARKER).exists()) dest.deleteRecursively()
            dest.mkdirs()

            val totalBytes = runCatching {
                context.assets.open(ASSET_ROOTFS).use { it.available().toLong() }
            }.getOrDefault(0L)
            var unpacked = 0L
            var count = 0

            state = State.Unpacking(0, "")
            onProgress(0, "")

            context.assets.open(ASSET_ROOTFS).use { raw ->
                GZIPInputStream(raw, 64 * 1024).use { gz ->
                    val tar = TarReader(gz)
                    var entry = tar.nextEntry()
                    while (entry != null) {
                        val name = entry.name
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val out = File(dest, name)
                            when {
                                entry.isDirectory -> out.mkdirs()

                                entry.isSymlink -> {
                                    out.parentFile?.mkdirs()
                                    runCatching {
                                        File(out.absolutePath).delete()
                                        Os.symlink(entry.linkName, out.absolutePath)
                                    }
                                }

                                entry.isFile -> {
                                    out.parentFile?.mkdirs()
                                    FileOutputStream(out).use { fos ->
                                        unpacked += tar.copyBody(fos)
                                    }
                                    // tar 的权限位在 Android 上不生效，可执行目录需显式设置
                                    if (name.startsWith("bin/") || name.startsWith("sbin/") ||
                                        name.startsWith("usr/bin/") || name.startsWith("usr/sbin/")
                                    ) {
                                        out.setExecutable(true, false)
                                    }
                                }

                                else -> tar.copyBody(java.io.OutputStream.nullOutputStream())
                            }
                        } else {
                            tar.copyBody(java.io.OutputStream.nullOutputStream())
                        }
                        count++
                        if (count % 200 == 0) {
                            val pct = if (totalBytes > 0) {
                                ((unpacked * 100) / totalBytes).toInt().coerceIn(0, 99)
                            } else 0
                            state = State.Unpacking(pct, name)
                            onProgress(pct, name)
                        }
                        entry = tar.nextEntry()
                    }
                }
            }
            File(dest, MARKER).writeText(ROOTFS_VERSION)
            state = State.Ready
            onProgress(100, "")
            true
        } catch (e: Throwable) {
            state = State.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * 组装 proot 命令行。
     *
     * @param command 要执行的命令；null 表示交互式登录 shell
     */
    fun buildCommand(context: Context, command: String?): List<String> {
        val pkg = context.packageName
        val rootfs = rootfsDir(context)
        val tmp = tmpDir(context)
        val dataDir = context.applicationInfo.dataDir
        val lib = libDir(context)

        val argv = mutableListOf<String>()
        argv += prootPath(context).absolutePath
        argv += listOf("-0", "-r", rootfs.absolutePath, "--link2symlink")

        argv += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/pts")
        argv += listOf("-b", "${tmp.absolutePath}:/dev/shm")
        argv += listOf("-b", "/proc/self/fd:/dev/fd")
        argv += listOf("-b", "/proc/self/fd/0:/dev/stdin")
        argv += listOf("-b", "/proc/self/fd/1:/dev/stdout")
        argv += listOf("-b", "/proc/self/fd/2:/dev/stderr")

        argv += listOf("-b", "/storage/emulated/0:/sdcard")
        argv += listOf("-b", "/storage/emulated/0")
        argv += listOf("-b", "/data/local/tmp")

        argv += listOf("-b", dataDir, "-b", "$dataDir:/data/data/$pkg")
        argv += listOf("-b", context.filesDir.absolutePath)
        argv += listOf("-b", lib.absolutePath)

        // SELinux 下读不到真实的 /proc/stat 等文件，用 rootfs 内的空文件顶上，
        // 否则 top / uptime 一类命令会直接报权限错误退出。
        val fakeProc = File(rootfs, ".proc").apply { mkdirs() }
        listOf("stat", "loadavg", "uptime", "version", "vmstat", "interrupts").forEach { f ->
            val src = File(fakeProc, f)
            if (!src.exists()) runCatching { src.writeText("") }
            argv += listOf("-b", "${src.absolutePath}:/proc/$f")
        }

        argv += listOf("-w", "/root")
        argv += "/usr/bin/env"
        argv += listOf(
            "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "PROOT_NO_SECCOMP=1",
            "PROOT_TMP_DIR=${tmp.absolutePath}",
            "LD_LIBRARY_PATH=${lib.absolutePath}"
        )
        argv += if (command != null) listOf("/bin/sh", "-lc", command) else listOf("/bin/sh", "-l")
        return argv
    }

    /** 非交互执行一条命令，返回合并后的输出。交互式终端走 TerminalSession。 */
    fun exec(context: Context, command: String, timeoutSec: Long = 60): ExecResult {
        if (!isReady(context)) return ExecResult(-1, "", "环境未初始化")
        val argv = buildCommand(context, command)
        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["PROOT_TMP_DIR"] = tmpDir(context).absolutePath
            val proc = pb.start()
            val output = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).forEachLine {
                output.appendLine(it)
            }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                ExecResult(-1, output.toString(), "执行超时")
            } else {
                ExecResult(proc.exitValue(), output.toString(), null)
            }
        } catch (e: Throwable) {
            ExecResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    data class ExecResult(val code: Int, val output: String, val error: String?) {
        val ok: Boolean get() = code == 0
    }
}
