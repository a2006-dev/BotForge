package com.ajx.botforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ajx.botforge.ui.theme.Bg1
import com.ajx.botforge.ui.theme.Bg2
import com.ajx.botforge.ui.theme.Bg3
import com.ajx.botforge.ui.theme.Brand
import com.ajx.botforge.ui.theme.Danger
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Line
import com.ajx.botforge.ui.theme.LineStrong
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.TextHigh
import com.ajx.botforge.ui.theme.TextLow
import com.ajx.botforge.ui.theme.TextMid

/**
 * 页面顶部栏。返回按钮仅在 showBack 为真时出现。
 * 不依赖 Material TopAppBar，避免主题色意外介入。
 */
@Composable
fun PageTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = true,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.topBar)
                .background(Bg1)
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack && onBack != null) {
                Box(
                    modifier = Modifier
                        .size(Sizes.touchTarget)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextHigh,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null && subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = TextLow,
                        fontSize = FontSize.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            actions()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.hairline)
                .background(Line)
        )
    }
}

/**
 * 通用列表行：左侧主标题 + 副标题，右侧尾部内容，可点击。
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizes.rowMin)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextHigh,
                fontSize = FontSize.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null && subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = TextMid,
                    fontSize = FontSize.small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

/**
 * 分组容器：卡片内多条 ListRow，行间细分割线。
 */
@Composable
fun RowGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, Line, RoundedCornerShape(Radius.lg))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/** 行间分割线，用于 RowGroup 内部。 */
@Composable
fun RowDivider(modifier: Modifier = Modifier, indent: Dp = Spacing.md) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(Sizes.hairline)
            .background(Line)
    )
}

/** 空状态：图标位 + 说明 + 可选操作按钮。 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: String = "○",
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Bg3, CircleShape)
                .border(Sizes.hairline, LineStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                color = TextLow,
                fontSize = FontSize.title
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            color = TextHigh,
            fontSize = FontSize.title,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        if (description != null && description.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = description,
                color = TextLow,
                fontSize = FontSize.small,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** 整页加载态。 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "加载中"
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Brand,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = message,
                color = TextMid,
                fontSize = FontSize.small
            )
        }
    }
}

/** 整页错误态，带重试。 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠",
            color = Danger,
            fontSize = FontSize.metric
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = message,
            color = TextMid,
            fontSize = FontSize.small,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.md))
            SmallButton(text = "重试", onClick = onRetry)
        }
    }
}

/** 键值展示行，用于详情页只读信息。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = TextHigh,
    mono: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextMid,
            fontSize = FontSize.small,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = value,
            color = valueColor,
            fontSize = if (mono) FontSize.small else FontSize.body,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 页面标准内边距容器。 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.md),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg1)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = verticalArrangement,
        content = content
    )
}
