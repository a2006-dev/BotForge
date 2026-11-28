package com.ajx.botforge.qq

import com.ajx.botforge.data.Logger

/**
 * 去重闸门 + 会话冷却。
 *
 * 抽出的原因：这两件事共用一把锁和三份可变状态（LRU 去重表、上次回复时间表、锁对象），
 * 留在 MessageHandler 里会和业务逻辑互相纠缠；抽出来后 MessageHandler 只剩「判断 → 处理」。
 *
 * 语义与原 MessageHandler 内部实现逐字一致，没有改动任何判空顺序、日志文案和返回值：
 *
 * - 去重：key = `scene:messageId`，命中即视为重复；未命中的会立刻登记时间戳。
 *   表是 accessOrder=true 的 LinkedHashMap，超过 400 条按最久未访问淘汰。
 * - 冷却：key = `botId:scene:groupOpenId ?: userOpenId`，即「同一会话」。
 *   检查阶段**只读不写**，绝不占用冷却窗口；真正的占位在 [markReplied]。
 */
object CooldownGate {

    /** 去重表初始容量，与抽取前一致 */
    private const val DEDUP_CAPACITY = 400

    /** 去重表上限条数，与抽取前一致 */
    private const val DEDUP_MAX = 400

    /** 会话键里 groupOpenId 为空时的兜底不需要额外分支：原实现同样是 ?: userOpenId */

    private val handled = object : LinkedHashMap<String, Long>(DEDUP_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > DEDUP_MAX
    }
    private val lastReply = mutableMapOf<String, Long>()
    private val lock = Any()

    /**
     * 去重检查：返回 true 表示这条消息已经处理过。
     *
     * 未重复时会顺手登记，所以调用方必须「在真的要处理时才调用」，
     * 与抽取前 `handle()` 开头的用法一致。
     */
    fun isDuplicate(scene: String, messageId: String): Boolean {
        val key = "$scene:$messageId"
        synchronized(lock) {
            if (handled.containsKey(key)) return true
            handled[key] = System.currentTimeMillis()
        }
        return false
    }

    /** 该会话是否仍在冷却中。只做检查，不占用冷却窗口。 */
    fun inCooldown(botId: String, ev: BotClient.Event, cooldownSec: Int): Boolean {
        val sKey = sessionKey(botId, ev)
        synchronized(lock) {
            val last = lastReply[sKey] ?: 0L
            val now = System.currentTimeMillis()
            if (now - last < cooldownSec * 1000L) {
                Logger.i("Msg", "冷却中，跳过")
                return true
            }
            // 注意：这里只做检查，不占用冷却。
            // 旧实现在此直接把 now 写入 lastReply，导致模型失败、图片加载失败、
            // 图片消息被过滤等"没有真正回复"的情况也消耗了冷却窗口，
            // 用户紧接着再发一句反而被静默丢弃。真正的占位在 markReplied()。
        }
        return false
    }

    /** 记录该会话已回复，用于冷却判断。只有真的发出去才调用。 */
    fun markReplied(botId: String, ev: BotClient.Event) {
        val k = sessionKey(botId, ev)
        synchronized(lock) { lastReply[k] = System.currentTimeMillis() }
    }

    /** `botId:scene:groupOpenId ?: userOpenId`，与原实现同一拼法 */
    private fun sessionKey(botId: String, ev: BotClient.Event): String =
        "$botId:${ev.scene}:${ev.groupOpenId ?: ev.userOpenId}"
}
