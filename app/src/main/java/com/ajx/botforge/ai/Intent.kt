package com.ajx.botforge.ai

/**
 * 情绪与意图分类
 * 正则思路取自大肥鱼 affective_core/dynamics.py 的中文场景识别
 * 用途：让回复贴合语境（求助要认真答、撒娇要软、挨骂别炸）
 */
object Intent {

    enum class Kind { SERIOUS, DISTRESS, COAX, TEASING, POSITIVE, NEGATIVE, NEUTRAL }

    private val serious = Regex(
        "(```|报错|异常|错误|怎么(写|配|修|解决|实现)|帮我(看|查|写|改|算|分析|排查)" +
                "|部署|代码|脚本|接口|服务器|日志|数据库|编译|运行失败|是什么|为什么|如何|教程|攻略|原理)"
    )
    private val distress = Regex(
        "(头疼|头痛|发烧|感冒|生病|胃疼|难受|睡不着|失眠|心累|崩溃|焦虑|压力|好累|累趴|委屈|难过|烦死)"
    )
    private val coax = Regex(
        "(摸摸|抱抱|贴贴|亲亲|乖|最喜欢你|好喜欢你|别生气|不气|哄哄|想你|好看|真棒|夸夸|可爱)"
    )
    private val teasing = Regex("(笨蛋|傻(?!瓜|白)|蠢|胖(?!东来|大海)|小可爱|略略略|哈哈|笑死)")
    private val positive = Regex("(谢谢|感谢|厉害|好玩|不错|牛(?![奶肉排油])|赞|开心|早安|晚安|你好|hi|hello)", RegexOption.IGNORE_CASE)
    // 「操」单字极易误判（操作、操场、操心、体操、操练、操课、操守、节操……），
    // 只保留真正带攻击性的组合，并对常见正常搭配做否定预查。
    private val negative = Regex(
        "(滚|闭嘴|傻逼|垃圾|废物|去死|妈的" +
                "|操(?![作场心练盘控办守课节行持办典劳])" +
                "|tmd|nmsl)", RegexOption.IGNORE_CASE
    )

    fun classify(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.NEUTRAL
        // 优先级：求助 > 难受 > 撒娇 > 负面 > 调侃 > 正面
        return when {
            serious.containsMatchIn(t) -> Kind.SERIOUS
            distress.containsMatchIn(t) -> Kind.DISTRESS
            coax.containsMatchIn(t) -> Kind.COAX
            negative.containsMatchIn(t) -> Kind.NEGATIVE
            teasing.containsMatchIn(t) -> Kind.TEASING
            positive.containsMatchIn(t) -> Kind.POSITIVE
            else -> Kind.NEUTRAL
        }
    }

    /** 给人设附加的语气提示 */
    fun hint(kind: Kind): String = when (kind) {
        Kind.SERIOUS -> "对方在问实际问题，认真给答案，别扯闲话，别卖萌。"
        Kind.DISTRESS -> "对方状态不好，先关心，别说教，别长篇大论。"
        Kind.COAX -> "对方在亲近你，可以软一点回应，但别腻。"
        Kind.TEASING -> "对方在逗你，可以嘴硬或者顺着接一句。"
        Kind.POSITIVE -> "气氛好，正常回应即可。"
        Kind.NEGATIVE -> "对方在骂人，别对骂也别道歉，简短冷静回应，把话题拉回来。"
        Kind.NEUTRAL -> ""
    }

    /** 是否可能值得联网 */
    fun maybeNeedsSearch(text: String): Boolean {
        val t = text
        return serious.containsMatchIn(t) ||
                Regex("(最新|现在|今天|最近|多少钱|价格|几点|哪年|发布|新闻|版本|更新|赛事|比分)").containsMatchIn(t)
    }
}