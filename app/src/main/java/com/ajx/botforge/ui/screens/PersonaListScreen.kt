package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import com.ajx.botforge.ui.viewmodel.PersonaListViewModel

@Composable
fun PersonaListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit
) {
    val vm: PersonaListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Persona?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(
            title = "人设",
            subtitle = if (state.personas.isEmpty()) null else "${state.personas.size} 条",
            onBack = onBack
        )

        when {
            state.loading && state.personas.isEmpty() -> LoadingState()
            state.error != null && state.personas.isEmpty() ->
                ErrorState(message = state.error!!, onRetry = { vm.refresh() })
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (state.personas.isEmpty()) {
                    item {
                        EmptyState(
                            title = "还没有人设",
                            description = "人设决定机器人如何说话：人格提示词、语气、称呼与记忆深度",
                            icon = "✎"
                        )
                    }
                }

                items(state.personas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        refCount = state.usage[persona.id] ?: 0,
                        onEdit = { onEdit(persona.id) },
                        onDelete = { pendingDelete = persona }
                    )
                }

                item {
                    Spacer(Modifier.height(Spacing.sm))
                    PrimaryButton(
                        text = "新建人设",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(null) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { persona ->
        val refs = state.usage[persona.id] ?: 0
        ConfirmDialog(
            title = "删除人设",
            message = if (refs > 0) {
                "「${persona.name}」正被 $refs 个机器人使用，删除后这些机器人将失去人设。"
            } else {
                "确定删除「${persona.name}」？此操作不可撤销。"
            },
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.delete(persona.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun PersonaCard(
    persona: Config.Persona,
    refCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persona.name.ifBlank { "未命名人设" },
                    color = TextHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = "称呼「${persona.address.ifBlank { "你" }}」 · 温度 ${persona.temperature} · 上限 ${persona.maxTokens} 字",
                    color = TextMid,
                    fontSize = FontSize.caption
                )
            }
            if (refCount > 0) {
                StatusBadge(text = "$refCount 个机器人", tone = StatusTone.Info)
            } else {
                StatusBadge(text = "未使用", tone = StatusTone.Neutral)
            }
        }

        if (persona.prompt.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = persona.prompt,
                color = TextLow,
                fontSize = FontSize.small,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (persona.memoryEnabled) {
                    "记忆开启 · 保留 ${persona.memoryDepth} 条"
                } else {
                    "记忆关闭"
                },
                color = TextMid,
                fontSize = FontSize.caption
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(text = "编辑", primary = true, onClick = onEdit)
            Spacer(Modifier.width(Spacing.md))
            TextButton(text = "删除", color = Danger, onClick = onDelete)
        }
    }
}