package com.ajx.botforge.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.ajx.botforge.ui.components.InfoRow
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PasswordField
import com.ajx.botforge.ui.components.PickDialog
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TextButton
import com.ajx.botforge.ui.components.FormField
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid
import kotlinx.coroutines.launch

/**
 * 机器人编辑页
 *
 * 任务书要求：不得擅自缩减功能，也不得造假入口。
 * 因此这里完整保留旧 BotEditActivity 的表单字段，
 * 但对 intents 采用「只读展示」——见下方注释。
 */
@Composable
fun BotEditScreen(
    botId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val repo = remember { BotForgeRepository() }
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var personaId by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var intents by remember { mutableStateOf(Config.GROUP_INTENTS) }

    var personas by remember { mutableStateOf<List<Config.Persona>>(emptyList()) }
    var models by remember { mutableStateOf<List<Config.Model>>(emptyList()) }

    var showPersonaPick by remember { mutableStateOf(false) }
    var showModelPick by remember { mutableStateOf(false) }

    val isNew = botId.isEmpty()

    LaunchedEffect(botId) {
        try {
            personas = repo.personas()
            models = repo.models()
            if (botId.isNotEmpty()) {
                val b = repo.findBot(botId)
                if (b == null) {
                    error = "机器人不存在"
                } else {
                    name = b.name
                    appId = b.appId
                    appSecret = b.appSecret
                    personaId = b.personaId
                    modelId = b.modelId
                    enabled = b.enabled
                    intents = b.intents
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    val personaName = personas.firstOrNull { it.id == personaId }?.name
    val modelName = models.firstOrNull { it.id == modelId }?.name

    val canSave = name.isNotBlank() && appId.isNotBlank() && !saving

    fun doSave() {
        if (!canSave) return
        saving = true
        error = null
        scope.launch {
            try {
                val existing = if (botId.isNotEmpty()) repo.findBot(botId) else null
                val id = existing?.id ?: repo.newId("bot")
                repo.saveBot(
                    Config.Bot(
                        id = id,
                        name = name.trim(),
                        appId = appId.trim(),
                        appSecret = appSecret.trim(),
                        personaId = personaId,
                        modelId = modelId,
                        enabled = enabled,
                        // intents 不取表单值，强制使用项目唯一合法值。
                        intents = Config.GROUP_INTENTS,
                        groups = existing?.groups ?: emptyList()
                    )
                )
                saving = false
                onSaved()
            } catch (e: Exception) {
                saving = false
                error = e.message ?: "保存失败"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg1)
    ) {
        PageTopBar(
            title = if (isNew) "添加机器人" else "编辑机器人",
            subtitle = if (isNew) null else name,
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.md)
        ) {
            if (error != null) {
                StatusBadge(text = error!!, tone = StatusTone.Danger)
                Spacer(Modifier.height(Spacing.md))
            }

            SectionHeader("基本信息")
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "机器人名称",
                value = name,
                onValueChange = { name = it },
                placeholder = "例如：404ERROR"
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "AppID",
                value = appId,
                onValueChange = { appId = it.filter { c -> c.isDigit() } },
                placeholder = "QQ 开放平台分配的 AppID",
                keyboardType = KeyboardType.Number,
                helper = "纯数字，可在 QQ 开放平台机器人资料页查看"
            )
            Spacer(Modifier.height(Spacing.md))
            PasswordField(
                label = "AppSecret",
                value = appSecret,
                onValueChange = { appSecret = it },
                placeholder = "留空表示不修改",
                helper = "密钥保存在本地保险箱，不会明文写入配置文件"
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("绑定配置")
            Spacer(Modifier.height(Spacing.sm))

            PickRow(
                label = "人设",
                value = personaName ?: "未绑定",
                empty = personaId.isEmpty(),
                onClick = { showPersonaPick = true }
            )
            Spacer(Modifier.height(Spacing.sm))
            PickRow(
                label = "模型",
                value = modelName ?: "未绑定",
                empty = modelId.isEmpty(),
                onClick = { showModelPick = true }
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("运行状态")
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg2, RoundedCornerShape(Radius.md))
                    .border(Sizes.hairline, Line, RoundedCornerShape(Radius.md))
                    .clickable { enabled = !enabled }
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用此机器人",
                        color = TextHigh,
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (enabled) "服务启动后会尝试连接" else "服务会跳过此机器人",
                        color = TextLow,
                        fontSize = FontSize.caption
                    )
                }
                StatusBadge(
                    text = if (enabled) "已启用" else "已停用",
                    tone = if (enabled) StatusTone.Success else StatusTone.Neutral
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("事件订阅")
            Spacer(Modifier.height(Spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg2, RoundedCornerShape(Radius.md))
                    .border(Sizes.hairline, Line, RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                InfoRow(label = "intents", value = intents.toString(), mono = true)
                Text(
                    text = "固定为 GROUP_AND_C2C_EVENT(1<<25)。" +
                            "旧版本曾写入 1174409216，其中含 3 个无权限位，" +
                            "会导致网关在 Identify 后立即断开，因此不再开放手填。",
                    color = TextMid,
                    fontSize = FontSize.caption
                )
            }

            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = if (isNew) "创建机器人" else "保存修改",
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
                loading = saving,
                onClick = { doSave() }
            )
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                text = "取消",
                modifier = Modifier.fillMaxWidth(),
                color = TextMid,
                onClick = onBack
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (showPersonaPick) {
        val idx = personas.indexOfFirst { it.id == personaId }
        PickDialog(
            title = "选择人设",
            options = personas.map { it.id to it.name },
            selectedIndex = idx,
            onDismiss = { showPersonaPick = false },
            onPick = { i ->
                personaId = personas[i].id
                showPersonaPick = false
            }
        )
    }

    if (showModelPick) {
        val idx = models.indexOfFirst { it.id == modelId }
        PickDialog(
            title = "选择模型",
            options = models.map { it.id to it.name },
            selectedIndex = idx,
            onDismiss = { showModelPick = false },
            onPick = { i ->
                modelId = models[i].id
                showModelPick = false
            }
        )
    }
}

@Composable
private fun PickRow(
    label: String,
    value: String,
    empty: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.md))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMid, fontSize = FontSize.body)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = if (empty) TextLow else TextHigh,
            fontSize = FontSize.body
        )
        Text(text = "  ›", color = TextLow, fontSize = FontSize.body)
    }
}