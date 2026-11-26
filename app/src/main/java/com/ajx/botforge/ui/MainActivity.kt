package com.ajx.botforge.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ajx.botforge.R
import com.ajx.botforge.data.Config
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.service.BotService
import com.ajx.botforge.service.ShizukuKeepAlive

/**
 * 首页：真实状态 + 主操作 + 功能入口 + 保活授权
 *
 * 重做要点（针对上一版暴露的问题）：
 *  - 所有颜色 / 尺寸走 R.color、R.dimen，不再出现 0xFFxxxxxx。
 *  - 状态区改为独立卡片，状态用圆点 + 文字 + 语义色，不再是纯文本堆叠。
 *  - 主操作（启动/停止）做成品牌色大按钮，与次级入口视觉分离。
 *  - 开关用自绘轨道与滑块，避免系统默认灰。
 *  - 功能入口做成带箭头的行式列表，而不是一排等重按钮。
 */
class MainActivity : AppCompatActivity() {

    // 绑定后由 refresh() 更新
    private lateinit var dot: View
    private lateinit var stateText: TextView
    private lateinit var statePill: TextView
    private lateinit var labelBots: TextView
    private lateinit var labelPersonas: TextView
    private lateinit var labelModels: TextView
    private lateinit var labelSchedule: TextView
    private lateinit var mainBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.ink_1))
            setPadding(dp(R.dimen.s5), dp(R.dimen.s6), dp(R.dimen.s5), dp(R.dimen.s8))
        }

        // ---------- 标题区 ----------
        page.addView(TextView(this).apply {
            text = "BotForge"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_hero))
            setTextColor(c(R.color.t_hi))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })

        page.addView(TextView(this).apply {
            text = "安卓常驻 QQ 机器人客户端"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_sm))
            setTextColor(c(R.color.t_mid))
            setPadding(0, dp(R.dimen.s1), 0, 0)
        })

        // ---------- 状态卡 ----------
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_card)
            setPadding(dp(R.dimen.s4), dp(R.dimen.s4), dp(R.dimen.s4), dp(R.dimen.s4))
            layoutParams = matchWrap(top = dp(R.dimen.s5))

            // 第一行：状态点 + 状态文字 + 胶囊
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                dot = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(R.dimen.dot), dp(R.dimen.dot))
                    setBackgroundColor(c(R.color.idle))
                    background = dotDrawable(c(R.color.idle))
                }
                addView(dot)

                stateText = TextView(this@MainActivity).apply {
                    text = "读取中…"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_lg))
                    setTextColor(c(R.color.t_hi))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setPadding(dp(R.dimen.s2), 0, 0, 0)
                }
                addView(stateText)

                addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                statePill = TextView(this@MainActivity).apply {
                    text = "—"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_cap))
                    setTextColor(c(R.color.idle))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setPadding(dp(R.dimen.s2), dp(R.dimen.s1) / 2, dp(R.dimen.s2), dp(R.dimen.s1) / 2)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.pill_idle)
                }
                addView(statePill)
            })

            // 分隔
            addView(View(this@MainActivity).apply {
                setBackgroundColor(c(R.color.line))
                layoutParams = matchWrap(top = dp(R.dimen.s3), h = dp(R.dimen.hair))
            })

            // 四格数据
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = matchWrap(top = dp(R.dimen.s3))

                labelBots = metric(this@MainActivity, addView, "机器人")
                labelPersonas = metric(this@MainActivity, addView, "人设")
                labelModels = metric(this@MainActivity, addView, "模型")
            })

            // 调度状态单独一行（可能较长）
            labelSchedule = TextView(this@MainActivity).apply {
                text = "调度：—"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_sm))
                setTextColor(c(R.color.t_mid))
                setPadding(0, dp(R.dimen.s2), 0, 0)
            }
            addView(labelSchedule)
        })

        // ---------- 主操作 ----------
        mainBtn = Button(this).apply {
            text = "启动"
            isAllCaps = false
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_lg))
            setTextColor(c(R.color.t_hi))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_btn_primary)
            layoutParams = matchWrap(top = dp(R.dimen.s4), h = dp(R.dimen.h_btn))
            setOnClickListener { toggle() }
        }
        page.addView(mainBtn)

        // ---------- 功能入口 ----------
        page.addView(sectionTitle("管理"))

        page.addView(navRow("机器人配置", "AppID / Secret / 群策略") { go(BotListActivity::class.java) }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(navRow("人设管理", "角色设定与语气") { go(PersonaListActivity::class.java) }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(navRow("模型配置", "接口地址与密钥") { go(ModelListActivity::class.java) }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(navRow("运行日志", "查看连接与报错") { go(LogActivity::class.java) }, matchWrap(top = dp(R.dimen.s2)))

        // ---------- 开关 ----------
        page.addView(sectionTitle("运行偏好"))

        page.addView(switchRow("开机自启", "重启后自动拉起服务", Config.autoStart) { Config.autoStart = it }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(switchRow("保活增强", "守护进程 + 定时重启", Config.keepAlive) { Config.keepAlive = it }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(switchRow("联网搜索", "回答前先检索最新信息", Config.searchEnabled) { Config.searchEnabled = it }, matchWrap(top = dp(R.dimen.s2)))

        // ---------- 授权 ----------
        page.addView(sectionTitle("系统授权"))

        page.addView(navRow("忽略电池优化", "避免后台被系统冻结") { requestBattery() }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(navRow("悬浮窗权限", "用于状态浮层") { requestOverlay() }, matchWrap(top = dp(R.dimen.s2)))
        page.addView(navRow("Shizuku 增强", "一键放开后台限制") {
            if (!applyShizuku()) ShizukuKeepAlive.request(this)
        }, matchWrap(top = dp(R.dimen.s2)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(c(R.color.ink_1))
            isFillViewport = true
            addView(page)
        })

        seedIfEmpty()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ 状态刷新

    private fun refresh() {
        val running = BotService.instance != null
        val bots = Config.loadBots()
        val personas = Config.loadPersonas()
        val models = Config.loadModels()

        // 主状态
        stateText.text = if (running) "服务运行中" else "服务已停止"
        val accent = if (running) R.color.run else R.color.idle
        dot.background = dotDrawable(c(accent))
        statePill.text = if (running) "ONLINE" else "OFFLINE"
        statePill.setTextColor(c(accent))
        statePill.background = ContextCompat.getDrawable(
            this,
            if (running) R.drawable.pill_run else R.drawable.pill_idle
        )

        // 数据格
        labelBots.text = "${bots.size} 个（启用 ${bots.count { it.enabled }}）"
        labelPersonas.text = "${personas.size} 个"
        labelModels.text = "${models.size} 个"
        labelSchedule.text = "调度：" + BotManager.status()

        // 数据为 0 时给个视觉提醒（用警示色，提示需要配置）
        labelBots.setTextColor(c(if (bots.isEmpty()) R.color.warn else R.color.t_hi))
        labelModels.setTextColor(c(if (models.isEmpty()) R.color.warn else R.color.t_hi))

        // 主按钮
        mainBtn.text = if (running) "停止服务" else "启动服务"
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
        mainBtn.postDelayed({ refresh() }, 600)
    }

    // ------------------------------------------------------------ 视图构建

    /** 分区标题 */
    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_sm))
        setTextColor(c(R.color.t_low))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(dp(R.dimen.s1), dp(R.dimen.s6), 0, 0)
        layoutParams = matchWrap()
    }

    /** 数据格：标题 + 数值竖排，横向均分 */
    private fun metric(
        ctx: MainActivity,
        addTo: (View, ViewGroup.LayoutParams) -> Unit,
        caption: String
    ): TextView {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(ctx).apply {
            text = caption
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_cap))
            setTextColor(c(R.color.t_low))
        })
        val value = TextView(ctx).apply {
            text = "—"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_md))
            setTextColor(c(R.color.t_hi))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(R.dimen.s1) / 2, 0, 0)
        }
        box.addView(value)
        addTo(box, box.layoutParams)
        return value
    }

    /** 行式入口：左标题+副标题，右箭头 */
    private fun navRow(title: String, desc: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_card)
            setPadding(dp(R.dimen.s4), dp(R.dimen.s3), dp(R.dimen.s4), dp(R.dimen.s3))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_md))
                    setTextColor(c(R.color.t_hi))
                })
                addView(TextView(this@MainActivity).apply {
                    text = desc
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_sm))
                    setTextColor(c(R.color.t_mid))
                    setPadding(0, dp(R.dimen.s1) / 2, 0, 0)
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_xl))
                setTextColor(c(R.color.t_low))
                setPadding(dp(R.dimen.s2), 0, 0, 0)
            })
        }

    /** 开关行 */
    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_card)
        setPadding(dp(R.dimen.s4), dp(R.dimen.s3), dp(R.dimen.s3), dp(R.dimen.s3))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_md))
                setTextColor(c(R.color.t_hi))
            })
            addView(TextView(this@MainActivity).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(R.dimen.f_sm))
                setTextColor(c(R.color.t_mid))
                setPadding(0, dp(R.dimen.s1) / 2, 0, 0)
            })
        })

        addView(Switch(this@MainActivity).apply {
            isChecked = checked
            trackDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_switch_track)
            thumbDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.sh_switch_thumb)
            setPadding(0, 0, 0, 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    // ------------------------------------------------------------ 业务逻辑（保持原实现）

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

    /** 用 Shizuku 把电池白名单 / 后台限制一次性放开，返回是否成功 */
    private fun applyShizuku(): Boolean {
        val st = ShizukuKeepAlive.status(this)
        if (!st.running) {
            Toast.makeText(this, "Shizuku 未运行，请先打开 Shizuku", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!st.granted) {
            Toast.makeText(this, "请在 Shizuku 弹窗里允许 BotForge", Toast.LENGTH_SHORT).show()
            return false
        }
        val a = ShizukuKeepAlive.whitelistBattery(this)
        val b = ShizukuKeepAlive.allowBackground(this)
        Toast.makeText(this, if (a && b) "已放开后台限制" else "部分完成，详见日志", Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------------------ 工具

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }

    private fun c(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(id: Int): Int = resources.getDimensionPixelSize(id)

    /** sp 值换算为 px，保证跟随系统字体缩放 */
    private fun sp(id: Int): Float {
        val v = resources.getDimension(id)
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, v, resources.displayMetrics)
    }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun matchWrap(top: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = top
        }
}