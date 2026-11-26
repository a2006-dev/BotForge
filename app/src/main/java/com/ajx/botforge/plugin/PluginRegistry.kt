package com.ajx.botforge.plugin

import android.content.Context
import com.ajx.botforge.data.Logger
import org.json.JSONObject
import java.io.File

/**
 * 插件注册中心
 *
 * 职责：
 *  - 扫描插件目录，加载所有合法 plugin.json
 *  - 维护 指令名 → 插件 的映射，处理冲突
 *  - 首次启动释放内置示例插件
 *
 * 插件目录：/sdcard/Android/data/com.ajx.botforge/files/plugins/<插件目录>/
 * 用外部私有目录，用户可以用任意文件管理器直接丢插件进去。
 */
object PluginRegistry {

    private var ctxRef: Context? = null
    private var pluginDir: File? = null

    private val plugins = mutableListOf<PluginManifest>()
    /** 指令名(小写) → 指令定义 */
    private val commandIndex = mutableMapOf<String, PluginCommand>()
    /** 指令 → 所属插件，供执行器反查目录 */
    private val cmdOwner = mutableMapOf<String, PluginManifest>()

    @Volatile
    private var loaded = false

    fun init(ctx: Context) {
        ctxRef = ctx.applicationContext
        // 优先外部私有目录（用户好操作），不可用时退回内部目录
        val ext = ctx.getExternalFilesDir("plugins")
        pluginDir = if (ext != null) ext else File(ctx.filesDir, "plugins")
        pluginDir?.mkdirs()
        ensureExamples()
    }

    /** 插件根目录 */
    fun rootDir(): File? = pluginDir

    /** 重新扫描（用户丢新插件后可调用） */
    @Synchronized
    fun reload() {
        plugins.clear()
        commandIndex.clear()
        cmdOwner.clear()
        loaded = false

        val root = pluginDir ?: return
        val dirs = root.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (d in dirs.sortedBy { it.name }) {
            val mf = File(d, "plugin.json")
            if (!mf.isFile) {
                Logger.w("Plugin", "跳过 ${d.name}：缺少 plugin.json")
                continue
            }
            val manifest = try {
                PluginManifest.parse(JSONObject(mf.readText()), d)
            } catch (e: Exception) {
                Logger.w("Plugin", "解析失败 ${d.name}: ${e.message}")
                null
            }
            if (manifest == null) {
                Logger.w("Plugin", "跳过 ${d.name}：manifest 格式错误")
                continue
            }
            if (manifest.api > PluginManifest.CURRENT_API) {
                Logger.w("Plugin", "跳过 ${manifest.id}：要求协议 v${manifest.api}，当前 v${PluginManifest.CURRENT_API}")
                continue
            }
            plugins.add(manifest)
            for (c in manifest.commands) {
                if (commandIndex.containsKey(c.command)) {
                    // 先到先得，冲突的后来者被忽略
                    Logger.w("Plugin", "指令冲突 /${c.command}：${manifest.id} 被忽略")
                    continue
                }
                commandIndex[c.command] = c
                cmdOwner[c.command] = manifest
            }
        }
        loaded = true
        Logger.i("Plugin", "已加载 ${plugins.size} 个插件，${commandIndex.size} 条指令")
    }

    /** 所有已加载插件 */
    fun all(): List<PluginManifest> {
        if (!loaded) reload()
        return plugins.toList()
    }

    /** 按指令名查 */
    fun find(command: String): PluginCommand? {
        if (!loaded) reload()
        return commandIndex[command.lowercase().removePrefix("/")]
    }

    /** 执行器反查插件目录 */
    fun manifestOf(cmd: PluginCommand): PluginManifest? = cmdOwner[cmd.command]

    /** 当前可用指令名列表，用于 /help */
    fun commandNames(): List<String> = commandIndex.keys.toList()

    // ------------------------------------------------------------ 示例插件
    /** 首次启动释放示例插件，已存在则不覆盖（用户可能改过） */
    private fun ensureExamples() {
        val root = pluginDir ?: return
        val flag = File(root, ".examples_released_v1")
        if (flag.exists()) return
        try {
            writeExampleSysinfo(root)
            writeExampleHello(root)
            flag.writeText("ok")
            Logger.i("Plugin", "示例插件已释放到 ${root.absolutePath}")
        } catch (e: Exception) {
            Logger.w("Plugin", "释放示例插件失败: ${e.message}")
        }
    }

    private fun writeExampleHello(root: File) {
        val dir = File(root, "hello")
        dir.mkdirs()
        File(dir, "plugin.json").writeText(
            """
            {
              "id": "com.ajx.botforge.hello",
              "name": "打招呼",
              "version": "1.0.0",
              "api": 1,
              "author": "BotForge",
              "description": "示例插件：演示如何接收参数并回复",
              "commands": [
                {
                  "command": "hello",
                  "description": "回一句问候",
                  "usage": "/hello [名字]",
                  "entry": "main.sh",
                  "timeout": 10,
                  "scope": ["c2c"]
                }
              ]
            }
            """.trimIndent()
        )
        File(dir, "main.sh").writeText(
            """
            #!/system/bin/sh
            # 参数通过 $1 $2 ... 传入
            NAME="${'$'}{1:-世界}"
            echo "你好，${'$'}NAME！"
            echo "这条消息由插件 $(basename ${'$'}BF_PLUGIN_DIR) 在你手机上跑出来的。"
            """.trimIndent()
        )
    }

    private fun writeExampleSysinfo(root: File) {
        val dir = File(root, "sysinfo")
        dir.mkdirs()
        File(dir, "plugin.json").writeText(
            """
            {
              "id": "com.ajx.botforge.sysinfo",
              "name": "设备信息",
              "version": "1.0.0",
              "api": 1,
              "author": "BotForge",
              "description": "示例插件：读取手机电量、内存、存储信息",
              "commands": [
                {
                  "command": "sysinfo",
                  "description": "查看手机状态",
                  "usage": "/sysinfo",
                  "entry": "main.sh",
                  "timeout": 15,
                  "scope": ["c2c"]
                }
              ]
            }
            """.trimIndent()
        )
        File(dir, "main.sh").writeText(
            """
            #!/system/bin/sh
            echo "=== 电量 ==="
            dumpsys battery 2>/dev/null | grep -E 'level|status' | head -2
            echo
            echo "=== 内存 ==="
            free -h 2>/dev/null || cat /proc/meminfo | head -3
            echo
            echo "=== 存储 ==="
            df -h /data 2>/dev/null | tail -1
            """.trimIndent()
        )
    }
}