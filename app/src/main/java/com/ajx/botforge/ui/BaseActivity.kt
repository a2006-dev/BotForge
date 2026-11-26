package com.ajx.botforge.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ajx.botforge.R

/**
 * 轻量 UI 基类：统一 dp、卡片、输入框构建，全部代码布局
 *
 * v2 重做：
 *  - 所有颜色改为读 R.color，尺寸读 R.dimen，不再硬编码 0xFFxxxxxx。
 *  - 卡片改用 sh_card 背景，解决「白底白字」。
 *  - 主按钮改用品牌色，次要按钮改用深灰描边，不再依赖系统默认灰块。
 *  - 刻意只保留这几个方法：业务页面靠它们统一，新增样式请加在这里而不是各页各写。
 */
abstract class BaseActivity : AppCompatActivity() {

    protected var rootContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.ink_1))
            setPadding(dp(R.dimen.s4), dp(R.dimen.s6), dp(R.dimen.s4), dp(R.dimen.s8))
        }
        rootContainer = root
        setContentView(ScrollView(this).apply {
            setBackgroundColor(color(R.color.ink_1))
            isFillViewport = true
            addView(root)
        })
        build()
    }

    abstract fun build()

    // ------------------------------------------------------------ 布局容器

    protected fun add(v: View, top: Int = 0, height: Int = 0): View {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height > 0) height else ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = top }
        rootContainer?.addView(v, lp)
        return v
    }

    // ------------------------------------------------------------ 文本

    protected fun title(t: String, sub: String? = null) {
        add(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_xl))
            setTextColor(color(R.color.t_hi))
            includeFontPadding = false
        })
        if (sub != null) {
            add(TextView(this).apply {
                text = sub
                setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_sm))
                setTextColor(color(R.color.t_mid))
                setPadding(0, dp(R.dimen.s1), 0, dp(R.dimen.s2))
            })
        }
    }

    protected fun label(t: String, top: Int = 12) {
        add(TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_sm))
            setTextColor(color(R.color.t_mid))
            setPadding(dp(R.dimen.s1) / 2, 0, 0, dp(R.dimen.s1))
        }, top)
    }

    protected fun text(
        t: String,
        size: Float = 14f,
        colorInt: Int = color(R.color.t_hi)
    ): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(colorInt)
    }

    /** 按资源令牌创建文本：textOf("...", R.dimen.f_lg, R.color.t_mid) */
    protected fun textOf(
        t: String,
        sizeId: Int = R.dimen.f_md,
        colorId: Int = R.color.t_hi
    ): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(sizeId))
        setTextColor(color(colorId))
    }

    // ------------------------------------------------------------ 输入

    protected fun input(hint: String, value: String = "", single: Boolean = true): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_md))
            setTextColor(color(R.color.t_hi))
            setHintTextColor(color(R.color.t_low))
            background = ContextCompat.getDrawable(this@BaseActivity, R.drawable.sh_input)
            setPadding(dp(R.dimen.s3), dp(R.dimen.s3), dp(R.dimen.s3), dp(R.dimen.s3))
            if (single) {
                isSingleLine = true
            } else {
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
        }
    }

    // ------------------------------------------------------------ 按钮

    protected fun button(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_md))
            if (primary) {
                setTextColor(color(R.color.t_hi))
                background = ContextCompat.getDrawable(this@BaseActivity, R.drawable.sh_btn_primary)
            } else {
                setTextColor(color(R.color.t_hi))
                background = ContextCompat.getDrawable(this@BaseActivity, R.drawable.sh_btn_ghost)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(R.dimen.h_btn)
            )
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ 卡片

    protected fun card(v: View, top: Int = 0): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@BaseActivity, R.drawable.sh_card)
            setPadding(dp(R.dimen.s3), dp(R.dimen.s3), dp(R.dimen.s3), dp(R.dimen.s3))
            addView(v)
        }
        return add(wrap, if (top > 0) top else dp(R.dimen.s2))
    }

    /** 把已有视图包进卡片（不改其内部结构） */
    protected fun cardWrap(v: View, top: Int = 0): View = card(v, top)

    // ------------------------------------------------------------ 工具

    protected fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** 从 dimen 资源取像素值，例如 dp(R.dimen.s4) */
    protected fun dp(id: Int): Int = resources.getDimensionPixelSize(id)

    /** sp 刻度换算为像素，跟随系统字体缩放 */
    protected fun spPx(id: Int): Float {
        val v = resources.getDimension(id)
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, v, resources.displayMetrics)
    }

    /** 数值型 dp，保留给尚未迁移的旧代码，例如 dpNum(12) */
    protected fun dpNum(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}