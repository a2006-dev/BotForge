package com.ajx.botforge.plugin

import com.ajx.botforge.data.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * `.bfpack` 扩展包导入器。
 *
 * ## 为什么需要它
 *
 * 现在装插件的唯一方式是：用户自己把文件夹拷进
 * `Android/data/com.ajx.botforge/files/plugins/`。
 * 这有三个问题：
 *
 *  1. Android 11+ 起，`Android/data` 在系统文件管理器里默认不可见，
 *     普通用户根本找不到这个路径；
 *  2. 拷进去的是散开的目录，没有「一个文件」可以分享给朋友；
 *  3. 装上之后没有校验——`api` 版本不对、id 冲突、manifest 写错，
 *     要等到重启 App、看日志才发现。
 *
 * `.bfpack` 把这三件事都解决：一个 zip 文件、一键导入、
 * 导入前就能把 manifest 解析出来给用户看。
 *
 * ## 为什么复用 zip 而不自定义格式
 *
 * 任务书允许 `tar` / `tar.gz` / `zip`。选 zip 的理由：
 *  - 它能被系统文件选择器直接识别，用户不需要额外工具；
 *  - 解压用 java.util.zip，零依赖，不用引 apache-commons；
 *  - tar.gz 需要自己实现解压（项目里已有 TarReader，但那是给 rootfs 用的，
 *    面向的是单个大文件，不是多文件目录树）。
 *
 * ## 关于 api 版本
 *
 * 任务书规划的是 v2（一个包可同时声明 commands / ai_tools / ui_actions），
 * 但当前 `PluginManifest.CURRENT_API` 仍是 **1**，v2 尚未实现。
 * 因此：
 *  - 本导入器只接受 api <= CURRENT_API 的包；
 *  - 「没有 commands 的包」目前会被 parse() 直接判非法
 *    （`if (cmds.isEmpty()) return null`），所以还装不了纯 ai_tools 包。
 *    v2 落地时需要同时改 parse() 与这里的校验。
 *
 * ## 安全边界
 *
 * zip 解压是经典的路径穿越攻击面（`../../` 写出去）。
 * 这里对每个 entry 名做归一化并校验前缀，见 [isSafeEntryName]。
 * 另外限制单文件与总解压体积，避免 zip bomb 把手机塞满。
 */
object BfpackImporter {

    /**
     * 支持的最高 api 版本。
     *
     * 直接取 [PluginManifest.CURRENT_API]，不要在这里另写一个数字：
     * 两处硬编码一定会漂移——解析器升到 2 而导入器还卡在 1，
     * 结果是「包能装进去但解析不出来」这种最难查的故障。
     */
    private val MAX_API: Int get() = PluginManifest.CURRENT_API

    /** 单个包解压后的体积上限。正常插件是几 KB 到几百 KB 脚本，256MB 足够宽松。 */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** 单个文件上限 */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 导入预览：解析完 manifest、还没落盘时给用户看的内容。
     */
    data class Preview(
        /** 包 id，来自 manifest */
        val id: String,
        /** 显示名 */
        val name: String,
        /** 版本号 */
        val version: String,
        /** 声明的协议版本 */
        val api: Int,
        /** 作者 */
        val author: String,
        /** 描述 */
        val description: String,
        /** 提供的指令名列表 */
        val commands: List<String>,
        /** 解压后会占多少字节 */
        val totalBytes: Long,
        /** 文件个数 */
        val fileCount: Int,
        /** 若为 null 表示可以安装；非 null 是拒绝原因 */
        val blockReason: String? = null,
        /** 警告：不影响安装，但用户该知道 */
        val warnings: List<String> = emptyList()
    )

    /**
     * 第一步：解析，不落盘。
     *
     * 调用方拿 [Preview] 给用户确认，确认后再调 [commit]。
     * 分成两步是因为「解压到临时目录 → 展示 → 用户确认 → 落盘」这条流程里，
     * 中间那步必须能看到真实解析结果，不能靠猜。
     */
    fun preview(pack: File, targetRoot: File): Preview {
        val staging = File(targetRoot, ".staging_${System.currentTimeMillis()}")
        try {
            val stats = unzip(pack, staging)
            val mf = File(staging, "plugin.json")
            if (!mf.isFile) {
                return failed("包里没有 plugin.json")
            }
            val json = runCatching { JSONObject(mf.readText()) }
                .getOrElse { return failed("plugin.json 不是合法 JSON：${it.message}") }

            // parse() 用 **null** 表示失败，不是抛异常，所以不能用 getOrElse
            // 兜底——那样只能接住真正抛出的异常，null 会直接漏到下一行，
            // 然后在 manifest.api 处编译失败（可空接收者）。
            val parsed = runCatching { PluginManifest.parse(json, staging) }
                .getOrElse { return failed("解析 plugin.json 时出错：${it.message}") }
            val manifest = parsed
                ?: return failed("manifest 字段不完整：缺少 id 或 commands，或 commands 全为空")

            val warnings = mutableListOf<String>()

            // api 版本检查
            if (manifest.api > MAX_API) {
                return failed(
                    "这个包要求协议 v${manifest.api}，当前 App 只支持到 v$MAX_API。" +
                        "请更新 BotForge 后再装。"
                )
            }
            // 注意：这里不把「低于当前版本」当警告。
            // 当前 CURRENT_API 是 1，也就是说只有 v1 一种合法值，
            // 写「该包使用旧协议」会让用户以为自己的包过时了，是误导。
            // 等真的推出 v2 并需要区分时再加这条警告。

            // id 冲突检查
            val existing = File(targetRoot, manifest.id)
            if (existing.exists()) {
                warnings += "已存在同 id 的包（${manifest.id}），继续安装会覆盖它。"
            }

            // 入口脚本缺失检查：指令声明了 entry 但文件不在，装上也会运行失败
            manifest.commands.forEach { c ->
                val entry = File(staging, c.entry)
                if (!entry.isFile) {
                    warnings += "指令 /${c.command} 声明的入口 ${c.entry} 不在包里，运行时会失败。"
                }
            }

            // 这里不再检查 commands 是否为空：
            // PluginManifest.parse() 里有 `if (cmds.isEmpty()) return null`，
            // 空指令的包在上一行就已经走 failed 分支了。
            // 保留一段注释是为了避免后来者以为漏了检查而重复添加。

            return Preview(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                api = manifest.api,
                author = manifest.author,
                description = manifest.description,
                commands = manifest.commands.map { it.command },
                totalBytes = stats.first,
                fileCount = stats.second,
                blockReason = null,
                warnings = warnings
            )
        } finally {
            // 预览阶段的临时目录一定要清掉：用户可能只是点开看看不装，
            // 留着就是一堆垃圾。真正的落盘在 commit 里重做一次。
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 第二步：真正落盘。
     *
     * 重新解压一次而不是复用 preview 的临时目录——那目录已经被删了，
     * 而且这样能保证「用户确认的内容」和「实际装上的内容」是同一份文件。
     *
     * @return null 表示成功，否则是失败原因
     */
    fun commit(pack: File, targetRoot: File, expectedId: String): String? {
        val staging = File(targetRoot, ".staging_${System.currentTimeMillis()}")
        try {
            unzip(pack, staging)
            val mf = File(staging, "plugin.json")
            if (!mf.isFile) return "包里没有 plugin.json"

            val json = runCatching { JSONObject(mf.readText()) }
                .getOrElse { return "plugin.json 不是合法 JSON：${it.message}" }
            val parsed = runCatching { PluginManifest.parse(json, staging) }
                .getOrElse { return "解析 plugin.json 时出错：${it.message}" }
            val manifest = parsed
                ?: return "manifest 字段不完整：缺少 id 或 commands，或 commands 全为空"

            // 防替换：用户在确认页看到的是 A，装的必须是 A
            if (manifest.id != expectedId) {
                return "包内容已变化（期望 $expectedId，实际 ${manifest.id}），请重新选择文件。"
            }

            // 必须校验 id 才能拿它拼路径。
            // parse() 只保证 id 非空，不校验字符——`../evil`、`a/b`、`x y`
            // 都能过 parse（它只做了 trim + isEmpty 检查）。
            // 不校验就直接 File(targetRoot, manifest.id)，等于把上面
            // 好不容易挡住 disable 的 zip 路径穿越又从 manifest 开了一个口子。
            // 这里的规则与 uninstall() 完全一致，两处不能各写一套。
            if (!isSafePackageId(manifest.id)) {
                return "包 id 非法（${manifest.id}）：不能包含路径分隔符或 .."
            }

            val dest = File(targetRoot, manifest.id)
            if (dest.exists()) {
                // 覆盖安装：先备份再删，失败能回滚
                val backup = File(targetRoot, ".backup_${manifest.id}")
                runCatching { backup.deleteRecursively() }
                if (!dest.renameTo(backup)) {
                    return "无法替换已安装的包（可能是文件被占用），请重启 App 后重试。"
                }
                if (!staging.renameTo(dest)) {
                    // 回滚
                    backup.renameTo(dest)
                    return "写入失败，已回滚到原版本。"
                }
                backup.deleteRecursively()
            } else {
                if (!staging.renameTo(dest)) {
                    return "写入失败，请检查存储空间。"
                }
            }
            Logger.i("Bfpack", "已安装 ${manifest.id} v${manifest.version}")
            return null
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 卸载：删除包目录。
     *
     * 不在这里清 PackagePrefs —— 那是 ai/tool 包体系的存储，
     * 两套体系独立，跨层删除会制造隐式耦合。调用方需要时自己清。
     */
    fun uninstall(targetRoot: File, packageId: String): String? {
        if (!isSafePackageId(packageId)) {
            return "非法的包 id：$packageId"
        }
        val d = File(targetRoot, packageId)
        if (!d.exists()) return "没有找到这个包（$packageId）"
        if (!d.isDirectory) return "$packageId 不是一个目录"
        return if (d.deleteRecursively()) null else "删除失败，请检查权限"
    }

    // ------------------------------------------------------------ 内部

    private fun failed(reason: String) = Preview(
        id = "", name = "", version = "", api = 0, author = "", description = "",
        commands = emptyList(), totalBytes = 0, fileCount = 0,
        blockReason = reason
    )

    /** @return (总字节数, 文件数) */
    private fun unzip(zipFile: File, destDir: File): Pair<Long, Int> {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        var total = 0L
        var count = 0

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!isSafeEntryName(name)) {
                    throw SecurityException("包内包含非法路径：$name")
                }
                // 跳过 __MACOSX / .DS_Store 这类系统垃圾
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) {
                    entry = zis.nextEntry
                    continue
                }

                val out = File(destCanonical, name)
                // 二次校验：canonicalFile 解析完软链接和 .. 后必须仍在目标目录内
                if (!out.canonicalPath.startsWith(destCanonical.path + File.separator) &&
                    out.canonicalPath != destCanonical.path
                ) {
                    throw SecurityException("包内路径越界：$name")
                }

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(8192)
                        var written = 0L
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            written += n
                            total += n
                            if (written > MAX_FILE_BYTES) {
                                throw IllegalStateException("包内单个文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB：$name")
                            }
                            if (total > MAX_TOTAL_BYTES) {
                                throw IllegalStateException("解压后总体积超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB")
                            }
                            fos.write(buf, 0, n)
                        }
                    }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return total to count
    }

    /**
     * 包 id 是否可安全地拼进路径。
     *
     * `commit()` 与 `uninstall()` 都要拿 id 去 `File(root, id)`，
     * 两处必须用同一套规则——之前只在 uninstall 里校验，commit 漏了，
     * 等于「装的时候能穿出去、卸的时候反而拦住」，是把校验放错了边。
     *
     * 规则比 zip 条目更严：还禁了反斜杠与空串。
     * id 本来就该是 `com.example.hello` 这种反向域名，不该出现路径字符。
     */
    private fun isSafePackageId(id: String): Boolean {
        if (id.isBlank()) return false
        if (id.contains('/') || id.contains('\\')) return false
        if (id.contains("..")) return false
        if (id.startsWith(".")) return false
        return true
    }

    /**
     * 拒绝绝对路径、盘符、以及任何形式的 `..` 段。
     *
     * 只做字符串级校验是不够的（`a/../../b` 也危险），
     * 所以这里按 `/` 切开逐段判断，任何一段是 `..` 就拒绝。
     */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.length >= 2 && name[1] == ':') return false  // C:\ 之类
        val parts = name.replace('\\', '/').split('/')
        return parts.none { it == ".." }
    }
}