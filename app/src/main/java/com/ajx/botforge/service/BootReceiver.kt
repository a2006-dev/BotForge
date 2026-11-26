package com.ajx.botforge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger

/**
 * 开机自启 / 应用更新后自启
 *
 * 两个条件都满足才拉起：
 *   1. 用户在设置里开了「开机自启」
 *   2. 用户没有主动停止过服务
 *
 * 旧实现只判断 autoStart，导致用户点了停止、重启手机后
 * 服务又自己起来了。开机属于最容易违背用户意图的场景，
 * 必须把主动停止的意图也算进去。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Logger.i("Boot", "收到广播: $action")

        if (!Config.autoStart) {
            Logger.i("Boot", "开机自启已关闭，忽略")
            return
        }
        if (Config.userStopped) {
            Logger.i("Boot", "用户曾主动停止服务，开机不自动拉起")
            return
        }
        if (!Config.canAutoRestart()) {
            Logger.i("Boot", "自动拉起已关闭，忽略")
            return
        }

        // 开机后系统限制多，先尝试 Shizuku 提权直拉
        val ok = ShizukuKeepAlive.startService(ctx.applicationContext)
        Logger.i("Boot", "Shizuku 提权拉起: $ok")
        // 无论成功与否都排一次兜底
        BotService.scheduleRestart(ctx.applicationContext, 5000L)
    }
}