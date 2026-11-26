package com.ajx.botforge.qq

import android.content.Context
import com.ajx.botforge.ai.AiEngine
import com.ajx.botforge.ai.Intent
import com.ajx.botforge.ai.Memory
import com.ajx.botforge.ai.WebSearch
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger

/**
 * 消息处理：去重 → 群策略校验 → 上下文召回 → 意图识别 → 可选联网 → 调模型 → 回复
 */
object MessageHandler {

    private val handled = object : LinkedHashMap<String, Long>(400, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 400
    }
    private val lastReply = mutableMapOf<String, Long>()
    private val lock = Any()

    fun handle(ctx: Context, bot: Config.Bot, ev: BotClient.Event) {
        val key = "${ev.scene}:${ev.messageId}"
        synchronized(lock) {
            if (handled.containsKey(key)) return
            handled[key] = System.currentTimeMillis()
        }

        // 群策略
        var cooldown = 5
        if (ev.scene == "group") {
            val g = bot.groups.firstOrNull { it.groupOpenId == ev.groupOpenId }
            if (g == null || !g.enabled) {
                Logger.i("Msg", "群未启用，忽略 ${ev.groupOpenId}")
                return
            }
            cooldown = g.cooldownSec
        }

        // 冷却（同一会话内）
        val sKey = "${bot.id}:${ev.scene}:${ev.groupOpenId ?: ev.userOpenId}"
        synchronized(lock) {
            val last = lastReply[sKey] ?: 0L
            val now = System.currentTimeMillis()
            if (now - last < cooldown * 1000L) {
                Logger.i("Msg", "冷却中，跳过")
                return
            }
            lastReply[sKey] = now
        }

        // 指令
        val text = ev.content.trim()
        if (text.startsWith("/")) {
            if (handleCommand(ctx, bot, ev, text)) return
        }
        if (text.isEmpty()) return

        // 找模型与人设
        val personas = Config.loadPersonas()
        val models = Config.loadModels()
        val persona = personas.firstOrNull { it.id == bot.personaId }
            ?: personas.firstOrNull()
        val model = models.firstOrNull { it.id == bot.modelId }
            ?: models.firstOrNull()
        if (model == null) {
            Logger.w("Msg", "没有可用模型配置")
            reply(bot, ev, "（未配置模型，请在 BotForge 里添加）")
            return
        }

        // 意图
        val kind = Intent.classify(text)
        val hint = Intent.hint(kind)

        // 记忆
        val memKey = if (ev.scene == "group") {
            "${bot.id}_g_${ev.groupOpenId}"
        } else {
            "${bot.id}_c_${ev.userOpenId}"
        }
        val depth = persona?.memoryDepth ?: 20
        val history = if (persona?.memoryEnabled != false) {
            Memory.recent(ctx, memKey, depth).map { AiEngine.Msg(it.role, it.content) }
        } else {
            emptyList()
        }

        // system
        val sb = StringBuilder()
        if (persona != null) {
            sb.append(persona.prompt)
            if (persona.tone.isNotBlank()) sb.append("\n\n【语气要求】").append(persona.tone)
        } else {
            sb.append("你是一个 QQ 群聊里的普通成员，说话自然口语化，别太长。")
        }
        if (hint.isNotBlank()) sb.append("\n\n【本轮语境】").append(hint)
        sb.append("\n\n【输出要求】直接输出要发到聊天里的话，不要加引号、不要写角色名、不要旁白描写，控制在 120 字内。")

        // 联网
        var userContent = text
        if (Config.searchEnabled && shouldSearch(text)) {
            try {
                val q = text
                Logger.i("Search", "检索: $q")
                val items = WebSearch.search(q, 4)
                val block = WebSearch.toPrompt(q, items)
                if (block.isNotBlank()) {
                    userContent = "$text\n\n$block"
                    Logger.i("Search", "命中 ${items.size} 条")
                }
            } catch (e: Exception) {
                Logger.w("Search", "检索失败: ${e.message}")
            }
        }

        // 调用
        val answer = AiEngine.chat(
            model, sb.toString(), history, userContent,
            persona?.temperature ?: 0.8f,
            persona?.maxTokens ?: model.maxTokens
        )

        if (answer == null) {
            Logger.w("Msg", "模型无返回，本次不回复")
            return
        }

        Logger.i("Msg", "[${ev.scene}] ${ev.content.take(40)} → ${answer.take(40)}")

        if (persona?.memoryEnabled != false) {
            Memory.append(ctx, memKey, "user", text)
            Memory.append(ctx, memKey, "assistant", answer)
        }

        reply(bot, ev, answer)
    }

    private fun shouldSearch(text: String): Boolean = when (Config.searchTrigger) {
        "always" -> true
        "never" -> false
        "keyword" -> Intent.maybeNeedsSearch(text)
        else -> Intent.maybeNeedsSearch(text)   // model 模式先用关键词做代理，避免每次都搜
    }

    private fun reply(bot: Config.Bot, ev: BotClient.Event, text: String) {
        val chunks = splitMessage(text)
        chunks.forEach { c ->
            try {
                when (ev.scene) {
                    "group" -> QqApi.sendGroup(bot, ev.groupOpenId!!, c)
                    "c2c" -> QqApi.sendC2C(bot, ev.userOpenId!!, c)
                }
            } catch (e: Exception) {
                Logger.w("Msg", "发送失败: ${e.message}")
            }
        }
    }

    /** QQ 单条上限保守取 1200，按标点断句 */
    fun splitMessage(text: String, limit: Int = 1200): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            var cut = -1
            for (p in listOf("\n", "。", "！", "？", "；", " ")) {
                val i = rest.lastIndexOf(p, limit)
                if (i > limit / 3) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= 0) cut = limit
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    // ------------------------------------------------------------ 内置指令
    private fun handleCommand(
        ctx: Context,
        bot: Config.Bot,
        ev: BotClient.Event,
        text: String
    ): Boolean {
        val cmd = text.substringBefore(' ').lowercase()
        val memKey = if (ev.scene == "group") {
            "${bot.id}_g_${ev.groupOpenId}"
        } else {
            "${bot.id}_c_${ev.userOpenId}"
        }
        return when (cmd) {
            "/reset", "/清空" -> {
                Memory.clear(ctx, memKey)
                reply(bot, ev, "上下文已清空。")
                true
            }
            "/ping", "/在吗" -> {
                reply(bot, ev, "在。")
                true
            }
            "/help", "/帮助" -> {
                reply(
                    bot, ev,
                    "可用指令：/reset 清空上下文 · /ping 测试在线 · /help 本说明"
                )
                true
            }
            else -> false
        }
    }
}