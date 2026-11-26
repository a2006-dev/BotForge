package com.ajx.botforge.ui

import android.widget.EditText
import com.ajx.botforge.data.Config

class ModelEditActivity : BaseActivity() {

    override fun build() {
        val id = intent.getStringExtra("id")
        val exist = Config.loadModels().firstOrNull { it.id == id }
        title(if (exist == null) "新建模型" else "编辑模型")

        label("名称")
        val nameEt: EditText = input("例如：本机反代", exist?.name ?: "")
        add(nameEt)

        label("Base URL")
        val baseEt: EditText = input(
            "http://127.0.0.1:7863/v1  或  https://api.openai.com/v1",
            exist?.baseUrl ?: ""
        )
        add(baseEt)
        add(
            text(
                "本机反代填 127.0.0.1:端口/v1；官方直连填官方地址；本地模型填局域网 IP。\n" +
                        "注意：本机反代若跑在电脑上，手机要用 192.168.x.x 而不是 127.0.0.1。",
                12f, 0xFF999999.toInt()
            ), 4
        )

        label("API Key")
        val keyEt: EditText = input("sk-…（本地模型可留空）", exist?.apiKey ?: "")
        add(keyEt)

        label("模型名")
        val modelEt: EditText = input("deepseek-v4.1-flash", exist?.model ?: "")
        add(modelEt)

        label("协议类型")
        val typeEt: EditText = input("openai / anthropic", exist?.type ?: "openai")
        add(typeEt)

        label("超时（秒）")
        val toEt: EditText = input("120", (exist?.timeout ?: 120).toString())
        add(toEt)

        label("默认回复上限")
        val maxEt: EditText = input("400", (exist?.maxTokens ?: 400).toString())
        add(maxEt)

        add(button("保存", primary = true) {
            val base = baseEt.text.toString().trim()
            val model = modelEt.text.toString().trim()
            if (base.isBlank() || model.isBlank()) {
                toast("Base URL 与模型名必填")
                return@button
            }
            val m = Config.Model(
                id = exist?.id ?: Config.newId("m"),
                name = nameEt.text.toString().trim().ifBlank { model },
                baseUrl = base,
                apiKey = keyEt.text.toString().trim(),
                model = model,
                type = typeEt.text.toString().trim().ifBlank { "openai" },
                timeout = toEt.text.toString().trim().toIntOrNull() ?: 120,
                maxTokens = maxEt.text.toString().trim().toIntOrNull() ?: 400
            )
            val list = Config.loadModels()
            val i = list.indexOfFirst { it.id == m.id }
            if (i >= 0) list[i] = m else list.add(m)
            Config.saveModels(list)
            toast("已保存")
            finish()
        }, 20)

        add(button("返回") { finish() }, 8)
    }
}