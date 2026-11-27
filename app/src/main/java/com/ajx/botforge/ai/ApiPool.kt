package com.ajx.botforge.ai

import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * API 池调度器。
 *
 * 目标：一次调用里把模型本体 + 池内所有候选依次试一遍，任何一条成功就返回。
 * 这样单条 key 被限流 / 中转挂掉 / 模型改名，都不会让机器人直接哑掉。
 *
 * ## 轮换策略
 *
 * 维护每个条目「连续失败次数」，排序时失败少的优先，次数相同则保持配置
 * 顺序。成功一次立即清零。这是最朴素也最好解释的策略：不搞加权随机，
 * 出问题时用户能直接看出是哪条在拖后腿。
 *
 * 失败次数只存在内存里，重启归零 —— 配置本身不该被临时故障污染。
 */
object ApiPool {

    private const val TAG = "Pool"

    /** key -> 连续失败次数 */
    private val failures = ConcurrentHashMap<String, Int>()

    private fun keyOf(modelId: String, entryId: String) = "$modelId::$entryId"

    /** 某条目的连续失败次数，供 UI 展示 */
    fun failCount(modelId: String, entryId: String): Int =
        failures[keyOf(modelId, entryId)] ?: 0

    private fun markOk(modelId: String, entryId: String) {
        failures.remove(keyOf(modelId, entryId))
    }

    private fun markFail(modelId: String, entryId: String) {
        val k = keyOf(modelId, entryId)
        failures[k] = (failures[k] ?: 0) + 1
    }

    /**
     * 展开成实际可用的调用目标。
     *
     * 池条目里留空的字段继承模型本体，因此「只换 key」的常见场景
     * 只需要填一个字段。
     */
    private fun candidates(model: Config.Model): List<Config.Model> {
        val out = mutableListOf<Config.Model>()

        // 本体排第一（它就是用户心里那个「主力」）
        out.add(model.copy(pool = emptyList()))

        model.pool.forEachIndexed { i, e ->
            val eid = e.id.ifBlank { "p$i" }
            out.add(
                model.copy(
                    id = "${model.id}::$eid",
                    baseUrl = e.baseUrl.ifBlank { model.baseUrl },
                    apiKey = e.apiKey.ifBlank { model.apiKey },
                    model = e.model.ifBlank { model.model },
                    pool = emptyList()
                )
            )
        }

        // 失败少的优先；稳定排序保证同分时维持配置顺序
        return out.sortedBy { failCount(model.id, it.id.substringAfter("::", "main")) }
    }

    /**
     * 带池容错的对话调用。
     *
     * 返回值语义与 [AiEngine.chat] 一致：成功返回文本，全失败返回 null。
     */
    fun chat(
        model: Config.Model,
        system: String,
        history: List<AiEngine.Msg>,
        user: String,
        temperature: Float,
        maxTokens: Int,
        images: List<String> = emptyList()
    ): String? {
        val list = candidates(model)
        if (list.isEmpty()) return null

        val total = list.size
        list.forEachIndexed { idx, target ->
            val entryId = target.id.substringAfter("::", "main")
            val r = AiEngine.chat(
                model = target,
                system = system,
                history = history,
                user = user,
                temperature = temperature,
                maxTokens = maxTokens,
                images = images
            )
            if (r != null) {
                markOk(model.id, entryId)
                if (idx > 0) {
                    Logger.i(TAG, "「${model.name}」由候选 #${idx + 1}/$total 返回成功")
                }
                return r
            }
            markFail(model.id, entryId)
            if (idx < total - 1) {
                Logger.w(TAG, "「${model.name}」候选 #${idx + 1}/$total 失败，切换下一条")
            }
        }
        Logger.w(TAG, "「${model.name}」全部 $total 条候选均失败")
        return null
    }

    /** 清空失败计数（用户手动「重置池状态」） */
    fun reset() {
        failures.clear()
    }

    /** 只重置某个模型 */
    fun reset(modelId: String) {
        failures.keys.removeAll { it.startsWith("$modelId::") }
    }
}