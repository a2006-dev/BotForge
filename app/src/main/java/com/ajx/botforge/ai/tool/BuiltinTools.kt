package com.ajx.botforge.ai.tool

import com.ajx.botforge.App
import com.ajx.botforge.data.Logger
import com.ajx.botforge.term.LinuxShell
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
        ReadFileTool,
        WriteFileTool,
        ListPluginsTool,
        RunPluginTool
    )

    // ------------------------------------------------------- 执行 shell 命令
    /**
     * 模型的主执行工具。
     *
     * 走 [LinuxShell]，也就是 proot 里的 Alpine 容器，模型因此能用到
     * apt / apk / python3 / git / gcc 这些真实 Linux 工具；不再像以前那样
     * 只能拿到 Android 自带的 toybox sh。
     *
     * 环境没就绪时会自动降级成 Android sh，并在输出前加一句说明，
     * 让模型知道「现在没有 apt」，而不是反复重试同一条命令。
     */
    object RunCommandTool : Tool {
        override val name = "run_command"
        override val description =
            "在手机的 Linux 容器（Alpine）里执行一条 shell 命令并返回输出。" +
                "支持 apk add / python3 / git / gcc / curl 等，支持管道、重定向、&&。" +
                "容器内以 root 身份运行，工作目录默认 /root。" +
                "宿主存储挂在 /sdcard。" +
                "耗时命令（如安装软件包）可调大 timeout，最长 300 秒。" +
                "不要用它做破坏性操作（rm -rf /、改系统分区）。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "cmd",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "要执行的 shell 命令，例如 ls -la /root 或 python3 -V")
                    )
                    .put(
                        "workdir",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "容器内的工作目录，绝对路径，可省略，默认 /root")
                    )
                    .put(
                        "timeout",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "超时秒数，默认 120，最长 300")
                    )
            )
            .put("required", JSONArray().put("cmd"))

        override fun run(args: JSONObject): String {
            val cmd = args.optString("cmd").trim()
            if (cmd.isEmpty()) return "错误：cmd 不能为空"
            val workdir = args.optString("workdir").takeIf { it.isNotBlank() }
            val timeout = args.optInt("timeout", 120).coerceIn(5, 300).toLong()

            val r = LinuxShell.run(App.instance, cmd, workdir, timeout)
            val head = if (r.degraded) {
                "[提示] Linux 容器还没准备好，本次退回 Android 自带 sh 执行（没有 apt/python3）。" +
                    "能在终端页看到解包进度。\n"
            } else ""
            r.error?.let { return head + "错误：" + it + (if (r.output.isNotEmpty()) "\n" + r.output else "") }
            val body = if (r.output.isEmpty()) "（无输出，退出码 ${r.code}）"
            else if (r.code == 0) r.output
            else "退出码 ${r.code}\n${r.output}"
            return head + body
        }
    }

    // --------------------------------------------------------- 读文件
    /**
     * 读文件。虽然模型也能用 run_command + cat，但专门给个工具更稳：
     * 能截断、能报行数，不会因为二进制文件把上下文搞脏。
     */
    object ReadFileTool : Tool {
        override val name = "read_file"
        override val description =
            "读取手机上某个文本文件的内容。路径可写容器路径（/root/x.txt）" +
                "也可写宿主路径（/sdcard/Download/x.txt）。超过 8000 字符会截断。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "path",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "文件绝对路径")
                    )
                    .put(
                        "max_chars",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "最多返回多少个字符，默认 8000")
                    )
            )
            .put("required", JSONArray().put("path"))

        override fun run(args: JSONObject): String {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return "错误：path 不能为空"
            val max = args.optInt("max_chars", 8000).coerceIn(200, 40000)
            val f = File(path)
            if (!f.exists()) return "文件不存在：$path"
            if (f.isDirectory) return "$path 是目录，请用 run_command 的 ls"
            if (f.length() > 2L * 1024 * 1024) {
                return "文件太大（${f.length() / 1024} KB），请用 run_command 配合 head/sed 分段读。"
            }
            return try {
                val text = f.readText()
                if (text.length <= max) text
                else text.take(max) + "\n…（已截断，原文 ${text.length} 字符）"
            } catch (e: Exception) {
                "读取失败：${e.message}"
            }
        }
    }

    // --------------------------------------------------------- 写文件
    /**
     * 写文件。模型让它存笔记、写脚本、保存搜索结果时用。
     * 自动建父目录，覆盖前不弹窗（工具层执行本身就意味着用户已授权）。
     */
    object WriteFileTool : Tool {
        override val name = "write_file"
        override val description =
            "把文本写入手机上的文件，父目录不存在会自动创建。" +
                "写宿主存储用 /sdcard/ 开头的路径；写容器内部用 /root/ 开头。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "path",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "目标文件绝对路径")
                    )
                    .put(
                        "content",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "要写入的完整文本")
                    )
                    .put(
                        "append",
                        JSONObject()
                            .put("type", "boolean")
                            .put("description", "true 为追加，默认 false 覆盖")
                    )
            )
            .put("required", JSONArray().put("path").put("content"))

        override fun run(args: JSONObject): String {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return "错误：path 不能为空"
            val content = args.optString("content")
            val append = args.optBoolean("append", false)
            return try {
                val f = File(path)
                f.parentFile?.mkdirs()
                if (append) f.appendText(content) else f.writeText(content)
                "已写入 ${f.length()} 字节：$path"
            } catch (e: Exception) {
                "写入失败：${e.message}（若是 /sdcard 路径，需已授予存储权限）"
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