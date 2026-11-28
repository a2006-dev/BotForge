package com.ajx.botforge.qq

/**
 * QQ 消息分片。
 *
 * 抽出的原因：这是一段纯函数逻辑（无状态、无 IO、不碰 Android API），
 * 留在 MessageHandler 里既放不进 JVM 单测，也让那个文件越来越难读。
 *
 * 分片策略（与抽取前逐字一致，未改动任何分支）：
 * 1. 不超过上限就原样返回一个分片；
 * 2. 超限时优先在「换行 / 句号 / 叹号 / 问号 / 分号 / 空格」处断句，
 *    断点必须落在分片的后 2/3 区间内（i > limit / 3），避免切出过短的碎句；
 * 3. 找不到合适断点就硬切在上限处；
 * 4. 每片两端去空白，剩余部分的头部空白也一并去掉。
 */
object MessageSplitter {

    /** QQ 单条上限保守取 1200 */
    const val DEFAULT_LIMIT = 1200

    /** 断句优先级：换行 > 句号 > 叹号 > 问号 > 分号 > 空格 */
    private val BREAKERS = listOf("\n", "。", "！", "？", "；", " ")

    /** QQ 单条上限保守取 1200，按标点断句 */
    fun split(text: String, limit: Int = DEFAULT_LIMIT): List<String> {
        if (text.length <= limit) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            var cut = -1
            for (p in BREAKERS) {
                val i = rest.lastIndexOf(p, limit)
                if (i > limit / 3) {
                    cut = i + 1
                    break
                }
            }
            if (cut <= 0) cut = limit
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
