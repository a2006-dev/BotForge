package com.ajx.botforge.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.ajx.botforge.data.Logger

/**
 * 保活悬浮窗（参考 GKD 的 KeepAliveOverlayCoordinator）
 *
 * 原理：系统会把「持有可见悬浮窗的应用」视为前台，进程优先级显著高于
 * 仅有前台服务的应用。挂一个 1x1 透明、不可触摸、不抢焦点的 Window 即可。
 *
 * 注意：
 *  - 必须有 SYSTEM_ALERT_WINDOW 权限
 *  - 尺寸不能为 0（0 会被判定不可见，失去意义）
 *  - 不要设 FLAG_NOT_TOUCHABLE 之外的交互 flag，避免影响用户操作
 */
object KeepAliveOverlay {

    private var view: View? = null
    private var wm: WindowManager? = null

    fun acquire(ctx: Context): Boolean {
        if (view != null) return true
        return try {
            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                1, 1,               // 必须是 1x1，0 会被判定不可见
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 0.01f     // 不完全透明，避免被系统优化掉
            }
            w.addView(v, lp)
            view = v
            wm = w
            Logger.i("Overlay", "保活悬浮窗已挂载")
            true
        } catch (e: Exception) {
            Logger.w("Overlay", "悬浮窗挂载失败（缺权限？）: ${e.message}")
            false
        }
    }

    fun release() {
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
        wm = null
        Logger.i("Overlay", "保活悬浮窗已移除")
    }

    fun isAttached(): Boolean = view != null
}