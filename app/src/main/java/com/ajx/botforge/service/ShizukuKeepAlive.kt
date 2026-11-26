package com.ajx.botforge.service

import android.content.Context
import android.content.pm.PackageManager
import com.ajx.botforge.data.Logger
import rikka.shizuku.Shizuku

/**
 * Shizuku 提权保活
 *
 * 原理：通过 Shizuku（以 shell 身份运行）执行 am 命令拉起前台服务，
 * 绕开系统对普通应用「后台启动前台服务」的限制。
 *
 * 这是最强的一条保活路径，但依赖用户在 Shizuku 里授权，
 * 且每次手机重启后需要重新激活 Shizuku（除非用 root 方式自启）。
 */
object ShizukuKeepAlive {

    private const val TAG = "Shizuku"
    private const val REQUEST_CODE = 4213

    class State(
        val installed: Boolean = false,
        val running: Boolean = false,
        val granted: Boolean = false,
        val available: Boolean = false,
        val version: String = ""
    )

    fun status(ctx: Context): State {
        val installed = try {
            ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val running = runCatching {
            @Suppress("DEPRECATION")
            Shizuku.pingBinder()
        }.getOrDefault(false)

        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val available = running && runCatching { !Shizuku.isPreV11() }.getOrDefault(false)

        return State(installed, running, granted, available)
    }

    /** 请求授权 */
    fun request(ctx: Context) {
        runCatching {
            if (!Shizuku.pingBinder()) {
                Logger.w(TAG, "Shizuku 服务未运行，请先启动 Shizuku")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "已有授权")
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
            Logger.i(TAG, "已发起授权请求，请在 Shizuku 弹窗中允许")
        }.onFailure { Logger.e(TAG, "请求授权失败: ${it.message}") }
    }

    /**
     * 用 shell 权限拉起自己的服务
     * 命令等价于：am start-foreground-service -n 包名/服务类名
     */
    fun startService(ctx: Context): Boolean {
        val st = status(ctx)
        if (!st.running) {
            Logger.w(TAG, "Shizuku 未运行，跳过提权拉起")
            return false
        }
        if (!st.granted) {
            Logger.w(TAG, "未获得 Shizuku 授权，跳过提权拉起")
            return false
        }

        val pkg = ctx.packageName
        val cmds = listOf(
            // 优先：直接拉起前台服务
            "am start-foreground-service -n $pkg/com.ajx.botforge.service.BotService",
            // 兜底：先 startservice
            "am start-service -n $pkg/com.ajx.botforge.service.BotService"
        )
        return try {
            for (cmd in cmds) {
                val result = exec(cmd)
                Logger.i(TAG, "提权执行: $cmd → ${result.take(120)}")
                if (result.contains("Starting service") || result.contains("Starting foreground")) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Logger.e(TAG, "提权拉起异常: ${e.message}")
            false
        }
    }

    /** 通过 Shizuku 执行 shell 命令，返回输出 */
    fun exec(cmd: String): String {
        return try {
            if (!Shizuku.pingBinder()) return "ERROR: Shizuku 未运行"
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "ERROR: 未授权"
            }
            val proc = Shizuku.newProcess(
                arrayOf("sh", "-c", cmd), null, null
            )
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            (out + err).trim()
        } catch (e: Throwable) {
            // newProcess 在旧版本不可用时降级为本地执行
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val err = p.errorStream.bufferedReader().use { it.readText() }
                p.waitFor()
                (out + err).trim()
            } catch (e2: Exception) {
                "ERROR: ${e2.message}"
            }
        }
    }

    /** 把自己加入电池优化白名单（shell 权限可直接做） */
    fun whitelistBattery(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val r = exec("dpm set-device-owner $pkg 2>/dev/null; " +
                "cmd deviceidle whitelist +$pkg")
        val ok = !r.contains("Error") && !r.contains("Exception")
        Logger.i(TAG, "电池白名单: ${if (ok) "已加入" else r.take(100)}")
        return ok
    }

    /** 允许后台运行 */
    fun allowBackground(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val r = exec("cmd appops set $pkg RUN_IN_BACKGROUND allow; " +
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                "cmd appops set $pkg START_FOREGROUND allow")
        Logger.i(TAG, "后台权限: ${r.take(120).ifEmpty { "ok" }}")
        return true
    }
}