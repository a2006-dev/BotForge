package com.ajx.botforge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.service.BotService
import com.ajx.botforge.service.ShizukuKeepAlive

/**
 * 主页：状态 + 启停 + 入口
 * 用纯代码布局，避免资源文件堆叠，减少编译风险
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "BotForge"
            textSize = 26f
            setTextColor(0xFF111111.toInt())
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = "安卓常驻 QQ 机器人客户端"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(4), 0, dp(18))
        }
        root.addView(sub)

        statusTv = TextView(this).apply {
            text = "状态：读取中…"
            textSize = 15f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFF3F4F6.toInt())
        }
        root.addView(statusTv, lp())

        startBtn = Button(this).apply {
            text = "启动"
            setOnClickListener { toggle() }
        }
        root.addView(startBtn, lp(top = 14))

        root.addView(btn("机器人配置") { go(BotListActivity::class.java) }, lp(top = 10))
        root.addView(btn("人设管理") { go(PersonaListActivity::class.java) }, lp(top = 10))
        root.addView(btn("模型配置") { go(ModelListActivity::class.java) }, lp(top = 10))
        root.addView(btn("运行日志") { go(LogActivity::class.java) }, lp(top = 10))

        // 开关区
        root.addView(switchRow("开机自启", Config.autoStart) { Config.autoStart = it }, lp(top = 20))
        root.addView(switchRow("保活增强", Config.keepAlive) { Config.keepAlive = it }, lp(top = 6))
        root.addView(switchRow("联网搜索", Config.searchEnabled) { Config.searchEnabled = it }, lp(top = 6))

        root.addView(btn("授权：忽略电池优化") { requestBattery() }, lp(top = 18))
        root.addView(btn("授权：悬浮窗") { requestOverlay() }, lp(top = 8))
        root.addView(btn("授权：Shizuku 增强") {
            if (!applyShizuku()) {
                ShizukuKeepAlive.request(this)
            }
        }, lp(top = 8))

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // 默认示例数据便于首次上手
        seedIfEmpty()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = BotService.instance != null
        val bots = Config.loadBots()
        val personas = Config.loadPersonas()
        val models = Config.loadModels()
        statusTv.text = buildString {
            append("运行：").append(if (running) "● 已启动" else "○ 已停止").append('\n')
            append("机器人：").append(bots.size).append(" 个（启用 ")
                .append(bots.count { it.enabled }).append("）\n")
            append("人设：").append(personas.size).append(" 个\n")
            append("模型：").append(models.size).append(" 个\n")
            append("调度：").append(BotManager.status())
        }
        startBtn.text = if (running) "停止" else "启动"
    }

    private fun toggle() {
        if (BotService.instance != null) {
            startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
        } else {
            val bots = Config.loadBots().filter { it.enabled }
            if (bots.isEmpty()) {
                Toast.makeText(this, "请先添加并启用一个机器人", Toast.LENGTH_SHORT).show()
                return
            }
            if (Config.loadModels().isEmpty()) {
                Toast.makeText(this, "请先添加模型配置", Toast.LENGTH_SHORT).show()
                return
            }
            BotService.start(this)
        }
        statusTv.postDelayed({ refresh() }, 600)
    }

    private fun seedIfEmpty() {
        if (Config.loadModels().isEmpty()) {
            Config.saveModels(
                listOf(
                    Config.Model(
                        id = Config.newId("m"),
                        name = "本机反代",
                        baseUrl = "http://127.0.0.1:7863/v1",
                        apiKey = "",
                        model = "deepseek-v4.1-flash"
                    )
                )
            )
        }
        if (Config.loadPersonas().isEmpty()) {
            Config.savePersonas(
                listOf(
                    Config.Persona(
                        id = Config.newId("p"),
                        name = "默认人格",
                        prompt = "你是一个在 QQ 群里聊天的普通网友。说话口语化、简短，带点自己的态度，" +
                                "不要客套，不要长篇大论，不用「首先其次最后」这种腔调。",
                        tone = "短句优先，偶尔反问，不用书面语。"
                    )
                )
            )
        }
    }

    // ------------------------------------------------------------ 授权
    /** 用 Shizuku 把电池白名单 / 后台限制一次性放开，返回是否成功 */
    private fun applyShizuku(): Boolean {
        val st = ShizukuKeepAlive.status(this)
        if (!st.running) {
            toast("Shizuku 未运行，请先打开 Shizuku")
            return false
        }
        if (!st.granted) {
            toast("请在 Shizuku 弹窗里允许 BotForge")
            return false
        }
        val a = ShizukuKeepAlive.whitelistBattery(this)
        val b = ShizukuKeepAlive.allowBackground(this)
        toast(if (a && b) "已放开后台限制" else "部分完成，详见日志")
        return true
    }

    private fun requestBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在白名单", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已授权", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ------------------------------------------------------------ 视图工具
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val tv = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = Switch(this@MainActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
            addView(tv)
            addView(sw)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}