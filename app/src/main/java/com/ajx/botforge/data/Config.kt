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
    data class Model(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val type: String = "openai",   // openai | anthropic | llama
        val timeout: Int = 120,
        val maxTokens: Int = 400
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
                        o.optInt("maxTokens", 400)
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
            arr.put(o)
        }
        sp.edit().putString("models", arr.toString()).apply()
    }

    // ---------------------------------------------------------------- 全局开关
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) = sp.edit().putBoolean("keep_alive", v).apply()

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

    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    fun newId(prefix: String): String =
        prefix + "_" + System.currentTimeMillis().toString(36)
}