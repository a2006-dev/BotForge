package com.ajx.botforge.term

import android.content.Context
import android.system.Os
import com.ajx.botforge.data.Logger
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

    /** Termux 用户空间的标准前缀。Termux 二进制的 RUNPATH 写死这个路径。 */
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    /** 容器内的 home 目录。 */
    const val TERMUX_HOME = "$TERMUX_PREFIX/home"
    const val ASSET_ROOTFS = "rootfs/termux-rootfs.tar"
    private const val MARKER = ".bf_ready"
    private const val ROOTFS_VERSION = "termux-bootstrap-2026.11.15"
    /** loader 的备份副本。Gradle 会按 ABI 过滤 jniLibs，纯符号文件容易被丢掉，
     *  所以 assets 里再放一份，运行期发现缺失就补回去。 */
    private const val ASSET_LOADER = "bin/libbfloader.so"

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
        // proot 与 loader 是指向 nativeLibraryDir 的软链接，本体已可执行；
        // 这里再 chmod 一次是兜底，避免某些 ROM 上 so 权限被重置。
        runCatching { ensureProot(context).setExecutable(true, false) }
        runCatching { ensureLoader(context).setExecutable(true, false) }
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

    fun prootPath(context: Context): File = ensureProot(context)

    /**
     * proot 本体。
     *
     * 与 loader 一样只能软链到 nativeLibraryDir —— 直接执行那里的
     * libbfproot.so 是合法的，复制到 filesDir 反而会被 SELinux 拒绝。
     */
    fun ensureProot(context: Context): File {
        // 直接把 nativeLibraryDir 里的 libbfproot.so 交给 proot 执行，不复制。
        //
        // 这是真机反复验证后的结论：proot 从私有目录执行会稳定失败在
        //   Cannot run program ".../files/lib/proot": error=13, Permission denied
        // 即使 filesExecProbe 能过（私有目录确实允许 exec 一个小探针），
        // 复制过去的 259KB 实体 proot 仍会被拦。而 /data/app/.../lib/arm64
        // 属于 apk_data_file，是系统专门为可执行代码开放的路径，直接跑没问题。
        //
        // 之前之所以绕道私有目录，是为了补一个名为 libtalloc.so.2 的库
        // （jniLibs 只能打 libtalloc.so，而 proot 的 DT_NEEDED 写的是带版本号的
        // 名字，Android linker 严格按文件名查找）。现在改为通过
        // LD_LIBRARY_PATH 同时挂上私有目录与 nativeLibraryDir，
        // 私有目录里的 libtalloc.so.2 依然能补上，proot 本体却不必搬出去。
        val src = File(context.applicationInfo.nativeLibraryDir, "libbfproot.so")
        runCatching { src.setExecutable(true, false) }
        return src
    }


    /**
     * 把一个位于 nativeLibraryDir 的 so 链接到私有目录，返回**软链接**的路径。
     *
     * ## 为什么不复制
     *
     * 这是踩过最深的坑。早期把 proot / loader 复制到 filesDir 再 setExecutable，
     * 看起来权限是 755，但一执行就 Permission denied：
     *
     * - `filesDir` 属于 SELinux 的 `app_data_file`，**exec 一律被 deny**，
     *   这是 Android 10 起对 data 分区施加的 W^X 策略，chmod 改不动；
     * - `data/app/<pkg>/lib/arm64` 属于 `apk_data_file`，是系统为 JNI
     *   加载专门开放的可执行路径。
     *
     * 所以正确做法与 Operit 一致：二进制**留在** nativeLibraryDir，
     * 只在私有目录建一个指向它的软链接。软链接本身不需要 exec 权限，
     * 解析后落在被允许的路径上即可执行。
     */
    private fun linkIntoBin(context: Context, libName: String, alias: String): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val src = File(nativeDir, libName)
        val link = File(runLibDir(context), alias)
        runCatching { link.parentFile?.mkdirs() }

        // 源文件不存在就无从谈起，直接返回原路径
        if (!src.exists() || src.length() <= 0L) return src
        runCatching { src.setExecutable(true, false) }

        // 清理私有目录里的陈旧副本。
        //
        // 这里踩过一个非常隐蔽的坑：旧实现「已存在且长度>0 就复用」，
        // 于是替换了包内 loader 之后，私有目录里那份 1.6KB 的残次文件
        // 一直被沿用，装多少版都报同一个错。
        // 现在只要不是指向当前源的软链接，就一律删除重建。
        val st = runCatching { android.system.Os.lstat(link.absolutePath) }.getOrNull()
        val isLink = st != null && android.system.OsConstants.S_ISLNK(st.st_mode)
        if (st != null) {
            val cur = if (isLink) {
                runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull()
            } else null
            val stale = cur != src.absolutePath
            if (stale) runCatching { link.delete() }
        }

        // 建一个指向 nativeLibraryDir 的软链接，作为兼容路径。
        if (isLink && runCatching { android.system.Os.readlink(link.absolutePath) }.getOrNull() == src.absolutePath) {
            return link
        }
        runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
        }

        // 直接返回 nativeLibraryDir 里的真实文件。
        //
        // 这是唯一确定可执行的路径：该目录属 apk_data_file，SELinux 为 JNI
        // 加载专门放开了 exec。而 filesDir 属 app_data_file，exec 一律 deny
        // —— 诊断日志里那条
        //   Cannot run program ".../files/lib/proot": error=13, Permission denied
        // 就是它，且 chmod 改不动。所以真正运行的必须是这一份。
        return src
    }
    /**
     * rootfs 落地目录。
     *
     * 必须放在内部存储（filesDir 所在的内部分区）。
     *
     * 曾尝试放到外部存储 getExternalFilesDir()（/sdcard/Android/data/...），
     * 结果解包只完成一小部分就失败：/sdcard 是 FUSE 文件系统，
     * 不支持创建符号链接，而 Alpine 里 335 个 applet 全是指向
     * busybox 的软链接，创建全部失败；退化成复制实体文件时，
     * 目标文件往往还没解出来，一样失败。
     *
     * 内部存储是 f2fs，支持软链接，根文件系统必须放这里。
     * 注意：rootfs 内的程序由 proot + loader 接管执行，与 filesDir
     * 本身是否允许 exec 无关（loader 与 proot 走 nativeLibraryDir）。
     * Config.termUseExternal 已废弃，保留只为兼容旧数据。
     */
    fun rootfsDir(context: Context): File = File(context.filesDir, "rootfs")

    fun tmpDir(context: Context): File = File(context.filesDir, "tmp").apply {
        // proot 要在这里搭 glue rootfs，目录必须存在且可写，否则
        // 会死成 "can't create temporary directory / can't create glue rootfs"。
        if (!exists()) mkdirs()
        if (!canWrite()) {
            runCatching { deleteRecursively() }
            mkdirs()
        }
    }

    /**
     * 用户是否同意使用内置容器。
     *
     * Operit 把终端做成「可插拔能力」：没配置就是没有，而不是反复尝试并失败。
     * 这里同样尊重用户选择——只有明确选了「内置 Alpine」才认为该建环境。
     * 未选择时保持沉默（返回 false），由 UI 引导用户去选，而不是后台偷偷解包。
     */
    fun userWantsContainer(): Boolean =
        com.ajx.botforge.data.Config.termMode == 1

    /** 用户是否已经就终端环境做过选择。 */
    fun userHasChosen(): Boolean = com.ajx.botforge.data.Config.termChosen

    /** proot 本体与依赖是否随包存在。 */
    /**
     * 最近一次失败的原因。
     *
     * 界面上原来只会显示一句固定的「解包失败」，用户和开发者都看不出
     * 到底是软链接没建成、条目数不够、还是缺了某个关键文件。这里把
     * State.Failed 携带的文本直接抛出去。
     */
    fun lastError(): String? = (state as? State.Failed)?.message

    fun hasBinaries(context: Context): Boolean {
        val dir = libDir(context)
        // 包内只会有 libtalloc.so；libtalloc.so.2 由 ensureRunLibs 在首次启动时生成。
        // loader 缺了 proot 只能启动不能执行任何 guest 程序，必须一起检查
        // ensureLoader 返回指向 nativeLibraryDir 的软链接，
        // exists() 会跟随链接，因此这里能真实反映 loader 是否可用。
        return File(dir, "libbfproot.so").exists() &&
            File(dir, "libtalloc.so").exists() &&
            ensureLoader(context).exists()
    }

    fun isReady(context: Context): Boolean {
        // 运行库补齐必须先于就绪判断：rootfs 已解包但私有库里还没有
        // libtalloc.so.2 时，proot 会在 exec 阶段直接链接失败。
        // 放在这里可以让每次启动都自动修复，无需用户重装或清数据。
        ensureRunLibs(context)
        runCatching { fixBootstrapFallback(rootfsDir(context)) }
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
            // 1.1.6 及以前把 rootfs 放在 /sdcard/Android/data/.../rootfs，
            // 那是 FUSE 文件系统，不支持符号链接：Alpine 里 335 个指向
            // busybox 的 applet 链接全部建不出来，解包会停在半路。
            // 现在 rootfs 改回内部存储（f2fs），这里负责把旧的外部残留清掉，
            // 否则那份残缺目录会一直占着空间，也容易让人误以为环境还在。
            runCatching {
                val legacy = context.getExternalFilesDir(null)?.let { File(it, "rootfs") }
                if (legacy != null && legacy.exists() && legacy.absolutePath != dest.absolutePath) {
                    legacy.deleteRecursively()
                }
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
                        // 进度回调不能太稀疏：tar 总共才 500 多条，原来每 200 条
                        // 才回一次，前 200 条里全是软链接，界面上看起来就是
                        // 「进度一直是 0」，用户无法判断是在跑还是卡死。
                        if (count % 20 == 0 || count < 20) {
                            val pct = if (totalBytes > 0) {
                                ((unpacked * 100) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                (count * 100 / 600).coerceIn(0, 99)
                            }
                            state = State.Unpacking(pct, name)
                            onProgress(pct, name)
                        }
                        entry = tar.nextEntry()
                    }
                }
            }
            // 解包收尾必须核对产物：曾出现 tar 提前读完、MARKER 照写、
            // 结果 rootfs 残缺，proot 一 exec 就 Permission denied 的情况。
            // 这里做硬校验，不达标就当失败，下次启动会重新解包。
            // Termux bootstrap 的布局与 Alpine 完全不同，校验标准必须跟着换：
            //   * env 在 /bin/env（软链到 coreutils），不在 usr/bin
            //   * 没有 busybox，基础命令走 coreutils
            //   * 没有 usr/bin 这一层，usr-merge 到 /bin
            // 之前沿用 Alpine 的三项检查，导致解包明明是好的
            // （bin=400、软链接失败=0），却被判成「解包不完整」拦在门外。
            val expectEnv = File(dest, "bin/env")
            val expectSh = File(dest, "bin/sh")
            val expectBash = File(dest, "bin/bash")
            val expectProot = File(dest, "bin/proot")
            val binCount = File(dest, "bin").list()?.size ?: 0
            val envOk = linkOk(expectEnv)
            val shOk = linkOk(expectSh)
            val bashOk = expectBash.length() > 100_000L
            val prootOk = expectProot.length() > 100_000L
            if (!envOk || !shOk || !bashOk || !prootOk || binCount < 50
            ) {
                state = State.Failed(
                    "rootfs 解包不完整（bin=$binCount " +
                        "env=$envOk sh=$shOk bash=$bashOk proot=$prootOk " +
                        "links=$count 软链接失败=$linkFallbacks 复制失败=$linkCopiesFailed）"
                )
                return false
            }
            // ===== 关掉 Termux bootstrap 第二阶段的 fallback 自动执行 =====
            //
            // 真机现象（1.4.5）：每次启动会话都会先跑一大段
            //   Starting fallback run of termux bootstrap second stage
            //   [*] Running 'coreutils' package postinst
            //   ...
            //   [*] The termux bootstrap second stage completed successfully
            // 然后 bash 直接退出，界面回到「会话未启动」，输入框发什么都没反应。
            //
            // 根因在 rootfs 自带的 /etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh：
            //   if [ ! -L ".../termux-bootstrap-second-stage.sh.lock" ]; then
            //       echo "Starting fallback run ..."
            //       "…/termux-bootstrap-second-stage.sh" || exit $?
            //   fi
            //   rm -f ".../01-termux-bootstrap-second-stage-fallback.sh"
            //
            // 这段由 /etc/profile 的 profile.d 循环自动 source。第二阶段
            // 脚本内部结尾是 exec/exit 语义，返回值一旦非 0，`|| exit $?`
            // 就把正在启动的交互 shell 一起结束掉，而且来不及 rm 掉自己，
            // 于是下次启动再跑一遍 —— 表现为「每次都初始化、每次都不给提示符」。
            //
            // 修法：解包后预先建好那个 .lock 符号链接，让 `[ ! -L ]` 恒假，
            // 整段 fallback 被跳过；再把 fallback 脚本本身删掉双保险。
            // 第二阶段该装的东西（coreutils/python/nano 等 postinst）在
            // 之前的手动验证里已经跑完并落地，不需要再执行一次。
            runCatching {
                val stageDir = File(dest, "etc/termux/termux-bootstrap/second-stage")
                val lock = File(stageDir, "termux-bootstrap-second-stage.sh.lock")
                if (!lock.exists()) {
                    stageDir.mkdirs()
                    // 指向不存在的目标也算「-L 为真」，用相对目标更省事
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            lock.toPath(),
                            File(stageDir, "termux-bootstrap-second-stage.sh").toPath()
                        )
                    }.onFailure {
                        // 部分文件系统不支持硬建软链，退化成空文件也能让
                        // `[ ! -L ]` 为真以外的分支走到，虽不理想但先保证能起来
                        runCatching { lock.writeText("") }
                    }
                }
                File(dest, "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh").delete()
            }

            // 软链接降级成实体复制时，目标文件必须可执行，否则 proot 依然起不来
            listOf(expectEnv, expectSh, expectBash, expectProot).forEach { f ->
                if (f.exists() && !f.canExecute()) runCatching { f.setExecutable(true, false) }
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
    /**
     * 判断 rootfs 里的一个条目「存在」，且**不跟随软链接**。
     *
     * 为什么不能直接用 File.exists()：Alpine 的 usr/bin/env、bin/sh 等都是
     * 指向 /bin/busybox 的**绝对路径软链接**。File.exists() 会跟随链接去
     * 找宿主机的 /bin/busybox —— 那当然不存在，于是明明解包 528 个条目
     * 全部成功、软链接失败 0 个，校验仍然报 env=false sh=false。
     *
     * 这里先用 Os.lstat 看条目本身在不在（lstat 不跟随链接），
     * 拿不到时再退回 canRead / exists 兜底。
     */
    fun linkOk(f: File): Boolean {
        // Os.lstat 在路径不存在时会抛 ErrnoException，所以必须 try/catch；
        // 能正常返回就意味着「条目本身存在」（不跟随链接）。
        val viaLstat = runCatching {
            android.system.Os.lstat(f.absolutePath)
            true
        }.getOrDefault(false)
        if (viaLstat) return true
        // 兜底：能列出父目录且名字在列表里，同样说明条目存在。
        return runCatching { f.parentFile?.list()?.contains(f.name) == true }
            .getOrDefault(false) || f.exists()
    }

    private fun hasCoreFiles(root: File): Boolean {
        // Termux 布局：env/sh 都在 /bin，bash 与 proot 是必须存在的实体。
        val env = File(root, "bin/env")
        val sh = File(root, "bin/sh")
        val bash = File(root, "bin/bash")
        val proot = File(root, "bin/proot")
        return linkOk(env) && linkOk(sh) &&
            bash.length() > 100_000L && proot.length() > 100_000L
    }

    /**
     * loader 二进制路径。
     *
     * 这是 proot 能在 Android 上跑起来的必要条件，之前漏了它，
     * 导致 proot 本体能启动但 guest 程序一律 execve 被拒。
     */
    fun loaderPath(context: Context): File = File(libDir(context), "libbfloader.so")

    /**
     * 取 loader 路径，必要时从 assets 补一份。
     *
     * Gradle 打包 jniLibs 时会按 ABI 过滤，loader 只有 1.6KB、无动态依赖，
     * 曾有版本被当成空库直接丢弃，导致 PROOT_LOADER 指向不存在的文件、
     * proot 在容器内 exec 一律 Permission denied。这里做兜底自愈。
     */
    fun ensureLoader(context: Context): File {
        // 与 ensureProot 同理：loader 必须从 nativeLibraryDir 直接使用。
        //
        // loader 是 proot 注入 guest 进程的小 stub（18136 字节）。
        // 一旦把它复制到私有目录，proot 在 execve 阶段解析 PROOT_LOADER
        // 时就会踩到 app_data_file 的 exec 限制，表现为
        //   execve(...): Permission denied / the loader was not found
        // 现在统一走 apk_data_file，与 proot 本体保持一致。
        val src = File(context.applicationInfo.nativeLibraryDir, "libbfloader.so")
        runCatching { src.setExecutable(true, false) }
        return src
    }

    /**
     * 一次性把 proot 所需的环境变量灌进去。
     *
     * 以前这套赋值在三处各写一遍（TerminalSession / LinuxShell / runRaw），
     * 结果漏了一处就出问题——PROOT_LOADER 正是这样丢的。
     * 现在统一从这里取，避免再靠人眼保证三处一致。
     */

    /**
     * 把终端环境的真实状态写到 /sdcard/Download/bf_terminal_diag.txt。
     * release 包日志被剥离、私有目录又被 SELinux 挡住，只能靠落地文件排查。
     */
    fun dumpDiagnostics(context: Context): String {
        val sb = StringBuilder()
        fun line(k: String, v: Any?) { sb.append(k).append(" = ").append(v).append('\n') }
        val lib = libDir(context)
        val runLib = runLibDir(context)
        val root = rootfsDir(context)
        val ld = loaderPath(context)

        line("time", System.currentTimeMillis())
        line("ROOTFS_VERSION", ROOTFS_VERSION)
        line("nativeLibraryDir", lib.absolutePath)
        line("filesDir", context.filesDir.absolutePath)
        line("rootfsDir", root.absolutePath)
        line("rootfsExists", root.exists())
        line("markerExists", File(root, MARKER).exists())
        line("markerContent", runCatching { File(root, MARKER).readText() }.getOrNull())
        // 诊断字段按 Termux 布局输出。之前查的是 Alpine 的
        // usr/bin/env、bin/busybox、ld-musl-*，在 Termux 下必然全是 false，
        // 会把人往「解包坏了」的方向带偏。
        line("binEnvExists", File(root, "bin/env").exists())
        line("binShExists", File(root, "bin/sh").exists())
        line("binBashExists", File(root, "bin/bash").exists())
        line("binProotExists", File(root, "bin/proot").exists())
        line("binBashLength", File(root, "bin/bash").length())
        line("binProotLength", File(root, "bin/proot").length())
        line("libTallocExists", File(root, "lib/libtalloc.so.2").exists())
        line("libAndroidShmemExists", File(root, "lib/libandroid-shmem.so").exists())
        line("prootLoaderExists", File(root, "libexec/proot/loader").exists())
        line("binCount", runCatching { File(root, "bin").list()?.size ?: -1 }.getOrDefault(-2))
        val envF = File(root, "bin/env")
        line("binEnvLength", if (envF.exists()) envF.length() else -1L)

        // 探测 filesDir 下能否 exec。
        //
        // 这决定了一个关键取舍：proot 需要一个名为 libtalloc.so.2 的库
        // （APK 里只能打成 libtalloc.so，名字不匹配 linker 的按名查找），
        // 而私有目录可写、nativeLibraryDir 不可写。若私有目录能 exec，
        // 就把 proot 与补名库都放私有目录；否则必须另想办法。
        line("filesExecProbe", runCatching {
            val probe = File(context.filesDir, "probe_exec.sh")
            probe.writeText("#!/system/bin/sh\necho PROBE_OK\n")
            probe.setExecutable(true, false)
            val pb = ProcessBuilder("/system/bin/sh", probe.absolutePath)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["PATH"] = "/system/bin:/vendor/bin"
            val pr = pb.start()
            val txt = pr.inputStream.bufferedReader().readText().trim()
            pr.waitFor()
            "$txt/${probe.canExecute()}"
        }.getOrElse { "ERR:" + it.javaClass.simpleName })
        // 裸跑 proot --version，看 linker 是否报缺 libtalloc.so.2
        line("prootLinkProbe", runCatching {
            val pb = ProcessBuilder(
                File(context.applicationInfo.nativeLibraryDir, "libbfproot.so").absolutePath,
                "--version"
            )
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["LD_LIBRARY_PATH"] =
                context.applicationInfo.nativeLibraryDir
            val pr = pb.start()
            val txt = pr.inputStream.bufferedReader().readText().trim()
            pr.waitFor()
            txt.lines().take(3).joinToString(" | ")
        }.getOrElse { "ERR:" + it.javaClass.simpleName })
        // 直接打印 ensureProot / ensureLoader 的最终返回值。
        // argv 里出现 nativeLibraryDir 说明 ensureProot 走了兜底分支，
        // 需要单独确认复制到私有目录到底成功没有。
        line("ensureProotPath", runCatching { ensureProot(context).absolutePath }.getOrElse { "ERR:" + it.message })
        line("ensureLoaderPath", runCatching { ensureLoader(context).absolutePath }.getOrElse { "ERR:" + it.message })
        line("runLibDirList", runCatching {
            runLibDir(context).list()?.joinToString(",") ?: "null"
        }.getOrElse { "ERR:" + it.message })
        line("copyProbe", runCatching {
            val t = File(runLibDir(context), "copyprobe.bin")
            File(lib, "libtalloc.so").copyTo(t, overwrite = true)
            "${t.exists()}/${t.length()}"
        }.getOrElse { "ERR:" + it.javaClass.simpleName + ":" + it.message })
        line("prootExists", File(lib, "libbfproot.so").exists())
        line("prootLength", File(lib, "libbfproot.so").length())
        line("tallocExists", File(lib, "libtalloc.so").exists())
        line("runLibTallocExists", File(runLib, "libtalloc.so.2").exists())
        line("loaderExists", ld.exists())
        line("loaderLength", ld.length())
        line("loaderCanExec", ld.canExecute())
        line("hasBinaries", hasBinaries(context))
        line("isReady", runCatching { isReady(context) }.getOrDefault(false))

        val argv = buildCommand(context, "echo BF_OK; id; uname -a")
        line("argv", argv.joinToString(" "))
        val envDump = StringBuilder()
        runCatching {
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            applyProotEnv(pb.environment(), context)
            envDump.append("LD_LIBRARY_PATH=").append(pb.environment()["LD_LIBRARY_PATH"]).append('\n')
            envDump.append("PROOT_TMP_DIR=").append(pb.environment()["PROOT_TMP_DIR"]).append('\n')
            envDump.append("PROOT_LOADER=").append(pb.environment()["PROOT_LOADER"]).append('\n')
            val proc = pb.start()
            proc.outputStream.close()
            val out = StringBuilder()
            val t = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { br ->
                        br.lineSequence().forEach { out.append(it).append('\n') }
                    }
                }
            }
            t.isDaemon = true
            t.start()
            val done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) proc.destroyForcibly()
            t.join(1000)
            line("prootTimedOut", !done)
            line("prootExitCode", if (done) proc.exitValue() else -1)
            line("prootOutput", (synchronized(out) { out.toString() }).trim())
        }.onFailure { e ->
            line("prootException", e.javaClass.name + ": " + e.message)
            line("prootStack", e.stackTrace.take(6).joinToString(" | ") { it.toString() })
        }
        sb.append("=== env ===\n").append(envDump)

        val text = sb.toString()
        runCatching { File(context.filesDir, "term_diag.txt").writeText(text) }
        runCatching {
            val pub = File("/storage/emulated/0/Download/bf_terminal_diag.txt")
            pub.writeText(text)
        }.onFailure { line("publicWriteError", it.message) }
        return text
    }

    fun applyProotEnv(env: MutableMap<String, String?>, context: Context) {
        // proot 的 DT_NEEDED 写的是 libtalloc.so.2，而 jniLibs 只能打
        // lib*.so，Android linker 又严格按文件名查找（不做 soname 映射），
        // 所以必须在私有目录里补一个真名叫 libtalloc.so.2 的文件。
        // ensureRunLibs 负责生成它，**必须在 proot 启动前调用**。
        ensureRunLibs(context)
        ensureProot(context)
        ensureLoader(context)
        env["LD_LIBRARY_PATH"] = listOf(
            runLibDir(context).absolutePath,
            libDir(context).absolutePath
        ).joinToString(":")
        // PROOT_TMP_DIR 必须指向一个**真实存在且可写**的目录。
        //
        // 这是「会话启动失败」的真正原因：proot 要在临时目录里搭 glue
        // rootfs、并做 f2fs 兼容性探针，建不出来就直接死在这一步，
        // 报错却只说 execve(...): Permission denied + "the loader was
        // not found or doesn't work"，极易被误判成 loader 问题。
        // 实测日志：can't create temporary directory / can't create glue rootfs。
        val tmp = tmpDir(context)
        runCatching { tmp.mkdirs() }
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        // 核心：proot 靠这个 loader 接管 guest 程序的 exec。
        // 不设它，proot 会退回 syscall 拦截模式，在 Android 10+ 上
        // 被 SELinux 直接拦成 execve Permission denied。
        env["PROOT_LOADER"] = ensureLoader(context).absolutePath
        // 父进程环境被 clear() 过，PATH 不补的话 proot 自己找可执行文件
        // 也会出问题；容器内的 PATH 由 buildCommand 里的 env -i 单独给。
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/sbin:/bin"
        // 关掉 seccomp 加速。
        //
        // 之前只在 guest 的 env -i 里传了它，那只对容器内的程序生效，
        // proot 本体读的是**自己的进程环境**。在 untrusted_app 域下
        // 开启 seccomp 加速会让 proot 对 guest 的 execve 拦截失效，
        // 表现为 env 明明在、busybox 明明可执行，却仍然
        //   execve("/usr/bin/env"): Permission denied
        env["PROOT_NO_SECCOMP"] = "1"
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["TERM"] = "dumb"
    }

    /**
     * 构造 PTY 子进程要用的环境变量。
     *
     * 和 applyProotEnv 的区别：那个是给宿主机侧的 proot 进程用的（需要
     * LD_LIBRARY_PATH 指向 nativeLibraryDir、PROOT_LOADER 等），这里则是
     * 直接从 proot 启动一个 guest 程序，环境得按容器内视角给。
     *
     * forkpty 的子进程会完全替换掉父进程环境，所以这里必须把 PATH、
     * HOME、PREFIX 这类基础变量都显式列全，少一个就会出现
     * 「python3: command not found」这种看似离奇的现象。
     */
    fun buildPtyEnvironment(context: Context): Map<String, String> {
        val rootfs = rootfsDir(context)
        val lib = libDir(context)
        val runLib = runLibDir(context)
        val tmp = tmpDir(context)
        val env = LinkedHashMap<String, String>()

        // proot 本体在宿主侧运行，靠这两个变量找到 loader 与补名后的库。
        // loader 走 nativeLibraryDir，proot 由 forkpty 直接 execve，
        // 所以 LD_LIBRARY_PATH 必须同时覆盖私有运行库与 nativeLibraryDir。
        env["PROOT_LOADER"] = File(lib, "libbfloader.so").absolutePath
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        // 1.6.0 真机报错：
        //   CANNOT LINK EXECUTABLE ".../lib/arm64/libbfproot.so":
        //   library "libtalloc.so.2" not found: needed by main executable
        // 原因：jniLibs 只能打包 lib*.so，proot 的 DT_NEEDED 写的却是带版本号的
        //   libtalloc.so.2，Android linker 严格按文件名匹配，LD_LIBRARY_PATH
        //   里指到的是私有目录，而私有目录那份补名副本可能没建出来。
        //
        // 这里在**启动前同步确保**补名副本存在；仍失败时退化为 LD_PRELOAD，
        // 因为 LD_PRELOAD 是按完整路径直接加载，不走 soname 匹配。
        val tallocAlias = File(runLib, "libtalloc.so.2")
        val tallocSrc = File(lib, "libtalloc.so")
        if (!tallocAlias.exists() || tallocAlias.length() <= 0L) {
            runCatching { tallocSrc.copyTo(tallocAlias, overwrite = true) }
        }
        val shmemAlias = File(runLib, "libandroid-shmem.so")
        val shmemSrc = File(lib, "libandroid-shmem.so")
        if (!shmemAlias.exists() || shmemAlias.length() <= 0L) {
            runCatching { shmemSrc.copyTo(shmemAlias, overwrite = true) }
        }
        env["LD_LIBRARY_PATH"] = "${runLib.absolutePath}:${lib.absolutePath}"
        // 补名副本没建出来时，用 PRELOAD 兜住 soname 匹配不上的库。
        val preloads = mutableListOf<String>()
        if (!tallocAlias.exists() || tallocAlias.length() <= 0L) {
            if (tallocSrc.exists()) preloads += tallocSrc.absolutePath
        }
        if (preloads.isNotEmpty()) env["LD_PRELOAD"] = preloads.joinToString(" ")"


        // 宿主侧基础环境
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/sbin:/bin"
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        // 交给 proot 执行的程序是 guest 内的 bash，终端类型必须给对，
        // 否则 readline 不会输出彩色提示符，方向键也会变成转义字符。
        env["TERM"] = "xterm-256color"

        // rootfs 位置供命令构造使用（不是给子进程直接读的，但保留便于排查）
        env["BF_ROOTFS"] = rootfs.absolutePath
        return env
    }

    // 关掉 rootfs 自带的 bootstrap 第二阶段 fallback。
    // 那个 profile.d 脚本用 `second-stage.sh || exit $?`，第二阶段返回值
    // 非 0 时会把正在启动的交互 shell 一起结束，而且来不及删掉自己，
    // 于是每次启动都重跑一遍：终端先刷一大段 postinst，最后一行是
    // completed successfully，随后 bash 消失、输入无反应。
    // 修法是预先建好 .lock 符号链接让 [ ! -L ] 恒假，并删除该脚本。
    private fun fixBootstrapFallback(dest: File) {
        val stageDir = File(dest, "etc/termux/termux-bootstrap/second-stage")
        val lock = File(stageDir, "termux-bootstrap-second-stage.sh.lock")
        if (!lock.exists()) {
            stageDir.mkdirs()
            runCatching {
                java.nio.file.Files.createSymbolicLink(
                    lock.toPath(),
                    File(stageDir, "termux-bootstrap-second-stage.sh").toPath()
                )
            }.onFailure { runCatching { lock.writeText("") } }
        }
        File(dest, "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh").delete()
    }

    /**
     * 组装 proot 命令行。
     *
     * @param command 要执行的命令；null 表示交互式登录 shell
     */
    /**
     * 构造「隐藏执行器」的 argv。
     *
     * 与 [buildCommand] 的差别只在最后一段：那边是把一条命令 exec 掉，
     * 这边要启动一个**常驻的 bash -i**，之后所有命令都通过管道写进它，
     * 用标记信封判断每条命令何时结束（见 [HiddenExec]）。
     *
     * 关键点：
     * - `bash -i` 必须先打印 [readyMarker]，上层据此确认 rootfs 已解包、
     *   绑定生效、容器内 bash 真的起来了。首次会把解包日志一起刷出来，
     *   上层在就绪前全部丢弃，不给模型看。
     * - **不能**用 `exec`，exec 会替换掉 proot 进程，管道那头就断了。
     * - 最后 `exec cat` 是刻意的：bash 退出后让进程挂着别再刷东西，
     *   避免容器崩溃时反复打错误日志污染管道。实际由上层 destroy 结束。
     */
    fun buildHiddenExecCommand(context: Context, readyMarker: String): List<String> {
        val base = buildCommand(context, null)
        // buildCommand(null) 返回的是「交互会话」版 argv，末尾是
        // bash -c '<一长串 script>'，里面用 script 包了 PTY。
        // 这里要的是更简单的东西：直接 bash -i，自己打印就绪标记。
        // 所以从 argv 里剥掉末尾的 -c 与其脚本，重新拼一段。
        val cut = base.indexOfLast { it == "-c" }
        val head = if (cut > 0) base.subList(0, cut) else base

        val script = buildString {
            append("export PREFIX=").append(TERMUX_PREFIX).append(';')
            append("export PATH=").append(TERMUX_PREFIX).append("/bin:")
                .append(TERMUX_PREFIX).append("/bin/applets:/system/bin;")
            append("export LD_LIBRARY_PATH=").append(TERMUX_PREFIX).append("/lib;")
            append("export HOME=").append(TERMUX_HOME).append(';')
            append("export TMPDIR=").append(TERMUX_PREFIX).append("/tmp;")
            append("export TERM=dumb;")
            append("export LANG=C.UTF-8;")
            append("export PROOT_NO_SECCOMP=1;")
            append("mkdir -p \"\$HOME\" \"\$TMPDIR\" \"")
                .append(TERMUX_PREFIX).append("/var/tmp\" 2>/dev/null;")
            append("cd \"\$HOME\" 2>/dev/null || cd /;")
            // 就绪标记单独一行，方便上层按行判定。
            append("printf '%s\\n' '").append(readyMarker).append("';")
            // 关掉历史、提示符等噪音，让管道里只有我们关心的内容。
            append("unset HISTFILE;")
            append("PROMPT_COMMAND='';")
            // -i 让 bash 保持交互态（不会因为 stdin 暂时空就退出），
            // TERM=dumb 关闭 readline 的行编辑与转义序列，输出更干净。
            append("exec bash --noprofile --norc -i")
        }

        return head + listOf("$TERMUX_PREFIX/bin/bash", "-c", script)
    }

    fun buildCommand(context: Context, command: String?): List<String> {
        val pkg = context.packageName
        val rootfs = rootfsDir(context)
        val tmp = tmpDir(context)
        val dataDir = context.applicationInfo.dataDir
        val lib = libDir(context)

        val argv = mutableListOf<String>()
        argv += prootPath(context).absolutePath
        // 这里**不能**加 -0（伪装成 root）。
        //
        // Termux 的 bootstrap 第二阶段的 termux-bootstrap-second-stage.sh
        // 会校验 id -u，一旦返回 0 就直接判失败并退出：
        //   [*] The uid '0' returned by 'id -u' command is not valid.
        // 实测现象：终端里能看到 "Starting fallback run of termux bootstrap
        // second stage" 然后停在那行报错，rootfs 永远初始化不完。
        //
        // Termux 假设自己跑在普通用户下（uid 非 0），包管理器与 dpkg
        // 的权限判定都建立在这个前提上。去掉 -0 后容器内 uid 就是本应用的
        // 真实 uid（如 u0_a645），符合 Termux 的预期。
        //
        // 代价：容器内不显示为 root。对一个面向模型执行命令的沙箱来说，
        // 这一点无所谓，反而更安全。
        argv += listOf("-r", rootfs.absolutePath, "--kill-on-exit")

        // ===== 完整 Termux 用户空间的关键绑定 =====
        //
        // 这几条是真机实测出来的，缺一条整套就起不来：
        //
        // 1) /system /apex /vendor 必须绑。
        //    Termux 二进制的 ELF interpreter 是 /system/bin/linker64，
        //    不是 musl loader。rootfs 里没有 system/ 目录，不绑的话
        //    proot 在 execve 阶段一律报 "No such file or directory"，
        //    并提示 "the loader was not found"，极容易误判成 loader 坏了。
        //    实测：绑定前 bash 永远起不来，绑定后立刻 ROOTFS_OK。
        //
        // 2) rootfs 自身要绑到 /data/data/com.termux/files/usr。
        //    Termux 二进制的 RUNPATH 写死这个绝对路径，
        //    bash / coreutils / apt 全部依赖它找 lib。
        //    不绑就会出现 "library libandroid-support.so not found"。
        //
        // 3) 宿主侧 proot 本体靠 LD_LIBRARY_PATH（见 applyProotEnv），
        //    容器内则靠上面的前缀绑定，两条路不能混。
        fun bind(src: String, dst: String? = null) {
            val f = File(src)
            val reachable = runCatching { f.exists() || f.canRead() }.getOrDefault(false)
            if (!reachable) return
            if (dst == null || dst == src) {
                argv += listOf("-b", src)
            } else {
                argv += listOf("-b", "$src:$dst")
            }
        }

        // 系统分区（Termux 二进制解释器与 bionic 依赖都在这）
        bind("/system")
        bind("/apex")
        bind("/vendor")
        // /linkerconfig 里放 ld.config.txt，Android linker 靠它决定
        // 各命名空间去哪里找库。宿主机上这个目录被 SELinux 锁死
        // （app 域与 shell 域都是 Permission denied），绑定必然失败，
        // 于是每个 guest 进程都会刷
        //   WARNING: linker: failed to find generated linker configuration
        //            from "/linkerconfig/ld.config.txt"
        //
        // 这个警告不只是难看，它会**污染命令输出**，直接搞坏 Termux 的
        // bootstrap 第二阶段：
        //   uid="$(id -u 2>&1)"   # 警告被 2>&1 混进变量
        //   [[ ! "$uid" =~ ^[0-9]+$ ]] && 报 "uid ... is not valid" 后退出
        // 终端里看到的就是停在那行、rootfs 永远初始化不完。
        //
        // 解法：在 rootfs 内自备一份合法的 ld.config.txt。
        // proot 把 rootfs 当 /，linker 读到它就不再报警，
        // id -u 输出恢复成干净的数字。实测警告完全消失。
        //
        // 注意：这里**不要**再 bind("/linkerconfig")。
        // 宿主机那个目录对 app 域是 Permission denied，
        // 但 File.exists() 会返回 true，绕过 bind() 的可达性检查，
        // 于是 proot 真去 bind 时被 SELinux 拒绝，启动即为：
        //   proot warning: can't sanitize binding "/linkerconfig": Permission denied
        // 然后会话起不来。rootfs 内自带的那份已经完全够用。
        // home 目录必须预先存在。
        //
        // 下面用 -w 指定了工作目录，如果目录不存在，proot 会先报
        //   proot warning: can't chdir(".../usr/home/."): No such file or directory
        // 然后把 cwd 退回 "/"，界面上一堆噪音，且 HOME 与实际 cwd 不一致，
        // 后续写 .bashrc / .termux 之类会落到根目录。
        File(rootfs, "home").mkdirs()
        // 终端里 ls 等命令会读 TERM，某些程序还会读 LANG，
        // 这两个文件缺失不影响 bash 启动，但能少很多无关报错。
        runCatching { File(rootfs, "etc/motd").takeIf { !it.exists() }?.writeText("") }

        val linkerCfg = File(rootfs, "linkerconfig").apply { mkdirs() }
        val ldConfig = File(linkerCfg, "ld.config.txt")
        if (!ldConfig.exists() || ldConfig.length() == 0L) {
            runCatching { ldConfig.writeText("[section foo]\n") }
        }

        bind("/dev")
        bind("/proc")
        bind("/sys")
        bind(tmp.absolutePath, "/dev/shm")
        bind("/storage/emulated/0", "/sdcard")
        bind("/storage/emulated/0")

        // 用户空间本体挂到 Termux 标准前缀
        bind(rootfs.absolutePath, TERMUX_PREFIX)

        // 注意：这里**不能**再绑定 filesDir 与 nativeLibraryDir。
        // 一旦把私有目录绑进容器，proot 解析 PROOT_LOADER / 可执行路径时
        // 会走 guest 视图，与宿主机的真实位置错位，loader 直接失效。

        argv += listOf("-w", TERMUX_HOME)
        argv += listOf("$TERMUX_PREFIX/bin/bash", "-c")

        val script = buildString {
            append("export PREFIX=").append(TERMUX_PREFIX).append(';')
            append("export PATH=").append(TERMUX_PREFIX).append("/bin;")
            append("export LD_LIBRARY_PATH=").append(TERMUX_PREFIX).append("/lib;")
            append("export HOME=").append(TERMUX_HOME).append(';')
            append("export TMPDIR=").append(TERMUX_PREFIX).append("/tmp;")
            append("export TERM=xterm-256color;")
            append("export LANG=C.UTF-8;")
            append("export PROOT_NO_SECCOMP=1;")
            append("mkdir -p \"\$HOME\" \"\$TMPDIR\" \"")
                .append(TERMUX_PREFIX).append("/var/tmp\" 2>/dev/null;")
            append("cd \"\$HOME\" 2>/dev/null || cd /;")
            if (command != null) {
                // 用 exec 让命令直接接管进程，信号能透传（Ctrl+C 才能杀得掉）
                append("exec bash -lc ").append(shellQuote(command))
            } else {
                // 交互会话：用 script 包一层伪终端。
                //
                // 直接 exec bash -il 不行。ProcessBuilder 给的是管道，不是
                // PTY，bash 启动时 stdin 立刻读到 EOF，打印完提示符就
                // logout 退出。界面上表现为会话「闪一下就没了」，输入框
                // 发什么都没反应 —— 因为进程早就不在了。
                //
                // 上一版用管道喂命令测能出提示符，是因为有数据可读，掩盖了
                // 这个问题。真实的 UI 场景是启动后等用户输入，这段空窗期
                // 就足以让 bash 走完 EOF 退出。
                //
                // Os.openpty() 是 hidden API，Android 17 上反射被拦，所以
                // 借 rootfs 里 util-linux 的 script 来建 PTY：它内部调
                // posix_openpt，对外表现为把子进程接到真实终端上。
                // 实测加上这层之后，bash 的两行警告（cannot set terminal
                // process group / no job control）也不再出现。
                append("exec script -q -c 'exec bash -il' /dev/null")
            }
        }
        argv += script
        return argv
    }

    /** 单引号包裹，内部单引号转义，保证命令原样传给 bash。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"


    /** 非交互执行一条命令，返回合并后的输出。交互式终端走 TerminalSession。 */
    fun exec(context: Context, command: String, timeoutSec: Long = 60): ExecResult {
        if (!isReady(context)) return ExecResult(-1, "", "环境未初始化")
        val argv = buildCommand(context, command)
        return try {
            val pb = ProcessBuilder(argv)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            // 统一走 applyProotEnv：少了 PROOT_LOADER，proot 只能启动本体，
            // 在容器内 exec 任何 guest 程序都会被 SELinux 拦成 Permission denied。
            applyProotEnv(pb.environment(), context)
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
