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
import com.ajx.botforge.ui.viewmodel.ModelListViewModel

@Composable
fun ModelListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit
) {
    val vm: ModelListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Model?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(
            title = "模型",
            subtitle = if (state.models.isEmpty()) null else "${state.models.size} 个",
            onBack = onBack
        )

        when {
            state.loading && state.models.isEmpty() -> LoadingState()
            state.error != null && state.models.isEmpty() ->
                ErrorState(message = state.error!!, onRetry = { vm.refresh() })
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (state.models.isEmpty()) {
                    item {
                        EmptyState(
                            title = "还没有模型配置",
                            description = "支持本机反代、官方 API 与本地模型，统一走 OpenAI 兼容协议",
                            icon = "◈"
                        )
                    }
                }

                items(state.models, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        refCount = state.usage[model.id] ?: 0,
                        onEdit = { onEdit(model.id) },
                        onDelete = { pendingDelete = model }
                    )
                }

                item {
                    Spacer(Modifier.height(Spacing.sm))
                    PrimaryButton(
                        text = "新建模型",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(null) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { model ->
        val refs = state.usage[model.id] ?: 0
        ConfirmDialog(
            title = "删除模型",
            message = if (refs > 0) {
                "「${model.name}」正被 $refs 个机器人使用，删除后这些机器人将无法调用模型。"
            } else {
                "确定删除「${model.name}」？此操作不可撤销。"
            },
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.delete(model.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ModelCard(
    model: Config.Model,
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
                    text = model.name.ifBlank { model.model },
                    color = TextHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = model.model,
                    color = TextMid,
                    fontSize = FontSize.small
                )
            }
            if (refCount > 0) {
                StatusBadge(text = "$refCount 个机器人", tone = StatusTone.Info)
            } else {
                StatusBadge(text = "未使用", tone = StatusTone.Neutral)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = model.baseUrl,
            color = TextLow,
            fontSize = FontSize.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "协议 ${model.type} · 超时 ${model.timeout}s · 上限 ${model.maxTokens} 字",
                color = TextMid,
                fontSize = FontSize.caption
            )
        }

        // 密钥状态：只显示是否已配置，永不明文回显
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (model.apiKey.isEmpty()) "密钥未配置" else "密钥已保存",
                color = if (model.apiKey.isEmpty()) {
                    StatusTone.Warning.fg
                } else {
                    StatusTone.Success.fg
                },
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