package com.ajx.botforge.ai

import android.content.Context
import com.ajx.botforge.data.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 轻量本地记忆：按 (botId, 会话key) 分文件存 JSON 数组
 * 只保留最近 N 条，超过上限截断。可扩展成摘要式长期记忆。
 */
object Memory {

    private const val MAX_TURNS = 120

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "mem")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(ctx: Context, key: String): File {
        val safe = key.replace("[^a-zA-Z0-9_\\-]".toRegex(), "_")
        return File(dir(ctx), "$safe.json")
    }

    data class Turn(val role: String, val content: String, val ts: Long)

    fun load(ctx: Context, key: String): MutableList<Turn> {
        val f = fileOf(ctx, key)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Turn>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Turn(o.optString("role"), o.optString("content"), o.optLong("ts")))
            }
            list
        } catch (e: Exception) {
            Logger.w("Memory", "读取失败 ${f.name}: ${e.message}")
            mutableListOf()
        }
    }

    fun append(ctx: Context, key: String, role: String, content: String) {
        try {
            val list = load(ctx, key)
            list.add(Turn(role, content, System.currentTimeMillis()))
            while (list.size > MAX_TURNS) list.removeAt(0)
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject()
                        .put("role", it.role)
                        .put("content", it.content)
                        .put("ts", it.ts)
                )
            }
            fileOf(ctx, key).writeText(arr.toString())
        } catch (e: Exception) {
            Logger.w("Memory", "写入失败: ${e.message}")
        }
    }

    fun clear(ctx: Context, key: String) {
        try {
            fileOf(ctx, key).delete()
        } catch (_: Exception) {
        }
    }

    /** 取最近 n 轮，返回 OpenAI 消息数组（不含 system） */
    fun recent(ctx: Context, key: String, n: Int): List<Turn> {
        val all = load(ctx, key)
        return if (all.size <= n) all else all.subList(all.size - n, all.size)
    }
}
