package com.ajx.botforge.ui

import android.widget.EditText
import android.widget.Switch
import com.ajx.botforge.R
import com.ajx.botforge.data.Config

class BotEditActivity : BaseActivity() {

    private lateinit var nameEt: EditText
    private lateinit var appIdEt: EditText
    private lateinit var secretEt: EditText
    private lateinit var enabledSw: Switch
    private var editingId: String? = null

    override fun build() {
        editingId = intent.getStringExtra("id")
        val exist = Config.loadBots().firstOrNull { it.id == editingId }
        title(if (exist == null) "新建机器人" else "编辑机器人")

        label("备注名")
        nameEt = input("例如：404ERROR", exist?.name ?: "")
        add(nameEt)

        label("AppID")
        appIdEt = input("QQ 开放平台 AppID", exist?.appId ?: "")
        add(appIdEt)

        label("AppSecret")
        secretEt = input("AppSecret", exist?.appSecret ?: "")
        add(secretEt)

        label("私域 Intents")
        val intentsEt = input("1174409216", (exist?.intents ?: 1174409216L).toString())
        add(intentsEt)
        add(text("默认 1174409216：群聊@+私聊+群全量。若只收 @ 消息可改 1174405120。", 12f, color(R.color.t_low)), 4)

        label("启用")
        enabledSw = Switch(this).apply { isChecked = exist?.enabled ?: true }
        add(enabledSw)

        add(button("保存", primary = true) {
            val name = nameEt.text.toString().trim()
            val appId = appIdEt.text.toString().trim()
            val secret = secretEt.text.toString().trim()
            if (appId.isBlank() || secret.isBlank()) {
                toast("AppID 和 AppSecret 必填")
                return@button
            }
            val list = Config.loadBots()
            val intents = intentsEt.text.toString().trim().toLongOrNull() ?: 1174409216L
            if (exist == null) {
                val p = Config.loadPersonas().firstOrNull()
                val m = Config.loadModels().firstOrNull()
                list.add(
                    Config.Bot(
                        id = Config.newId("b"),
                        name = name.ifBlank { "Bot" },
                        appId = appId,
                        appSecret = secret,
                        personaId = p?.id ?: "",
                        modelId = m?.id ?: "",
                        enabled = enabledSw.isChecked,
                        intents = intents
                    )
                )
            } else {
                val idx = list.indexOfFirst { it.id == exist.id }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(
                        name = name.ifBlank { "Bot" },
                        appId = appId,
                        appSecret = secret,
                        enabled = enabledSw.isChecked,
                        intents = intents
                    )
                }
            }
            Config.saveBots(list)
            toast("已保存")
            finish()
        }, 20)

        add(button("返回") { finish() }, 8)
    }
}