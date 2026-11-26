package com.ajx.botforge.plugin

import android.content.SharedPreferences

/**
 * 插件安全层（P6）
 *
 * 规划书原则：白名单必须用 openid，因为官方机器人 API 拿不到 QQ 号。
 * 黑名单不作为防线——只能挡明显滥用的，真正的安全靠白名单默认拒绝。
 *
 * 策略：
 *  - 白名单为空 → 视为「未配置」，允许所有私聊执行（方便首次使用）
 *  - 白名单非空 → 只有名单内的 openid 能执行插件
 *  - 群聊默认不执行插件，除非群在群白名单里
 *  - 所有插件执行都记审计日志
 */
object PluginGuard {

    private var sp: SharedPreferences? = null

    private const val KEY_USERS = "plugin_allow_users"
    private const val KEY_GROUPS = "plugin_allow_groups"
    private const val KEY_GROUP_ENABLED = "plugin_group_enabled"

    fun init(sp: SharedPreferences) {
        this.sp = sp
    }

    // ---------------------------------------------------------------- 白名单
    /** 允许执行插件的用户 openid 列表 */
    fun allowedUsers(): Set<String> =
        sp?.getStringSet(KEY_USERS, emptySet())?.toSet() ?: emptySet()

    /** 允许执行插件的群 openid 列表 */
    fun allowedGroups(): Set<String> =
        sp?.getStringSet(KEY_GROUPS, emptySet())?.toSet() ?: emptySet()

    /** 是否开放群聊执行（默认关闭） */
    var groupEnabled: Boolean
        get() = sp?.getBoolean(KEY_GROUP_ENABLED, false) ?: false
        set(v) { sp?.edit()?.putBoolean(KEY_GROUP_ENABLED, v)?.apply() }

    fun addUser(openId: String) {
        val id = openId.trim()
        if (id.isEmpty()) return
        val s = allowedUsers().toMutableSet().apply { add(id) }
        sp?.edit()?.putStringSet(KEY_USERS, s)?.apply()
    }

    fun removeUser(openId: String) {
        val s = allowedUsers().toMutableSet().apply { remove(openId.trim()) }
        sp?.edit()?.putStringSet(KEY_USERS, s)?.apply()
    }

    fun addGroup(openId: String) {
        val id = openId.trim()
        if (id.isEmpty()) return
        val s = allowedGroups().toMutableSet().apply { add(id) }
        sp?.edit()?.putStringSet(KEY_GROUPS, s)?.apply()
    }

    fun removeGroup(openId: String) {
        val s = allowedGroups().toMutableSet().apply { remove(openId.trim()) }
        sp?.edit()?.putStringSet(KEY_GROUPS, s)?.apply()
    }

    /**
     * 判断该事件是否有权执行插件。
     * 返回 null 表示放行，否则返回拒绝原因。
     */
    fun check(scene: String, userOpenId: String, groupOpenId: String): String? {
        val users = allowedUsers()

        if (scene == "c2c") {
            // 白名单为空 = 未配置，允许（首次使用门槛低）
            if (users.isEmpty()) return null
            return if (users.contains(userOpenId)) null else "你不在插件白名单里"
        }

        if (scene == "group") {
            if (!groupEnabled) return "群聊插件功能未开启"
            val groups = allowedGroups()
            if (groups.isEmpty()) return "未配置允许的群"
            if (!groups.contains(groupOpenId)) return "本群不在插件白名单里"
            return null
        }

        return "未知场景"
    }

    // ---------------------------------------------------------------- 审计
    /** 记录一次插件执行，供排查与追责 */
    fun audit(scene: String, userOpenId: String, groupOpenId: String, command: String, ok: Boolean, brief: String) {
        val who = if (scene == "group") "群 $groupOpenId / $userOpenId" else userOpenId
        val flag = if (ok) "OK " else "ERR"
        com.ajx.botforge.data.Logger.i(
            "Audit",
            "$flag [$scene] $who → /$command :: ${brief.take(80).replace("\n", " ")}"
        )
    }
}