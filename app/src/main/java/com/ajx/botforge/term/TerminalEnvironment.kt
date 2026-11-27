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
 * filesDir/rootfs。
 *
 * 文件在源码里就叫 `alpine-rootfs.tar`（内容是 gzip 流）：AAPT 对
 * 声明为 noCompress 的 `.gz` 会在打包时剥掉后缀，若源码保留 `.gz`，
 * 运行时按原路径 open 必然失败。让源码名与产物名一致可以免掉这层隐式转换。
 *
 * ## 为什么只绑定必要路径
 *
 * proot 的 -b 决定宿主哪些目录对容器可见。全部挂载会把整个外部存储暴露给
 * 容器内任意命令，这里只绑定 /sdcard、/dev、/proc、/sys 与应用私有目录。
 */
object TerminalEnvironment {

    const val ASSET_ROOTFS = "rootfs/alpine-rootfs.tar"
    private const val MARKER = ".bf_ready"
    private const val ROOTFS_VERSION = "alpine-3.19.0-r3"

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

    /**
     * 运行期库目录。
     *
     * proot 的 DT_NEEDED 里写的是 `libtalloc.so.2`，但 Android 打包只接受
     * `lib*.so` 形式的文件名，`.so.2` 会被 jniLibs 静默丢弃。这里在首次
     * 启动时把 `libtalloc.so` 复制一份为 `libtalloc.so.2` 放到私有目录，
     * 让动态链接器按 soname 找得到。
     */
    fun runLibDir(context: Context): File = File(context.filesDir, "lib").apply { mkdirs() }

    /**
     * 补齐运行库。可重复调用，目标文件已存在时立即返回。
     *
     * @return 所需运行库是否齐备
     */
    fun ensureRunLibs(context: Context): Boolean {
        val src = libDir(context)
        val dst = runLibDir(context)
        // soname -> 包内实际文件名
        val mapping = mapOf(
            "libtalloc.so.2" to "libtalloc.so",
            "libandroid-shmem.so" to "libandroid-shmem.so"
        )
        var ok = true
        mapping.forEach { (soname, real) ->
            val out = File(dst, soname)
            if (out.exists() && out.length() > 0) return@forEach
            val from = File(src, real)
            if (!from.exists()) {
                ok = false
                return@forEach
            }
            runCatching { from.copyTo(out, overwrite = true) }.onFailure { ok = false }
        }
        return ok
    }

    fun prootPath(context: Context): File = File(libDir(context), "libbfproot.so")

    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    fun tmpDir(context: Context): File = File(context.filesDir, "tmp").apply { mkdirs() }

    /** proot 本体与依赖是否随包存在。 */
    fun hasBinaries(context: Context): Boolean {
        val dir = libDir(context)
        // 包内只会有 libtalloc.so；libtalloc.so.2 由 ensureRunLibs 在首次启动时生成。
        // loader 缺了 proot 只能启动不能执行任何 guest 程序，必须一起检查
        return File(dir, "libbfproot.so").exists() &&
            File(dir, "libtalloc.so").exists() &&
            loaderPath(context).exists()
    }

    fun isReady(context: Context): Boolean {
        // 运行库补齐必须先于就绪判断：rootfs 已解包但私有库里还没有
        // libtalloc.so.2 时，proot 会在 exec 阶段直接链接失败。
        // 放在这里可以让每次启动都自动修复，无需用户重装或清数据。
        ensureRunLibs(context)
        val marker = File(rootfsDir(context), MARKER)
        if (!marker.exists()) return false
        val verOk = runCatching { marker.readText().trim() == ROOTFS_VERSION }.getOrDefault(false)
        if (!verOk) return false
        // 光看 MARKER 不够：旧版本把软链接失败静默吞了，环境是坏的但
        // MARKER 照样写进去了，于是「就绪」从此刻永久成立，重装也修不好。
        // 这里每次启动验证两个关键入口文件，缺一个就判定需要重建。
        return hasCoreFiles(rootfsDir(context))
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
            if (!ensureRunLibs(context)) {
                state = State.Failed("运行库准备失败")
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
            // 软链接降级统计，收尾时打进日志，便于确认环境是否完整
            var linkFallbacks = 0
            var linkCopiesFailed = 0

            state = State.Unpacking(0, "")
            onProgress(0, "")

            context.assets.open(ASSET_ROOTFS).use { raw ->
                GZIPInputStream(raw, 64 * 1024).use { gz ->
                    val tar = TarReader(gz)
                    var entry = tar.nextEntry()
                    while (entry != null) {
                        val e = entry ?: break
                        val name = e.name
                        if (name.isNotEmpty() && !name.contains("..")) {
                            val out = File(dest, name)
                            when {
                                e.isDirectory -> out.mkdirs()

                                e.isSymlink -> {
                                    // Android 10+ 的 app 私有目录被 SELinux 限制，
                                    // Os.symlink 经常直接抛异常。早期版本用 runCatching
                                    // 静默吞掉，结果 Alpine 里 335 个指向 busybox 的
                                    // applet 链接全都没建成，proot 执行 /usr/bin/env
                                    // 只会得到一句模糊的 Permission denied。
                                    // 现在失败就退化成「复制实体文件」，语义上等价。
                                    out.parentFile?.mkdirs()
                                    val created = runCatching {
                                        File(out.absolutePath).delete()
                                        Os.symlink(e.linkName, out.absolutePath)
                                        true
                                    }.getOrDefault(false)
                                    if (!created) {
                                        linkFallbacks++
                                        val target = resolveLinkTarget(dest, name, e.linkName)
                                        if (target != null && target.isFile) {
                                            runCatching {
                                                target.copyTo(out, overwrite = true)
                                                out.setExecutable(true, false)
                                            }.onFailure { linkCopiesFailed++ }
                                        } else {
                                            linkCopiesFailed++
                                        }
                                    }
                                }

                                e.isFile -> {
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
     * 把 tar 里的软链接目标解析成 rootfs 内的实际文件。
     *
     * tar 里两种写法都有：
     *  - 绝对路径 /bin/busybox  → 相对 rootfs 根
     *  - 相对路径 ../lib/x.so    → 相对链接所在目录
     * 都要先归一化成 rootfs 下的绝对路径再取 File，否则复制会拿到空。
     *
     * @param dest  rootfs 根目录
     * @param name  链接自身的相对路径（前无前缀，已去 ./）
     * @param linkName  tar 记录的目标路径
     * @return 可用的目标文件，解析不出来返回 null
     */
    private fun resolveLinkTarget(dest: File, name: String, linkName: String?): File? {
        if (linkName.isNullOrBlank()) return null
        val raw = linkName.trim()
        // 绝对的容器内路径，直接拼 rootfs
        val path = if (raw.startsWith("/")) {
            raw
        } else {
            val parent = name.substringBeforeLast('/', "")
            if (parent.isEmpty()) raw else "$parent/$raw"
        }
        // 归一化 .. 与 .，避免 ././ 之类残留
        val parts = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        if (parts.isEmpty()) return null
        return File(dest, parts.joinToString("/"))
    }

    /**
     * 抽查容器内的关键入口是否真实可用。
     *
     * /usr/bin/env 是 proot 启动链的第一环（buildCommand 里直接 exec 它），
     * /bin/sh 是所有命令的兜底解释器。这两个只要缺一个，容器就是废的，
     * 与其让模型拿到「Permission denied」这种看不出来源的错误，
     * 不如在这里直接判定环境不完整并触发重建。
     */
    private fun hasCoreFiles(root: File): Boolean {
        val env = File(root, "usr/bin/env")
        val sh = File(root, "bin/sh")
        return env.exists() && sh.exists()
    }

    /**
     * loader 二进制路径。
     *
     * 这是 proot 能在 Android 上跑起来的必要条件，之前漏了它，
     * 导致 proot 本体能启动但 guest 程序一律 execve 被拒。
     */
    fun loaderPath(context: Context): File = File(libDir(context), "libbfloader.so")

    /**
     * 一次性把 proot 所需的环境变量灌进去。
     *
     * 以前这套赋值在三处各写一遍（TerminalSession / LinuxShell / runRaw），
     * 结果漏了一处就出问题——PROOT_LOADER 正是这样丢的。
     * 现在统一从这里取，避免再靠人眼保证三处一致。
     */
    fun applyProotEnv(env: MutableMap<String, String?>, context: Context) {
        // proot 的 DT_NEEDED 写的是 libtalloc.so.2，jniLibs 只能打 lib*.so，
        // 补好 soname 的副本在 runLibDir，必须排在库搜索路径最前。
        env["LD_LIBRARY_PATH"] = listOf(
            runLibDir(context).absolutePath,
            libDir(context).absolutePath
        ).joinToString(":")
        env["PROOT_TMP_DIR"] = tmpDir(context).absolutePath
        // 核心：proot 靠这个 loader 接管 guest 程序的 exec。
        // 不设它，proot 会退回 syscall 拦截模式，在 Android 10+ 上
        // 被 SELinux 直接拦成 execve Permission denied。
        env["PROOT_LOADER"] = loaderPath(context).absolutePath
        env["TERM"] = "dumb"
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
            "LD_LIBRARY_PATH=${runLibDir(context).absolutePath}:${lib.absolutePath}"
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
            // 与 TerminalSession 同理：proot 自身加载就需要 libtalloc.so.2，
            // 这里的库路径必须包含补好 soname 的私有目录。
            pb.environment()["LD_LIBRARY_PATH"] = listOf(
                runLibDir(context).absolutePath,
                libDir(context).absolutePath
            ).joinToString(":")
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
