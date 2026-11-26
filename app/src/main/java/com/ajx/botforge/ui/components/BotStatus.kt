package com.ajx.botforge.ui.components

/**
 * 语义状态（设计规范 §1 / 提示词 §4.1）
 *
 * 为什么需要这一层：仓库里已有 [StatusTone]（配色），但**没有「状态」这个业务概念**。
 * 结果各页面自己把 BotConnState 翻成中文 + 自己挑 tone，
 * 于是 `STOPPED` 在首页显示「已停止」、别处可能显示「离线」。
 *
 * 这里把「状态 → 文案 + 配色」集中一处，页面只拿 [BotStatus]，不再自己映射。
 *
 * 6 种状态（提示词 §4.1 要求）：
 *  - ONLINE       服务运行 + 连接正常
 *  - CONNECTING   首次建立连接
 *  - RECONNECTING 断线后重连
 *  - ERROR        连续失败 / 鉴权失败
 *  - PAUSED       用户主动停止（Config.userStopped = true）
 *  - OFFLINE      服务未运行
 *
 * PAUSED 与 OFFLINE 必须区分：前者是「我让它停的」，后者是「它没在跑」。
 * 旧实现两者都渲染成「已停止」，用户看不出到底是不是自己关的。
 */
enum class BotStatus(
    val label: String,
    val tone: StatusTone,
    /** 状态下方的一句话说明，StatusHero 用 */
    val hint: String
) {
    ONLINE("在线", StatusTone.Success, "服务运行中，机器人会正常响应"),
    CONNECTING("连接中", StatusTone.Info, "正在建立网关连接"),
    RECONNECTING("重连中", StatusTone.Warning, "连接中断，正在自动重连"),
    ERROR("异常", StatusTone.Danger, "连接出现异常，需要处理"),
    PAUSED("已暂停", StatusTone.Neutral, "你已手动停止，不会被自动拉起"),
    OFFLINE("离线", StatusTone.Neutral, "服务未运行，机器人不会响应");

    /** 只有 ONLINE 算健康，用于概览计数 */
    val isHealthy: Boolean get() = this == ONLINE

    /** 需要用户在首页看到警示的状态 */
    val needsAttention: Boolean get() = this == ERROR || this == RECONNECTING
}

/**
 * 把运行时事实映射为语义状态。规则集中在此，改规则只改这一处。
 *
 * 判定顺序有讲究：先看「服务有没有在跑」，再看「连接好不好」。
 * 否则服务已停但残留的 connected=true 会让首页显示「在线」——这是典型的假阳性。
 *
 * @param running     前台服务是否在运行
 * @param connected   网关连接是否正常
 * @param userStopped 用户是否主动停止过（Config.userStopped）
 * @param generation  当前连接代数，≥ 2 说明重连过
 * @param failCount   连续失败次数
 */
fun resolveBotStatus(
    running: Boolean,
    connected: Boolean,
    userStopped: Boolean = false,
    generation: Int = 1,
    failCount: Int = 0
): BotStatus = when {
    !running && userStopped -> BotStatus.PAUSED
    !running -> BotStatus.OFFLINE
    connected -> BotStatus.ONLINE
    failCount >= 3 -> BotStatus.ERROR
    generation >= 2 -> BotStatus.RECONNECTING
    else -> BotStatus.CONNECTING
}