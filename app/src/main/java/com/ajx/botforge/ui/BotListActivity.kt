package com.ajx.botforge.ui

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import com.ajx.botforge.R
import com.ajx.botforge.data.Config

class BotListActivity : BaseActivity() {

    override fun build() {
        title("机器人", "QQ 开放平台的应用凭据，每份对应一个机器人")
        add(button("+ 新建机器人", primary = true) {
            startActivity(Intent(this, BotEditActivity::class.java))
        }, 12)

        val list = Config.loadBots()
        if (list.isEmpty()) {
            add(text("还没有机器人，点上面新建。", 13f, color(R.color.t_low)), 20)
            return
        }
        list.forEach { b ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(b.name.ifBlank { "未命名" }, 16f, color(R.color.t_hi)))
            box.addView(text("AppID ${b.appId}  ·  ${if (b.enabled) "已启用" else "已禁用"}", 12f, color(R.color.t_mid)))
            box.addView(text("群 ${b.groups.size} 个", 12f, color(R.color.t_mid)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("编辑") {
                startActivity(
                    Intent(this, BotEditActivity::class.java).putExtra("id", b.id)
                )
            })
            row.addView(button("群管理") {
                startActivity(
                    Intent(this, GroupListActivity::class.java).putExtra("botId", b.id)
                )
            })
            row.addView(button("删除") {
                confirm("删除机器人「${b.name}」?") {
                    Config.saveBots(Config.loadBots().filter { it.id != b.id })
                    recreate()
                }
            })
            box.addView(row)
            card(box, 10)
        }
    }
}