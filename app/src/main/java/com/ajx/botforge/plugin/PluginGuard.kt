package com.ajx.botforge.plugin

import android.content.SharedPreferences

/**
 * 插件安全层（P6）
 *
 * 规划书原则：白名单必须用 openid，因为官方机器人 API 拿不到 QQ 号。
 * 黑名单不作为防线——只能挡明显滥用的，真正的安全靠白名单默认拒绝。
 *
 * 策略（默认拒绝）：
 *  - 白名单为空 → 拒绝一切私聊插件执行
 *  - 白名单非空 → 只有名单内的 openid 能执行插件
 *  - 群聊默认不执行插件，除非群在群白名单里
 *  - 所有插件执行都记审计日志
 *
 * 为什么从「默认放行」改成「默认拒绝」：
 * 插件是第三方脚本，拥有本应用的读写权限。原先白名单为空时放行所有私聊，
 * 意味着新装用户只要知道机器人 openid 就能触发任意插件脚本，而用户
 * 完全不知道这件事发生过。默认拒绝 + 首次使用显式加自己，才是对的默认值。
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
            // 白名单为空 = 未授权任何人，一律拒绝。
            // 首次使用引导见 PluginGuardHint（设置页会提示先把自己加进白名单）。
            if (users.isEmpty()) return "插件未授权：请先在设置里把自己加入插件白名单"
            return if (users.contains(userOpenId)) null else "你不在插件白名单里"
        }

        if (scene == "group") {
            if (!groupEnabled) return "群聊插件功能未开启"
            val groups = allowedGroups()
            if (groups.isEmpty()) return "未配置允许的群"
            if (!groups.contains(groupOpenId)) return "本群不在插件白名单里"
            // 群在名单里只说明「这个群允许跑插件」，不代表「群里任何人都能触发」。
            // 发言者还必须是主人或本群管理员，这条由调用方（botAllowedTools）用
            // 由调用方（botAllowedTools）再叠一层发言者身份判定。
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
