package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.InputShape
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import com.ajx.botforge.ui.text.Txt

/**
 * 表单字段：标签 + 输入框 + 可选说明
 *
 * 统一「标签在上、说明在下」的纵向结构，
 * 避免某些页面标签在左、某些在上的混乱。
 */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next
) {
    val c = botColors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = Spacing.xxs, bottom = Spacing.sm)
        )

        AppTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = singleLine,
            keyboardType = keyboardType,
            imeAction = imeAction,
            isError = error != null
        )

        // 说明与错误互斥：有错就只显示错误，避免信息堆叠
        val hint = error ?: helper
        if (hint != null) {
            Text(
                text = hint,
                color = if (error != null) c.danger else c.textLow,
                fontSize = FontSize.caption,
                modifier = Modifier.padding(
                    start = Spacing.xxs,
                    top = Spacing.xs
                )
            )
        }
    }
}

/**
 * 基础输入框
 *
 * 用 BasicTextField 自绘，而不是 Material OutlinedTextField——
 * M3 默认样式在深色下会有多余的容器色和过大的内边距，
 * 自绘能精确控制描边、聚焦态和高度。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isError: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = Sizes.inputMin
) {
    val c = botColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val borderColor = when {
        isError -> c.danger
        focused -> c.brand
        else -> c.line
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .background(c.bg3, InputShape)
            .border(Sizes.hairline, borderColor, InputShape)
            .padding(
                horizontal = Spacing.md,
                vertical = if (singleLine) Spacing.md else Spacing.md
            ),
        textStyle = LocalTextStyle.current.copy(
            color = c.textHigh,
            fontSize = FontSize.body
        ),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        cursorBrush = SolidColor(c.brand),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = c.textLow,
                        fontSize = FontSize.body
                    )
                }
                inner()
            }
        }
    )
}

/**
 * 敏感字段输入框：默认打码，可切换显隐
 *
 * 任务书 4.4 明确要求「API Key 必须默认隐藏，提供显示/隐藏操作」。
 * AppSecret 同样处理。
 */
@Composable
fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null
) {
    val c = botColors
    var visible by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val borderColor = when {
        error != null -> c.danger
        focused -> c.brand
        else -> c.line
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = c.textMid,
            fontSize = FontSize.small,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = Spacing.xxs, bottom = Spacing.sm)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizes.inputMin)
                .background(c.bg3, InputShape)
                .border(Sizes.hairline, borderColor, InputShape)
                .padding(start = Spacing.md, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(
                    color = c.textHigh,
                    fontSize = FontSize.body
                ),
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                cursorBrush = SolidColor(c.brand),
                interactionSource = interaction,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                color = c.textLow,
                                fontSize = FontSize.body
                            )
                        }
                        inner()
                    }
                }
            )
            IconTextButton(
                icon = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (visible) Txt.CD_HIDE else Txt.CD_SHOW,
                tint = c.textMid,
                onClick = { visible = !visible }
            )
        }

        val hint = error ?: helper
        if (hint != null) {
            Text(
                text = hint,
                color = if (error != null) c.danger else c.textLow,
                fontSize = FontSize.caption,
                modifier = Modifier.padding(start = Spacing.xxs, top = Spacing.xs)
            )
        }
    }
}

/**
 * 多行文本输入
 *
 * 给「人格 Prompt」这类长文本用：固定最小高度、左上对齐、
 * 内部可滚动，不撑破页面。
 */
@Composable
fun MultilineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    error: String? = null,
    minHeight: androidx.compose.ui.unit.Dp = Sizes.inputMultiline
) {
    val c = botColors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val borderColor = when {
        error != null -> c.danger
        focused -> c.brand
        else -> c.lineStrong
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = Spacing.xxs, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = label,
                color = c.textMid,
                fontSize = FontSize.small,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = String.format(Txt.CHAR_COUNT_FMT, value.length),
                color = c.textLow,
                fontSize = FontSize.caption
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .background(c.bg3, InputShape)
                .border(Sizes.hairline, borderColor, InputShape)
                .padding(Spacing.md),
            textStyle = LocalTextStyle.current.copy(
                color = c.textHigh,
                fontSize = FontSize.body,
                lineHeight = (FontSize.body.value * 1.5f).sp
            ),
            singleLine = false,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default
            ),
            cursorBrush = SolidColor(c.brand),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = c.textLow,
                            fontSize = FontSize.body
                        )
                    }
                    inner()
                }
            }
        )

        val hint = error ?: helper
        if (hint != null) {
            Text(
                text = hint,
                color = if (error != null) c.danger else c.textLow,
                fontSize = FontSize.caption,
                modifier = Modifier.padding(start = Spacing.xxs, top = Spacing.xs)
            )
        }
    }
}

/**
 * 搜索框
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索"
) {
    val c = botColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.inputMin)
            .background(c.bg3, InputShape)
            .border(Sizes.hairline, c.line, InputShape)
            .padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(Sizes.icon),
            tint = c.textLow
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = LocalTextStyle.current.copy(
                color = c.textHigh,
                fontSize = FontSize.body
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            cursorBrush = SolidColor(c.brand),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = c.textLow, fontSize = FontSize.body)
                    }
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) {
            IconTextButton(
                icon = Icons.Filled.Close,
                contentDescription = "清除",
                tint = c.textLow,
                onClick = { onValueChange("") }
            )
        }
    }
}