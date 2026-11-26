package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Spacing

/**
 * 迁移过渡页。
 *
 * 用于尚未完成 Compose 化的路由占位。刻意不展示任何虚构数据或假按钮，
 * 只说明当前状态，避免出现「点了没反应」的空入口。
 */
@Composable
fun PlaceholderScreen(
    title: String,
    note: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(title = title, onBack = onBack)
        Spacer(Modifier.height(Spacing.xl))
        EmptyState(
            title = "此页面正在重构",
            description = note,
            icon = "…"
        )
    }
}