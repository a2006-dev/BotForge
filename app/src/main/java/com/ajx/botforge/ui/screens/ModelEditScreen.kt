package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.ajx.botforge.ui.components.FormField
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PasswordField
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.StatusBadge
import com.ajx.botforge.ui.components.StatusTone
import com.ajx.botforge.ui.components.TextButton
import com.ajx.botforge.ui.data.BotForgeRepository
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.launch

/** 三类接口协议，与 Config.Model.type 注释里的取值一致。 */
private val MODEL_TYPES = listOf(
    "openai" to "OpenAI 兼容",
    "anthropic" to "Anthropic",
    "llama" to "llama.cpp"
)

/**
 * 模型编辑页
 *
 * apiKey 走 SecretStore 保险箱：读出来是真的，但界面上永远打码，
 * 只显示「已保存 / 未配置」，避免明文回显。
 */
@Composable
fun ModelEditScreen(
    modelId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val c = botColors
    val repo = remember { BotForgeRepository() }
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("openai") }
    var timeout by remember { mutableStateOf("120") }
    var maxTokens by remember { mutableStateOf("400") }

    val isNew = modelId.isEmpty()

    LaunchedEffect(modelId) {
        if (modelId.isNotEmpty()) {
            try {
                val m = repo.findModel(modelId)
                if (m == null) {
                    error = "模型不存在"
                } else {
                    name = m.name
                    baseUrl = m.baseUrl
                    hasKey = m.apiKey.isNotEmpty()
                    // 密钥不在输入框里回显，用户不填就沿用原值。
                    apiKey = ""
                    model = m.model
                    type = m.type
                    timeout = m.timeout.toString()
                    maxTokens = m.maxTokens.toString()
                }
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            }
        }
    }

    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && !saving

    fun doSave() {
        if (!canSave) return
        saving = true
        error = null
        scope.launch {
            try {
                val id = if (modelId.isNotEmpty()) modelId else repo.newId("model")
                val old = if (modelId.isNotEmpty()) repo.findModel(modelId) else null
                val finalKey = if (apiKey.isNotEmpty()) apiKey.trim() else old?.apiKey.orEmpty()
                repo.saveModel(
                    Config.Model(
                        id = id,
                        name = name.trim(),
                        baseUrl = baseUrl.trim().trimEnd('/'),
                        apiKey = finalKey,
                        model = model.trim(),
                        type = type,
                        timeout = (timeout.toIntOrNull() ?: 120).coerceIn(5, 600),
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
            title = if (isNew) Txt.MODEL_ADD else Txt.MODEL_EDIT,
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

            SectionHeader("接口信息")
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "名称",
                value = name,
                onValueChange = { name = it },
                placeholder = Txt.MODEL_NAME_PLACEHOLDER
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = Txt.MODEL_BASEURL_LABEL,
                value = baseUrl,
                onValueChange = { baseUrl = it },
                placeholder = "http://127.0.0.1:7863/v1",
                keyboardType = KeyboardType.Uri,
                helper = Txt.FIELD_BASEURL_HELP
            )
            Spacer(Modifier.height(Spacing.md))
            PasswordField(
                label = Txt.MODEL_APIKEY_LABEL,
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = if (hasKey) Txt.MODEL_KEY_KEEP else Txt.NOT_SET,
                helper = "保存在本地保险箱，界面不回显明文"
            )
            Spacer(Modifier.height(Spacing.sm))
            StatusBadge(
                text = if (hasKey) Txt.MODEL_KEY_SAVED else Txt.MODEL_KEY_MISSING,
                tone = if (hasKey) StatusTone.Success else StatusTone.Warning
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "模型标识",
                value = model,
                onValueChange = { model = it },
                placeholder = "deepseek-v4.1-flash",
                helper = Txt.FIELD_MODEL_HELP
            )

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("协议类型")
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MODEL_TYPES.forEach { (value, label) ->
                    TypeChip(
                        label = label,
                        selected = type == value,
                        modifier = Modifier.weight(1f),
                        onClick = { type = value }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("参数")
            Spacer(Modifier.height(Spacing.sm))
            FormField(
                label = "超时（秒）",
                value = timeout,
                onValueChange = { timeout = it.filter { c -> c.isDigit() } },
                placeholder = "120",
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(Spacing.md))
            FormField(
                label = "maxTokens",
                value = maxTokens,
                onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                placeholder = "400",
                keyboardType = KeyboardType.Number
            )

            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = if (isNew) Txt.MODEL_ADD else Txt.BOT_SAVE,
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

@Composable
private fun TypeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = botColors
    Column(
        modifier = modifier
            .background(
                if (selected) c.brand.copy(alpha = 0.15f) else c.bg2,
                RoundedCornerShape(Radius.sm)
            )
            .border(
                Sizes.hairline,
                if (selected) c.brand else c.line,
                RoundedCornerShape(Radius.sm)
            )
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (selected) c.brand else c.textLow,
            fontSize = FontSize.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}