package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.ajx.botforge.ui.components.FormField
import com.ajx.botforge.ui.components.MultilineField
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TextButton
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.launch

/**
 * 人设编辑页
 *
 * 字段与 Config.Persona 一一对应，不新增、不省略：
 * prompt / tone / address / memoryEnabled / memoryDepth / temperature / maxTokens
 */
@Composable
fun PersonaEditScreen(
    personaId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val c = botColors
    val repo = remember { BotForgeRepository() }
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("你") }
    var memoryEnabled by remember { mutableStateOf(true) }
    var memoryDepth by remember { mutableStateOf("20") }
    var temperature by remember { mutableStateOf("0.8") }
    var maxTokens by remember { mutableStateOf("400") }

    val isNew = personaId.isEmpty()

    LaunchedEffect(personaId) {
        if (personaId.isNotEmpty()) {
            try {
                val p = repo.findPersona(personaId)
                if (p == null) {
                    error = "人设不存在"
                } else {
                    name = p.name
                    prompt = p.prompt
                    tone = p.tone
                    address = p.address
                    memoryEnabled = p.memoryEnabled
                    memoryDepth = p.memoryDepth.toString()
                    temperature = p.temperature.toString()
                    maxTokens = p.maxTokens.toString()
                }
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            }
        }
    }

    val canSave = name.isNotBlank() && prompt.isNotBlank() && !saving

    fun doSave() {
        if (!canSave) return
        saving = true
        error = null
        scope.launch {
            try {
                val id = if (personaId.isNotEmpty()) personaId else repo.newId("persona")
                repo.savePersona(
                    Config.Persona(
                        id = id,
                        name = name.trim(),
                        prompt = prompt,
                        tone = tone.trim(),
                        address = address.trim().ifEmpty { "你" },
                        memoryEnabled = memoryEnabled,
                        memoryDepth = (memoryDepth.toIntOrNull() ?: 20).coerceIn(0, 200),
                        temperature = (temperature.toFloatOrNull() ?: 0.8f).coerceIn(0f, 2f),
                        maxTokens = (maxTokens.toIntOrNull() ?: 400).coerceIn(32, 32000)
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

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = if (isNew) Txt.PERSONA_NEW else Txt.PERSONA_EDIT,
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

            SectionHeader("基本设定")
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "人设名称",
                value = name,
                onValueChange = { name = it },
                placeholder = "例如：毒舌助手"
            )
            Spacer(Modifier.height(Spacing.md))
            MultilineField(
                label = Txt.PERSONA_PROMPT_LABEL,
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "描述它的身份、性格、说话方式与边界……",
                helper = Txt.FIELD_PROMPT_HELP
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "语气风格",
                value = tone,
                onValueChange = { tone = it },
                placeholder = "例如：简短、直接、不说客套话",
                helper = "可选，会附加在 prompt 之后"
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "自称/称呼",
                value = address,
                onValueChange = { address = it },
                placeholder = "你"
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("记忆")
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { memoryEnabled = !memoryEnabled }
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用上下文记忆",
                        color = c.textHigh,
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "关闭后每次对话都是全新会话",
                        color = c.textLow,
                        fontSize = FontSize.caption
                    )
                }
                StatusBadge(
                    text = if (memoryEnabled) Txt.ON else Txt.OFF,
                    tone = if (memoryEnabled) StatusTone.Success else StatusTone.Neutral
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "记忆深度（条）",
                value = memoryDepth,
                onValueChange = { memoryDepth = it.filter { c -> c.isDigit() } },
                placeholder = "20",
                keyboardType = KeyboardType.Number,
                helper = "每次请求携带最近多少条历史"
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("生成参数")
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "temperature",
                value = temperature,
                onValueChange = { temperature = it.filter { c -> c.isDigit() || c == '.' } },
                placeholder = "0.8",
                keyboardType = KeyboardType.Decimal,
                helper = Txt.FIELD_TEMP_HELP
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "maxTokens",
                value = maxTokens,
                onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                placeholder = "400",
                keyboardType = KeyboardType.Number,
                helper = Txt.FIELD_TOKENS_HELP
            )

            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = if (isNew) Txt.PERSONA_CREATE else Txt.BOT_SAVE,
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
                loading = saving,
                onClick = { doSave() }
            )
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                text = Txt.CANCEL,
                modifier = Modifier.fillMaxWidth(),
                color = c.textMid,
                onClick = onBack
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}