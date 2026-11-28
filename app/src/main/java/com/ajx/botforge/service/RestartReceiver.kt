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
        // 用户手动点了「停止服务」。此时绝不能拉起 —— 否则定时器会把服务
        // 一遍遍救活，用户按了停止却停不掉，只能去系统设置里强停应用。
        //
        // 之前这里只查了 keepAlive，漏了 canAutoRestart()，而后者才是
        // 「自动重启」与「用户主动停止」这两个意图的合取判断：
        //   canAutoRestart = autoRestart && !userStopped
        if (!Config.canAutoRestart()) {
            val why = if (Config.userStopped) "用户已手动停止服务" else "自动重启已关闭"
            Logger.i("Restart", "$why，跳过拉起")
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