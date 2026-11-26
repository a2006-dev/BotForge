package com.ajx.botforge.ui

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ajx.botforge.data.Logger
import com.ajx.botforge.qq.BotManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogActivity : BaseActivity() {

    private lateinit var logView: TextView
    private var tailing = true
    private val pool = Executors.newSingleThreadScheduledExecutor()

    override fun build() {
        title("运行日志", "内存 600 条 + 落盘轮转；只在此页渲染，不进主线程阻塞")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("清空") {
            Logger.clear()
            logView.text = ""
        })
        row.addView(button("刷新") { render() })
        row.addView(button("跟随") { tailing = !tailing; toast(if (tailing) "已开启跟随" else "已暂停跟随") })
        row.addView(button("重载机器人") {
            BotManager.reload(this)
            toast("已重载")
        })
        add(row, 10)

        logView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF222222.toInt())
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        add(logView, 10, height = dp(520))

        render()
        pool.scheduleWithFixedDelay({
            if (!tailing) return@scheduleWithFixedDelay
            runOnUiThread { render() }
        }, 1, 2, TimeUnit.SECONDS)
    }

    private fun render() {
        val lines = Logger.snapshot()
        val sb = StringBuilder()
        lines.takeLast(500).forEach { l ->
            sb.append(l.time).append(' ')
            when (l.level) {
                1 -> sb.append("⚠ ")
                2 -> sb.append("✖ ")
                else -> sb.append("  ")
            }
            sb.append(l.text).append('\n')
        }
        if (sb.isEmpty()) sb.append("（暂无日志）")
        logView.text = sb.toString()
    }

    override fun onDestroy() {
        tailing = false
        pool.shutdownNow()
        super.onDestroy()
    }
}