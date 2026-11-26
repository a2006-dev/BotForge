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
import com.ajx.botforge.ui.viewmodel.PersonaListViewModel

@Composable
fun PersonaListScreen(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit
) {
    val c = botColors
    val vm: PersonaListViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Config.Persona?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg1)
    ) {
        PageTopBar(
            title = Txt.PERSONA_TITLE,
            subtitle = if (state.personas.isEmpty()) null else String.format(Txt.LIST_COUNT_PERSONA_FMT, state.personas.size),
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
                            title = Txt.PERSONA_EMPTY_TITLE,
                            description = Txt.PERSONA_EMPTY_DESC,
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
                        text = Txt.PERSONA_NEW,
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
            title = Txt.PERSONA_DELETE_TITLE,
            message = if (refs > 0) {
                String.format(Txt.CONFIRM_REF_PERSONA_FMT, persona.name, refs)
            } else {
                String.format(Txt.CONFIRM_DELETE_PERSONA, persona.name)
            },
            confirmText = Txt.DELETE,
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
    val c = botColors
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persona.name.ifBlank { Txt.PERSONA_UNTITLED },
                    color = c.textHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = String.format(
                        Txt.PERSONA_SUMMARY_FMT,
                        persona.address.ifBlank { Txt.NONE },
                        persona.temperature.toString(),
                        persona.maxTokens
                    ),
                    color = c.textMid,
                    fontSize = FontSize.caption
                )
            }
            if (refCount > 0) {
                StatusBadge(text = String.format(Txt.PERSONA_REF_FMT, refCount), tone = StatusTone.Info)
            } else {
                StatusBadge(text = Txt.UNUSED, tone = StatusTone.Neutral)
            }
        }

        if (persona.prompt.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = persona.prompt,
                color = c.textLow,
                fontSize = FontSize.small,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (persona.memoryEnabled) {
                    String.format(Txt.PERSONA_MEMORY_FMT, persona.memoryDepth)
                } else {
                    "记忆关闭"
                },
                color = c.textMid,
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