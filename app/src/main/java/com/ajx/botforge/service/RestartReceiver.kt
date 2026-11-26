package com.ajx.botforge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger

/**
 * 定时拉起：由 AlarmManager 触发
 * 这是保活的最后一道防线
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Config.keepAlive) {
            Logger.i("Restart", "保活已关闭，跳过")
            return
        }
        val app = ctx.applicationContext
        // 主服务还活着就撤掉排程，避免不必要的唤醒
        if (BotService.instance != null) {
            Logger.i("Restart", "主服务存活，跳过拉起")
            return
        }
        Logger.w("Restart", "主服务不在，开始拉起")
        runCatching {
            val i = Intent(app, BotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(i)
            } else {
                app.startService(i)
            }
        }.onFailure {
            Logger.e("Restart", "拉起失败: ${it.message}")
            // 再排一次，指数退避
            BotService.scheduleRestart(app, 15_000L)
        }
    }
}