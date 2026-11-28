package com.ajx.botforge.ui.navigation

/**
 * 全站路由表。所有页面均为 Compose 单 Activity 内的路由，
 * 与 Manifest 中保留的空壳 Activity 无冲突。
 */
object Routes {
    const val DASHBOARD = "dashboard"
    const val BOTS = "bots"
    const val BOT_EDIT = "bots/edit"
    const val BOT_GROUPS = "bots/groups"
    const val PERSONAS = "personas"
    const val PERSONA_EDIT = "personas/edit"
    const val MODELS = "models"
    const val MODEL_EDIT = "models/edit"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
    const val PLUGINS = "plugins"
    const val PLUGIN_DETAIL = "plugins/detail"
    const val ABOUT = "about"
    /** 主人绑定与管理员管理。 */
    const val PERMISSIONS = "permissions"
    /** 内置 Linux 终端。 */
    const val TERMINAL = "terminal"
    /** 工具列表页（插件/工具包安装）。 */
    const val TOOLS = "tools"
    /** 工具包权限管理：包开关与单工具 ALLOW/ASK/DENY。 */
    const val TOOL_PACKAGES = "tools/packages"
    /** 记忆管理：按会话列出本地记忆文件、预览与清理。 */
    const val MEMORY = "memory"

    /**
     * 应用内试聊页：选定人设 + 模型 + 是否带工具，直接在 App 内测试对话与工具调用。
     *
     * 不需要配置 QQ 机器人，也不需要切出 App。
     */
    const val TEST_CHAT = "tools/test_chat"

    /** 带机器人 id 的编辑页路由，id 为空表示新建。 */
    fun botEdit(id: String? = null): String =
        if (id.isNullOrEmpty()) BOT_EDIT else "$BOT_EDIT?id=$id"

    fun botGroups(botId: String): String = "$BOT_GROUPS?botId=$botId"

    fun personaEdit(id: String? = null): String =
        if (id.isNullOrEmpty()) PERSONA_EDIT else "$PERSONA_EDIT?id=$id"

    fun modelEdit(id: String? = null): String =
        if (id.isNullOrEmpty()) MODEL_EDIT else "$MODEL_EDIT?id=$id"

    fun pluginDetail(name: String): String = "$PLUGIN_DETAIL?name=$name"
}