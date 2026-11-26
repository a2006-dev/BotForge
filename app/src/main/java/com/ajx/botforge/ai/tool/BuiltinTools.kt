package com.ajx.botforge.ai.tool

import com.ajx.botforge.data.Logger
import com.ajx.botforge.plugin.PluginCommand
import com.ajx.botforge.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 内置工具集。工具只做「能做的事」，权限判断由调用方（MessageHandler）先做。 */
object BuiltinTools {

    /** 单次执行输出上限，超了截断，避免把上下文撑爆 */
    private const val MAX_OUT = 2000

    fun all(): List<Tool> = listOf(
        RunCommandTool,
        ListPluginsTool,
        RunPluginTool
    )

    // ------------------------------------------------------- 执行 shell 命令
    object RunCommandTool : Tool {
        override val name = "run_command"
        override val description =
            "在手机上的 Linux 环境执行一条 shell 命令并返回输出。" +
                "适合查看文件、系统信息、网络状态、运行脚本等。" +
                "不要用它做危险操作（删系统文件、改系统设置）。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "cmd",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "要执行的 shell 命令，例如 df -h")
                )
            )
            .put("required", JSONArray().put("cmd"))

        override fun run(args: JSONObject): String {
            val cmd = args.optString("cmd").trim()
            if (cmd.isEmpty()) return "错误：cmd 不能为空"

            var proc: Process? = null
            return try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.redirectErrorStream(true)
                proc = pb.start()
                proc.outputStream.close()

                val sb = StringBuilder()
                val reader = Thread {
                    try {
                        proc.inputStream.bufferedReader().use { br ->
                            val buf = CharArray(1024)
                            while (true) {
                                val n = br.read(buf)
                                if (n <= 0) break
                                if (sb.length < MAX_OUT * 4) {
                                    synchronized(sb) { sb.appendRange(buf, 0, n) }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                reader.isDaemon = true
                reader.start()

                if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return "执行超时（20s），已终止"
                }
                reader.join(800)
                val out = synchronized(sb) { sb.toString() }.trim()
                val code = proc.exitValue()
                val body = if (out.isEmpty()) "（无输出）"
                else if (out.length > MAX_OUT) out.take(MAX_OUT) + "\n…（已截断）"
                else out
                if (code == 0) body else "退出码 $code\n$body"
            } catch (e: Exception) {
                Logger.w("Tool", "run_command 失败: ${e.message}")
                "执行出错：${e.message}"
            } finally {
                try { proc?.destroy() } catch (_: Exception) {}
            }
        }
    }

    // --------------------------------------------------------- 列出插件
    object ListPluginsTool : Tool {
        override val name = "list_plugins"
        override val description = "列出手机上已安装的 BotForge 插件及其可用指令。"

        override val parameters: JSONObject = noParams()

        override fun run(args: JSONObject): String {
            val list = PluginRegistry.all()
            if (list.isEmpty()) return "当前没有安装任何插件。"
            val sb = StringBuilder("已安装 ${list.size} 个插件：\n")
            for (p in list) {
                sb.append("\n- ${p.name} v${p.version} (${p.id})\n")
                sb.append("  ${p.description}\n")
                for (c in p.commands) {
                    sb.append("  指令：${c.usage} — ${c.description}\n")
                }
            }
            return sb.toString().trim()
        }
    }

    // --------------------------------------------------------- 执行插件
    object RunPluginTool : Tool {
        override val name = "run_plugin"
        override val description =
            "执行一个已安装插件的指令。只能执行 list_plugins 返回过的指令名。" +
                "如果用户要求做某件事而插件列表里有对应指令，就用它。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "command",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "指令名，不带斜杠，例如 sysinfo")
                    )
                    .put(
                        "args",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "传给指令的参数，空格分隔，可为空")
                    )
            )
            .put("required", JSONArray().put("command"))

        override fun run(args: JSONObject): String {
            val name = args.optString("command").trim().removePrefix("/")
            if (name.isEmpty()) return "错误：command 不能为空"

            val cmd: PluginCommand = PluginRegistry.find(name)
                ?: return "没有名为 $name 的指令。可先调用 list_plugins 查看。"

            // 工具层只允许私聊安全的指令，群聊由上层拦截
            if (!cmd.allows("c2c")) return "指令 $name 不允许以这种方式执行。"

            val argList = args.optString("args").trim()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }

            val manifest = PluginRegistry.manifestOf(cmd) ?: return "插件未加载"
            val script = File(manifest.dir, cmd.entry)
            if (!script.exists()) return "入口脚本不存在：${cmd.entry}"

            var proc: Process? = null
            return try {
                val pb = ProcessBuilder(listOf("/system/bin/sh", script.absolutePath) + argList)
                pb.directory(manifest.dir)
                pb.redirectErrorStream(true)
                pb.environment()["BF_PLUGIN_DIR"] = manifest.dir.absolutePath
                pb.environment()["BF_PLUGIN_ID"] = manifest.id
                proc = pb.start()
                proc.outputStream.close()

                val sb = StringBuilder()
                val reader = Thread {
                    try {
                        proc.inputStream.bufferedReader().use { br ->
                            val buf = CharArray(1024)
                            while (true) {
                                val n = br.read(buf)
                                if (n <= 0) break
                                if (sb.length < MAX_OUT * 4) {
                                    synchronized(sb) { sb.appendRange(buf, 0, n) }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                reader.isDaemon = true
                reader.start()

                if (!proc.waitFor(cmd.timeout.toLong(), TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return "插件执行超时（${cmd.timeout}s），已终止"
                }
                reader.join(800)
                val out = synchronized(sb) { sb.toString() }.trim()
                if (out.isEmpty()) "（无输出）"
                else if (out.length > MAX_OUT) out.take(MAX_OUT) + "\n…（已截断）"
                else out
            } catch (e: Exception) {
                Logger.w("Tool", "run_plugin 失败: ${e.message}")
                "插件执行出错：${e.message}"
            } finally {
                try { proc?.destroy() } catch (_: Exception) {}
            }
        }
    }
}