package com.ajx.botforge.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ajx.botforge.data.Logger

/**
 * 权限引导器。
 *
 * 背景：Manifest 里早就声明了 POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW，
 * 但代码里从来没有主动申请过。结果是：
 *  - 通知整体 importance=NONE，前台服务通知不可见，进程被降优先级，后台更易被冻结；
 *  - 悬浮窗 addView 直接抛 BadTokenException（permission denied for window type 2038）。
 *
 * 本类的职责就是"缺什么补什么"，并且区分两种权限类型：
 *  - 运行时权限（POST_NOTIFICATIONS，API 33+）：用系统弹窗申请；
 *  - 特殊权限（悬浮窗 / 电池白名单）：无法弹窗授予，只能跳到设置页由用户手动打开。
 */
object PermissionGuide {

    private const val REQ_NOTIFICATION = 1001

    /** 一次权限检查的结果快照。 */
    data class Status(
        val notification: Boolean,
        val notificationSupported: Boolean,
        val overlay: Boolean,
        val battery: Boolean
    ) {
        /** 是否还有可以自动申请（弹窗）的权限没拿到 */
        val needsRuntimeRequest: Boolean
            get() = notificationSupported && !notification

        /** 是否还有需要去设置页手动打开的权限 */
        val needsSettingsJump: Boolean
            get() = !overlay || !battery

        val allGood: Boolean
            get() = notification && overlay && battery
    }

    /** 通知权限是否已授予。API < 33 无需该权限，视为已授予。 */
    fun hasNotification(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 通知权限在当前系统上是否存在（API < 33 不存在，不需要申请） */
    fun notificationSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 悬浮窗权限（特殊权限，只能跳设置页授予） */
    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 是否已加入电池优化白名单 */
    fun hasBatteryWhitelist(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun status(ctx: Context): Status = Status(
        notification = hasNotification(ctx),
        notificationSupported = notificationSupported(),
        overlay = hasOverlay(ctx),
        battery = hasBatteryWhitelist(ctx)
    )

    /**
     * 申请运行时权限（目前只有通知）。
     * 特殊权限无法通过此方式授予，调用方需另外处理 overlay / battery。
     */
    fun requestRuntime(activity: Activity) {
        if (!notificationSupported()) return
        if (hasNotification(activity)) return
        Logger.i("Permission", "申请通知权限 POST_NOTIFICATIONS")
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATION
        )
    }

    /** 跳到悬浮窗授权页 */
    fun openOverlaySettings(ctx: Context) {
        Logger.i("Permission", "跳转悬浮窗授权页")
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Logger.w("Permission", "打开悬浮窗设置失败: " + it.message)
            toastFallback(ctx, "无法打开悬浮窗设置，请手动到 系统设置→应用→特殊权限 中开启")
        }
    }

    /**
     * 跳电池优化白名单。
     * 优先用官方弹窗 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，
     * 部分 ROM（含 vivo）禁用了这个弹窗，则退回列表页。
     */
    fun requestBatteryWhitelist(ctx: Context) {
        if (hasBatteryWhitelist(ctx)) {
            Logger.i("Permission", "已在电池白名单中，跳过")
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + ctx.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(direct); true }.getOrDefault(false)
        if (!ok) {
            Logger.w("Permission", "电池优化弹窗不可用，跳转列表页")
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { toastFallback(ctx, "无法打开电池优化设置，请手动添加白名单") }
        }
    }

    /**
     * 一次性补齐所有权限：
     *  - 能弹窗的（通知）立即弹窗；
     *  - 悬浮窗、电池白名单只在 [jumpSettings] 为 true 时跳设置页，
     *    避免每次启动都把用户丢到设置页（首次启动 / 点按钮时才跳）。
     */
    fun ensureAll(activity: Activity, jumpSettings: Boolean) {
        val st = status(activity)
        Logger.i(
            "Permission",
            "权限检查 通知=${st.notification} 悬浮窗=${st.overlay} 电池=${st.battery}"
        )
        if (st.needsRuntimeRequest) requestRuntime(activity)
        if (!jumpSettings) return
        if (!st.overlay) openOverlaySettings(activity)
        else if (!st.battery) requestBatteryWhitelist(activity)
    }

    /** 供 UI 显示的一行权限摘要 */
    fun summary(ctx: Context): String {
        val st = status(ctx)
        val n = if (st.notification) "通知✓" else "通知✗"
        val o = if (st.overlay) "悬浮窗✓" else "悬浮窗✗"
        val b = if (st.battery) "电池✓" else "电池✗"
        return "$n  $o  $b"
    }

    /** 用中文说清楚"缺了什么、缺了会怎样"，给提示条用 */
    fun missingText(st: Status): String {
        val miss = mutableListOf<String>()
        if (st.notificationSupported && !st.notification) {
            miss.add("通知权限未开，前台服务通知不可见，后台容易被系统降级冻结")
        }
        if (!st.overlay) {
            miss.add("悬浮窗权限未开，保活悬浮窗无法显示")
        }
        if (!st.battery) {
            miss.add("未加入电池优化白名单，熄屏后进程可能被回收")
        }
        return if (miss.isEmpty()) "权限已全部就绪" else miss.joinToString("；")
    }

    private fun toastFallback(ctx: Context, msg: String) {
        runCatching {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
