package com.ajx.botforge.ui

import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ajx.botforge.R
import com.ajx.botforge.data.Config

/**
 * 机器人列表 · v2
 *
 * 改造要点（相对旧实现）：
 *  - 每个机器人一张独立卡片，卡片内分「标题行 / 元信息 / 操作行」三层，
 *    不再是三行文字 + 三个等宽按钮堆在一起。
 *  - 顶部加状态胶囊（已启用/已禁用），状态用颜色表达而不是句子。
 *  - 「编辑」是主操作（品牌蓝），「群管理」次要，「删除」降为文字按钮并靠右，
 *    降低误触概率。
 *  - 空列表不再是干巴巴一行字，给一个带说明的空态卡。
 */
class BotListActivity : BaseActivity() {

    override fun build() {
        title("机器人", "QQ 开放平台应用凭据，每份对应一个机器人")

        add(button("+ 新建机器人", primary = true) {
            startActivity(Intent(this, BotEditActivity::class.java))
        }, dp(R.dimen.s4))

        val list = Config.loadBots()
        if (list.isEmpty()) {
            card(emptyCard(), dp(R.dimen.s4))
            return
        }

        // 统计行：真实数量，不写死
        val on = list.count { it.enabled }
        add(textOf(
            "共 ${list.size} 个 · $on 个已启用",
            R.dimen.f_sm,
            if (on == 0) R.color.warn else R.color.t_low
        ), dp(R.dimen.s4))

        list.forEach { b -> card(botCard(b), dp(R.dimen.s3)) }
    }

    // ------------------------------------------------------------ 空态

    private fun emptyCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(R.dimen.s5), 0, dp(R.dimen.s5))

        addView(textOf("还没有机器人", R.dimen.f_lg, R.color.t_hi).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(textOf("点上方按钮新建，填入 AppID 与 AppSecret", R.dimen.f_sm, R.color.t_mid).apply {
            setPadding(0, dp(R.dimen.s1), 0, 0)
        })
    }

    // ------------------------------------------------------------ 单张卡片

    private fun botCard(b: Config.Bot): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // ---- 标题行：名字 + 状态胶囊
        addView(LinearLayout(this@BotListActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(textOf(b.name.ifBlank { "未命名" }, R.dimen.f_lg, R.color.t_hi).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pill(
                if (b.enabled) "已启用" else "已禁用",
                if (b.enabled) R.color.run else R.color.idle,
                if (b.enabled) R.drawable.pill_run else R.drawable.pill_idle
            ))
        })

        // ---- 元信息：AppID / 群数 / 绑定情况
        addView(textOf("AppID ${b.appId}", R.dimen.f_sm, R.color.t_mid).apply {
            setPadding(0, dp(R.dimen.s2), 0, 0)
        })
        addView(textOf(
            "群 ${b.groups.size} 个（${b.groups.count { it.enabled }} 启用）",
            R.dimen.f_cap,
            R.color.t_low
        ))
        // 未绑定人设/模型时给警示色，这是真实缺配置，不是装饰
        if (b.personaId.isBlank() || b.modelId.isBlank()) {
            addView(textOf("未绑定人设或模型，无法回复", R.dimen.f_cap, R.color.warn))
        }

        // ---- 分隔
        addView(View(this@BotListActivity).apply {
            setBackgroundColor(color(R.color.line))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(R.dimen.hair)
            ).apply { topMargin = dp(R.dimen.s3) }
        })

        // ---- 操作行
        addView(LinearLayout(this@BotListActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(R.dimen.s2), 0, 0)

            addView(smallBtn("编辑", true) {
                startActivity(Intent(this@BotListActivity, BotEditActivity::class.java).putExtra("id", b.id))
            })
            addView(smallBtn("群管理", false) {
                startActivity(Intent(this@BotListActivity, GroupListActivity::class.java).putExtra("botId", b.id))
            })
            addView(View(this@BotListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(textOf("删除", R.dimen.f_sm, R.color.danger).apply {
                setPadding(dp(R.dimen.s3), dp(R.dimen.s2), 0, dp(R.dimen.s2))
                isClickable = true
                setOnClickListener {
                    confirm("删除机器人「${b.name}」？此操作不可撤销。") {
                        Config.saveBots(Config.loadBots().filter { it.id != b.id })
                        recreate()
                    }
                }
            })
        })
    }

    // ------------------------------------------------------------ 小组件

    /** 状态胶囊 */
    private fun pill(t: String, colorId: Int, bgId: Int): TextView =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_cap))
            setTextColor(color(colorId))
            background = ContextCompat.getDrawable(this@BotListActivity, bgId)
            setPadding(dp(R.dimen.s2), dp(R.dimen.s1) / 2, dp(R.dimen.s2), dp(R.dimen.s1) / 2)
        }

    /**
     * 卡片内的小按钮。
     * 注意：不能复用 BaseActivity.button()，那个固定 match_parent 宽高、只能用在竖向容器里。
     */
    private fun smallBtn(t: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(R.dimen.f_sm))
            setTextColor(color(if (primary) R.color.brand else R.color.t_hi))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(
                this@BotListActivity,
                if (primary) R.drawable.sh_btn_primary else R.drawable.sh_btn_ghost
            )
            setPadding(dp(R.dimen.s3), dp(R.dimen.s2), dp(R.dimen.s3), dp(R.dimen.s2))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(R.dimen.h_btn_sm)
            ).apply { rightMargin = dp(R.dimen.s2) }
            setOnClickListener { onClick() }
        }
}