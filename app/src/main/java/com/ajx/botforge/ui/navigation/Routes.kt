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