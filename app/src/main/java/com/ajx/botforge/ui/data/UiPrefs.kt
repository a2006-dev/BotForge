package com.ajx.botforge.ui.data

import android.content.Context
import com.ajx.botforge.data.Config

/**
 * 全局界面偏好（目前只有主题）。
 *
 * 为什么单独开一个 Store 而不是直接用 `remember { Config.themeMode }`：
 *  - MainActivity 需要读它决定 BotForgeTheme 的 mode；
 *  - 设置页需要写它，并让根 Composition 立即重组；
 *  - `remember` 只在进入 Compose 时读一次，设置页改了以后根节点不会变，
 *    表现就是「点了主题但界面没动，得杀进程重进」。
 *
 * 用 StateFlow 承载，两边都订阅同一份真值，写操作统一走 [setThemeMode]。
 */
object UiPrefs {
    private val _themeMode = kotlinx.coroutines.flow.MutableStateFlow(
        com.ajx.botforge.ui.theme.ThemeMode.SYSTEM
    )
    val themeMode: kotlinx.coroutines.flow.StateFlow<com.ajx.botforge.ui.theme.ThemeMode> =
        _themeMode

    /** 由 Application/Activity 启动时调用一次，把磁盘值灌进内存。 */
    fun load() {
        _themeMode.value = com.ajx.botforge.ui.theme.currentThemeMode()
    }

    /** 设置页调用：先落盘再更新内存，保证冷启动读到的就是新值。 */
    fun setThemeMode(mode: com.ajx.botforge.ui.theme.ThemeMode) {
        Config.themeMode = mode.name
        _themeMode.value = mode
    }
}

/**
 * 首次引导可见性判定。
 *
 * 规则（《02-信息架构》§8）：仅在「无任何机器人 且 从未完成引导」时出现。
 * 不判断服务是否在跑 —— 有机器人就说明用户已经会用，不需要再教。
 */
object Onboarding {
    fun shouldShow(ctx: Context): Boolean {
        if (Config.onboarded) return false
        val hasBot = runCatching { Config.loadBots().isNotEmpty() }.getOrDefault(false)
        return !hasBot
    }

    fun markDone() {
        Config.onboarded = true
    }
}
