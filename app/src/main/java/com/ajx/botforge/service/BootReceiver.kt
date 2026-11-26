package com.ajx.botforge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger

/**
 * 开机自启 / 应用更新后自启
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Logger.i("Boot", "收到广播: $action")
        if (!Config.autoStart) {
            Logger.i("Boot", "自动启动已关闭，忽略")
            return
        }
        // 开机后系统限制多，先尝试 Shizuku 提权直拉
        val ok = ShizukuKeepAlive.startService(ctx.applicationContext)
        Logger.i("Boot", "Shizuku 提权拉起: $ok")
        // 无论成功与否都排一次兜底
        BotService.scheduleRestart(ctx.applicationContext, 5000L)
    }
}