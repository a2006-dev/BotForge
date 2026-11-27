package com.ajx.botforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ajx.botforge.data.AuthStore
import com.ajx.botforge.ui.components.PageTopBar
import com.ajx.botforge.ui.components.PrimaryButton
import com.ajx.botforge.ui.components.SecondaryButton
import com.ajx.botforge.ui.components.SectionHeader
import com.ajx.botforge.ui.components.SmallButton
import com.ajx.botforge.ui.components.DangerButton
import com.ajx.botforge.ui.text.Txt
import com.ajx.botforge.ui.theme.FontSize
import com.ajx.botforge.ui.theme.Radius
import com.ajx.botforge.ui.theme.Sizes
import com.ajx.botforge.ui.theme.Spacing
import com.ajx.botforge.ui.theme.botColors
import kotlinx.coroutines.delay

/**
 * 权限页。
 *
 * 主人绑定与管理员管理都在这里完成。绑定流程本身走 QQ：应用生成 8 位
 * 验证码，用户在手机 QQ 里私聊机器人发送该码，机器人侧校验后写入主人。
 * 因此本页只负责「生成码」与「撤销」，不收集用户的 openid。
 *
 * 验证码有 5 分钟有效期，页面每秒刷新剩余时间，过期后自动提示重新生成。
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val c = botColors

    var hasOwner by remember { mutableStateOf(AuthStore.hasOwner) }
    var ownerMasked by remember {
        mutableStateOf(if (AuthStore.hasOwner) AuthStore.mask(AuthStore.ownerOpenId) else "")
    }
    var admins by remember { mutableStateOf(AuthStore.admins()) }

    var code by remember { mutableStateOf(AuthStore.pendingCode()?.code.orEmpty()) }
    var remainSec by remember { mutableStateOf(0L) }
    var showUnbind by remember { mutableStateOf(false) }

    // 刷新剩余有效期。用 1 秒轮询而不是在生成时算死：
    // 用户可能在页面停留很久，也可能切后台再回来。
    LaunchedEffect(code) {
        while (true) {
            val p = AuthStore.pendingCode()
            if (p == null) {
                remainSec = 0
                if (code.isNotEmpty()) code = ""
                break
            }
            remainSec = (p.remainMs() + 999) / 1000
            delay(1000)
        }
    }

    fun refreshAll() {
        hasOwner = AuthStore.hasOwner
        ownerMasked = if (hasOwner) AuthStore.mask(AuthStore.ownerOpenId) else ""
        admins = AuthStore.admins()
    }

    Column(modifier = Modifier.fillMaxWidth().background(c.bg1)) {
        PageTopBar(
            title = Txt.TITLE_PERMISSIONS,
            subtitle = Txt.PERM_SUB_OWNER,
            onBack = onBack
        )

        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(Modifier.height(Spacing.md))

            // ---------- 主人状态 ----------
            SectionHeader(Txt.AUTH_ROLE_OWNER)
            CardBox {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text(
                        text = if (hasOwner) Txt.AUTH_OWNER_BOUND else Txt.AUTH_OWNER_NONE,
                        color = if (hasOwner) c.textHigh else c.textMid,
                        fontSize = FontSize.body,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasOwner) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = ownerMasked,
                            color = c.textLow,
                            fontSize = FontSize.caption,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    if (hasOwner) {
                        DangerButton(
                            text = Txt.PERM_UNBIND,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showUnbind = true }
                        )
                    } else {
                        Text(
                            text = Txt.PERM_UNBIND_UNAVAILABLE,
                            color = c.textLow,
                            fontSize = FontSize.caption
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ---------- 验证码 ----------
            SectionHeader(Txt.PERM_CODE_TITLE)
            CardBox {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    if (code.isEmpty()) {
                        Text(
                            text = Txt.PERM_CODE_EMPTY,
                            color = c.textMid,
                            fontSize = FontSize.caption
                        )
                    } else {
                        Text(
                            text = code,
                            color = c.brand,
                            fontSize = FontSize.title,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = if (remainSec > 0) Txt.PERM_CODE_REMAIN(remainSec)
                            else Txt.PERM_CODE_EXPIRED,
                            color = if (remainSec > 0) c.textLow else c.danger,
                            fontSize = FontSize.caption
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(
                        text = if (code.isEmpty()) Txt.PERM_CODE_GENERATE
                        else Txt.PERM_CODE_REGENERATE,
                        modifier = Modifier.fillMaxWidth(),
                        // 已绑定主人时仍允许生成：用于换绑前的准备，但语义上
                        // 需要先解绑，这里给出提示而不是直接禁用。
                        onClick = {
                            code = AuthStore.generateCode().code
                            remainSec = (AuthStore.CODE_TTL_MS + 999) / 1000
                        }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = Txt.PERM_CODE_USAGE,
                        color = c.textLow,
                        fontSize = FontSize.caption
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ---------- 管理员 ----------
            SectionHeader(Txt.PERM_ADMIN_TITLE)
            CardBox {
                Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                    if (admins.isEmpty()) {
                        Text(
                            text = Txt.PERM_ADMIN_EMPTY,
                            color = c.textLow,
                            fontSize = FontSize.caption,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    } else {
                        admins.forEachIndexed { index, admin ->
                            if (index > 0) {
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.lg)
                                        .height(Sizes.hairline)
                                        .background(c.line)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = AuthStore.mask(admin.openId),
                                        color = c.textHigh,
                                        fontSize = FontSize.body,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = listOf(
                                            if (admin.scope.isEmpty()) Txt.AUTH_SCOPE_GLOBAL
                                            else Txt.AUTH_SCOPE_GROUP,
                                            admin.note.ifEmpty { Txt.AUTH_ROLE_ADMIN }
                                        ).joinToString(" · "),
                                        color = c.textLow,
                                        fontSize = FontSize.caption
                                    )
                                }
                                Spacer(Modifier.width(Spacing.sm))
                                SmallButton(
                                    text = Txt.PERM_ADMIN_REMOVE,
                                    onClick = {
                                        AuthStore.removeAdmin(admin.openId, admin.scope)
                                        refreshAll()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ---------- 说明 ----------
            SectionHeader(Txt.PERM_NOTE_TITLE)
            CardBox {
                Text(
                    text = Txt.PERM_NOTE_BODY,
                    color = c.textMid,
                    fontSize = FontSize.caption,
                    modifier = Modifier.padding(Spacing.lg)
                )
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    if (showUnbind) {
        AlertDialog(
            onDismissRequest = { showUnbind = false },
            containerColor = c.bg2,
            shape = RoundedCornerShape(Radius.xl),
            title = {
                Text(
                    text = Txt.PERM_UNBIND_TITLE,
                    color = c.textHigh,
                    fontSize = FontSize.title,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = Txt.PERM_UNBIND_BODY,
                    color = c.textMid,
                    fontSize = FontSize.caption
                )
            },
            confirmButton = {
                DangerButton(
                    text = Txt.PERM_CONFIRM,
                    onClick = {
                        AuthStore.unbindOwner()
                        AuthStore.clearCode()
                        code = ""
                        remainSec = 0
                        refreshAll()
                        showUnbind = false
                    }
                )
            },
            dismissButton = {
                SecondaryButton(
                    text = Txt.PERM_CANCEL,
                    onClick = { showUnbind = false }
                )
            }
        )
    }
}

/** 统一的卡片外壳：底色 + 描边 + 圆角，避免各区块样式漂移。 */
@Composable
private fun CardBox(content: @Composable () -> Unit) {
    val c = botColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg2, RoundedCornerShape(Radius.lg))
            .border(Sizes.hairline, c.line, RoundedCornerShape(Radius.lg))
    ) {
        content()
    }
}