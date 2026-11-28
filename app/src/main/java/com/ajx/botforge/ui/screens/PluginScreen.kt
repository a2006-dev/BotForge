package com.ajx.botforge.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.ajx.botforge.plugin.BfpackImporter
import com.ajx.botforge.plugin.PluginManifest
import com.ajx.botforge.plugin.PluginRegistry
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.PackageImportSheet
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.RiskConfirmSheet
import com.ajx.botforge.ui.components.RiskConfirmSpec
import com.ajx.botforge.ui.components.RiskLevel
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.SnackBar
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 插件页。
 *
 * 数据源是 [PluginRegistry.all]，真实反射磁盘上的 plugin.json。
 *
 * ## 导入入口
 *
 * 顶栏右侧的「导入」按钮走系统文件选择器（SAF）选一个 `.bfpack`，
 * 预览清单、确认风险，由 [BfpackImporter] 落盘，然后重载注册中心。
 * 完整链路（一行一步，便于对照代码）：
 *
 *  1. [rememberLauncherForActivityResult] + [ActivityResultContracts.OpenDocument] 建启动器
 *  2. `launcher.launch(arrayOf(MIME...))` 拉起系统选择器
 *  3. 回调收到 `Uri?`，null 表示用户放弃，此时不动任何文件
 *  4. `context.contentResolver.openInputStream(uri)` 拿到输入流
 *  5. 在 [Dispatchers.IO] 里复制到 `cacheDir/import_<time>.bfpack`
 *  6. [BfpackImporter.preview] 得到 [BfpackImporter.Preview]
 *  7. 用户确认后 [BfpackImporter.commit]，期望 id 传预览时看到的那一个
 *  8. 返回 null = 成功，重载 [PluginRegistry] 并刷新列表
 *  9. 无论成败都删除 cacheDir 里的临时包
 *
 * ## 为什么删除仍然不在本页
 *
 * 删目录的风险高于收益，导入是有闸门的写，删除没有可回滚的东西。
 */
@Composable
fun PluginScreen(onBack: () -> Unit) {
    val c = botColors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var plugins by remember { mutableStateOf<List<PluginManifest>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 预览中的包：预览弹窗打开时非空
    var previewing by remember { mutableStateOf<BfpackImporter.Preview?>(null) }
    // 待落盘的临时文件 + 用户在预览页看到的 id
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    // 非空时显示 L3 确认面板
    var riskSpec by remember { mutableStateOf<RiskConfirmSpec?>(null) }
    // 读盘 / 落盘期间禁止再次点导入
    var busy by remember { mutableStateOf(false) }
    // 一次性结果提示；成功走 Success，失败走 Danger
    var notice by remember { mutableStateOf<Pair<String, StatusTone>?>(null) }

    LaunchedEffect(Unit) {
        plugins = try {
            PluginRegistry.all()
        } catch (e: Exception) {
            emptyList()
        }
        loaded = true
    }

    // MIME 过滤故意放宽：不同文件管理器对 .bfpack 的判定不一致，
    // 常见是 application/octet-stream，也有报 application/zip 的，
    // 个别报 application/x-zip-compressed 甚至 text/plain。
    // 全是压缩包的合法 MIME 却选不了，等于功能不可用，
    // 所以末尾兜一个通配符，真正的校验放在 preview() 里做完。
    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                notice = null

                var copyError: String? = null
                val pack: File? = try {
                    withContext(Dispatchers.IO) { copyPackToCache(ctx, uri) }
                } catch (e: Exception) {
                    copyError = "读取所选文件失败：" + (e.message ?: e.javaClass.simpleName)
                    null
                }

                if (pack == null) {
                    busy = false
                    notice = (copyError ?: "读取所选文件失败。") to StatusTone.Danger
                    return@launch
                }

                val root = PluginRegistry.rootDir()
                val rootError = "插件目录不可用，无法导入。"

                // preview() 对非法 zip 是抛异常而不是返回 blockReason
                // （路径穿越抛 SecurityException、体积超限抛 IllegalStateException），
                // 这里把异常文案带出来，否则用户只看到一句「解析失败」，
                // 根本不知道是包坏了还是 App 版本太老。
                var previewError: String? = null
                val preview = if (root == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            BfpackImporter.preview(pack, root)
                        } catch (e: Exception) {
                            previewError = e.message ?: e.javaClass.simpleName
                            null
                        }
                    }
                }

                busy = false
                if (root == null || preview == null) {
                    runCatching { pack.delete() }
                    val reason = if (root == null) {
                        rootError
                    } else {
                        "解析扩展包失败：" + (previewError ?: "该文件可能不是有效的 .bfpack。")
                    }
                    notice = reason to StatusTone.Danger
                } else {
                    pending = PendingImport(pack, preview.id)
                    previewing = preview
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = Txt.TITLE_PLUGINS,
            subtitle = if (loaded) String.format(Txt.PLUGIN_LOADED_FMT, plugins.size) else null,
            onBack = onBack,
            actions = {
                SmallButton(
                    text = if (busy) "读取中" else "导入",
                    enabled = !busy,
                    onClick = {
                        notice = null
                        pickLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*")
                        )
                    }
                )
            }
        )

        val shown = notice
        if (shown != null) {
            SnackBar(
                message = shown.first,
                tone = shown.second,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }

        if (loaded && plugins.isEmpty()) {
            EmptyState(
                title = Txt.PLUGIN_EMPTY_TITLE,
                description = Txt.PLUGIN_EMPTY_DESC,
                icon = "⚙",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.md,
                    vertical = Spacing.sm
                )
            ) {
                items(plugins, key = { it.id }) { p ->
                    PluginCard(p)
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }

    // 预览弹窗。blockReason 非空时只显示原因与关闭，没有安装入口。
    val shownPreview = previewing
    if (shownPreview != null) {
        PackageImportSheet(
            preview = shownPreview,
            onInstall = {
                val ready = pending
                if (ready == null) {
                    previewing = null
                    notice = "临时文件已丢失，请重新选择文件。" to StatusTone.Danger
                } else {
                    riskSpec = RiskConfirmSpec(
                        level = RiskLevel.L3,
                        action = "安装扩展包 " + shownPreview.id,
                        consequence = "把 " + shownPreview.id + " v" + shownPreview.version +
                            " 写入插件目录并立即重新加载。若已存在同 id 的包会被整体替换，" +
                            "原目录内容不保留。",
                        confirmWord = shownPreview.id,
                        facts = previewFacts(shownPreview)
                    )
                    // 同一个点击里把预览置空、把确认面板置上：两次状态写入在同一帧合并，
                    // 重绘后只剩一层弹窗，不会出现两层 Dialog 叠着抢返回键。
                    previewing = null
                }
            },
            onDismiss = {
                previewing = null
                val abandoned = pending
                pending = null
                if (abandoned != null) runCatching { abandoned.pack.delete() }
            }
        )
    }

    // L3 风险确认：confirmWord 是包 id，用户必须完整输入一遍才能点确认。
    val spec = riskSpec
    if (spec != null) {
        RiskConfirmSheet(
            spec = spec,
            onConfirm = {
                riskSpec = null
                val ready = pending
                pending = null
                if (ready != null) {
                    scope.launch {
                        busy = true
                        try {
                            val root = PluginRegistry.rootDir()
                            val reason: String? = if (root == null) {
                                "插件目录不可用，无法写入。"
                            } else {
                                try {
                                    withContext(Dispatchers.IO) {
                                        BfpackImporter.commit(ready.pack, root, ready.expectedId)
                                    }
                                } catch (e: Exception) {
                                    "写入扩展包失败：" + (e.message ?: e.javaClass.simpleName)
                                }
                            }

                            if (reason == null) {
                                // reload() 与 all() 都要读磁盘，不能留在主线程
                                val fresh = withContext(Dispatchers.IO) {
                                    runCatching { PluginRegistry.reload() }
                                    runCatching { PluginRegistry.all() }.getOrDefault(emptyList())
                                }
                                plugins = fresh
                                loaded = true
                                notice = ("已安装 " + ready.expectedId + "。") to StatusTone.Success
                            } else {
                                notice = reason to StatusTone.Danger
                            }
                        } finally {
                            // 成功失败都要清掉缓存里的临时包，否则每导入一次就多留一份副本
                            runCatching { ready.pack.delete() }
                            busy = false
                        }
                    }
                }
            },
            onDismiss = {
                riskSpec = null
                val abandoned = pending
                pending = null
                if (abandoned != null) runCatching { abandoned.pack.delete() }
            }
        )
    }
}

/**
 * 待落盘的导入任务。
 *
 * 预览页看到的 [expectedId] 与临时包 [pack] 必须一起带走：
 * 分成两个状态变量存，会在「预览 A、确认 B」的时序下错位，
 * 而错位的后果是 [BfpackImporter.commit] 的防替换校验失去意义。
 */
private data class PendingImport(val pack: File, val expectedId: String)

/**
 * 把 SAF 给的 Uri 复制到 cacheDir，返回临时文件。
 *
 * 调用方必须放在 IO 线程：这里同步读完整份输入流，
 * 包可能有几百 KB 到几十 MB，在主线程做就是一次 ANR 抽奖。
 *
 * 用 cacheDir 而不是 filesDir：系统能自行回收，
 * 导入结束立刻删除只是尽快还空间，不是唯一的兜底。
 */
private fun copyPackToCache(context: Context, uri: Uri): File {
    val target = File(context.cacheDir, "import_" + System.currentTimeMillis() + ".bfpack")
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("contentResolver 无法打开所选文件")
    input.use { ins ->
        FileOutputStream(target).use { out ->
            ins.copyTo(out, 8192)
        }
    }
    return target
}

/**
 * L3 确认面板里的事实清单。
 *
 * 权限排在指令之后、警告之前：它们是「装之前该知道」的核心信息，
 * 若全塞进 consequence 一句话里会被读成背景噪音。
 */
private fun previewFacts(p: BfpackImporter.Preview): List<String> {
    val facts = ArrayList<String>()
    facts.add("标识 " + p.id + "，版本 " + p.version + "，协议 v" + p.api)
    facts.add("解压后 " + formatBytes(p.totalBytes) + "，共 " + p.fileCount + " 个文件")
    facts.add("运行时 " + p.runtime)

    if (p.commands.isNotEmpty()) {
        facts.add("覆盖指令 " + p.commands.size + " 条：" + joinAsSlash(p.commands))
    }
    if (p.apiTools.isNotEmpty()) {
        facts.add("暴露 AI 工具 " + p.apiTools.size + " 个：" + p.apiTools.joinToString("、"))
    }
    if (p.uiActions.isNotEmpty()) {
        facts.add("面板动作 " + p.uiActions.size + " 个：" + p.uiActions.joinToString("、"))
    }

    if (p.permissions.isEmpty()) {
        facts.add("未声明任何权限")
    } else {
        facts.add("声明权限 " + p.permissions.size + " 项：")
        facts.addAll(p.permissions)
    }

    if (p.warnings.isNotEmpty()) {
        facts.add("导入前提示 " + p.warnings.size + " 条：")
        facts.addAll(p.warnings)
    }
    return facts
}

/** 指令名在 UI 里一律带斜杠前缀，与 /help 的展示保持一致。 */
private fun joinAsSlash(list: List<String>): String =
    list.joinToString("、") { "/" + it }

/**
 * 解压体积给人看：MB 保留一位小数，KB 取整，B 原样。
 *
 * 固定 [Locale.US]：某些区域设置会把小数点写成逗号，
 * 而「3.2 MB」在中文界面里没有歧义。
 */
private fun formatBytes(bytes: Long): String {
    if (bytes >= 1024L * 1024L) {
        return String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }
    if (bytes >= 1024L) {
        return (bytes / 1024L).toString() + " KB"
    }
    return bytes.toString() + " B"
}

@Composable
private fun PluginCard(p: PluginManifest) {
    val c = botColors
    val compatible = p.api == PluginManifest.CURRENT_API
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.name,
                    color = botColors.textHigh,
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = p.id,
                    color = botColors.textLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (compatible) "v${p.version}" else Txt.PLUGIN_API_INCOMPATIBLE,
                tone = if (compatible) StatusTone.Success else StatusTone.Warning
            )
        }

        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = p.description,
                color = botColors.textMid,
                fontSize = FontSize.caption
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row {
            Text(text = "API ", color = botColors.textLow, fontSize = FontSize.caption)
            Text(
                text = p.api.toString(),
                color = if (compatible) botColors.textMid else botColors.textHigh,
                fontSize = FontSize.caption
            )
            if (p.author.isNotBlank()) {
                Text(text = "   " + Txt.PLUGIN_AUTHOR + " ", color = botColors.textLow, fontSize = FontSize.caption)
                Text(text = p.author, color = botColors.textMid, fontSize = FontSize.caption)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = String.format(Txt.PLUGIN_CMD_FMT, p.commands.size),
            color = botColors.textLow,
            fontSize = FontSize.caption
        )
        Spacer(Modifier.height(Spacing.xxs))
        p.commands.forEach { c ->
            Row(modifier = Modifier.padding(vertical = Spacing.xxs)) {
                Text(
                    text = "/" + c.command,
                    color = botColors.textHigh,
                    fontSize = FontSize.caption
                )
                Text(
                    text = "  " + c.description.ifBlank { c.usage },
                    color = botColors.textLow,
                    fontSize = FontSize.caption
                )
                if (c.scope.contains("group")) {
                    Text(text = "  群", color = botColors.textMid, fontSize = FontSize.caption)
                }
            }
        }
    }
}
