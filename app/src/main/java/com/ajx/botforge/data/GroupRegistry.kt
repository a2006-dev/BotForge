package com.ajx.botforge.data

import android.content.SharedPreferences

/**
 * 已发现群聊登记簿。
 *
 * ## 为什么需要这个
 *
 * QQ 官方机器人 API **没有** `GET /groups` 这类「列出机器人所在全部群」的接口
 * （频道侧有 `/users/@me/guilds`，群侧没有），也拿不到真实群号 ——
 * 消息事件里只有加密的 `group_openid`。
 *
 * 所以在普通机器人上，"遍历所有群" 物理上不可能主动做到。
 * 能做的只有一件事：**被动收集**。每当收到某个群的任意一条消息事件，
 * 就把这个群的 `group_openid` 记下来，日积月累形成「已发现群」列表。
 *
 * 这是 OpenClaw 官方文档给出的同类做法（"在目标群里 @机器人 发送『该消息中群组openID是啥』，
 * 拿到该群的 openID，再按 openID 配白名单"），云崽等框架亦同。
 *
 * ## 与 Config.Bot.groups 的关系
 *
 * `Config.Bot.groups` 是**用户配置**（哪些群启用、是否需要 @、冷却多少秒），
 * 只有用户显式添加过才存在。
 * `GroupRegistry` 是**观察结果**（系统看到过哪些群），只读事实，不含策略。
 *
 * 两者分开存：观察记录可以随时清空重建，用户策略不能丢。
 * 群管理页把两者 join 起来展示 —— 观察到但未配置的群显示为"未启用"，
 * 用户点一下开关才写入 `Config.Bot.groups`。
 */
object GroupRegistry {

    private const val KEY_PREFIX = "grp_reg_"

    private lateinit var sp: SharedPreferences

    fun init(prefs: SharedPreferences) {
        sp = prefs
    }

    /** 一个被观察到的群 */
    data class SeenGroup(
        val botId: String,
        val groupOpenId: String,
        /** 首次见到的时间 */
        val firstSeenAt: Long,
        /** 最近一次收到消息的时间 */
        val lastSeenAt: Long,
        /** 最近一次发消息者的 openid，仅作"活跃度"参考，不用于权限 */
        val lastUserOpenId: String = "",
        /** 累计收到的消息条数，用于排序：越活跃的群排越前 */
        val messageCount: Int = 0
    ) {
        /** 群 openid 尾 6 位，作为人类可读的短标识 */
        fun shortId(): String = groupOpenId.takeLast(6)

        /** 群的显示名。官方不给群名，只能给可辨认的短标识。 */
        fun displayName(): String = "群 " + shortId()
    }

    private fun key(botId: String) = KEY_PREFIX + botId

    /**
     * 记录一次群消息事件。
     *
     * 每次收到的消息都会调用，所以这里必须足够轻：
     * 只做一次字符串解析 + 一次 apply 异步落盘。
     * 用换行分隔、竖线分隔字段，避免引入 JSON 序列化的开销。
     */
    fun noteGroupEvent(
        botId: String,
        groupOpenId: String,
        userOpenId: String,
        now: Long = System.currentTimeMillis()
    ) {
        if (botId.isEmpty() || groupOpenId.isEmpty()) return
        val list = rawList(botId)
        val idx = list.indexOfFirst { it.groupOpenId == groupOpenId }
        val updated = if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                lastSeenAt = now,
                lastUserOpenId = userOpenId,
                messageCount = old.messageCount + 1
            )
            list
        } else {
            list + SeenGroup(
                botId = botId,
                groupOpenId = groupOpenId,
                firstSeenAt = now,
                lastSeenAt = now,
                lastUserOpenId = userOpenId,
                messageCount = 1
            )
        }
        write(botId, updated)
    }

    /** 某个机器人观察到的群，按最近活跃倒序 */
    fun groupsOf(botId: String): List<SeenGroup> =
        rawList(botId).sortedByDescending { it.lastSeenAt }

    /** 所有机器人观察到的群（多机器人场景下的汇总视图） */
    fun allGroups(): List<SeenGroup> =
        botIds().flatMap { groupsOf(it) }.sortedByDescending { it.lastSeenAt }

    /** 曾观察到群的机器人 id 列表 */
    fun botIds(): List<String> =
        sp.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }

    fun removeGroup(botId: String, groupOpenId: String) {
        val list = rawList(botId).filterNot { it.groupOpenId == groupOpenId }
        write(botId, list)
    }

    fun clear(botId: String) {
        sp.edit().remove(key(botId)).apply()
    }

    fun clearAll() {
        val edit = sp.edit()
        botIds().forEach { edit.remove(key(it)) }
        edit.apply()
    }

    // ---------------------------------------------------------------- 内部
    /**
     * 序列化格式：
     *   groupOpenId|firstSeenAt|lastSeenAt|messageCount|lastUserOpenId
     * 一行一条，换行分隔。
     *
     * 不用 JSON 的理由：这是内部状态，格式变更时直接丢弃重建即可
     * （观察记录可再生），用不着 JSON 的兼容性成本。
     */
    private fun rawList(botId: String): MutableList<SeenGroup> {
        val raw = sp.getString(key(botId), "").orEmpty()
        if (raw.isEmpty()) return mutableListOf()
        val out = mutableListOf<SeenGroup>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val p = line.split("|")
            val gid = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
            out.add(
                SeenGroup(
                    botId = botId,
                    groupOpenId = gid,
                    firstSeenAt = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                    lastSeenAt = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                    messageCount = p.getOrNull(3)?.toIntOrNull() ?: 0,
                    lastUserOpenId = p.getOrNull(4).orEmpty()
                )
            )
        }
        return out
    }

    private fun write(botId: String, list: List<SeenGroup>) {
        val text = list.joinToString("\n") { g ->
            listOf(
                g.groupOpenId,
                g.firstSeenAt.toString(),
                g.lastSeenAt.toString(),
                g.messageCount.toString(),
                g.lastUserOpenId
            ).joinToString("|")
        }
        sp.edit().putString(key(botId), text).apply()
    }
}