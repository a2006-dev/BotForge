package com.ajx.botforge.ui

import android.widget.EditText
import android.widget.Switch
import com.ajx.botforge.data.Config

class PersonaEditActivity : BaseActivity() {

    override fun build() {
        val id = intent.getStringExtra("id")
        val exist = Config.loadPersonas().firstOrNull { it.id == id }
        title(if (exist == null) "新建人设" else "编辑人设")

        label("名称")
        val nameEt: EditText = input("例如：小饼", exist?.name ?: "")
        add(nameEt)

        label("人格 Prompt（核心）")
        val promptEt: EditText = input(
            "描述这个角色的身份、说话方式、世界观、禁区…",
            exist?.prompt ?: "",
            single = false
        )
        add(promptEt)

        label("语气补充（可空）")
        val toneEt: EditText = input("例如：短句、偶尔反问、不用书面语", exist?.tone ?: "")
        add(toneEt)

        label("称呼对方")
        val addrEt: EditText = input("你", exist?.address ?: "你")
        add(addrEt)

        label("记忆条数（上下文轮数量）")
        val depthEt: EditText = input("20", (exist?.memoryDepth ?: 20).toString())
        add(depthEt)

        label("温度（0~1.5）")
        val tempEt: EditText = input("0.8", (exist?.temperature ?: 0.8f).toString())
        add(tempEt)

        label("回复字数上限")
        val maxEt: EditText = input("400", (exist?.maxTokens ?: 400).toString())
        add(maxEt)

        val memSw = Switch(this).apply {
            isChecked = exist?.memoryEnabled ?: true
            text = "启用记忆"
        }
        add(memSw, 12)

        add(button("保存", primary = true) {
            val name = nameEt.text.toString().trim().ifBlank { "未命名" }
            val prompt = promptEt.text.toString().trim()
            if (prompt.isBlank()) {
                toast("人格 Prompt 不能为空")
                return@button
            }
            val p = Config.Persona(
                id = exist?.id ?: Config.newId("p"),
                name = name,
                prompt = prompt,
                tone = toneEt.text.toString().trim(),
                address = addrEt.text.toString().trim().ifBlank { "你" },
                memoryEnabled = memSw.isChecked,
                memoryDepth = depthEt.text.toString().trim().toIntOrNull() ?: 20,
                temperature = tempEt.text.toString().trim().toFloatOrNull() ?: 0.8f,
                maxTokens = maxEt.text.toString().trim().toIntOrNull() ?: 400
            )
            val list = Config.loadPersonas()
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) list[i] = p else list.add(p)
            Config.savePersonas(list)
            toast("已保存")
            finish()
        }, 20)

        add(button("返回") { finish() }, 8)
    }
}