package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.viewmodel.LogViewModel

@Composable
fun LogScreen(onBack: () -> Unit) {
    val c = botColors
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
            .background(c.bg1)
    ) {
        PageTopBar(
            title = Txt.LOG_TITLE,
            subtitle = String.format(Txt.LOG_COUNT_FMT, state.shown.size),
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
                text = if (state.tailing) Txt.LOG_TAILING_ON else Txt.LOG_TAILING_OFF,
                primary = state.tailing,
                onClick = { vm.toggleTailing() }
            )
            SmallButton(text = Txt.REFRESH, onClick = { vm.reload() })
            SmallButton(text = Txt.LOG_CLEAR, onClick = { vm.clear() })
        }

        // 级别过滤
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip(Txt.LOG_LEVEL_ALL, selected = state.filterLevel < 0) { vm.setFilter(-1) }
            FilterChip(Txt.LOG_LEVEL_INFO, selected = state.filterLevel == 0) { vm.setFilter(0) }
            FilterChip(Txt.LOG_LEVEL_WARN, selected = state.filterLevel == 1) { vm.setFilter(1) }
            FilterChip(Txt.LOG_LEVEL_ERROR, selected = state.filterLevel == 2) { vm.setFilter(2) }
        }

        Spacer(Modifier.height(Spacing.sm))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .background(c.bg2, RoundedCornerShape(Radius.md))
                .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.md))
        ) {
            if (state.shown.isEmpty()) {
                EmptyState(
                    title = Txt.LOG_EMPTY,
                    description = Txt.LOG_EMPTY_DESC,
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
    val c = botColors
    val color = when (line.level) {
        2 -> StatusTone.Danger.fg
        1 -> StatusTone.Warning.fg
        else -> c.textMid
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg3, RoundedCornerShape(Radius.xs))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = line.time,
            color = c.textLow,
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
    val c = botColors
    Box(
        modifier = Modifier
            .background(
                if (selected) StatusTone.Info.bg else c.bg2,
                RoundedCornerShape(Radius.full)
            )
            .border(
                Sizes.hairline,
                if (selected) StatusTone.Info.fg.copy(alpha = 0.5f) else c.line,
                RoundedCornerShape(Radius.full)
            )
            .androidxClickable(onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Text(
            text = label,
            color = if (selected) StatusTone.Info.fg else c.textMid,
            fontSize = FontSize.caption
        )
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))