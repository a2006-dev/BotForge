package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

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
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.viewmodel.BotListViewModel

@Composable
fun BotListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onGroups: (String) -> Unit
) {
    val c = botColors
    val vm: BotListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Bot?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg1)
    ) {
        PageTopBar(
            title = Txt.DASH_SECTION_BOTS,
            subtitle = if (state.bots.isEmpty()) null else
                String.format(Txt.LIST_BOT_SUMMARY_FMT, state.bots.size, state.enabledCount),
            onBack = onBack,
            actions = {
                TextButton(text = Txt.REFRESH, onClick = { vm.refresh() })
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
                            title = Txt.BOT_LIST_EMPTY_TITLE,
                            description = Txt.BOT_LIST_EMPTY_DESC,
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
                        text = Txt.DASH_EMPTY_BOT_ACTION,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(null) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { bot ->
        ConfirmDialog(
            title = Txt.BOT_DELETE_TITLE,
            message = String.format(Txt.CONFIRM_DELETE_BOT, bot.name, bot.groups.size),
            confirmText = Txt.DELETE,
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
    val c = botColors
    AppCard {
        // 第一层：名称 + 状态
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bot.name,
                    color = c.textHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = String.format(Txt.BOT_APPID_FMT, bot.appId),
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (bot.enabled) Txt.ENABLED else Txt.DISABLED,
                tone = if (bot.enabled) StatusTone.Success else StatusTone.Neutral
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // 第二层：元信息 + 缺失警示
        val enabledGroups = bot.groups.count { it.enabled }
        Text(
            text = if (bot.groups.isEmpty()) Txt.BOT_NO_GROUP else
                String.format(Txt.LIST_COUNT_GROUP_FMT, bot.groups.size, enabledGroups),
            color = c.textMid,
            fontSize = FontSize.small
        )

        val missing = buildList {
            if (bot.personaId.isEmpty()) add(Txt.LABEL_PERSONA)
            if (bot.modelId.isEmpty()) add(Txt.LABEL_MODEL)
        }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            StatusBadge(
                text = Txt.NOT_BOUND + missing.joinToString("、"),
                tone = StatusTone.Warning
            )
        }

        Spacer(Modifier.height(Spacing.md))

        // 第三层：操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(text = Txt.EDIT, primary = true, onClick = onEdit)
            Spacer(Modifier.width(Spacing.sm))
            SmallButton(text = Txt.BOT_MANAGE_GROUP, onClick = onGroups)
            Spacer(Modifier.width(Spacing.sm))
            SmallButton(
                text = if (bot.enabled) Txt.DISABLE else Txt.ENABLE,
                onClick = { onToggle(!bot.enabled) }
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(text = Txt.DELETE, color = c.danger, onClick = onDelete)
        }
    }
}