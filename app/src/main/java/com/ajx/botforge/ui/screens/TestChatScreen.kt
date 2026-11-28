package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ai.AiEngine
import com.ajx.botforge.ai.tool.AgentLoop
import com.ajx.botforge.ai.tool.BuiltinTools
import com.ajx.botforge.ai.tool.PackageRegistry
import com.ajx.botforge.ai.tool.Tool
import com.ajx.botforge.ai.tool.ToolResult
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.ui.components.AppTextField
import com.ajx.botforge.ui.components.EmptyState
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PickDialog
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次工具调用的可见记录。
 *
 * ## 为什么需要自己记
 *
 * [AgentLoop.RunResult] 只有 `usedTools: List<String>`，也就是「调过哪些工具名」；
 * [AgentLoop.run] 的 `onToolCall` 回调只带 `(name, args)`，拿不到结果，也拿不到耗时。
 * 而试聊页最需要看到的恰恰是结果和耗时。
 *
 * 因此本文件不改 AgentLoop 的公开签名，改用 [RecordingTool] 装饰 [Tool] 接口：
 * 工具仍由 AgentLoop 正常调度，只是多包一层，在 run() 前后取时间戳、
 * 把 [ToolResult] 抄一份进 [ToolSink]。这样 AgentLoop 的单测完全不受影响。
 */
private data class ToolTrace(
    val seq: Int,
    val name: String,
    val argsJson: String,
    val success: Boolean,
    val output: String,
    val error: String?,
    val durationMs: Long,
    val note: String
)

/** 试聊页的一条对话。ai 角色带工具轨迹与耗时。 */
private data class ChatTurn(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val traces: List<ToolTrace> = emptyList(),
    val elapsedMs: Long = 0L,
    val errorDetail: String? = null
)

/** AgentLoop.run 一次调用的产物，包含异常字符串（便于 UI 直接显示）。 */
private data class SendOutcome(
    val reply: AgentLoop.RunResult?,
    val elapsedMs: Long,
    val traces: List<ToolTrace>,
    val thrown: String?
)

/**
 * 线程安全的工具调用收集器。
 *
 * AgentLoop.run 全程同步阻塞，工具在调用方线程里执行，所以这里用
 * `@Synchronized` 保护即可，不需要协程同步原语。
 */
private class ToolSink {

    private val items = ArrayList<ToolTrace>()
    private var next = 1

    @Synchronized
    fun record(name: String, argsJson: String, result: ToolResult, durationMs: Long) {
        items.add(
            ToolTrace(
                seq = next,
                name = name,
                argsJson = argsJson,
                success = result.success,
                output = result.content,
                error = result.error,
                durationMs = durationMs,
                note = noteOf(result)
            )
        )
        next += 1
    }

    /** 取走并清空，保证每条轨迹只出现在一个气泡里。 */
    @Synchronized
    fun drain(): List<ToolTrace> {
        val out = items.toList()
        items.clear()
        return out
    }
}

/**
 * Tool 装饰器：只做记录，不改变任何执行语义。
 *
 * 注意 ASK 档工具在 [PackageRegistry.activeTools] 里已经被换成
 * ToolExecutor 的守卫对象，这里再包一层仍然拿原名字与原 schema，
 * 模型看到的调用方式不变。
 */
private class RecordingTool(
    private val delegate: Tool,
    private val sink: ToolSink
) : Tool {

    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parameters: JSONObject get() = delegate.parameters

    override fun run(args: JSONObject): ToolResult {
        val argsJson = redactArgs(args)
        val started = System.currentTimeMillis()
        val result = try {
            delegate.run(args)
        } catch (e: Throwable) {
            ToolResult.fail("${e.javaClass.simpleName}: ${e.message ?: "无详情"}")
        }
        sink.record(name, argsJson, result, System.currentTimeMillis() - started)
        return result
    }
}

/** 判断这次到底是「真的执行了」「被 ASK 拦了」还是「执行了但失败」。 */
private fun noteOf(result: ToolResult): String {
    if (result.success) return "真实执行成功"
    val content = result.content
    if (content.contains("确认码") || content.contains("需要用户确认")) {
        return "未执行：ASK 权限未授权"
    }
    return "真实执行失败"
}

/** 入参里这些关键词对应的值一律打码，避免密钥出现在截图里。 */
private val SENSITIVE_KEYS = listOf(
    "key", "token", "secret", "password", "passwd", "authorization", "cookie", "credential"
)

private fun redactArgs(args: JSONObject): String {
    return try {
        val copy = JSONObject(args.toString())
        redactObject(copy)
        copy.toString(2)
    } catch (e: Exception) {
        args.toString()
    }
}

private fun redactObject(obj: JSONObject) {
    // 先把 key 搬进临时列表再遍历：org.json 的 keys() 迭代器不允许边走边 put。
    val keyIter = obj.keys()
    val keyList = ArrayList<String>()
    while (keyIter.hasNext()) keyList.add(keyIter.next())
    keyList.forEach { key ->
        when (val value = obj.opt(key)) {
            is JSONObject -> redactObject(value)
            is JSONArray -> redactArray(value)
            is String -> {
                // 变量名不用 it，避免与下面 any{} 的隐式 it 混淆。
                val lowerKey = key.lowercase()
                val sensitive = SENSITIVE_KEYS.any { word -> lowerKey.contains(word) }
                if (sensitive) obj.put(key, "***已脱敏***")
            }
        }
    }
}

private fun redactArray(arr: JSONArray) {
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is JSONObject -> redactObject(v)
            is JSONArray -> redactArray(v)
        }
    }
}

/**
 * 从日志环里捞出本次请求的失败线索。
 *
 * AgentLoop 的 HTTP 分支在非 2xx 时会写
 * `HTTP <code>: <响应前 300 字>`，这正是「HTTP 码 + 响应片段」。
 * 不改 AgentLoop 就拿到这条信息的最短路径就是读它自己的日志。
 */
private fun lastHttpProblem(mark: Int): String? {
    val all = Logger.snapshot()
    val fresh = if (mark in 0..all.size) all.drop(mark) else all
    for (i in fresh.indices.reversed()) {
        val t = fresh[i].text
        if (t.contains("HTTP ") || t.contains("请求失败")) return t
    }
    return null
}

private fun buildSystemPrompt(persona: Config.Persona?, useTools: Boolean): String {
    val sb = StringBuilder()
    if (persona != null) {
        sb.append(persona.prompt)
        if (persona.tone.isNotBlank()) sb.append("\n\n【语气要求】").append(persona.tone)
    } else {
        sb.append("你是一个用于联调测试的助手，回答简洁准确。")
    }
    sb.append("\n\n【输出要求】直接输出回复正文，不要加旁白，不要写角色名。")
    if (useTools) {
        sb.append("\n\n【工具说明】你可以调用工具来真实执行操作，不是只能聊天。")
        sb.append("调用前把参数补全；拿到结果后如实汇报，失败就把真实错误说出来，不要编造结果。")
    }
    return sb.toString()
}

/**
 * 应用内试聊页。
 *
 * ## 它解决什么问题
 *
 * 以前调工具出问题只能靠猜：QQ 里发一句、等回复、翻日志，看不到模型到底传了什么参数、
 * 工具返回了什么、慢在哪一步。这一页把整条链路摊开：选人设 + 模型 + 是否带工具，
 * 直接对话，每次回复下方可以展开完整的工具调用详情。
 *
 * ## 关于「会不会真的执行」
 *
 * 会。这是本页存在的意义——测试一个不执行的工具等于没测。所以：
 *  - 顶部常驻红色警示条，说明工具会真实作用于设备；
 *  - 开启工具需要点两次（第一次只是把按钮换成确认态），避免手滑；
 *  - 每条轨迹都标了「真实执行成功 / 失败 / 未执行（ASK 未授权）」。
 *
 * ## 会话与副作用（诚实说明）
 *
 *  - **不写 Memory**：历史只存在本页的内存列表里，不调用 `Memory.append`，
 *    退出页面即丢弃。所以试聊不会污染 QQ 那边的上下文。
 *  - **会写审计**：`AgentLoop.run` 内部在 finally 里调 `AuditStore.record`，
 *    每执行一个工具落一条审计（botId 固定为 `test_chat`，scene 为 `internal`）。
 *    这是 AgentLoop 的内部行为，不改它的签名就无法关闭；按 botId 可筛出试聊记录。
 *  - **会写运行日志**：Logger 的常规行为。
 */
@Composable
fun TestChatScreen(onBack: () -> Unit) {
    val c = botColors
    val scope = rememberCoroutineScope()

    // 第一次进页面兜底注册内置包，正常流程 App.onCreate 已做过。
    remember { BuiltinTools.ensureRegistered(); 0 }

    val personas = remember { Config.loadPersonas() }
    val models = remember { Config.loadModels() }

    var personaId by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(models.firstOrNull()?.id.orEmpty()) }
    var toolsArmed by remember { mutableStateOf(false) }
    var armPending by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var showPersonaPick by remember { mutableStateOf(false) }
    var showModelPick by remember { mutableStateOf(false) }

    // 只存在内存里的临时会话，绝不落到 Memory。
    val history = remember { mutableStateListOf<AiEngine.Msg>() }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    val selectedModel = models.firstOrNull { it.id == modelId }
    val selectedPersona = personas.firstOrNull { it.id == personaId }
    val toolCount = remember(toolsArmed) {
        if (toolsArmed) PackageRegistry.activeTools().size else 0
    }

    fun push(t: ChatTurn) {
        turns = turns + t
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || running) return
        val model = selectedModel
        if (model == null) {
            push(ChatTurn(role = "ai", text = "还没有可用的模型配置，请先到「模型」页添加一个。", isError = true))
            return
        }
        val persona = selectedPersona
        val useTools = toolsArmed
        input = ""
        push(ChatTurn(role = "user", text = text))
        running = true

        val sink = ToolSink()
        val logMark = Logger.snapshot().size
        val historySnapshot = history.toList()

        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val raw: List<Tool> = if (useTools) PackageRegistry.activeTools() else emptyList()
                    val wrapped = raw.map { RecordingTool(it, sink) }
                    val system = buildSystemPrompt(persona, useTools)
                    val started = System.currentTimeMillis()
                    val r = AgentLoop.run(
                        model = model,
                        system = system,
                        history = historySnapshot,
                        user = text,
                        temperature = persona?.temperature ?: 0.8f,
                        maxTokens = persona?.maxTokens ?: model.maxTokens,
                        tools = wrapped,
                        auditCtx = AgentLoop.AuditContext(
                            botId = "test_chat",
                            scene = "internal",
                            userOpenId = null
                        )
                    )
                    SendOutcome(r, System.currentTimeMillis() - started, sink.drain(), null)
                } catch (e: Throwable) {
                    SendOutcome(
                        null, 0L, sink.drain(),
                        "${e.javaClass.simpleName}: ${e.message ?: "无详情"}"
                    )
                }
            }
            running = false

            if (outcome.thrown != null) {
                push(
                    ChatTurn(
                        role = "ai",
                        text = "调用抛出异常，未拿到回复。",
                        isError = true,
                        traces = outcome.traces,
                        errorDetail = outcome.thrown
                    )
                )
                return@launch
            }

            val reply = outcome.reply
            if (reply == null) {
                push(
                    ChatTurn(
                        role = "ai",
                        text = "模型没有返回内容（请求失败）。",
                        isError = true,
                        traces = outcome.traces,
                        elapsedMs = outcome.elapsedMs,
                        errorDetail = lastHttpProblem(logMark)
                            ?: "没有拿到 HTTP 状态，请到「日志」页看 Agent 标签下的记录。"
                    )
                )
                return@launch
            }

            val answerText = reply.text.ifEmpty { "（本轮模型只做了工具调用，没有给正文）" }
            history.add(AiEngine.Msg("user", text))
            history.add(AiEngine.Msg("assistant", answerText))
            while (history.size > 40) history.removeAt(0)

            push(
                ChatTurn(
                    role = "ai",
                    text = answerText,
                    traces = outcome.traces,
                    elapsedMs = outcome.elapsedMs
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg1)
    ) {
        PageTopBar(
            title = "试聊",
            subtitle = "临时会话 · 不写记忆",
            onBack = onBack,
            actions = {
                SmallButton(
                    text = "清空",
                    enabled = !running,
                    onClick = {
                        turns = emptyList()
                        history.clear()
                    }
                )
            }
        )

        // ---------------- 选择器 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SmallButton(
                text = selectedPersona?.name ?: "不用人设",
                onClick = { showPersonaPick = true }
            )
            SmallButton(
                text = selectedModel?.name ?: "选模型（必选）",
                primary = selectedModel == null,
                onClick = { showModelPick = true }
            )
            Spacer(Modifier.width(Spacing.xs))
            SmallButton(
                text = if (toolsArmed) "工具：开(${toolCount})" else "工具：关",
                primary = toolsArmed,
                enabled = !running,
                onClick = {
                    if (toolsArmed) {
                        toolsArmed = false
                        armPending = false
                    } else if (armPending) {
                        toolsArmed = true
                        armPending = false
                    } else {
                        armPending = true
                    }
                }
            )
        }

        // ---------------- 危险警示 ----------------
        if (toolsArmed || armPending) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .background(c.dangerGhost, RoundedCornerShape(Radius.md))
                    .border(Sizes.hairline, c.danger.copy(alpha = 0.5f), RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Column {
                    Text(
                        text = if (toolsArmed) "⚠ 工具会真的执行" else "⚠ 再点一次确认开启工具",
                        color = c.danger,
                        fontSize = FontSize.small,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = if (toolsArmed) {
                            "模型调用的命令、读写文件会真实作用于这台设备，不是模拟。" +
                                "当前可见 $toolCount 个工具；ASK 档工具会被拦下并返回确认码，不算执行。"
                        } else {
                            "开启后模型可以执行 run_command 等真实动作，可能改动磁盘内容。" +
                                "确认要测就再点一次按钮。"
                        },
                        color = c.textMid,
                        fontSize = FontSize.caption
                    )
                }
            }
        }

        // anthropic 分支在 AgentLoop 里直接退回纯对话，这里必须说清楚，
        // 否则用户会以为是工具没生效。
        if (toolsArmed && selectedModel?.type == "anthropic") {
            Text(
                text = "当前模型是 anthropic 协议，AgentLoop 对这类模型走纯对话分支，工具不会被执行（这是现有实现，本页不绕过）。",
                color = c.warning,
                fontSize = FontSize.caption,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
        }

        // ---------------- 对话流 ----------------
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (turns.isEmpty()) {
                EmptyState(
                    title = "还没有对话",
                    description = "选好模型后直接发消息。开启工具时，每次回复下方能展开工具调用详情：" +
                        "工具名、入参、返回结果、耗时。",
                    icon = "💬",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(turns) { turn ->
                        if (turn.role == "user") {
                            UserBubble(turn.text)
                        } else {
                            AiBubble(turn)
                        }
                    }
                }
            }
        }

        // ---------------- 输入区 ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg2)
                .padding(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    AppTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = "输入测试消息…",
                        singleLine = false,
                        minHeight = 56.dp
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                SmallButton(
                    text = if (running) "运行中" else "发送",
                    primary = true,
                    enabled = !running && input.isNotBlank(),
                    onClick = { send() }
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "试聊不写 Memory（退出即丢）；每个工具调用会落一条审计，" +
                    "botId 标记为 test_chat，可在日志里筛出。",
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }
    }

    // ---------------- 选择弹窗 ----------------
    if (showPersonaPick) {
        val options = listOf("不用人设" to "只用通用助手提示词") +
            personas.map { it.name to "temperature ${it.temperature} · 上限 ${it.maxTokens} tokens" }
        val current = if (selectedPersona == null) 0 else personas.indexOf(selectedPersona) + 1
        PickDialog(
            title = "选择人设（可空）",
            options = options,
            selectedIndex = current,
            onPick = { idx ->
                personaId = if (idx == 0) "" else personas[idx - 1].id
                showPersonaPick = false
            },
            onDismiss = { showPersonaPick = false }
        )
    }

    if (showModelPick) {
        if (models.isEmpty()) {
            PickDialog(
                title = "还没有模型",
                options = listOf("（空）" to "请先到模型页添加"),
                selectedIndex = -1,
                onPick = { showModelPick = false },
                onDismiss = { showModelPick = false }
            )
        } else {
            val options = models.map { it.name to "${it.model} · ${it.type}" }
            val current = models.indexOfFirst { it.id == modelId }
            PickDialog(
                title = "选择模型（必选）",
                options = options,
                selectedIndex = current,
                onPick = { idx ->
                    modelId = models[idx].id
                    showModelPick = false
                },
                onDismiss = { showModelPick = false }
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val c = botColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(c.brand, RoundedCornerShape(Radius.lg))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Text(
                text = text,
                color = c.textInverse,
                fontSize = FontSize.body
            )
        }
    }
}

@Composable
private fun AiBubble(turn: ChatTurn) {
    val c = botColors
    var expanded by remember(turn) { mutableStateOf(false) }
    val hasTraces = turn.traces.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(
                Sizes.hairline,
                if (turn.isError) c.danger.copy(alpha = 0.6f) else c.line,
                RoundedCornerShape(Radius.lg)
            )
            .padding(Spacing.md)
    ) {
        Text(
            text = turn.text,
            color = if (turn.isError) c.danger else c.textHigh,
            fontSize = FontSize.body
        )

        if (turn.errorDetail != null) {
            Spacer(Modifier.height(Spacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.dangerGhost, RoundedCornerShape(Radius.sm))
                    .padding(Spacing.sm)
            ) {
                Column {
                    Text(
                        text = "失败详情",
                        color = c.danger,
                        fontSize = FontSize.caption,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = turn.errorDetail,
                        color = c.textMid,
                        fontSize = FontSize.caption,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (hasTraces) {
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (if (expanded) "▾ " else "▸ ") + "工具调用 ${turn.traces.size} 次",
                    color = c.brand,
                    fontSize = FontSize.small,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "合计 " + turn.traces.sumOf { it.durationMs } + " ms",
                    color = c.textLow,
                    fontSize = FontSize.caption
                )
            }

            if (expanded) {
                turn.traces.forEach { trace ->
                    ToolTraceCard(trace)
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }

        if (turn.elapsedMs > 0L) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "本轮总耗时 " + turn.elapsedMs + " ms" + if (hasTraces) "（含 " + turn.traces.size + " 次工具调用）" else "",
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }
    }
}

@Composable
private fun ToolTraceCard(trace: ToolTrace) {
    val c = botColors
    val toneColor = when {
        !trace.success -> c.danger
        else -> c.success
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg3, RoundedCornerShape(Radius.sm))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.sm))
            .padding(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#" + trace.seq + " " + trace.name,
                color = c.textHigh,
                fontSize = FontSize.small,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = trace.note,
                color = toneColor,
                fontSize = FontSize.micro
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = trace.durationMs.toString() + " ms",
                color = if (trace.durationMs > 3000L) c.warning else c.textMid,
                fontSize = FontSize.micro,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(text = "入参", color = c.textLow, fontSize = FontSize.micro)
        Spacer(Modifier.height(Spacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .background(c.bg1, RoundedCornerShape(Radius.xs))
                .padding(Spacing.sm)
        ) {
            Text(
                text = trace.argsJson,
                color = c.textMid,
                fontSize = FontSize.micro,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (trace.success) "返回结果" else "失败原因",
            color = c.textLow,
            fontSize = FontSize.micro
        )
        Spacer(Modifier.height(Spacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(c.bg1, RoundedCornerShape(Radius.xs))
                .padding(Spacing.sm)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (trace.error != null && trace.error.isNotBlank()) {
                    Text(
                        text = trace.error,
                        color = c.danger,
                        fontSize = FontSize.micro,
                        fontFamily = FontFamily.Monospace
                    )
                    if (trace.output.isNotBlank()) Spacer(Modifier.height(Spacing.xs))
                }
                Text(
                    text = trace.output.ifBlank { "（空）" },
                    color = c.textMid,
                    fontSize = FontSize.micro,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}