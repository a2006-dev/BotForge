package com.ajx.botforge.ui.components

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.ajx.botforge.R

/**
 * 颜色令牌访问器（唯一入口）
 *
 * 约定：
 *  - 页面与组件禁止再出现 0xFFxxxxxx 这类硬编码颜色。
 *  - 一律通过本对象或 R.color 引用，保证颜色只有 colors.xml 一份来源。
 *  - 未来若引入 Compose，Compose 侧同样用 colorResource(R.color.xxx) 读同一份令牌。
 */
object UIColors {

    fun get(context: Context, @ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    // ===== 背景 =====
    fun bgPrimary(context: Context) = get(context, R.color.bg_primary)
    fun bgSecondary(context: Context) = get(context, R.color.bg_secondary)
    fun bgCard(context: Context) = get(context, R.color.bg_card)
    fun bgElevated(context: Context) = get(context, R.color.bg_elevated)
    fun bgInput(context: Context) = get(context, R.color.bg_input)

    // ===== 文字 =====
    fun textPrimary(context: Context) = get(context, R.color.text_primary)
    fun textSecondary(context: Context) = get(context, R.color.text_secondary)
    fun textTertiary(context: Context) = get(context, R.color.text_tertiary)
    fun textOnAccent(context: Context) = get(context, R.color.text_on_accent)
    fun textLink(context: Context) = get(context, R.color.text_link)

    // ===== 强调 =====
    fun accent(context: Context) = get(context, R.color.accent_primary)
    fun accentPressed(context: Context) = get(context, R.color.accent_pressed)

    // ===== 状态 =====
    fun success(context: Context) = get(context, R.color.status_success)
    fun warning(context: Context) = get(context, R.color.status_warning)
    fun error(context: Context) = get(context, R.color.status_error)
    fun idle(context: Context) = get(context, R.color.status_idle)

    // ===== 线 =====
    fun divider(context: Context) = get(context, R.color.divider)
    fun border(context: Context) = get(context, R.color.border)

    /**
     * 运行状态 → 颜色
     * 集中一处判定，避免每个页面各写一套 if-else 导致颜色不一致。
     */
    fun forRunState(context: Context, running: Boolean, hasError: Boolean = false): Int = when {
        hasError -> error(context)
        running -> success(context)
        else -> idle(context)
    }

    /**
     * 运行状态 → 标签背景 drawable
     */
    fun tagBgForRunState(running: Boolean, hasError: Boolean = false): Int = when {
        hasError -> R.drawable.bg_tag_error
        running -> R.drawable.bg_tag_success
        else -> R.drawable.bg_tag_idle
    }

    /**
     * 日志等级 → 颜色（LogActivity 使用）
     */
    fun forLogLevel(context: Context, level: String): Int = when (level.uppercase()) {
        "E", "ERROR" -> get(context, R.color.log_error)
        "W", "WARN" -> get(context, R.color.log_warn)
        "I", "INFO" -> get(context, R.color.log_info)
        else -> get(context, R.color.log_debug)
    }
}