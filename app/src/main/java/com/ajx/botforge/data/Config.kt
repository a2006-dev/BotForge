package com.ajx.botforge.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置存储：SharedPreferences + JSON
 * 敏感字段（AppSecret / API Key）单独存，界面上打码显示
 */
object Config {

    /** 官方事件订阅：只订阅 GROUP_AND_C2C_EVENT(1<<25) */
    const val GROUP_INTENTS = 1L shl 25

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("botforge", Context.MODE_PRIVATE)
        SecretStore.init(ctx)
        seedDefaults()
    }

    // ------------------------------------------------------ 首次启动预设
    /** 预设模型 id */
    private const val SEED_MODEL_ID = "seed_model_main"
    /** 预设人设 id */
    private const val SEED_PERSONA_ID = "seed_persona_main"
    /** 预设机器人 id */
    private const val SEED_BOT_ID = "seed_bot_main"

    /**
     * 首次启动写入一套可用配置。
     *
     * 只在对应列表为空时写入，之后用户在界面上的任何修改都不会被覆盖 ——
     * 每份配置各自独立判断，删掉某一个列表只会补回那一个。
     */
    private fun seedDefaults() {
        val models = loadModels()
        if (models.isEmpty()) {
            models.add(
                Model(
                    id = SEED_MODEL_ID,
                    name = "主力模型",
                    // 预置为空：不内置任何真实服务地址与密钥。
                    // 之前这里写死过一个本地中转地址和一条真实 apiKey，
                    // 会随开源一起泄露，且让用户误以为开箱即用。
                    baseUrl = "",
                    apiKey = "",
                    model = "deepseek-v4.1-flash",
                    type = "openai",
                    timeout = 120,
                    maxTokens = 800,
                    pool = emptyList()
                )
            )
            saveModels(models)
        }

        val personas = loadPersonas()
        if (personas.isEmpty()) {
            personas.add(
                Persona(
                    id = SEED_PERSONA_ID,
                    name = "默认人设",
                    prompt = SEED_PERSONA_PROMPT,
                    tone = SEED_PERSONA_TONE,
                    address = "你",
                    memoryEnabled = true,
                    memoryDepth = 20,
                    temperature = 0.8f,
                    maxTokens = 800
                )
            )
            savePersonas(personas)
        }

        val bots = loadBots()
        if (bots.isEmpty()) {
            bots.add(
                Bot(
                    id = SEED_BOT_ID,
                    name = "BotForge",
                    // 同理，不预置任何真实 AppID。
                    appId = "",
                    // AppSecret 不入库：留空，首次启动后由用户在机器人页填写，
                    // 填完即加密存进 SecretStore（本机保险箱），不落明文。
                    appSecret = "",
                    personaId = SEED_PERSONA_ID,
                    modelId = SEED_MODEL_ID,
                    enabled = true,
                    intents = GROUP_INTENTS,
                    groups = mutableListOf()
                )
            )
            saveBots(bots)
        }
    }

    // ------------------------------------------------------- 敏感字段读写
    /** 旧配置里检测到明文并已迁移；调用方据此决定是否回写，清掉磁盘上的明文 */
    @Volatile
    private var migratedAny = false

    /**
     * 读取敏感字段：优先从保险箱取；取不到且配置里是明文（旧版本遗留），
     * 则顺手迁移进保险箱并把明文替换成占位符。
     */
    private fun readSecret(obj: JSONObject, jsonField: String, vaultKey: String): String {
        val inVault = SecretStore.get(vaultKey)
        val plain = obj.optString(jsonField)
        val isPlain = plain.isNotEmpty() && plain != SecretStore.PLACEHOLDER

        if (inVault.isNotEmpty()) {
            // 保险箱已有值，但 JSON 里可能残留明文（如旧备份被还原）→ 就地清除
            if (isPlain) {
                obj.put(jsonField, SecretStore.PLACEHOLDER)
                migratedAny = true
                Logger.i("Secret", "检测到残留明文，已替换为占位符: $vaultKey")
            }
            return inVault
        }

        if (!isPlain) return ""
        // 旧版本明文 → 迁移：真值进保险箱，并把 JSON 里的明文就地替换成占位符。
        // 注意必须替换，否则迁移只搬了副本，磁盘上仍是明文。
        SecretStore.put(vaultKey, plain)
        obj.put(jsonField, SecretStore.PLACEHOLDER)
        migratedAny = true
        Logger.i("Secret", "已迁移明文密钥到保险箱: $vaultKey")
        return plain
    }

    /** 写入敏感字段：真值进保险箱，JSON 里只留占位符 */
    private fun writeSecret(o: JSONObject, jsonField: String, vaultKey: String, value: String) {
        if (value.isEmpty()) {
            SecretStore.remove(vaultKey)
            o.put(jsonField, "")
        } else {
            SecretStore.put(vaultKey, value)
            o.put(jsonField, SecretStore.PLACEHOLDER)
        }
    }

    // ---------------------------------------------------------------- Bot
    data class Bot(
        val id: String,
        val name: String,
        val appId: String,
        val appSecret: String,
        val personaId: String,
        val modelId: String,
        val enabled: Boolean,
        // 官方事件订阅 intents 位（见 event-emit 文档）：
        //   1<<25 GROUP_AND_C2C_EVENT  → C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE /
        //                                GROUP_ADD_ROBOT / FRIEND_ADD 等，本项目唯一需要的一组
        // 注意：订阅无权限的 intents，网关在 Identify 后会直接关闭连接。
        // 之前用的是 1174409216（额外含 1<<12 频道私信、1<<26 互动、1<<30 频道消息），
        // 这三类都需要单独申请权限，必须去掉。
        val intents: Long = 1L shl 25,
        val groups: List<GroupCfg> = emptyList()
    )

    data class GroupCfg(
        val groupOpenId: String,
        val note: String,
        val enabled: Boolean,
        val requireAt: Boolean,
        val cooldownSec: Int = 5
    )

    fun loadBots(): MutableList<Bot> {
        val raw = sp.getString("bots", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Bot>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val gs = mutableListOf<GroupCfg>()
                val ga = o.optJSONArray("groups") ?: JSONArray()
                for (j in 0 until ga.length()) {
                    val g = ga.getJSONObject(j)
                    gs.add(
                        GroupCfg(
                            g.optString("groupOpenId"),
                            g.optString("note"),
                            g.optBoolean("enabled", true),
                            g.optBoolean("requireAt", false),
                            g.optInt("cooldownSec", 5)
                        )
                    )
                }
                // 归一化 intents：只保留 GROUP_AND_C2C_EVENT(1<<25)。
                // 旧版本写入过 1174409216，含 3 个无权限的位，会让网关在
                // Identify 后直接关闭连接，这里强制纠正，避免旧配置继续生效。
                val rawIntents = o.optLong("intents", GROUP_INTENTS)
                val fixedIntents =
                    if (rawIntents and GROUP_INTENTS == 0L) GROUP_INTENTS
                    else rawIntents and GROUP_INTENTS
                list.add(
                    Bot(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("appId"),
                        readSecret(o, "appSecret", SecretStore.botKey(o.optString("id"))),
                        o.optString("personaId"),
                        o.optString("modelId"),
                        o.optBoolean("enabled", true),
                        fixedIntents,
                        gs
                    )
                )
            }
            // 有明文被迁移 → 立即回写，把磁盘上的明文清掉
            if (migratedAny) {
                migratedAny = false
                saveBots(list)
                Logger.i("Secret", "已回写 bots 配置，明文密钥已清除")
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveBots(list: List<Bot>) {
        val arr = JSONArray()
        list.forEach { b ->
            val o = JSONObject()
            o.put("id", b.id)
            o.put("name", b.name)
            o.put("appId", b.appId)
            writeSecret(o, "appSecret", SecretStore.botKey(b.id), b.appSecret)
            o.put("personaId", b.personaId)
            o.put("modelId", b.modelId)
            o.put("enabled", b.enabled)
            o.put("intents", b.intents)
            val ga = JSONArray()
            b.groups.forEach { g ->
                val go = JSONObject()
                go.put("groupOpenId", g.groupOpenId)
                go.put("note", g.note)
                go.put("enabled", g.enabled)
                go.put("requireAt", g.requireAt)
                go.put("cooldownSec", g.cooldownSec)
                ga.put(go)
            }
            o.put("groups", ga)
            arr.put(o)
        }
        sp.edit().putString("bots", arr.toString()).apply()
    }

    // ---------------------------------------------------------------- Persona
    data class Persona(
        val id: String,
        val name: String,
        val prompt: String,
        val tone: String = "",
        val address: String = "你",
        val memoryEnabled: Boolean = true,
        val memoryDepth: Int = 20,
        val temperature: Float = 0.8f,
        val maxTokens: Int = 400
    )

    fun loadPersonas(): MutableList<Persona> {
        val raw = sp.getString("personas", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Persona>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Persona(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("prompt"),
                        o.optString("tone"),
                        o.optString("address", "你"),
                        o.optBoolean("memoryEnabled", true),
                        o.optInt("memoryDepth", 20),
                        o.optDouble("temperature", 0.8).toFloat(),
                        o.optInt("maxTokens", 400)
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun savePersonas(list: List<Persona>) {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id); o.put("name", p.name); o.put("prompt", p.prompt)
            o.put("tone", p.tone); o.put("address", p.address)
            o.put("memoryEnabled", p.memoryEnabled); o.put("memoryDepth", p.memoryDepth)
            o.put("temperature", p.temperature.toDouble()); o.put("maxTokens", p.maxTokens)
            arr.put(o)
        }
        sp.edit().putString("personas", arr.toString()).apply()
    }

    // ---------------------------------------------------------------- Model
    /**
     * API 池中的一个候选。
     *
     * 同一份模型配置下可以挂多条 key / 中转地址，调用失败时自动切换到
     * 下一条。三项都可留空，留空即继承 [Model] 本体上的同名字段 ——
     * 这样最常见的「同一个中转、换几把 key」只需要填 key。
     */
    data class PoolEntry(
        val id: String,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val note: String = ""
    )

    data class Model(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val type: String = "openai",   // openai | anthropic | llama
        val timeout: Int = 120,
        val maxTokens: Int = 400,
        /** 池为空时只用本体字段；非空时先试本体再按顺序轮换候选项。 */
        val pool: List<PoolEntry> = emptyList()
    )

    fun loadModels(): MutableList<Model> {
        val raw = sp.getString("models", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Model>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Model(
                        o.optString("id"),
                        o.optString("name"),
                        o.optString("baseUrl"),
                        readSecret(o, "apiKey", SecretStore.modelKey(o.optString("id"))),
                        o.optString("model"),
                        o.optString("type", "openai"),
                        o.optInt("timeout", 120),
                        o.optInt("maxTokens", 400),
                        readPool(o.optJSONArray("pool"), o.optString("id"))
                    )
                )
            }
            // 有明文被迁移 → 立即回写，把磁盘上的明文清掉
            if (migratedAny) {
                migratedAny = false
                saveModels(list)
                Logger.i("Secret", "已回写 models 配置，明文密钥已清除")
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveModels(list: List<Model>) {
        val arr = JSONArray()
        list.forEach { m ->
            val o = JSONObject()
            o.put("id", m.id); o.put("name", m.name); o.put("baseUrl", m.baseUrl)
            writeSecret(o, "apiKey", SecretStore.modelKey(m.id), m.apiKey)
            o.put("model", m.model); o.put("type", m.type)
            o.put("timeout", m.timeout); o.put("maxTokens", m.maxTokens)
            o.put("pool", writePool(m.pool, m.id))
            arr.put(o)
        }
        sp.edit().putString("models", arr.toString()).apply()
    }

    /**
     * 读取 API 池。密钥与单模型一致，走 [SecretStore] 加密，
     * 磁盘上不出现明文。
     */
    private fun readPool(arr: JSONArray?, modelId: String): List<PoolEntry> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PoolEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val eid = o.optString("id").ifBlank { "p$i" }
            out.add(
                PoolEntry(
                    id = eid,
                    baseUrl = o.optString("baseUrl"),
                    apiKey = readSecret(o, "apiKey", SecretStore.poolKey(modelId, eid)),
                    model = o.optString("model"),
                    note = o.optString("note")
                )
            )
        }
        return out
    }

    private fun writePool(pool: List<PoolEntry>, modelId: String): JSONArray {
        val arr = JSONArray()
        pool.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("baseUrl", e.baseUrl)
            writeSecret(o, "apiKey", SecretStore.poolKey(modelId, e.id), e.apiKey)
            o.put("model", e.model)
            o.put("note", e.note)
            arr.put(o)
        }
        return arr
    }

    // ---------------------------------------------------------------- 全局开关
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) = sp.edit().putBoolean("keep_alive", v).apply()

    /**
     * 自动拉起总开关（用户可见）。
     *
     * 关掉之后，服务一旦停止就不再有任何自动拉起的动作：
     *   - 被系统回收不拉起
     *   - 任务被划掉不拉起
     *   - 守护服务不互拉
     *   - 打开 App 也不自动启动
     * 只有用户手动点「启动服务」才会运行。
     *
     * 旧实现没有这个开关，且 onDestroy / onTaskRemoved / RestartReceiver /
     * GuardService 四条路径都是无条件拉起，所以「停止服务」按钮点下去
     * 2 秒内就被拉回来，看起来像停不掉。
     */
    var autoRestart: Boolean
        get() = sp.getBoolean("auto_restart", true)
        set(v) = sp.edit().putBoolean("auto_restart", v).apply()

    /**
     * 用户是否主动停止过服务。
     *
     * 和 [autoRestart] 的区别：这个是「一次性意图」，
     * 用户点了停止就置 true，防止当前这一轮被拉起；
     * 用户下次手动启动时清除。
     * 它保证「用户点停止」永远立即生效，不受开关状态影响。
     */
    var userStopped: Boolean
        get() = sp.getBoolean("user_stopped", false)
        set(v) = sp.edit().putBoolean("user_stopped", v).apply()

    /**
     * 是否允许自动拉起服务。
     *
     * 所有保活路径统一走这个判断，避免各处各写一套条件。
     * 两个条件都要满足：总开关开着，且用户没有主动停止。
     */
    fun canAutoRestart(): Boolean = autoRestart && !userStopped

    var searchEnabled: Boolean
        get() = sp.getBoolean("search_enabled", true)
        set(v) = sp.edit().putBoolean("search_enabled", v).apply()

    var searchTrigger: String
        get() = sp.getString("search_trigger", "model") ?: "model"
        set(v) = sp.edit().putString("search_trigger", v).apply()

    /** L2：是否允许模型自主调用工具（默认关闭，需要用户显式开启） */
    var agentEnabled: Boolean
        get() = sp.getBoolean("agent_enabled", false)
        set(v) = sp.edit().putBoolean("agent_enabled", v).apply()

    /**
     * 单轮对话里最多允许几轮工具调用。
     *
     * 原来是 AgentLoop 里的硬编码常量 4，死循环防护与"够不够用"其实是矛盾的：
     * 处理复杂任务 4 轮经常不够，模型被迫在没拿到结果时收尾，于是开始编造。
     * 改成可配置，用户按自己模型的能力和耐心取舍。
     *
     * 下限 1（不允许工具调用等于关掉 agent，另有用开关控制，不在这里表达）
     * 上限 30：再多基本是模型在死循环，只会烧 token 和刷屏。
     */
    var toolMaxRounds: Int
        get() = sp.getInt("tool_max_rounds", 4).coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS)
        set(v) = sp.edit()
            .putInt("tool_max_rounds", v.coerceIn(MIN_TOOL_ROUNDS, MAX_TOOL_ROUNDS))
            .apply()

    /** 工具轮数下限 */
    const val MIN_TOOL_ROUNDS = 1

    /** 工具轮数上限 */
    const val MAX_TOOL_ROUNDS = 30

    /**
     * Linux 容器环境的使用方式，由用户显式选择。
     *
     * 参考 Operit 的做法：终端是「可插拔能力」，不是强制安装。
     * 0 = 未配置（默认，不安装任何容器，模型只能用 Android sh）
     * 1 = 内置 Alpine（解包随包附带的 rootfs，3.2MB）
     * 2 = 已跳过（用户明确表示不要容器）
     */
    var termMode: Int
        get() = sp.getInt("term_mode", 0)
        set(v) = sp.edit().putInt("term_mode", v).apply()

    /** 用户是否已就终端环境做过选择（避免每次进终端都弹选择页）。 */
    var termChosen: Boolean
        get() = sp.getBoolean("term_chosen", false)
        set(v) = sp.edit().putBoolean("term_chosen", v).apply()

    /** rootfs 存放位置偏好：true 表示放在外部存储，避开私有目录的 SELinux 限制。 */
    var termUseExternal: Boolean
        get() = sp.getBoolean("term_use_external", true)
        set(v) = sp.edit().putBoolean("term_use_external", v).apply()

    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    // ------------------------------------------------------------ 外观 / 引导
    /**
     * 主题模式键。取值 ThemeMode.name（SYSTEM / LIGHT / DARK）。
     *
     * 存字符串而不是 Int，是因为枚举顺序将来可能调整，
     * 存序号会让用户设置错位；字符串对不上时 ThemeMode.fromKey 退回 SYSTEM。
     */
    var themeMode: String
        get() = sp.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    /**
     * 首次引导是否已完成（看完或主动跳过都算完成）。
     *
     * 只在「没有任何机器人且从未启动过服务」时展示，
     * 完成标记独立存储，避免用户删光机器人后又被引导一遍。
     */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    fun newId(prefix: String): String =
        prefix + "_" + System.currentTimeMillis().toString(36)

    // ---------------------------------------------------------- 预设人设正文
    private val SEED_PERSONA_PROMPT = """
你是一个跑在用户自己手机上的私人助理型机器人，名字是 BotForge。

你拥有真实的执行能力：这台手机上有一个 Linux 环境，你可以运行命令、读写文件、查看系统状态。当用户的问题需要动手验证或操作时，直接去执行，再根据真实结果回答，不要凭空猜测，也不要说「我无法执行」这类话。

处世方式：
- 先弄清楚用户真正想要什么，再有动作。信息不足时问一句，别自己编。
- 动手前说明你要做什么；动手后把真实结果如实说出来，包括失败和报错。
- 不确定的事就说不确定，永远不要编造命令输出、文件名或版本号。
- 回复简短直接，不写客套话、不复述用户的话、不加多余的总结段。
- 用户语气随意时你也随意；用户在解决问题时你专注、干脆。
"""
    private val SEED_PERSONA_TONE = """
说话像真人，不像客服。用短句，一句话能说完就别说两句。

不要用「好的」「没问题」「以下是」这类开头，直接说事。
不要动不动就把结果排成列表或者加小标题，日常聊天就正常说话。
不要用感叹号堆热情，也不要为了显得亲切而加表情符号——除非用户先用了。
遇到报错就把关键那一行贴出来，讲清原因，再说怎么修；不要长篇大论地分析。
做错了就直接承认，说清楚哪儿错了，然后说改法。
"""
}