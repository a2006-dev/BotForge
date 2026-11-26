package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ajx.botforge.data.Config
import com.ajx.botforge.ui.components.AppCard
import com.ajx.botforge.ui.components.ConfirmDialog
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.ErrorState
import com.ajx.botforge.ui.components.LoadingState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TextButton
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.viewmodel.BotListViewModel

@Composable
fun BotListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onGroups: (String) -> Unit
) {
    val vm: BotListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Bot?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(
            title = "机器人",
            subtitle = if (state.bots.isEmpty()) null else
                "共 ${state.bots.size} 个 · ${state.enabledCount} 个已启用",
            onBack = onBack,
            actions = {
                TextButton(text = "刷新", onClick = { vm.refresh() })
            }
        )

        when {
            state.loading && state.bots.isEmpty() -> LoadingState()
            state.error != null && state.bots.isEmpty() ->
                ErrorState(message = state.error!!, onRetry = { vm.refresh() })
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (state.bots.isEmpty()) {
                    item {
                        EmptyState(
                            title = "还没有机器人",
                            description = "添加一个官方机器人应用，填入 AppID 与密钥后即可接入",
                            icon = "＋"
                        )
                    }
                }

                items(state.bots, key = { it.id }) { bot ->
                    BotCard(
                        bot = bot,
                        onEdit = { onEdit(bot.id) },
                        onGroups = { onGroups(bot.id) },
                        onToggle = { enabled -> vm.toggleEnabled(bot.id, enabled) },
                        onDelete = { pendingDelete = bot }
                    )
                }

                item {
                    Spacer(Modifier.height(Spacing.sm))
                    PrimaryButton(
                        text = "添加机器人",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(null) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { bot ->
        ConfirmDialog(
            title = "删除机器人",
            message = "将删除「${bot.name}」及其 ${bot.groups.size} 条群配置，此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.delete(bot.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun BotCard(
    bot: Config.Bot,
    onEdit: () -> Unit,
    onGroups: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    AppCard {
        // 第一层：名称 + 状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bot.name,
                    color = TextHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = "AppID ${bot.appId}",
                    color = TextLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (bot.enabled) "已启用" else "已禁用",
                tone = if (bot.enabled) StatusTone.Success else StatusTone.Neutral
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // 第二层：元信息 + 缺失警示
        val enabledGroups = bot.groups.count { it.enabled }
        Text(
            text = if (bot.groups.isEmpty()) "未配置群" else
                "${bot.groups.size} 个群 · ${enabledGroups} 个已启用",
            color = TextMid,
            fontSize = FontSize.small
        )

        val missing = buildList {
            if (bot.personaId.isEmpty()) add("人设")
            if (bot.modelId.isEmpty()) add("模型")
        }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            StatusBadge(
                text = "未绑定" + missing.joinToString("、"),
                tone = StatusTone.Warning
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // 第三层：操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(text = "编辑", primary = true, onClick = onEdit)
            Spacer(Modifier.width(Spacing.sm))
            SmallButton(text = "群管理", onClick = onGroups)
            Spacer(Modifier.width(Spacing.sm))
            SmallButton(
                text = if (bot.enabled) "禁用" else "启用",
                onClick = { onToggle(!bot.enabled) }
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(text = "删除", color = com.ajx.botforge.ui.theme.Danger, onClick = onDelete)
        }
    }
}