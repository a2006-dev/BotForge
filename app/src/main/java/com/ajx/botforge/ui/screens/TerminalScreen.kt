package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ajx.botforge.term.TerminalEnvironment
import com.ajx.botforge.term.TerminalSession
import com.ajx.botforge.ui.components.AppCard
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置 Linux 终端。
 *
 * ## 分三段状态
 *
 * 一、未解包：展示解包进度与说明，此时不能启动会话；
 * 二、已就绪：显示终端输出区与输入栏；
 * 三、未就绪且解包失败：给出失败原因与重试入口。
 *
 * ## 输出渲染
 *
 * 终端输出含 ANSI 控制序列。这里做的是**逐行文本渲染**而非完整 VT100 模拟：
 * 把 `\r` 处理成回到行首，滤掉光标控制与颜色序列，保留可见字符。完整终端
 * 模拟器（终端缓冲区 + 字符网格 + 属性）体积与复杂度都远超当前阶段所需，
 * 而命令行使用场景中绝大多数输出都是顺序流式的，逐行渲染已够用。
 *
 * 全屏交互程序（vim、top）在这种渲染方式下会显示异常，这一点写在界面说明里，
 * 不做隐藏。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val c = botColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- 环境状态 ----------
    var ready by remember { mutableStateOf(TerminalEnvironment.isReady(context)) }
    var unpacking by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }

    // ---------- 会话 ----------
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var running by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    // 当前未换行的半行缓冲。终端输出经常在句子中间分块，直接按块入列表会
    // 把一句话拆成多行，必须等收到换行再落行。
    var pending by remember { mutableStateOf(StringBuilder()) }
    var input by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    /** 把一块输出并入行列表。 */
    fun feed(chunk: String) {
        val sb = pending
        for (ch in chunk) {
            when (ch) {
                '\n' -> {
                    lines += sb.toString()
                    sb.setLength(0)
                    if (lines.size > 2000) {
                        // 上限保护：内存里只留最近两千行，避免长会话把堆吃满。
                        repeat(lines.size - 2000) { lines.removeAt(0) }
                    }
                }
                '\r' -> {
                    // 回车回到行首。若后面紧跟换行，两者合并即普通换行；
                    // 单独出现时代表覆盖当前行，这里按清空处理。
                    sb.setLength(0)
                }
                '\u0007' -> Unit // 响铃，忽略
                else -> if (ch.code >= 32 || ch == '\t') sb.append(ch)
            }
        }
        // 行数变化后滚到底部。
        scope.launch {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
    }

    /** 启动会话。 */
    fun start() {
        if (running) return
        lines.clear()
        pending.setLength(0)
        val s = TerminalSession(
            context = context,
            onOutput = { chunk -> feed(stripAnsi(chunk)) },
            onExit = { code ->
                scope.launch {
                    running = false
                    if (code >= 0) lines += ""
                }
            }
        )
        val ok = s.start(null)
        if (ok) {
            session = s
            running = true
        } else {
            lines += Txt.TERM_START_FAILED
        }
    }

    /** 解包 rootfs。 */
    fun unpack() {
        if (unpacking) return
        unpacking = true
        failure = null
        percent = 0
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                TerminalEnvironment.ensureReady(context) { p, d ->
                    percent = p
                    detail = d
                    }
            }
            unpacking = false
            ready = ok
            if (!ok) failure = Txt.TERM_UNPACK_FAILED
        }
    }

    // 进入页面时若尚未就绪，自动开始解包。
    LaunchedEffect(Unit) {
        if (!TerminalEnvironment.hasBinaries(context)) {
            failure = Txt.TERM_NO_BINARY
        } else if (!ready) {
            unpack()
        }
    }

    // 离开页面时结束会话，避免残留进程。
    DisposableEffect(Unit) {
        onDispose {
            session?.close()
            session = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(c.bg1)) {
        PageTopBar(
            title = Txt.TITLE_TERMINAL,
            subtitle = if (running) Txt.TERM_SUB_RUNNING else Txt.TERM_SUB_IDLE,
            onBack = {
                session?.close()
                session = null
                onBack()
            }
        )

        when {
            // ---------- 缺少可执行文件 ----------
            failure != null && !ready -> Box(
                modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                contentAlignment = Alignment.TopCenter
            ) {
                AppCard(bordered = true) {
                    Text(
                        text = failure ?: "",
                        color = c.danger,
                        fontSize = FontSize.body
                    )
                    Text(
                        text = Txt.TERM_NO_BINARY_DESC,
                        color = c.textMid,
                        fontSize = FontSize.small,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
            }

            // ---------- 解包中 ----------
            !ready -> Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                AppCard(bordered = true) {
                    Text(
                        text = Txt.TERM_UNPACKING,
                        color = c.textHigh,
                        fontSize = FontSize.bodyLarge
                    )
                    Text(
                        text = detail,
                        color = c.textMid,
                        fontSize = FontSize.small,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.md)
                            .height(4.dp),
                        color = c.brand,
                        trackColor = c.bg3
                    )
                    Text(
                        text = "$percent%",
                        color = c.textLow,
                        fontSize = FontSize.caption,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }

            // ---------- 终端 ----------
            else -> Column(modifier = Modifier.fillMaxSize()) {
                // 输出区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(c.bg0)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            text = Txt.TERM_EMPTY,
                            color = c.textLow,
                            fontSize = FontSize.small,
                            modifier = Modifier.padding(Spacing.sm)
                        )
                    }
                    LazyColumn(state = listState) {
                        itemsIndexed(lines) { index, line ->
                            Text(
                                text = if (index == lines.lastIndex) {
                                    // 最后一行补上未换行的半行内容
                                    line + pending.toString()
                                } else {
                                    line
                                },
                                color = c.textHigh,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // 快捷键条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bg2)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    listOf(
                        "Ctrl+C" to "\u0003",
                        "Ctrl+D" to "\u0004",
                        "Ctrl+L" to "\u000c",
                        "Tab" to "\t",
                        "↑" to "\u001b[A",
                        "↓" to "\u001b[B"
                    ).forEach { (label, seq) ->
                        Box(
                            modifier = Modifier
                                .background(c.bg3, RoundedCornerShape(6.dp))
                                .clickable(enabled = running) { session?.write(seq) }
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (running) c.textMid else c.textLow,
                                fontSize = FontSize.caption,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // 输入栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bg2)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$",
                        color = c.success,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = Spacing.xs)
                    )
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        enabled = running,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.textHigh,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.brand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (input.isNotEmpty()) {
                                    session?.write(input + "\n")
                                    input = ""
                                }
                            }
                        ),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    text = Txt.TERM_INPUT_HINT,
                                    color = c.textLow,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            inner()
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(Sizes.buttonSmall)
                            .clickable(enabled = running && input.isNotEmpty()) {
                                session?.write(input + "\n")
                                input = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = Txt.TERM_SEND,
                            color = if (running && input.isNotEmpty()) c.brand else c.textLow,
                            fontSize = FontSize.small
                        )
                    }
                }

                // 操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bg2)
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SecondaryButton(
                        text = if (running) Txt.TERM_RESTART else Txt.TERM_START,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            session?.close()
                            session = null
                            running = false
                            start()
                        }
                    )
                    SecondaryButton(
                        text = Txt.TERM_CLEAR,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            lines.clear()
                            pending.setLength(0)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 剔除 ANSI 转义序列。
 *
 * 终端程序会输出 SGR 颜色、光标移动、清屏等控制序列，纯文本渲染时这些字节
 * 会显示成 `[32m` 一类乱码。这里按 CSI / OSC 两类前缀做状态机剔除，
 * 同时丢弃除 `\n \r \t` 之外的控制字符。
 */
internal fun stripAnsi(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\u001b' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                '[' -> {
                    // CSI: ESC [ 参数 终止字符(0x40..0x7E)
                    var j = i + 2
                    while (j < raw.length && raw[j].code !in 0x40..0x7E) j++
                    i = (j + 1).coerceAtMost(raw.length)
                    continue
                }
                ']' -> {
                    // OSC: ESC ] ... 以 BEL 或 ESC \ 结束
                    var j = i + 2
                    while (j < raw.length) {
                        if (raw[j] == '\u0007') {
                            j++
                            break
                        }
                        if (raw[j] == '\u001b' && j + 1 < raw.length && raw[j + 1] == '\\') {
                            j += 2
                            break
                        }
                        j++
                    }
                    i = j.coerceAtMost(raw.length)
                    continue
                }
                else -> {
                    i += 2
                    continue
                }
            }
        }
        if (ch == '\n' || ch == '\r' || ch == '\t' || ch.code >= 32) {
            out.append(ch)
        }
        i++
    }
    return out.toString()
}