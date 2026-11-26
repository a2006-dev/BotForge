package com.ajx.botforge.ui.data

import android.content.Context
import com.ajx.botforge.data.Config
import com.ajx.botforge.data.Logger
import com.ajx.botforge.qq.BotManager
import com.ajx.botforge.service.BotService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI 层唯一的数据入口。
 *
 * 设计约束（务必遵守）：
 * 1. 不直接 new 任何业务单例，全部走 Config / Logger / BotManager 的现有静态接口；
 * 2. 不做任何字段映射上的“美化”，业务字段原样透传，避免 UI 与存量 SharedPreferences 脱节；
 * 3. 所有磁盘读写切到 IO 线程，返回不可变 List，防止 Compose 重组时拿到被外部改动的可变集合。
 *
 * 敏感字段（appSecret / apiKey）由 Config 层与 SecretStore 处理：
 * loadBots() 返回真值、saveBots() 自动写入保险箱并把 JSON 落成占位符，
 * 因此 Repository 不做加解密，只负责“不要在界面上明文展示”。
 */
class BotForgeRepository {

    // ------------------------------------------------------------------ Bot

    suspend fun bots(): List<Config.Bot> = withContext(Dispatchers.IO) {
        Config.loadBots().toList()
    }

    suspend fun findBot(id: String): Config.Bot? = withContext(Dispatchers.IO) {
        Config.loadBots().firstOrNull { it.id == id }
    }

    suspend fun saveBot(bot: Config.Bot): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadBots()
        val index = list.indexOfFirst { it.id == bot.id }
        if (index >= 0) list[index] = bot else list.add(bot)
        Config.saveBots(list)
    }

    suspend fun deleteBot(id: String): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadBots()
        list.removeAll { it.id == id }
        Config.saveBots(list)
    }

    suspend fun setBotEnabled(id: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val list = Config.loadBots()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                list[index] = list[index].copy(enabled = enabled)
                Config.saveBots(list)
            }
        }

    /** 保存某机器人下的群配置。 */
    suspend fun saveGroups(botId: String, groups: List<Config.GroupCfg>): Unit =
        withContext(Dispatchers.IO) {
            val list = Config.loadBots()
            val index = list.indexOfFirst { it.id == botId }
            if (index >= 0) {
                list[index] = list[index].copy(groups = groups)
                Config.saveBots(list)
            }
        }

    // -------------------------------------------------------------- Persona

    suspend fun personas(): List<Config.Persona> = withContext(Dispatchers.IO) {
        Config.loadPersonas().toList()
    }

    suspend fun findPersona(id: String): Config.Persona? = withContext(Dispatchers.IO) {
        Config.loadPersonas().firstOrNull { it.id == id }
    }

    suspend fun savePersona(persona: Config.Persona): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadPersonas()
        val index = list.indexOfFirst { it.id == persona.id }
        if (index >= 0) list[index] = persona else list.add(persona)
        Config.savePersonas(list)
    }

    suspend fun deletePersona(id: String): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadPersonas()
        list.removeAll { it.id == id }
        Config.savePersonas(list)
    }

    // ---------------------------------------------------------------- Model

    suspend fun models(): List<Config.Model> = withContext(Dispatchers.IO) {
        Config.loadModels().toList()
    }

    suspend fun findModel(id: String): Config.Model? = withContext(Dispatchers.IO) {
        Config.loadModels().firstOrNull { it.id == id }
    }

    suspend fun saveModel(model: Config.Model): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadModels()
        val index = list.indexOfFirst { it.id == model.id }
        if (index >= 0) list[index] = model else list.add(model)
        Config.saveModels(list)
    }

    suspend fun deleteModel(id: String): Unit = withContext(Dispatchers.IO) {
        val list = Config.loadModels()
        list.removeAll { it.id == id }
        Config.saveModels(list)
    }

    /**
     * 模型被多少个机器人引用。任务书要求显示“默认模型”，
     * 但 Config.Model 里并没有该字段（见审计报告），
     * 因此这里改为展示真实可计算的引用次数，不虚构字段。
     */
    suspend fun modelRefCount(modelId: String): Int = withContext(Dispatchers.IO) {
        Config.loadBots().count { it.modelId == modelId }
    }

    suspend fun newId(prefix: String): String = withContext(Dispatchers.IO) {
        Config.newId(prefix)
    }

    // --------------------------------------------------------------- Logger

    suspend fun logs(): List<Logger.Line> = withContext(Dispatchers.IO) {
        Logger.snapshot()
    }

    suspend fun clearLogs(): Unit = withContext(Dispatchers.IO) {
        Logger.clear()
    }

    // ------------------------------------------------------------ BotManager

    fun serviceStatus(): String = BotManager.status()

    /** 已加载的客户端数（配置层面），不是在线数 */
    fun runningCount(): Int = BotManager.count()

    /** 真正在线（WebSocket 处于 CONNECTED）的数量 */
    fun onlineCount(): Int = BotManager.onlineCount()

    /** 聚合状态，Dashboard 用真实数字渲染 */
    fun summary(): BotManager.Summary = BotManager.summary()

    /** 每个机器人的连接快照 */
    fun connStates(): List<com.ajx.botforge.qq.BotConnectionState> = BotManager.states()

    /** 订阅连接状态变化（返回反订阅函数） */
    fun observeConnState(cb: (com.ajx.botforge.qq.BotConnectionState) -> Unit): () -> Unit {
        com.ajx.botforge.qq.BotStatusRegistry.listener = cb
        return { com.ajx.botforge.qq.BotStatusRegistry.listener = null }
    }

    suspend fun reloadBots(context: Context): Unit = withContext(Dispatchers.IO) {
        BotManager.reload(context.applicationContext)
    }

    // ------------------------------------------------------------ 服务启停

    /** 服务是否在运行（前台服务存活 ⇒ 主循环在跑，但不代表在线） */
    fun isServiceRunning(): Boolean = BotService.instance != null

    /**
     * 启动前台服务。
     *
     * 返回 null 表示启动成功，否则返回给用户看的原因。
     * 空配置直接拒绝，避免用户点了没反应又不知道原因。
     */
    fun startService(context: Context): String? {
        if (BotService.instance != null) return null
        val enabled = Config.loadBots().filter { it.enabled }
        if (enabled.isEmpty()) {
            return "没有已启用的机器人，请先在「机器人配置」里启用一个"
        }
        if (enabled.any { it.appId.isBlank() || it.appSecret.isBlank() }) {
            return "有机器人缺少 AppID 或密钥，请先补全"
        }
        return runCatching {
            BotService.start(context.applicationContext)
            null
        }.getOrElse { "启动失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 停止前台服务，同时断开所有网关连接 */
    fun stopService(context: Context) {
        runCatching {
            val i = android.content.Intent(context, BotService::class.java)
                .setAction(BotService.ACTION_STOP)
            context.startService(i)
        }.onFailure {
            // Android 12+ 后台无法 startService，退化为直接停 BotManager
            BotManager.stop()
        }
    }
}