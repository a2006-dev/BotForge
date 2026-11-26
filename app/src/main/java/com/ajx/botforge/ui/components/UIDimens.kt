package com.ajx.botforge.ui.components

import android.content.Context
import androidx.annotation.DimenRes
import com.ajx.botforge.R

/**
 * 尺寸令牌访问器（唯一入口）
 *
 * 约定：
 *  - 页面禁止出现 dp(13)、dp(17) 这类随意值，间距只允许用 4dp 阶梯。
 *  - 字号统一用 sp 令牌，组件高度用 dp 令牌。
 */
object UIDimens {

    fun px(context: Context, @DimenRes id: Int): Int =
        context.resources.getDimensionPixelSize(id)

    // ===== 间距 =====
    fun xs(context: Context) = px(context, R.dimen.spacing_xs)
    fun sm(context: Context) = px(context, R.dimen.spacing_sm)
    fun md(context: Context) = px(context, R.dimen.spacing_md)
    fun lg(context: Context) = px(context, R.dimen.spacing_lg)
    fun xl(context: Context) = px(context, R.dimen.spacing_xl)
    fun xxl(context: Context) = px(context, R.dimen.spacing_xxl)

    // ===== 页面留白 =====
    fun screenH(context: Context) = px(context, R.dimen.screen_padding_h)
    fun screenTop(context: Context) = px(context, R.dimen.screen_padding_top)
    fun screenBottom(context: Context) = px(context, R.dimen.screen_padding_bottom)

    // ===== 圆角 =====
    fun radiusSm(context: Context) = px(context, R.dimen.radius_sm)
    fun radiusMd(context: Context) = px(context, R.dimen.radius_md)
    fun radiusLg(context: Context) = px(context, R.dimen.radius_lg)

    // ===== 卡片 =====
    fun cardPadding(context: Context) = px(context, R.dimen.card_padding)
    fun cardGap(context: Context) = px(context, R.dimen.card_gap)

    // ===== 组件高度 =====
    fun buttonHeight(context: Context) = px(context, R.dimen.button_height)
    fun buttonHeightSm(context: Context) = px(context, R.dimen.button_height_sm)
    fun inputHeight(context: Context) = px(context, R.dimen.input_height)
    fun listItemMinHeight(context: Context) = px(context, R.dimen.list_item_min_height)

    /**
     * 字号（sp 值，用于 setTextSize(TypedValue.COMPLEX_UNIT_PX, ...) 时请改用 px）
     * 这里返回的是像素值，已按 sp 换算，直接用 setTextSize(UNIT_PX) 可保证跟随系统字体缩放。
     */
    fun textCaption(context: Context) = px(context, R.dimen.text_size_caption)
    fun textSmall(context: Context) = px(context, R.dimen.text_size_small)
    fun textLabel(context: Context) = px(context, R.dimen.text_size_label)
    fun textBody(context: Context) = px(context, R.dimen.text_size_body)
    fun textTitle(context: Context) = px(context, R.dimen.text_size_title)
    fun textHeading(context: Context) = px(context, R.dimen.text_size_heading)
    fun textDisplay(context: Context) = px(context, R.dimen.text_size_display)
}