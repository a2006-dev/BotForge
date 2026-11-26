package com.ajx.botforge.ui.components

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * BotForge UI 组件工厂（新组件层）
 *
 * 设计原则：
 *  - 只读令牌（UIColors / UIDimens），不出现任何硬编码颜色和数值。
 *  - 旧页面继续用 BaseActivity 的老方法，互不影响；迁移一页改一页。
 *  - 每个组件都带明确用途，不做“万能方法”。
 */
object BotForgeUI {

    // ==================== 文本 ====================

    /** 页面主标题 */
    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textHeading(context).toFloat())
        setTextColor(UIColors.textPrimary(context))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    /** 分区标题 */
    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textTitle(context).toFloat())
        setTextColor(UIColors.textPrimary(context))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    /** 正文 */
    fun body(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
        setTextColor(UIColors.textPrimary(context))
    }

    /** 次要说明 */
    fun secondary(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textLabel(context).toFloat())
        setTextColor(UIColors.textSecondary(context))
    }

    /** 小号说明 / 时间戳 */
    fun caption(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textSmall(context).toFloat())
        setTextColor(UIColors.textTertiary(context))
    }

    /** 表单字段标签 */
    fun fieldLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textLabel(context).toFloat())
        setTextColor(UIColors.textSecondary(context))
        setPadding(UIDimens.xs(context) / 2, 0, 0, UIDimens.xs(context))
    }

    // ==================== 按钮 ====================

    /** 主按钮：强调色实心 */
    fun primaryButton(
        context: Context,
        text: String,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
        setTextColor(UIColors.textOnAccent(context))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = ContextCompat.getDrawable(context, com.ajx.botforge.R.drawable.bg_button_primary)
        isAllCaps = false
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            UIDimens.buttonHeight(context)
        )
        setOnClickListener { onClick() }
    }

    /** 次按钮：描边 */
    fun secondaryButton(
        context: Context,
        text: String,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
        setTextColor(UIColors.textPrimary(context))
        background = ContextCompat.getDrawable(context, com.ajx.botforge.R.drawable.bg_button_secondary)
        isAllCaps = false
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            UIDimens.buttonHeight(context)
        )
        setOnClickListener { onClick() }
    }

    // ==================== 输入框 ====================

    /** 单行输入框 */
    fun input(context: Context, hint: String, value: String = ""): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
            setTextColor(UIColors.textPrimary(context))
            setHintTextColor(UIColors.textTertiary(context))
            background = ContextCompat.getDrawable(context, com.ajx.botforge.R.drawable.bg_input)
            setPadding(
                UIDimens.md(context), UIDimens.md(context),
                UIDimens.md(context), UIDimens.md(context)
            )
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    /** 多行输入框：用于人设内容等长文本 */
    fun multilineInput(context: Context, hint: String, value: String = ""): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
            setTextColor(UIColors.textPrimary(context))
            setHintTextColor(UIColors.textTertiary(context))
            background = ContextCompat.getDrawable(context, com.ajx.botforge.R.drawable.bg_input)
            setPadding(
                UIDimens.md(context), UIDimens.md(context),
                UIDimens.md(context), UIDimens.md(context)
            )
            isSingleLine = false
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    // ==================== 卡片 ====================

    /** 卡片容器：深色 Surface + 圆角 + 描边 */
    fun card(context: Context, content: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, com.ajx.botforge.R.drawable.bg_card)
            setPadding(
                UIDimens.cardPadding(context), UIDimens.cardPadding(context),
                UIDimens.cardPadding(context), UIDimens.cardPadding(context)
            )
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = UIDimens.cardGap(context)
            }
        }

    /** 竖向容器，统一间距 */
    fun column(context: Context, gap: Int = UIDimens.sm(context)): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (gap > 0) setPadding(0, 0, 0, 0)
        }

    /** 横向容器，用于状态行、按钮行 */
    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    // ==================== 状态标签 ====================

    /**
     * 状态标签（胶囊）
     * @param state success / warning / error / idle
     */
    fun tag(context: Context, text: String, state: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textCaption(context).toFloat())
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(
            UIDimens.sm(context), UIDimens.xs(context) / 2,
            UIDimens.sm(context), UIDimens.xs(context) / 2
        )
        val (color, bg) = when (state) {
            "success" -> UIColors.success(context) to com.ajx.botforge.R.drawable.bg_tag_success
            "warning" -> UIColors.warning(context) to com.ajx.botforge.R.drawable.bg_tag_warning
            "error" -> UIColors.error(context) to com.ajx.botforge.R.drawable.bg_tag_error
            else -> UIColors.idle(context) to com.ajx.botforge.R.drawable.bg_tag_idle
        }
        setTextColor(color)
        background = ContextCompat.getDrawable(context, bg)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 运行状态标签：running=true → 运行中(green)，否则未启动(gray) */
    fun runStateTag(context: Context, running: Boolean, hasError: Boolean = false): TextView {
        val text = when {
            hasError -> "异常"
            running -> "运行中"
            else -> "未启动"
        }
        val state = when {
            hasError -> "error"
            running -> "success"
            else -> "idle"
        }
        return tag(context, text, state)
    }

    // ==================== 空 / 加载 / 错误状态 ====================

    /**
     * 空状态：居中提示 + 可选操作按钮
     */
    fun emptyState(
        context: Context,
        title: String,
        desc: String? = null,
        actionText: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            UIDimens.xl(context), UIDimens.xxl(context),
            UIDimens.xl(context), UIDimens.xxl(context)
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
            setTextColor(UIColors.textSecondary(context))
            gravity = Gravity.CENTER
        })
        if (desc != null) {
            addView(TextView(context).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textSmall(context).toFloat())
                setTextColor(UIColors.textTertiary(context))
                gravity = Gravity.CENTER
                setPadding(0, UIDimens.sm(context), 0, 0)
            })
        }
        if (actionText != null && onAction != null) {
            addView(primaryButton(context, actionText, onAction).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    UIDimens.buttonHeightSm(context)
                ).apply {
                    topMargin = UIDimens.lg(context)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        }
    }

    /**
     * 错误状态：用于页面级加载失败
     */
    fun errorState(
        context: Context,
        message: String,
        retry: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            UIDimens.xl(context), UIDimens.xxl(context),
            UIDimens.xl(context), UIDimens.xxl(context)
        )
        addView(TextView(context).apply {
            text = "出错了"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textBody(context).toFloat())
            setTextColor(UIColors.error(context))
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_PX, UIDimens.textSmall(context).toFloat())
            setTextColor(UIColors.textSecondary(context))
            gravity = Gravity.CENTER
            setPadding(0, UIDimens.sm(context), 0, 0)
        })
        if (retry != null) {
            addView(secondaryButton(context, "重试", retry).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    UIDimens.buttonHeightSm(context)
                ).apply {
                    topMargin = UIDimens.lg(context)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        }
    }

    /** 分割线 */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(UIColors.divider(context))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            UIDimens.px(context, com.ajx.botforge.R.dimen.border_width)
        )
    }
}