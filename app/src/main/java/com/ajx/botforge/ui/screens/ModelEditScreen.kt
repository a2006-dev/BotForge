package com.ajx.botforge.ui.screens

import com.ajx.botforge.ui.text.Txt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ai.ApiPool
import com.ajx.botforge.ai.ModelProbe
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // ---- 连通性测试 ----
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ModelProbe.PingResult?>(null) }

    // ---- 拉取可选模型 ----
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<ModelProbe.ModelInfo>>(emptyList()) }
    var fetchMsg by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    // ---- API 池 ----
    var pool by remember { mutableStateOf<List<Config.PoolEntry>>(emptyList()) }

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
                    pool = m.pool
                }
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            }
        }
    }

    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && !saving

    /** 把当前界面上的编辑值拼成一个临时模型，供测试/拉列表使用。 */
    fun draftModel(): Config.Model = Config.Model(
        id = if (modelId.isNotEmpty()) modelId else "draft",
        name = name.ifBlank { "未命名" },
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = if (apiKey.isNotEmpty()) apiKey.trim() else "",
        model = model.trim(),
        type = type,
        timeout = (timeout.toIntOrNull() ?: 120).coerceIn(5, 600),
        maxTokens = (maxTokens.toIntOrNull() ?: 400).coerceIn(32, 32000),
        pool = emptyList()
    )

    /**
     * 连通性测试。
     *
     * 刚新建、还没填密钥时用已保存的那把，避免用户改个名字就测不通。
     */
    fun doTest() {
        if (testing) return
        testing = true
        testResult = null
        scope.launch {
            val draft = draftModel()
            val effective = if (draft.apiKey.isEmpty()) {
                withContext(Dispatchers.IO) {
                    if (modelId.isNotEmpty()) repo.findModel(modelId)?.apiKey.orEmpty() else ""
                }
            } else draft.apiKey
            val r = withContext(Dispatchers.IO) {
                ModelProbe.ping(draft.copy(apiKey = effective))
            }
            testResult = r
            testing = false
        }
    }

    /** 向服务端要一份可用模型清单。 */
    fun doFetch() {
        if (fetching) return
        fetching = true
        fetchMsg = null
        fetched = emptyList()
        showPicker = false
        scope.launch {
            val draft = draftModel()
            val effective = if (draft.apiKey.isEmpty()) {
                withContext(Dispatchers.IO) {
                    if (modelId.isNotEmpty()) repo.findModel(modelId)?.apiKey.orEmpty() else ""
                }
            } else draft.apiKey
            val r = withContext(Dispatchers.IO) {
                ModelProbe.listModels(draft.copy(apiKey = effective))
            }
            fetched = r.models
            fetchMsg = r.message
            showPicker = r.ok && r.models.isNotEmpty()
            fetching = false
        }
    }

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
                        maxTokens = (maxTokens.toIntOrNull() ?: 400).coerceIn(32, 32000),
                        pool = pool
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
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                text = if (fetching) Txt.MP_FETCHING else Txt.MP_FETCH_BTN,
                onClick = { if (!fetching) doFetch() },
                color = c.brand
            )
            if (fetchMsg != null) {
                Spacer(Modifier.height(Spacing.xs))
                StatusBadge(
                    text = fetchMsg!!,
                    tone = if (fetched.isEmpty()) StatusTone.Warning else StatusTone.Success
                )
            }
            if (showPicker && fetched.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = Txt.MP_PICK_HINT,
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
                Spacer(Modifier.height(Spacing.xs))
                fetched.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = info.id
                                showPicker = false
                            }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = info.id,
                            color = if (info.id == model) c.brand else c.textHigh,
                            fontSize = FontSize.caption,
                            fontWeight = if (info.id == model) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (info.display != info.id) {
                            Text(
                                text = info.display,
                                color = c.textLow,
                                fontSize = FontSize.caption
                            )
                        }
                    }
                }
            }

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

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("连通性")
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    text = if (testing) Txt.MP_TESTING else Txt.MP_TEST_BTN,
                    onClick = { if (!testing) doTest() },
                    color = c.brand
                )
                Spacer(Modifier.width(Spacing.sm))
                testResult?.let { r ->
                    StatusBadge(
                        text = r.message,
                        tone = if (r.ok) StatusTone.Success else StatusTone.Danger
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader(Txt.MP_SECTION_POOL)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = Txt.MP_POOL_HINT,
                color = c.textLow,
                fontSize = FontSize.caption
            )
            Spacer(Modifier.height(Spacing.sm))
            // 主配置行，固定第一条，只展示不删除
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bg2, RoundedCornerShape(Radius.sm))
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Txt.MP_POOL_MAIN,
                    color = c.textHigh,
                    fontSize = FontSize.caption,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = model.ifBlank { "—" },
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            if (pool.isEmpty()) {
                Text(
                    text = Txt.MP_POOL_EMPTY,
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }
            pool.forEachIndexed { index, entry ->
                val eid = entry.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm)
                        .background(c.bg2, RoundedCornerShape(Radius.sm))
                        .padding(Spacing.sm)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "#${index + 1}  " + (entry.note.ifBlank { Txt.MP_POOL_ADD }),
                            color = c.textHigh,
                            fontSize = FontSize.caption,
                            modifier = Modifier.weight(1f)
                        )
                        val fails = ApiPool.failCount(
                            if (modelId.isNotEmpty()) modelId else "draft", eid
                        )
                        StatusBadge(
                            text = if (fails == 0) Txt.MP_POOL_HEALTHY else "${Txt.MP_POOL_FAILS} $fails",
                            tone = if (fails == 0) StatusTone.Success else StatusTone.Warning
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        TextButton(
                            text = Txt.MP_POOL_DEL,
                            color = c.danger,
                            onClick = { pool = pool.filterNot { it.id == eid } }
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    PoolField(
                        label = Txt.MP_POOL_KEY,
                        value = entry.apiKey,
                        placeholder = Txt.MP_POOL_INHERIT,
                        onChange = { v ->
                            pool = pool.map { if (it.id == eid) it.copy(apiKey = v) else it }
                        }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    PoolField(
                        label = Txt.MP_POOL_BASEURL,
                        value = entry.baseUrl,
                        placeholder = Txt.MP_POOL_INHERIT,
                        onChange = { v ->
                            pool = pool.map { if (it.id == eid) it.copy(baseUrl = v) else it }
                        }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    PoolField(
                        label = Txt.MP_POOL_MODEL,
                        value = entry.model,
                        placeholder = Txt.MP_POOL_INHERIT,
                        onChange = { v ->
                            pool = pool.map { if (it.id == eid) it.copy(model = v) else it }
                        }
                    )
                }
            }
            TextButton(
                text = Txt.MP_POOL_ADD,
                color = c.brand,
                onClick = {
                    pool = pool + Config.PoolEntry(
                        id = "p" + System.currentTimeMillis().toString().takeLast(6)
                    )
                }
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

/**
 * API 池内的单个输入框。
 *
 * 与 FormField 的区别：这里不显示 label 上方的整行排版，做成紧凑一行，
 * 因为池条目可能有好几条，纵向空间要省。
 */
@Composable
private fun PoolField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit
) {
    val c = botColors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.caption,
            modifier = Modifier.width(72.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.textHigh, fontSize = FontSize.caption),
            cursorBrush = SolidColor(c.brand),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bg1, RoundedCornerShape(Radius.xs))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = c.textLow,
                            fontSize = FontSize.caption
                        )
                    }
                    inner()
                }
            }
        )
    }
}
