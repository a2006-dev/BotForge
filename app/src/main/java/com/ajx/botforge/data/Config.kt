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
                        o.optString("appSecret"),
                        o.optString("personaId"),
                        o.optString("modelId"),
                        o.optBoolean("enabled", true),
                        fixedIntents,
                        gs
                    )
                )
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
            o.put("appSecret", b.appSecret)
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
                        o.optString("apiKey"),
                        o.optString("model"),
                        o.optString("type", "openai"),
                        o.optInt("timeout", 120),
                        o.optInt("maxTokens", 400)
                    )
                )
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
            o.put("apiKey", m.apiKey); o.put("model", m.model); o.put("type", m.type)
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

    var logEnabled: Boolean
        get() = sp.getBoolean("log_enabled", true)
        set(v) = sp.edit().putBoolean("log_enabled", v).apply()

    fun newId(prefix: String): String =
        prefix + "_" + System.currentTimeMillis().toString(36)
}