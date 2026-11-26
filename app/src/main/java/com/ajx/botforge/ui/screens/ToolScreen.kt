package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajx.botforge.tools.ToolManifest
import com.ajx.botforge.tools.ToolPackage
import com.ajx.botforge.tools.ToolResult
import com.ajx.botforge.ui.components.AppCard
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.FormField
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.launch

/**
 * 工具包管理。
 *
 * 页面分三层：说明区（含安全边界声明）、工具列表、结果面板。
 * 点击某个工具展开参数表单，填完执行，输出就地显示。
 *
 * 安全边界刻意写在最上面而不是折叠进帮助页：工具包脚本以应用自身权限运行，
 * 能读写外部存储与私有目录，应用不做沙箱隔离。让用户在执行前就看到这句话，
 * 比事后补一份免责声明更诚实。
 */
@Composable
fun ToolScreen(onBack: () -> Unit) {
    val c = botColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var list by remember { mutableStateOf<List<ToolManifest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf<String?>(null) }

    // 每个工具的参数值与执行结果，按 id 分开存
    val values = remember { mutableStateMapOf<String, MutableMap<String, String>>() }
    val results = remember { mutableStateMapOf<String, ToolResult?>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun reload() {
        loading = true
        ToolPackage.ensureSample(context)
        list = ToolPackage.scan(context)
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = Txt.TITLE_TOOLS,
            subtitle = Txt.TOOL_SUB(list.size),
            onBack = onBack,
            actions = {
                Box(
                    modifier = Modifier
                        .size(com.ajx.botforge.ui.theme.Sizes.touchTarget)
                        .clickable {
                            scope.launch { reload() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = Txt.TOOL_REFRESH,
                        color = c.brand,
                        fontSize = FontSize.small
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.md,
                vertical = Spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // ---------- 说明与安全边界 ----------
            item {
                AppCard(bordered = true) {
                    Text(
                        text = Txt.TOOL_INTRO_TITLE,
                        color = c.textHigh,
                        fontSize = FontSize.bodyLarge
                    )
                    Text(
                        text = Txt.TOOL_INTRO_DESC,
                        color = c.textMid,
                        fontSize = FontSize.small,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm)
                            .background(c.warningGhost, RoundedCornerShape(8.dp))
                            .padding(Spacing.sm)
                    ) {
                        Text(
                            text = Txt.TOOL_SECURITY_NOTE,
                            color = c.warning,
                            fontSize = FontSize.caption,
                            lineHeight = 16.sp
                        )
                    }
                    Text(
                        text = Txt.TOOL_DIR_NOTE,
                        color = c.textLow,
                        fontSize = FontSize.caption,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
            }

            // ---------- 空态 ----------
            if (!loading && list.isEmpty()) {
                item {
                    EmptyState(
                        title = Txt.TOOL_EMPTY_TITLE,
                        description = Txt.TOOL_EMPTY_DESC,
                        icon = "○"
                    )
                }
            }

            // ---------- 工具列表 ----------
            items(list, key = { it.id }) { tool ->
                val open = expanded == tool.id
                val toolValues = values.getOrPut(tool.id) {
                    mutableMapOf<String, String>().apply {
                        tool.params.forEach { p -> put(p.name, p.default) }
                    }
                }
                val result = results[tool.id]
                val running = busy[tool.id] == true

                AppCard(bordered = true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) null else tool.id },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = tool.name,
                                    color = c.textHigh,
                                    fontSize = FontSize.bodyLarge
                                )
                                if (tool.risky) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = Spacing.xs)
                                            .background(c.dangerGhost, RoundedCornerShape(4.dp))
                                            .padding(horizontal = Spacing.xs, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = Txt.TOOL_RISKY,
                                            color = c.danger,
                                            fontSize = FontSize.micro
                                        )
                                    }
                                }
                            }
                            Text(
                                text = tool.description,
                                color = c.textMid,
                                fontSize = FontSize.small,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Text(
                                text = Txt.TOOL_META(
                                    tool.version,
                                    tool.author.ifEmpty { Txt.TOOL_UNKNOWN },
                                    tool.timeoutSec
                                ),
                                color = c.textLow,
                                fontSize = FontSize.micro,
                                modifier = Modifier.padding(top = Spacing.xs)
                            )
                        }
                        Text(
                            text = if (open) "▾" else "▸",
                            color = c.textLow,
                            fontSize = FontSize.small
                        )
                    }

                    if (open) {
                        // 参数表单
                        if (tool.params.isNotEmpty()) {
                            Column(modifier = Modifier.padding(top = Spacing.sm)) {
                                tool.params.forEach { p ->
                                    FormField(
                                        label = p.label,
                                        value = toolValues[p.name].orEmpty(),
                                        onValueChange = { toolValues[p.name] = it },
                                        placeholder = p.hint.ifEmpty { p.name },
                                        helper = p.hint.ifEmpty { null },
                                        modifier = Modifier.padding(bottom = Spacing.sm)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            PrimaryButton(
                                text = if (running) Txt.TOOL_RUNNING else Txt.TOOL_RUN,
                                modifier = Modifier.weight(1f),
                                enabled = !running,
                                loading = running,
                                onClick = {
                                    busy[tool.id] = true
                                    scope.launch {
                                        val r = ToolPackage.run(
                                            context,
                                            tool,
                                            toolValues.toMap()
                                        )
                                        results[tool.id] = r
                                        busy[tool.id] = false
                                    }
                                }
                            )
                            SecondaryButton(
                                text = Txt.TOOL_CLEAR_RESULT,
                                modifier = Modifier.weight(1f),
                                onClick = { results.remove(tool.id) }
                            )
                        }

                        // 结果面板
                        if (result != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.sm)
                                    .background(c.bg0, RoundedCornerShape(8.dp))
                                    .padding(Spacing.sm)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                if (result.ok) c.success else c.danger,
                                                RoundedCornerShape(3.dp)
                                            )
                                    )
                                    Text(
                                        text = Txt.TOOL_RESULT_META(
                                            result.exitCode,
                                            result.durationMs
                                        ),
                                        color = if (result.ok) c.success else c.danger,
                                        fontSize = FontSize.caption,
                                        modifier = Modifier.padding(start = Spacing.xs)
                                    )
                                }
                                val body = buildString {
                                    append(result.output.ifEmpty { Txt.TOOL_NO_OUTPUT })
                                    if (!result.error.isNullOrEmpty()) {
                                        if (isNotEmpty()) append('\n')
                                        append(result.error)
                                    }
                                }
                                Text(
                                    text = body,
                                    color = c.textHigh,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = Spacing.xs)
                                )
                            }
                        }
                    }
                }
            }

            item { Box(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.huge)) }
        }
    }
}
