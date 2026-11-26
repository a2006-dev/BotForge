package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.ajx.botforge.data.Config
import com.ajx.botforge.ui.components.BotDialog
import com.ajx.botforge.ui.components.ConfirmDialog
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.FormField
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.launch

/**
 * 群配置页
 *
 * 业务事实：群以 groupOpenId 为唯一键（旧版 GroupListActivity 的编辑逻辑就是
 * 按 openId indexOfFirst 后 copy 替换），因此这里沿用同一套规则。
 */
@Composable
fun GroupListScreen(
    botId: String,
    onBack: () -> Unit
) {
    val c = botColors
    val repo = remember { BotForgeRepository() }
    val scope = rememberCoroutineScope()

    var bot by remember { mutableStateOf<Config.Bot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Config.GroupCfg?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Config.GroupCfg?>(null) }

    fun reload() {
        scope.launch {
            try {
                bot = repo.findBot(botId)
                error = null
            } catch (e: Exception) {
                error = e.message ?: Txt.READ_FAILED
            }
        }
    }

    LaunchedEffect(botId) { reload() }

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = Txt.GROUP_TITLE,
            subtitle = bot?.name,
            onBack = onBack
        )

        if (error != null) {
            Text(
                text = error!!,
                color = c.textMid,
                fontSize = FontSize.caption,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }

        val groups = bot?.groups.orEmpty()

        if (groups.isEmpty()) {
            EmptyState(
                title = Txt.GROUP_EMPTY_TITLE,
                description = Txt.GROUP_EMPTY_DESC,
                icon = "＋",
                modifier = Modifier.weight(1f),
                action = {
                    PrimaryButton(
                        text = Txt.GROUP_ADD,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            editing = null
                            showEditor = true
                        }
                    )
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Spacing.md,
                    vertical = Spacing.sm
                )
            ) {
                items(groups, key = { it.groupOpenId }) { g ->
                    GroupCard(
                        group = g,
                        onEdit = {
                            editing = g
                            showEditor = true
                        },
                        onDelete = { pendingDelete = g },
                        onToggle = {
                            val list = groups.map {
                                if (it.groupOpenId == g.groupOpenId) it.copy(enabled = !it.enabled) else it
                            }
                            scope.launch {
                                repo.saveGroups(botId, list)
                                reload()
                            }
                        }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }

        if (groups.isNotEmpty()) {
            PrimaryButton(
                text = "添加群",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                onClick = {
                    editing = null
                    showEditor = true
                }
            )
        }
    }

    if (showEditor) {
        GroupEditorDialog(
            botId = botId,
            existing = editing,
            onDismiss = { showEditor = false },
            onSaved = {
                showEditor = false
                reload()
            }
        )
    }

    pendingDelete?.let { g ->
        ConfirmDialog(
            title = Txt.GROUP_DELETE_TITLE,
            message = String.format(Txt.CONFIRM_DELETE_GROUP, g.note.ifBlank { g.groupOpenId }),
            confirmText = Txt.DELETE,
            danger = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val list = bot?.groups.orEmpty().filter { it.groupOpenId != g.groupOpenId }
                pendingDelete = null
                scope.launch {
                    repo.saveGroups(botId, list)
                    reload()
                }
            }
        )
    }
}

@Composable
private fun GroupCard(
    group: Config.GroupCfg,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.md))
            .padding(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.note.ifBlank { Txt.GROUP_UNTITLED },
                    color = c.textHigh,
                    fontSize = FontSize.body,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = group.groupOpenId,
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
            StatusBadge(
                text = if (group.enabled) Txt.ENABLED else Txt.DISABLED,
                tone = if (group.enabled) StatusTone.Success else StatusTone.Neutral
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
            StatusBadge(
                text = if (group.requireAt) Txt.STATE_NEED_AT else Txt.STATE_NO_AT,
                tone = if (group.requireAt) StatusTone.Info else StatusTone.Neutral
            )
            StatusBadge(
                text = String.format(Txt.GROUP_COOLDOWN_FMT, group.cooldownSec),
                tone = StatusTone.Neutral
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
            SmallButton(text = Txt.EDIT, onClick = onEdit)
            SmallButton(text = if (group.enabled) Txt.DISABLE else Txt.ENABLE, primary = true, onClick = onToggle)
            SmallButton(text = Txt.DELETE, onClick = onDelete)
        }
    }
}

@Composable
private fun GroupEditorDialog(
    botId: String,
    existing: Config.GroupCfg?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val c = botColors
    val repo = remember { BotForgeRepository() }
    val scope = rememberCoroutineScope()

    var openId by remember { mutableStateOf(existing?.groupOpenId ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var requireAt by remember { mutableStateOf(existing?.requireAt ?: false) }
    var cooldown by remember { mutableStateOf((existing?.cooldownSec ?: 5).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun submit() {
        val oid = openId.trim()
        if (oid.isEmpty()) {
            error = Txt.GROUP_OPENID_REQUIRED
            return
        }
        val cd = cooldown.trim().toIntOrNull() ?: 5
        saving = true
        scope.launch {
            try {
                val b = repo.findBot(botId)
                val gs = b?.groups.orEmpty().toMutableList()
                val item = Config.GroupCfg(
                    groupOpenId = oid,
                    note = note.trim(),
                    enabled = enabled,
                    requireAt = requireAt,
                    cooldownSec = cd.coerceAtLeast(0)
                )
                val idx = gs.indexOfFirst { it.groupOpenId == oid }
                if (idx >= 0) gs[idx] = item else gs.add(item)
                repo.saveGroups(botId, gs)
                saving = false
                onSaved()
            } catch (e: Exception) {
                saving = false
                error = e.message ?: Txt.SAVE_FAILED
            }
        }
    }

    BotDialog(onDismiss = onDismiss) {
        Text(
            text = if (existing == null) Txt.GROUP_ADD else Txt.GROUP_EDIT,
            color = c.textHigh,
            fontSize = FontSize.title,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(Spacing.md))

        FormField(
            label = Txt.GROUP_OPENID_LABEL,
            value = openId,
            onValueChange = { openId = it },
            placeholder = Txt.GROUP_OPENID_PLACEHOLDER,
            helper = Txt.FIELD_GROUPID_HELP
        )
        Spacer(Modifier.height(Spacing.md))
        FormField(
            label = Txt.GROUP_NOTE_LABEL,
            value = note,
            onValueChange = { note = it },
            placeholder = Txt.GROUP_NOTE_PLACEHOLDER
        )
        Spacer(Modifier.height(Spacing.md))
        FormField(
            label = Txt.GROUP_COOLDOWN_LABEL,
            value = cooldown,
            onValueChange = { cooldown = it.filter { c -> c.isDigit() } },
            placeholder = Txt.GROUP_COOLDOWN_PLACEHOLDER,
            keyboardType = KeyboardType.Number,
            helper = Txt.FIELD_COOLDOWN_HELP
        )

        Spacer(Modifier.height(Spacing.md))
        ToggleLine(Txt.GROUP_TOGGLE_ENABLED, enabled) { enabled = it }
        ToggleLine(Txt.GROUP_TOGGLE_REQUIRE_AT, requireAt) { requireAt = it }

        if (error != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(text = error!!, color = c.textMid, fontSize = FontSize.caption)
        }

        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                text = Txt.CANCEL,
                modifier = Modifier.weight(1f),
                onClick = onDismiss
            )
            PrimaryButton(
                text = Txt.SAVE,
                modifier = Modifier.weight(1f),
                loading = saving,
                enabled = !saving,
                onClick = { submit() }
            )
        }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = botColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = c.textHigh, fontSize = FontSize.body)
        Spacer(Modifier.weight(1f))
        StatusBadge(
            text = if (checked) Txt.ENABLED else Txt.DISABLED,
            tone = if (checked) StatusTone.Success else StatusTone.Neutral
        )
    }
}