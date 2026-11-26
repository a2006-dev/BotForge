package com.ajx.botforge.qq

import android.content.Context
import com.ajx.botforge.ai.AiEngine
import com.ajx.botforge.ai.ImageUtil
import com.ajx.botforge.ai.Intent
import com.ajx.botforge.ai.Memory
import com.ajx.botforge.ai.WebSearch
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.plugin.PluginRegistry
import com.ajx.botforge.plugin.PluginRunner

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
            // 注意：这里只做检查，不占用冷却。
            // 旧实现在此直接把 now 写入 lastReply，导致模型失败、图片加载失败、
            // 图片消息被过滤等"没有真正回复"的情况也消耗了冷却窗口，
            // 用户紧接着再发一句反而被静默丢弃。真正的占位在下方 markCooldown()。
        }

        // 指令
        val text = ev.content.trim()
        if (text.startsWith("/")) {
            if (handleCommand(ctx, bot, ev, text)) return
        }
        if (text.isEmpty() && ev.images.isEmpty()) return

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
        var userContent = if (text.isBlank()) "（发了图片，没配文字）" else text
        val hasImage = ev.images.isNotEmpty()
        if (!hasImage && Config.searchEnabled && shouldSearch(text)) {
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

        // 图片：下载 + 压缩 + 转 data URL
        var imagePayload: List<String> = emptyList()
        if (hasImage) {
            val loaded = ImageUtil.loadAll(ev.images, limit = 3)
            if (loaded.isEmpty()) {
                Logger.w("Img", "图片全部加载失败，按纯文字处理")
                if (text.isBlank()) {
                    reply(bot, ev, "图没加载出来，再发一次？")
                    return
                }
            } else {
                imagePayload = loaded.map { it.dataUrl }
                sb.append("\n\n【看图要求】用户发了 ${loaded.size} 张图片，请结合图片内容回答，说到图里的具体东西，别只说“我看到一张图片”。")
                Logger.i("Img", "准备 ${loaded.size} 张图送入模型")
            }
        }

        // 调用（失败重试 1 次，仍失败则给出可见提示，不再静默 —— F6）
        var answer = AiEngine.chat(
            model, sb.toString(), history, userContent,
            persona?.temperature ?: 0.8f,
            persona?.maxTokens ?: model.maxTokens,
            imagePayload
        )
        if (answer == null) {
            Logger.w("Msg", "模型无返回，1 秒后重试一次")
            Thread.sleep(1000)
            answer = AiEngine.chat(
                model, sb.toString(), history, userContent,
                persona?.temperature ?: 0.8f,
                persona?.maxTokens ?: model.maxTokens,
                imagePayload
            )
        }

        if (answer == null) {
            Logger.w("Msg", "模型重试仍失败，回一条提示")
            reply(bot, ev, "（我这边模型没响应，稍后再发一次？）")
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
        // 被动回复必须带上原消息 msg_id，且同一 msg_id 下的多条回复 msg_seq 需递增，
        // 否则平台会判定为重复或直接拒收
        var seq = 1
        var sent = 0
        chunks.forEach { c ->
            try {
                val ok = when (ev.scene) {
                    "group" -> QqApi.sendGroup(bot, ev.groupOpenId, c, ev.messageId.ifEmpty { null }, seq)
                    "c2c" -> QqApi.sendC2C(bot, ev.userOpenId, c, ev.messageId.ifEmpty { null }, seq)
                    else -> false
                }
                if (ok) sent++
                seq++
            } catch (e: Exception) {
                Logger.w("Msg", "发送失败: ${e.message}")
            }
        }
        // 只有真的发出去才占用冷却窗口（F10）
        if (sent > 0) markCooldown(bot, ev)
    }

    /** 记录该会话已回复，用于冷却判断 */
    private fun markCooldown(bot: Config.Bot, ev: BotClient.Event) {
        val k = "${bot.id}:${ev.scene}:${ev.groupOpenId ?: ev.userOpenId}"
        synchronized(lock) { lastReply[k] = System.currentTimeMillis() }
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
                reply(bot, ev, helpText())
                true
            }
            else -> handlePlugin(bot, ev, text, cmd)
        }
    }

    /** 生成帮助文本，内置指令 + 当前场景可用的插件指令 */
    private fun helpText(): String {
        val sb = StringBuilder("内置指令\n")
        sb.append("/reset 清空上下文\n/ping 测试在线\n/help 本说明\n")
        val plug = PluginRegistry.all()
        if (plug.isEmpty()) {
            sb.append("\n（未安装插件）")
        } else {
            sb.append("\n插件指令\n")
            for (p in plug) {
                for (c in p.commands) {
                    sb.append(c.usage).append(" — ").append(c.description).append("\n")
                }
            }
            sb.append("\n放插件到：\n").append(PluginRegistry.rootDir()?.absolutePath ?: "-")
        }
        return sb.toString().trim()
    }

    /**
     * 插件路由。返回 true 表示该消息已被插件处理。
     * 安全约束：
     *  - scope 未声明 group 的插件，群里不会执行
     *  - confirm 为 true 的指令需要二次确认（当前先提示，不执行）
     */
    private fun handlePlugin(
        bot: Config.Bot,
        ev: BotClient.Event,
        text: String,
        cmdWithSlash: String
    ): Boolean {
        if (!cmdWithSlash.startsWith("/")) return false
        val name = cmdWithSlash.removePrefix("/")
        if (name.isEmpty()) return false

        // /插件 列表
        if (name == "plugins" || name == "插件") {
            val list = PluginRegistry.all()
            if (list.isEmpty()) {
                reply(bot, ev, "还没有安装任何插件。\n把插件目录放到：\n${PluginRegistry.rootDir()?.absolutePath ?: "-"}")
            } else {
                val sb = StringBuilder("已安装 ${list.size} 个插件\n")
                for (p in list) {
                    sb.append("\n${p.name} v${p.version}  (${p.id})\n")
                    for (c in p.commands) sb.append("  ${c.usage}\n")
                }
                reply(bot, ev, sb.toString().trim())
            }
            return true
        }

        // /reload-plugins 重新扫描
        if (name == "reload-plugins" || name == "重载插件") {
            PluginRegistry.reload()
            reply(bot, ev, "已重新扫描，当前 ${PluginRegistry.all().size} 个插件，${PluginRegistry.commandNames().size} 条指令。")
            return true
        }

        val pc = PluginRegistry.find(name) ?: return false

        if (!pc.allows(ev.scene)) {
            reply(bot, ev, "指令 /${pc.command} 不允许在${if (ev.scene == "group") "群聊" else "私聊"}中使用。")
            return true
        }

        if (pc.confirm) {
            reply(bot, ev, "指令 /${pc.command} 需要确认后执行（确认功能尚未开放）。")
            return true
        }

        // 参数：指令名之后的部分按空白切分
        val args = text.trim().split(Regex("\\s+")).drop(1)
        val r = PluginRunner.run(pc, args)
        val body = if (r.ok) r.output else "❌ ${r.output}"
        reply(bot, ev, body)
        return true
    }
}