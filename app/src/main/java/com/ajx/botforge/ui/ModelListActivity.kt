package com.ajx.botforge.ui

import android.content.Intent
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.ajx.botforge.ai.AiEngine
import com.ajx.botforge.R
import com.ajx.botforge.data.Config
import java.util.concurrent.Executors

class ModelListActivity : BaseActivity() {

    private val pool = Executors.newSingleThreadExecutor()

    override fun build() {
        title("模型", "三路可切换：本机反代 / 官方 API / 本地模型，都走 OpenAI 兼容协议")
        add(button("+ 新建模型", primary = true) {
            startActivity(Intent(this, ModelEditActivity::class.java))
        }, 12)

        val list = Config.loadModels()
        if (list.isEmpty()) {
            add(text("还没有模型配置。", 13f, color(R.color.t_low)), 16)
            return
        }
        list.forEach { m ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(m.name.ifBlank { m.model }, 15f, color(R.color.t_hi)))
            box.addView(text("${m.model}  ·  ${m.type}", 12f, color(R.color.t_mid)))
            box.addView(text(m.baseUrl, 11f, color(R.color.t_low)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("测试") { test(m) })
            row.addView(button("编辑") {
                startActivity(Intent(this, ModelEditActivity::class.java).putExtra("id", m.id))
            })
            row.addView(button("删除") {
                confirm("删除模型「${m.name}」?") {
                    Config.saveModels(Config.loadModels().filter { it.id != m.id })
                    recreate()
                }
            })
            box.addView(row)
            card(box, 10)
        }
    }

    private fun test(m: Config.Model) {
        toast("正在测试…")
        pool.execute {
            val r = AiEngine.ping(m)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("连通性")
                    .setMessage(r.take(500))
                    .setPositiveButton("好", null)
                    .show()
            }
        }
    }
}