package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

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
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.viewmodel.ModelListViewModel

@Composable
fun ModelListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit
) {
    val c = botColors
    val vm: ModelListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Model?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg1)
    ) {
        PageTopBar(
            title = Txt.MODEL_TITLE,
            subtitle = if (state.models.isEmpty()) null else String.format(Txt.LIST_COUNT_MODEL_FMT, state.models.size),
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
                            title = Txt.MODEL_EMPTY_TITLE,
                            description = Txt.MODEL_EMPTY_DESC,
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
                        text = Txt.MODEL_NEW,
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
            title = Txt.MODEL_DELETE_TITLE,
            message = if (refs > 0) {
                String.format(Txt.CONFIRM_REF_MODEL_FMT, model.name, refs)
            } else {
                String.format(Txt.CONFIRM_DELETE_MODEL, model.name)
            },
            confirmText = Txt.DELETE,
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
    val c = botColors
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name.ifBlank { model.model },
                    color = c.textHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = model.model,
                    color = c.textMid,
                    fontSize = FontSize.small
                )
            }
            if (refCount > 0) {
                StatusBadge(text = String.format(Txt.PERSONA_REF_FMT, refCount), tone = StatusTone.Info)
            } else {
                StatusBadge(text = Txt.UNUSED, tone = StatusTone.Neutral)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = model.baseUrl,
            color = c.textLow,
            fontSize = FontSize.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format(Txt.MODEL_SUMMARY_FMT, model.type, model.timeout, model.maxTokens),
                color = c.textMid,
                fontSize = FontSize.caption
            )
        }

        // 密钥状态：只显示是否已配置，永不明文回显
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (model.apiKey.isEmpty()) Txt.MODEL_KEY_MISSING else Txt.MODEL_KEY_SAVED,
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
            SmallButton(text = Txt.EDIT, primary = true, onClick = onEdit)
            Spacer(Modifier.width(Spacing.md))
            TextButton(text = Txt.DELETE, color = c.danger, onClick = onDelete)
        }
    }
}