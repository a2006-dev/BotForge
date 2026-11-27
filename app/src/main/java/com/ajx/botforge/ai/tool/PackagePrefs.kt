package com.ajx.botforge.ai.tool

import android.content.Context
import android.content.SharedPreferences

/**
 * 包启用状态与工具权限的存储。
 *
 * 单独一个 SharedPreferences 文件，不塞进 Config 的那个：
 * 这块数据是「用户对工具的管理决定」，跟模型/机器人配置寿命不同，
 * 分开存以后要清配置或导出配置都不用牵连。
 *
 * key 设计：
 *  - pkg_enabled_<pkgId>        Boolean
 *  - tool_perm_<pkgId>__<tool>  String（ALLOW / ASK / DENY）
 * 用 __ 分隔包名和工具名，因为包 id 里可能出现下划线，工具名里不会。
 */
object PackagePrefs {

    private const val FILE = "botforge_packages"
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun ready(): Boolean = ::sp.isInitialized

    // ---------------------------------------------------------------- 包开关
    fun packageEnabled(id: String, defaultForBuiltin: Boolean): Boolean {
        if (!ready()) return defaultForBuiltin
        return sp.getBoolean("pkg_enabled_$id", defaultForBuiltin)
    }

    fun setPackageEnabled(id: String, on: Boolean) {
        if (!ready()) return
        sp.edit().putBoolean("pkg_enabled_$id", on).apply()
    }

    // ---------------------------------------------------------------- 工具权限
    fun toolPermission(pkgId: String, toolName: String): String? {
        if (!ready()) return null
        return sp.getString("tool_perm_${pkgId}__$toolName", null)
    }

    fun setToolPermission(pkgId: String, toolName: String, perm: String) {
        if (!ready()) return
        sp.edit().putString("tool_perm_${pkgId}__$toolName", perm).apply()
    }

    /** 清掉某个工具的用户覆盖，回到包默认 */
    fun clearToolPermission(pkgId: String, toolName: String) {
        if (!ready()) return
        sp.edit().remove("tool_perm_${pkgId}__$toolName").apply()
    }

    /** 插件包被卸载时，顺手清掉它的所有设置，避免重装后带着旧权限 */
    fun clearPackage(pkgId: String) {
        if (!ready()) return
        val ed = sp.edit()
        ed.remove("pkg_enabled_$pkgId")
        sp.all.keys.filter { it.startsWith("tool_perm_${pkgId}__") }
            .forEach { ed.remove(it) }
        ed.apply()
    }
}
