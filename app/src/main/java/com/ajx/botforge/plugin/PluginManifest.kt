package com.ajx.botforge.plugin

import org.json.JSONObject
import java.io.File

/**
 * 插件清单（plugin.json）
 *
 * 设计原则：插件与主程序只通过这份 JSON + 目录结构交互，绝不暴露 Kotlin 内部对象。
 * 这样主程序可以随意重构，插件不受影响。
 */
data class PluginManifest(
    /** 反向域名风格唯一标识，如 com.example.hello */
    val id: String,
    val name: String,
    val version: String,
    /** 协议版本。主程序据此决定是否兼容，不兼容的插件直接跳过而不是崩溃 */
    val api: Int,
    val author: String,
    val description: String,
    val commands: List<PluginCommand>,
    /** 插件所在目录，运行时填充，不来自 JSON */
    val dir: File
) {
    companion object {
        /** 主程序当前支持的协议版本 */
        const val CURRENT_API = 1

        /**
         * 从 plugin.json 解析。
         * 任何字段缺失或格式错误都返回 null——坏插件不能影响主程序。
         */
        fun parse(json: JSONObject, dir: File): PluginManifest? {
            return try {
                val id = json.optString("id").trim()
                val api = json.optInt("api", 0)
                if (id.isEmpty()) return null
                val cmdsArr = json.optJSONArray("commands") ?: return null
                val cmds = mutableListOf<PluginCommand>()
                for (i in 0 until cmdsArr.length()) {
                    val c = cmdsArr.getJSONObject(i)
                    val cmd = c.optString("command").trim().removePrefix("/")
                    if (cmd.isEmpty()) continue
                    cmds.add(
                        PluginCommand(
                            command = cmd.lowercase(),
                            description = c.optString("description", ""),
                            usage = c.optString("usage", "/$cmd"),
                            entry = c.optString("entry", "main.sh"),
                            timeout = c.optInt("timeout", 15).coerceIn(1, 120),
                            scope = parseScope(c),
                            confirm = c.optBoolean("confirm", false)
                        )
                    )
                }
                if (cmds.isEmpty()) return null
                PluginManifest(
                    id = id,
                    name = json.optString("name", id),
                    version = json.optString("version", "0.0.0"),
                    api = api,
                    author = json.optString("author", ""),
                    description = json.optString("description", ""),
                    commands = cmds,
                    dir = dir
                )
            } catch (e: Exception) {
                null
            }
        }

        /** scope 支持数组或字符串；默认只允许私聊 */
        private fun parseScope(c: JSONObject): List<String> {
            val arr = c.optJSONArray("scope")
            if (arr != null) {
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).lowercase()
                    if (s.isNotEmpty()) out.add(s)
                }
                if (out.isNotEmpty()) return out
            }
            val single = c.optString("scope", "").lowercase()
            if (single == "both") return listOf("c2c", "group")
            if (single == "group") return listOf("group")
            if (single == "c2c") return listOf("c2c")
            return listOf("c2c")   // 默认只私聊，安全优先
        }
    }
}

/** 单条指令定义 */
data class PluginCommand(
    /** 不带斜杠的指令名，小写 */
    val command: String,
    val description: String,
    val usage: String,
    /** 入口脚本，相对插件目录 */
    val entry: String,
    /** 超时秒数 */
    val timeout: Int,
    /** 允许触发的场景：c2c / group */
    val scope: List<String>,
    /** 是否需要在 QQ 里二次确认 */
    val confirm: Boolean
) {
    fun allows(scene: String): Boolean = scope.contains(scene)
}
