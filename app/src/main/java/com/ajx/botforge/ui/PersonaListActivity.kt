package com.ajx.botforge.ui

import android.content.Intent
import android.widget.LinearLayout
import com.ajx.botforge.data.Config

class PersonaListActivity : BaseActivity() {

    override fun build() {
        title("人设", "决定机器人怎么说话：人格 prompt、语气、记忆深度")
        add(button("+ 新建人设", primary = true) {
            startActivity(Intent(this, PersonaEditActivity::class.java))
        }, 12)

        val list = Config.loadPersonas()
        if (list.isEmpty()) {
            add(text("还没有人设。", 13f, 0xFF999999.toInt()), 16)
            return
        }
        list.forEach { p ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(p.name.ifBlank { "未命名" }, 16f, 0xFF111111.toInt()))
            box.addView(
                text(
                    "记忆 ${if (p.memoryEnabled) "${p.memoryDepth} 条" else "关闭"} · 温度 ${p.temperature} · 上限 ${p.maxTokens} 字",
                    12f, 0xFF888888.toInt()
                )
            )
            box.addView(text(p.prompt.take(70) + if (p.prompt.length > 70) "…" else "", 12f, 0xFF666666.toInt()))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("编辑") {
                startActivity(Intent(this, PersonaEditActivity::class.java).putExtra("id", p.id))
            })
            row.addView(button("删除") {
                confirm("删除人设「${p.name}」?") {
                    Config.savePersonas(Config.loadPersonas().filter { it.id != p.id })
                    recreate()
                }
            })
            box.addView(row)
            card(box, 10)
        }
    }
}