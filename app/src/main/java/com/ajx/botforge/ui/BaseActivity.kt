package com.ajx.botforge.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 轻量 UI 基类：统一 dp、卡片、输入框构建，全部代码布局
 */
abstract class BaseActivity : AppCompatActivity() {

    protected var rootContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
        rootContainer = root
        setContentView(ScrollView(this).apply { addView(root) })
        build()
    }

    abstract fun build()

    protected fun add(v: View, top: Int = 0, height: Int = 0): View {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (height > 0) height else LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
        rootContainer?.addView(v, lp)
        return v
    }

    protected fun title(t: String, sub: String? = null) {
        add(TextView(this).apply {
            text = t
            textSize = 22f
            setTextColor(0xFF111111.toInt())
        })
        if (sub != null) {
            add(TextView(this).apply {
                text = sub
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(0, dp(4), 0, dp(8))
            })
        }
    }

    protected fun label(t: String, top: Int = 12) {
        add(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(2), 0, 0, dp(3))
        }, top)
    }

    protected fun input(hint: String, value: String = "", single: Boolean = true): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            if (single) {
                isSingleLine = true
            } else {
                minLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    protected fun button(t: String, primary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t
            textSize = 14f
            setOnClickListener { onClick() }
            if (primary) {
                setBackgroundColor(0xFF111827.toInt())
                setTextColor(0xFFFFFFFF.toInt())
            }
        }

    protected fun card(v: View, top: Int = 8): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFF7F8FA.toInt())
            addView(v)
        }
        return add(wrap, top)
    }

    protected fun text(t: String, size: Float = 14f, color: Int = 0xFF333333.toInt()): TextView =
        TextView(this).apply {
            this.text = t
            textSize = size
            setTextColor(color)
        }

    protected fun confirm(msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .show()
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}