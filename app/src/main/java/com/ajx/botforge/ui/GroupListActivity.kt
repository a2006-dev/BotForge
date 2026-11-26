package com.ajx.botforge.ui

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import com.ajx.botforge.R
import com.ajx.botforge.data.Config

class GroupListActivity : BaseActivity() {

    private var botId: String = ""

    override fun build() {
        botId = intent.getStringExtra("botId") ?: ""
        val bot = Config.loadBots().firstOrNull { it.id == botId }
        if (bot == null) {
            title("群管理")
            add(text("机器人不存在", 13f, color(R.color.t_low)), 12)
            return
        }
        title("群管理", "机器人在哪些群说话、是否需要 @、冷却几秒")

        add(button("+ 添加群", primary = true) { editGroup(null) }, 12)

        if (bot.groups.isEmpty()) {
            add(
                text(
                    "还没有群。先在群里 @ 机器人发一句话，日志里会打印 " +
                            "「未配置的群 <openid>，忽略」，把那个 openid 填进来即可。",
                    13f, color(R.color.t_low)
                ), 16
            )
            return
        }

        bot.groups.forEach { g ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(g.note.ifBlank { g.groupOpenId }, 15f, color(R.color.t_hi)))
            box.addView(text(g.groupOpenId, 11f, color(R.color.t_low)))
            box.addView(
                text(
                    "${if (g.enabled) "启用" else "禁用"} · ${if (g.requireAt) "需要@" else "自由发言"} · 冷却 ${g.cooldownSec}s",
                    12f, color(R.color.t_mid)
                )
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("编辑") { editGroup(g) })
            row.addView(button("删除") {
                confirm("删除该群配置？") {
                    updateBot { it.copy(groups = it.groups.filter { x -> x.groupOpenId != g.groupOpenId }) }
                    recreate()
                }
            })
            box.addView(row)
            card(box, 10)
        }
    }

    private fun updateBot(f: (Config.Bot) -> Config.Bot) {
        val list = Config.loadBots()
        val i = list.indexOfFirst { it.id == botId }
        if (i >= 0) {
            list[i] = f(list[i])
            Config.saveBots(list)
        }
    }

    private fun editGroup(exist: Config.GroupCfg?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpNum(16), dpNum(8), dpNum(16), 0)
        }
        val openIdEt: EditText = input("group_openid", exist?.groupOpenId ?: "")
        val noteEt: EditText = input("备注，例如：测试群", exist?.note ?: "")
        val coolEt: EditText = input("冷却秒数", (exist?.cooldownSec ?: 5).toString())
        val enabledSw = Switch(this).apply { isChecked = exist?.enabled ?: true; text = "启用" }
        val atSw = Switch(this).apply { isChecked = exist?.requireAt ?: false; text = "需要 @" }

        box.addView(text("group_openid", 12f, color(R.color.t_mid)))
        box.addView(openIdEt)
        box.addView(text("备注", 12f, color(R.color.t_mid)))
        box.addView(noteEt)
        box.addView(text("冷却（秒）", 12f, color(R.color.t_mid)))
        box.addView(coolEt)
        box.addView(enabledSw)
        box.addView(atSw)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (exist == null) "添加群" else "编辑群")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val oid = openIdEt.text.toString().trim()
                if (oid.isBlank()) {
                    toast("group_openid 必填")
                    return@setPositiveButton
                }
                val item = Config.GroupCfg(
                    groupOpenId = oid,
                    note = noteEt.text.toString().trim(),
                    enabled = enabledSw.isChecked,
                    requireAt = atSw.isChecked,
                    cooldownSec = coolEt.text.toString().trim().toIntOrNull() ?: 5
                )
                updateBot { b ->
                    val gs = b.groups.toMutableList()
                    val idx = gs.indexOfFirst { it.groupOpenId == oid }
                    if (idx >= 0) gs[idx] = item else gs.add(item)
                    b.copy(groups = gs)
                }
                recreate()
            }
            .show()
    }
}