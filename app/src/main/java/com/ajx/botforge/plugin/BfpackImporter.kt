package com.ajx.botforge.plugin

import com.ajx.botforge.data.Logger
import com.ajx.botforge.pkg.PackageManifestReader
import com.ajx.botforge.pkg.PackageSpec
import com.ajx.botforge.pkg.PermissionDecl
import com.ajx.botforge.pkg.ReadResult
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
 *  3. 装上之后没有校验——`api` 版本不对、id 冲突、清单写错，
 *     要等到重启 App、看日志才发现。
 *
 * `.bfpack` 把这三件事都解决：一个 zip 文件、一键导入、
 * 导入前就能把清单解析出来给用户看。
 *
 * ## 职责划分（本次重构的要点）
 *
 * 清单的解析——manifest.json / plugin.json / tool.json 三种格式长什么样、
 * 各自怎么归一成 [PackageSpec]——全部交给 [PackageManifestReader]。
 * 本文件只负责它在结构上必须自己做的三件事：
 *
 *  - 解压：zip 条目的路径穿越与体积上限，见 [isSafeEntryName] 与 [unzip]；
 *  - 准入：api 版本上限与 id 合法性，在落盘之前拦一道；
 *  - 落盘：覆盖安装的备份与回滚。
 *
 * 从「自己读 plugin.json」改成「调 reader」，唯一理由是不让同一份清单
 * 在新旧两条路径上被读成两种结果——那是最难查的一类故障。
 * 代价是 v1 包的处理细节不在本文件里了，读代码时要顺着 reader 看过去。
 *
 * ## 关于 api 版本
 *
 * 支持上限 [MAX_API] 现在是 **2**，与 [PackageSpec.CURRENT_API] 同源：
 *
 *  - api=2 的 manifest.json 包正常安装（v2 唯一的安装入口就在这里）；
 *  - api=1 的 plugin.json / tool.json 包**仍然能装**，走 reader 的兼容分支，
 *    并附带一条「建议迁移到 manifest.json」的警告。不回退 v1 是硬要求：
 *    已经发出去的包不能因为升级 App 就全部变成装不上；
 *  - api 大于 2 直接拒绝，提示用户更新 App。
 *
 * ## 关于 org.json
 *
 * 本文件不再直接碰 JSON。全部 JSON 处理集中在 [PackageManifestReader]，
 * 把「不可单测的 IO」压缩在一层里；本文件因此可以在 JVM 单测里
 * 覆盖安全边界（见 BfpackImporterTest）。
 *
 * ## 安全边界
 *
 * zip 解压是经典的路径穿越攻击面（`../../` 写出去）。
 * 这里对每个 entry 名做归一化并校验前缀，见 [isSafeEntryName]；
 * 解压写盘前再用 canonicalPath 复核一次，见 [unzip]。
 * 另外限制单文件与总解压体积，避免 zip bomb 把手机塞满。
 */
object BfpackImporter {

    /**
     * 支持的最高 api 版本。
     *
     * 取 [PackageSpec.CURRENT_API]（当前为 2），不在这里另写一个数字：
     * 两处硬编码一定会漂移——解析器升到 2 而导入器还卡在 1，
     * 结果就是「带 manifest.json 的包被拒，说只支持到 v1」这种最难查的故障。
     *
     * 注意本文件和 reader 各自都判一次 api 上限。这不是冗余失误：
     * 两道闸任意一道被改错，另一道仍然会拒绝，方向永远是 fail-closed，
     * 不会出现「两边谁都没拦」的静默放行。
     */
    private val MAX_API: Int get() = PackageSpec.CURRENT_API

    /** 单个包解压后的体积上限。正常插件是几 KB 到几百 KB 脚本，256MB 足够宽松。 */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** 单个文件上限 */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 导入预览：解析完清单、还没落盘时给用户看的内容。
     *
     * 后四个字段是 v2 引入的，全部带默认值，所以老的构造调用、复制调用、
     * 解构声明都不用改。v1 包上它们留空是**如实反映**：那份清单里
     * 根本没有 ai_tools、ui_actions、permissions 这些概念，凭空填默认值
     * 会让用户以为包里声明过。
     */
    data class Preview(
        /** 包 id，来自清单 */
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
        /** 提供的指令名列表；v2 的纯 AI 工具包这里为空 */
        val commands: List<String>,
        /** 解压后会占多少字节 */
        val totalBytes: Long,
        /** 文件个数 */
        val fileCount: Int,
        /** 若为 null 表示可以安装；非 null 是拒绝原因 */
        val blockReason: String? = null,
        /** 警告：不影响安装，但用户该知道 */
        val warnings: List<String> = emptyList(),
        /** 暴露给模型的 AI 工具名列表 */
        val apiTools: List<String> = emptyList(),
        /** 面板动作 id 列表；v1 tool.json 迁移后是单条 run */
        val uiActions: List<String> = emptyList(),
        /** 权限的人类可读描述，按风险从高到低排好序 */
        val permissions: List<String> = emptyList(),
        /**
         * 运行时标识：android_sh 或 proot。
         *
         * 这是**清单里声明的值**，不是实际执行环境。当前版本声明 proot 也仍按
         * android_sh 执行（见 PackageSpec 的 runtimeWarnings），所以界面上必须
         * 连带显示读取层追加的那条警告，不能只把这个字符串亮出来让用户以为
         * 已经跑在容器里。
         */
        val runtime: String = "android_sh"
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

            // 清单查找顺序（manifest.json → plugin.json → tool.json）由 reader 决定，
            // 本文件不再各写一份——写两份就会出现「导入器认 manifest.json、
            // 读取器认 plugin.json」这种自相矛盾的状态。
            val read = PackageManifestReader.read(staging)
            val success = read as? ReadResult.Success
            if (success == null) {
                val reason = (read as? ReadResult.Failure)?.reason ?: "清单解析失败"
                return failed(reason)
            }
            val spec = success.spec

            // 第一道闸：api 上限。reader 内部也判过一次，见 MAX_API 的说明。
            if (spec.api > MAX_API) return failed(tooNewReason(spec.api))

            val warnings = ArrayList<String>(success.warnings)

            // id 冲突检查
            if (File(targetRoot, spec.id).exists()) {
                warnings.add("已存在同 id 的包（${spec.id}），继续安装会覆盖它。")
            }

            // 入口脚本缺失检查：声明了 entry 但文件不在，装上也会运行失败。
            // 三类能力都查——v2 的 ai_tools / ui_actions 同样有 entry 字段，
            // 只查 commands 会让纯 AI 工具包带着断链装进去。
            spec.commands.forEach { c ->
                if (!File(staging, c.entry).isFile) {
                    warnings.add("指令 /${c.command} 声明的入口 ${c.entry} 不在包里，运行时会失败。")
                }
            }
            spec.aiTools.forEach { t ->
                if (!File(staging, t.entry).isFile) {
                    warnings.add("AI 工具 ${t.name} 声明的入口 ${t.entry} 不在包里，运行时会失败。")
                }
            }
            spec.uiActions.forEach { a ->
                if (!File(staging, a.entry).isFile) {
                    warnings.add("面板动作 ${a.id} 声明的入口 ${a.entry} 不在包里，运行时会失败。")
                }
            }

            // 「三类能力全空」的拒绝放在 reader 的统一校验里，这里不重复写：
            // v2 允许 commands 为空（纯 ai_tools 包正是 v2 最想支持的形态），
            // 但 commands、ai_tools、ui_actions 三者都空的包没有安装意义。
            // 判据只有一处，避免两边规则漂移后出现「导入器放过、加载器丢弃」。

            return Preview(
                id = spec.id,
                name = spec.name,
                version = spec.version,
                api = spec.api,
                author = spec.author,
                description = spec.description,
                commands = spec.commands.map { it.command },
                totalBytes = stats.first,
                fileCount = stats.second,
                blockReason = null,
                warnings = warnings,
                apiTools = spec.aiTools.map { it.name },
                uiActions = spec.uiActions.map { it.id },
                permissions = readablePermissions(spec.permissions),
                runtime = runtimeLabel(spec.runtime)
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

            val read = PackageManifestReader.read(staging)
            val success = read as? ReadResult.Success
            if (success == null) {
                return (read as? ReadResult.Failure)?.reason ?: "清单解析失败"
            }
            val spec = success.spec

            if (spec.api > MAX_API) return tooNewReason(spec.api)

            // 防替换：用户在确认页看到的是 A，装的必须是 A
            if (spec.id != expectedId) {
                return "包内容已变化（期望 $expectedId，实际 ${spec.id}），请重新选择文件。"
            }

            // 必须校验 id 才能拿它拼路径。
            // reader 已经在 read() 里用 packageIdProblem 判过一次，
            // 这里仍保留本文件自己的 isSafePackageId：commit 是与文件系统
            // 直接相邻的那一层，不能把「拼路径前先校验」的责任转交给上游。
            // 不校验就直接 File(targetRoot, spec.id)，等于给 zip 路径穿越
            // 在 manifest 上开第二个口子。规则与 uninstall() 必须一致。
            if (!isSafePackageId(spec.id)) {
                return "包 id 非法（${spec.id}）：不能包含路径分隔符或 .."
            }

            // 读取层的警告只影响「用户知不知道」，不影响「能不能装」。
            // 日志落盘本身可能因存储异常失败，这里兜住：宁可少一条日志，
            // 也不能因为写日志失败就让一次合法安装中断在半路。
            runCatching { success.warnings.forEach { Logger.w("Bfpack", it) } }

            val dest = File(targetRoot, spec.id)
            if (dest.exists()) {
                // 覆盖安装：先备份再删，失败能回滚
                val backup = File(targetRoot, ".backup_${spec.id}")
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
            Logger.i("Bfpack", "已安装 ${spec.id} v${spec.version}（api ${spec.api}）")
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

    /** api 过新时的统一中文原因；preview 与 commit 共用一句，避免两处措辞不一致。 */
    private fun tooNewReason(declared: Int): String =
        "这个包要求协议 v$declared，当前 App 只支持到 v$MAX_API。请更新 BotForge 后再装。"

    /**
     * 把权限声明转成给人看的字符串，按风险降序。
     *
     * 排序在读取层已经做过一次，这里再排一遍是因为 [Preview] 是要直接渲染的产物：
     * 它不该依赖调用方记得「spec 里的 permissions 已经排好」这个隐含约定。
     * sortedByDescending 是稳定排序，同一风险档内保持作者的声明顺序。
     *
     * 标签用原文而不是翻成中文：用户对照包作者的 manifest 排查时，
     * 原文才能一一对上；翻成中文反而要多做一次心算。
     */
    private fun readablePermissions(decls: List<PermissionDecl>): List<String> =
        decls.sortedByDescending { it.tag.risk }.map { d ->
            val base = "${d.tag.tag}（${riskLabel(d.tag.risk)}）"
            val reason = d.reason.trim()
            if (reason.isEmpty()) base else "$base：$reason"
        }

    /**
     * 风险等级的中文说法，与 [com.ajx.botforge.pkg.PermissionTag] 里 risk 的 0..3 定义对应。
     * 越界值不崩，原样报数字——将来加了新等级，用户看到的是可查的数字而不是空白。
     */
    private fun riskLabel(risk: Int): String = when (risk) {
        0 -> "无副作用"
        1 -> "低风险"
        2 -> "中风险"
        3 -> "高风险"
        else -> "风险等级 $risk"
    }

    /** 运行时标识串，供设置页直接显示；未知值由 Runtime 的解析保证不会出现。 */
    private fun runtimeLabel(runtime: com.ajx.botforge.pkg.Runtime): String = when (runtime) {
        com.ajx.botforge.pkg.Runtime.ANDROID_SH -> "android_sh"
        com.ajx.botforge.pkg.Runtime.PROOT -> "proot"
    }

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
