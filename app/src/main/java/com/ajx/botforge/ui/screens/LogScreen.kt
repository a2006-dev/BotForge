package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ajx.botforge.data.Logger
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.viewmodel.LogViewModel

@Composable
fun LogScreen(onBack: () -> Unit) {
    val vm: LogViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 跟随模式下自动滚到底部
    LaunchedEffect(state.shown.size, state.tailing) {
        if (state.tailing && state.shown.isNotEmpty()) {
            listState.animateScrollToItem(state.shown.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(
            title = "运行日志",
            subtitle = "${state.shown.size} 条 · 内存上限 600",
            onBack = onBack
        )

        // 工具条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(
                text = if (state.tailing) "暂停跟随" else "继续跟随",
                primary = state.tailing,
                onClick = { vm.toggleTailing() }
            )
            SmallButton(text = "刷新", onClick = { vm.reload() })
            SmallButton(text = "清空", onClick = { vm.clear() })
        }

        // 级别过滤
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip("全部", selected = state.filterLevel < 0) { vm.setFilter(-1) }
            FilterChip("信息", selected = state.filterLevel == 0) { vm.setFilter(0) }
            FilterChip("警告", selected = state.filterLevel == 1) { vm.setFilter(1) }
            FilterChip("错误", selected = state.filterLevel == 2) { vm.setFilter(2) }
        }

        Spacer(Modifier.height(Spacing.sm))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .background(Bg2, Radius.md)
                .border(Sizes.hairline, Line, Radius.md)
        ) {
            if (state.shown.isEmpty()) {
                EmptyState(
                    title = "暂无日志",
                    description = "机器人启动后，网关连接、消息处理与模型调用记录会出现在这里",
                    icon = "▤"
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    items(state.shown) { line ->
                        LogLineRow(line)
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun LogLineRow(line: Logger.Line) {
    val color = when (line.level) {
        2 -> StatusTone.Danger.fg
        1 -> StatusTone.Warning.fg
        else -> TextMid
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg3, Radius.xs)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = line.time,
            color = TextLow,
            fontSize = FontSize.micro,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = line.text,
            color = color,
            fontSize = FontSize.caption,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) StatusTone.Info.bg else Bg2,
                Radius.full
            )
            .border(
                Sizes.hairline,
                if (selected) StatusTone.Info.fg.copy(alpha = 0.5f) else Line,
                Radius.full
            )
            .androidxClickable(onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Text(
            text = label,
            color = if (selected) StatusTone.Info.fg else TextMid,
            fontSize = FontSize.caption
        )
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))