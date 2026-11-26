package com.ajx.botforge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.permission.PermissionGuide
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.service.BotService
import com.ajx.botforge.service.ShizukuKeepAlive
import com.ajx.botforge.ui.navigation.AppNavHost
import com.ajx.botforge.ui.theme.BotForgeTheme

/**
 * 应用唯一入口 Activity。
 *
 * UI 层已整体迁移到 Jetpack Compose（见 ui/theme、ui/components、ui/navigation、ui/screens）。
 * 本类只保留两类职责：
 *  1. 启动 Compose 内容树；
 *  2. 承载必须由 Activity 完成的原生能力：前台服务启停、电池白名单、
 *     悬浮窗权限、Shizuku 保活授权。
 *
 * 原 BaseActivity 的 View 辅助方法（card/button/toast 等）已全部废弃，
 * 新版页面不再依赖它们。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seedIfEmpty()

        val native = NativeActions()

        // 启动即补齐权限：通知权限能弹窗就弹窗；
        // 悬浮窗/电池白名单只在"首次启动"时跳设置页，避免每次进来都被甩去设置。
        val firstRun = !getSharedPreferences("perms", MODE_PRIVATE)
            .getBoolean("guided", false)
        PermissionGuide.ensureAll(this, jumpSettings = firstRun)
        if (firstRun) {
            getSharedPreferences("perms", MODE_PRIVATE).edit()
                .putBoolean("guided", true).apply()
        }

        setContent {
            BotForgeTheme {
                AppNavHost(
                    onRequestBattery = { native.requestBatteryWhitelist() },
                    onRequestOverlay = { native.requestOverlay() },
                    onApplyShizuku = { native.applyShizuku() },
                    onReloadBots = { native.reloadBots() },
                    onToggleService = { native.toggleService() },
                    onRequestPermissions = { PermissionGuide.ensureAll(this, true) }
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        Logger.i("Permission", "通知权限结果 = $granted")
    }

    /** 首启写入默认模型，保持与旧版本一致的行为。 */
    private fun seedIfEmpty() {
        if (Config.loadModels().isEmpty()) {
            Config.saveModels(
                mutableListOf(
                    Config.Model(
                        id = Config.newId("m"),
                        name = "默认模型",
                        baseUrl = "https://api.openai.com/v1",
                        apiKey = "",
                        model = "gpt-4o-mini"
                    )
                )
            )
            Logger.i("Main", "未检测到模型配置，已写入默认占位模型")
        }
    }

    /** 原生能力集合，供 Compose 页面按需回调。 */
    inner class NativeActions {

        fun toggleService() {
            if (BotService.instance != null) {
                startService(
                    Intent(this@MainActivity, BotService::class.java)
                        .setAction(BotService.ACTION_STOP)
                )
            } else {
                val enabled = Config.loadBots().filter { it.enabled }
                if (enabled.isEmpty()) {
                    toast("没有已启用的机器人，请先在「机器人」里启用一个")
                    return
                }
                BotService.start(this@MainActivity)
            }
        }

        fun requestBatteryWhitelist() {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已在电池白名单")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        fun requestOverlay() {
            if (Settings.canDrawOverlays(this@MainActivity)) {
                toast("已授权悬浮窗")
                return
            }
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        fun applyShizuku() {
            val st = ShizukuKeepAlive.status(this@MainActivity)
            if (!st.running) {
                toast("Shizuku 未运行，请先打开 Shizuku")
                return
            }
            if (!st.granted) {
                toast("请在 Shizuku 弹窗里允许 BotForge")
                return
            }
            val a = ShizukuKeepAlive.whitelistBattery(this@MainActivity)
            val b = ShizukuKeepAlive.allowBackground(this@MainActivity)
            toast(if (a && b) "已放开后台限制" else "部分完成，详见日志")
        }

        /** 重新读取机器人配置并重建网关连接。 */
        fun reloadBots() {
            BotManager.reload(this@MainActivity)
            toast("已重载机器人配置")
        }

        private fun toast(msg: String) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}