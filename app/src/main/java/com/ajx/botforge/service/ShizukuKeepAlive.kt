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
        rememberContext(ctx)
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
        rememberContext(ctx)
        runCatching {
            if (!Shizuku.pingBinder()) {
                Logger.w(TAG, "Shizuku 服务未运行，请先启动 Shizuku")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "已有授权")
                return
            }
            ensureListener()
            Shizuku.requestPermission(REQUEST_CODE)
            Logger.i(TAG, "已发起授权请求，请在 Shizuku 弹窗中允许")
        }.onFailure { Logger.e(TAG, "请求授权失败: ${it.message}") }
    }

    /**
     * 注册授权结果监听（必须在 requestPermission 之前）
     *
     * Shizuku 的弹窗是异步的：requestPermission() 只是把请求转给 Shizuku 服务，
     * Shizuku 服务随后在主进程里查找已注册的 OnRequestPermissionResultListener，
     * 找到才拉起授权弹窗并回调结果。
     *
     * 不注册监听器时：requestPermission() 返回正常、不抛异常、日志也打得出
     * 「已发起授权请求」，但弹窗永远不会出现——这正是「点了没反应」的真实原因。
     *
     * 用 listenerRegistered 防重复注册：Shizuku 内部用列表保存监听器，
     * 每次 request() 都 add 一次会导致同一结果被回调 N 次。
     */
    @Volatile
    private var listenerRegistered = false

    /**
     * 应用级 Context（授权成功后要拉起服务用）
     *
     * 监听器回调不带 Context，所以在任何拿到 Context 的入口顺手缓存
     * applicationContext。这个引用不会泄漏 Activity，进程内单例持有无风险。
     */
    @Volatile
    private var appContext: Context? = null

    private fun rememberContext(ctx: Context) {
        if (appContext == null) {
            appContext = ctx.applicationContext ?: ctx
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Logger.i(TAG, "用户已授权 Shizuku 提权")
                // 授权成功后立刻做一次保活动作，省得用户再点一次按钮
                appContext?.let { c ->
                    runCatching { startService(c) }
                        .onFailure { Logger.w(TAG, "授权后拉起服务失败: ${it.message}") }
                }
            } else {
                Logger.w(TAG, "用户拒绝授权 Shizuku（grantResult=$grantResult）")
            }
        }

    private fun ensureListener() {
        if (listenerRegistered) return
        synchronized(this) {
            if (listenerRegistered) return
            runCatching {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                listenerRegistered = true
                Logger.i(TAG, "已注册授权结果监听")
            }.onFailure { Logger.e(TAG, "注册授权监听失败: ${it.message}") }
        }
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

    /** Shizuku.newProcess 在 13.x 是 private API，用反射拿 */
    @Suppress("PrivateAPI", "DiscouragedPrivateApi")
    private fun newProcess(cmdArray: Array<String>): Process {
        val cls = Shizuku::class.java
        val m = cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(null, cmdArray, null, null) as Process
    }

    /** 通过 Shizuku 执行 shell 命令，返回输出 */
    fun exec(cmd: String): String {
        return try {
            if (!Shizuku.pingBinder()) return "ERROR: Shizuku 未运行"
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "ERROR: 未授权"
            }
            val proc = newProcess(arrayOf("sh", "-c", cmd))
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
        // 只做电池白名单。不要用 dpm set-device-owner：
        // 那是把设备变成受管设备，属于系统级不可逆改动，普通 App 不应触碰
        val r = exec("cmd deviceidle whitelist +$pkg 2>&1")
        val ok = !r.contains("Error") && !r.contains("Exception") && !r.contains("Unknown")
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