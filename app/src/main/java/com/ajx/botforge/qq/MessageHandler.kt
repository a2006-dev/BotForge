package com.ajx.botforge.qq

import android.content.Context
import com.ajx.botforge.ai.AiEngine
import com.ajx.botforge.ai.ApiPool
import com.ajx.botforge.ai.ImageUtil
import com.ajx.botforge.ai.Intent
import com.ajx.botforge.ai.Memory
import com.ajx.botforge.ai.WebSearch
import com.ajx.botforge.data.AuthStore
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.GroupRegistry
import com.ajx.botforge.data.Logger
import com.ajx.botforge.ai.tool.AgentLoop
import com.ajx.botforge.ai.tool.BuiltinTools
import com.ajx.botforge.plugin.PluginGuard
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
            // 先登记这个群，再判策略。
            // 顺序很重要：官方没有「列出全部群」的接口，群是被动发现的。
            // 如果先判策略再登记，那些还没被用户配置过的群永远进不了
            // 「已发现群」列表 —— 用户就永远无从开启它们，形成死锁。
            // 所以这里先记一笔（纯观察，不产生任何回复），再按配置决定是否响应。
            runCatching {
                GroupRegistry.noteGroupEvent(bot.id, ev.groupOpenId, ev.userOpenId)
            }
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
        // 工具使用说明。只在本次真的带工具时注入，避免平时聊天空耗 token。
        // 这段按 Operit 的写法来：先讲清「你有能力做什么」，再给调用纪律，
        // 模型才知道什么时候该调、调完怎么回话。
        // 条件与后面 useAgent 一致：只有真的会带上工具时才讲工具。
        val willUseTools = Config.agentEnabled && botAllowedTools(bot, ev)
        if (willUseTools) {
            sb.append(TOOL_GUIDE)
        }

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

        // 调用：有工具权限且无图时走 AgentLoop，否则走原路径
        // （有图时模型更容易乱调工具，且多模态+工具混用部分反代支持不稳，故保守处理）
        val useAgent = Config.agentEnabled && imagePayload.isEmpty() && botAllowedTools(bot, ev)
        var answer: String? = null
        var usedTools: List<String> = emptyList()

        if (useAgent) {
            val tools = BuiltinTools.all()
            val r = AgentLoop.run(
                model = model,
                system = sb.toString(),
                history = history,
                user = userContent,
                temperature = persona?.temperature ?: 0.8f,
                maxTokens = persona?.maxTokens ?: model.maxTokens,
                tools = tools,
                onToolCall = { name, args ->
                    PluginGuard.audit(ev.scene, ev.userOpenId, ev.groupOpenId, "tool:$name", true, args.toString())
                }
            )
            answer = r?.text
            usedTools = r?.usedTools ?: emptyList()
        } else {
            answer = ApiPool.chat(
                model, sb.toString(), history, userContent,
                persona?.temperature ?: 0.8f,
                persona?.maxTokens ?: model.maxTokens,
                imagePayload
            )
        }

        if (answer == null) {
            Logger.w("Msg", "模型无返回，1 秒后重试一次")
            Thread.sleep(1000)
            answer = ApiPool.chat(
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

        if (usedTools.isNotEmpty()) {
            Logger.i("Msg", "本轮调用工具: ${usedTools.joinToString()}")
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
            // ------------------------------------------------ 主人绑定
            // 参考云崽：#设置主人 → 机器人回验证码 → 用户原样回复。
            // 本项目把「生成」放在 APP 里（避免任何人在群里就能触发绑定流程，
            // 那样等于把主人位开放给第一个看到消息的人），
            // 群里只负责「校验并绑定」，所以这里只有一条指令。
            "/绑定主人", "/bindowner", "/设置主人" -> {
                val code = text.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
                if (code.isEmpty()) {
                    reply(bot, ev, "用法：/绑定主人 验证码\n验证码请先在 BotForge APP 的「主人绑定」页生成。")
                    return true
                }
                if (AuthStore.hasOwner) {
                    val isMe = AuthStore.ownerOpenId == ev.userOpenId
                    reply(
                        bot, ev,
                        if (isMe) "你已经是主人了，无需重复绑定。"
                        else "本机器人已绑定主人，如需更换请先在 APP 里解除绑定。"
                    )
                    return true
                }
                val r = AuthStore.consumeCode(code)
                r.onSuccess {
                    AuthStore.bindOwner(ev.userOpenId, ev.raw.optString("username", ""))
                    Logger.i("Auth", "主人已绑定：${AuthStore.mask(ev.userOpenId)}")
                    reply(
                        bot, ev,
                        "✅ 绑定成功，你现在是本机器人的主人。\n" +
                            "openid：${AuthStore.mask(ev.userOpenId)}\n" +
                            "发送 /help 查看可用指令。"
                    )
                }.onFailure { e ->
                    reply(bot, ev, "❌ " + (e.message ?: "绑定失败"))
                }
                true
            }
            "/解除主人", "/unbindowner" -> {
                if (!AuthStore.isOwnerOnly(ev.userOpenId)) {
                    reply(bot, ev, "⛔ 只有主人本人可以解除绑定。")
                    return true
                }
                AuthStore.unbindOwner()
                reply(bot, ev, "已解除主人绑定。")
                true
            }
            // ------------------------------------------------ 授权管理
            "/授权", "/addadmin" -> {
                if (!AuthStore.isOwnerOnly(ev.userOpenId)) {
                    reply(bot, ev, "⛔ 只有主人可以授予管理员。")
                    return true
                }
                val arg = text.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
                // 不带参数 = 把当前群设为管理员范围，授权给「发送者」自身。
                // 这是群里最常用的路径：主人在某个群里发 /授权，就把自己设成该群管理员。
                val target = if (arg.isEmpty()) ev.userOpenId else arg
                val scope = if (ev.scene == "group") ev.groupOpenId else ""
                val r = AuthStore.addAdmin(
                    AuthStore.Admin(openId = target, scope = scope, note = "由主人授权")
                )
                reply(
                    bot, ev,
                    r.fold(
                        onSuccess = {
                            "✅ 已授权管理员：${AuthStore.mask(target)}\n" +
                                "范围：" + (if (scope.isEmpty()) "全局（任何场景生效）" else "仅本群")
                        },
                        onFailure = { "❌ " + (it.message ?: "授权失败") }
                    )
                )
                true
            }
            "/取消授权", "/rmadmin" -> {
                if (!AuthStore.isOwnerOnly(ev.userOpenId)) {
                    reply(bot, ev, "⛔ 只有主人可以撤销管理员。")
                    return true
                }
                val arg = text.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
                val target = if (arg.isEmpty()) ev.userOpenId else arg
                val scope = if (ev.scene == "group") ev.groupOpenId else ""
                // 先尝试精确匹配（同群），再退化到全局条目
                var ok = AuthStore.removeAdmin(target, scope)
                if (!ok) ok = AuthStore.removeAdmin(target, "")
                reply(
                    bot, ev,
                    if (ok) "已撤销管理员：${AuthStore.mask(target)}" else "未找到该管理员。"
                )
                true
            }
            else -> handlePlugin(bot, ev, text, cmd)
        }
    }

    /** 判断本次对话是否允许模型调用工具（L2 安全闸门） */
    private fun botAllowedTools(bot: Config.Bot, ev: BotClient.Event): Boolean {
        // 群聊里模型不能自主执行工具，只允许私聊
        if (ev.scene != "c2c") return false
        // 复用插件白名单：能被允许跑插件的人，才允许让 AI 调工具
        return PluginGuard.check(ev.scene, ev.userOpenId, ev.groupOpenId) == null
    }

    /**
     * 工具使用说明，注入 system prompt。
     *
     * 关键在最后两条：模型很容易「说要做什么」但不去调用工具，
     * 或者调完工具后把原始输出直接甩给用户。这两条把两个坑都堵上。
     */
    private val TOOL_GUIDE = """

【你有一台真的 Linux 手机】
你可以调用工具来真的做事，不是只能聊天。可用工具：
- run_command：在手机的 Linux 环境（Ubuntu，proot 内 root 身份）里执行 shell 命令。
  包管理器是 apt（apt update / apt install -y xxx），跑 Python 用 python3，
  curl、git、gcc 都有；缺什么先装。
  手机文件在 /sdcard 下可见。命令默认 120 秒超时，跑久一点可传 timeout（最大 300）。
  第一条命令可能较慢（远端要起一次常驻 shell），后续会复用，不要因为慢就以为失败。
  注意：这里没有 Android 系统权限，调不动 am / pm / settings 这类命令。
- system_command：在 Android 系统 shell（ADB 调试身份）里执行命令，需要用户授权 Shizuku。
  打开 App 用 am start -n 包名/Activity；查已装应用用 pm list packages；
  读改系统设置用 settings get/put；模拟点击用 input tap x y；截图用 screencap。
  这里没有 Linux 用户空间，没有 apt、没有 python3，要装软件请用 run_command。
  如果返回「Shizuku 未运行」或「未获授权」，就把这个情况如实告诉用户，
  让他去 BotForge 设置页处理，不要换个命令反复重试。
- diagnose_terminal：诊断 Linux 环境起不来的原因，把自检结果写到
  /sdcard/Download/bf_terminal_diag.txt。run_command 报错、提示找不到命令或
  怀疑环境没起来时，先调它，再看结果判断。
- read_file / write_file：读写文本文件，路径可用 /sdcard/... 或容器内 /root/...。
- list_plugins / run_plugin：查看和执行手机端插件。

【什么时候用工具】
凡是需要「真的查/真的算/真的改」的请求，直接调用工具，别凭记忆编。
例如查磁盘、看时间、算数、找文件、装依赖、写脚本、跑代码。
纯闲聊、问观点、接梗时不要调工具。

【能力与人设的关系】
你的人设（上面那段）只是说话风格和性格，不是能力边界。
即使人设是「普通群成员」「路人」，你依然拥有上面这些工具，能真的操作这台手机。
绝对不要说「我哪来的终端」「我就是个普通网友没法用命令」这类话——那是错的，你有。
别人让你调用终端或工具时，直接调用，不要推托给管理员。

【纪律】
1. 一次只做一件事，等工具结果回来再决定下一步；不要在一条回复里假装结果。
2. 命令失败时先看错误信息，换个写法重试一次；仍失败就如实告诉用户，不要硬凑。
3. 拿到工具输出后，用你自己的话把结论说给用户听，不要把原始终端输出整段贴出来。
4. 不要执行删除系统文件、改系统分区这类不可逆操作，除非用户明确要求。"""

    /** 生成帮助文本，内置指令 + 当前场景可用的插件指令 */
    private fun helpText(): String {
        val sb = StringBuilder("内置指令\n")
        sb.append("/{reset,clear} 清空上下文\n/{ping,在吗} 测试在线\n/{help,帮助} 本说明\n")
        sb.append("/{whoami,我是谁} 查看我的 openid 与角色\n")
        sb.append("/allow 把自己加入插件白名单\n")
        sb.append("/plugins 插件列表 · /reload-plugins 重扫目录\n")
        // 权限相关指令只在有主人才提示，避免无关用户看到一堆用不了的命令
        if (AuthStore.hasOwner) {
            sb.append("\n权限指令\n")
            sb.append("/绑定主人 验证码 — 首次绑定（验证码在 APP 生成）\n")
            sb.append("/授权 [openid] — 主人授予管理员，群内使用则限本群\n")
            sb.append("/取消授权 [openid] — 主人撤销管理员\n")
            sb.append("/解除主人 — 主人解除绑定\n")
        }
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

        // /whoami 查看自己的 openid（配白名单用）
        if (name == "whoami" || name == "我是谁") {
            val sb = StringBuilder()
            sb.append("场景：").append(if (ev.scene == "group") "群聊" else "私聊").append("\n")
            sb.append("你的 openid：\n").append(ev.userOpenId).append("\n")
            // 群友辨别的核心：把当前角色直接告诉用户，
            // 否则用户根本不知道自己在这个群里算什麼身份（官方不给 QQ 号，只能靠 openid）。
            val role = AuthStore.roleOf(ev.userOpenId, ev.groupOpenId)
            sb.append("\n你在本场景的身份：").append(role.label).append("\n")
            if (role == AuthStore.Role.USER && AuthStore.hasOwner) {
                sb.append("（如需管理员权限，请让主人发送 /授权 ").append(ev.userOpenId).append("）\n")
            }
            if (ev.scene == "group") {
                sb.append("\n本群 openid：\n").append(ev.groupOpenId).append("\n")
                sb.append("注意：QQ 官方不提供真实群号，只有这个加密 openid。\n")
            }
            // 同一用户跨群 openid 不同，把这一点明说，
            // 否则用户会疑惑「为什么我在 A 群是管理员，在 B 群不是」。
            sb.append("\n提示：同一账号在不同群/单聊里的 openid 不同，\n授权只在对应场景生效。\n")
            sb.append("\n白名单用户数：").append(PluginGuard.allowedUsers().size)
            sb.append("\n群聊插件：").append(if (PluginGuard.groupEnabled) "已开启" else "未开启")
            reply(bot, ev, sb.toString())
            return true
        }

        // /allow <openid> 把某个 openid 加入插件白名单
        // 安全设计：谁都能发这条，但只能在自己还没被限制时用一次；
        // 一旦白名单非空，非白名单用户连 /allow 都调不动（下面有 check）。
        if (name == "allow" || name == "允许") {
            val target = text.trim().split(Regex("\\s+")).getOrNull(1)
                ?: ev.userOpenId   // 不带参数就允许自己
            val users = PluginGuard.allowedUsers()
            if (users.isNotEmpty() && !users.contains(ev.userOpenId)) {
                reply(bot, ev, "⛔ 白名单已配置，你没有权限修改。")
                return true
            }
            PluginGuard.addUser(target)
            reply(bot, ev, "已把 $target 加入插件白名单。\n当前共 ${PluginGuard.allowedUsers().size} 人。")
            return true
        }

        // /disallow <openid> 移出白名单
        if (name == "disallow" || name == "禁止") {
            val users = PluginGuard.allowedUsers()
            if (users.isNotEmpty() && !users.contains(ev.userOpenId)) {
                reply(bot, ev, "⛔ 你不在插件白名单里，无权操作。")
                return true
            }
            val target = text.trim().split(Regex("\\s+")).getOrNull(1)
            if (target == null) {
                reply(bot, ev, "用法：/disallow <openid>")
                return true
            }
            PluginGuard.removeUser(target)
            reply(bot, ev, "已移除 $target。剩余 ${PluginGuard.allowedUsers().size} 人。")
            return true
        }

        val pc = PluginRegistry.find(name) ?: return false

        // P6 安全层：白名单校验
        val deny = PluginGuard.check(ev.scene, ev.userOpenId, ev.groupOpenId)
        if (deny != null) {
            PluginGuard.audit(ev.scene, ev.userOpenId, ev.groupOpenId, pc.command, false, deny)
            reply(bot, ev, "⛔ $deny\n发送 /whoami 查看你的 openid")
            return true
        }

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
        PluginGuard.audit(ev.scene, ev.userOpenId, ev.groupOpenId, pc.command, r.ok, r.output)
        val body = if (r.ok) r.output else "❌ ${r.output}"
        reply(bot, ev, body)
        return true
    }
}