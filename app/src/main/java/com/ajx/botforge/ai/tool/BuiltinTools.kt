package com.ajx.botforge.ai.tool

import com.ajx.botforge.App
import com.ajx.botforge.term.HiddenExec
import com.ajx.botforge.data.Logger
import com.ajx.botforge.term.LinuxShell
import com.ajx.botforge.term.TerminalEnvironment
import com.ajx.botforge.plugin.PluginCommand
import com.ajx.botforge.plugin.PluginRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 内置工具集。工具本身只做「能做的事」，不做权限判断。
 *
 * 谁能用、要不要确认，由 [PackageRegistry] 按包与用户偏好决定；
 * 工具这里只管执行并把成败如实报回 [ToolResult]。
 */
object BuiltinTools {

    /** 单次执行输出上限，超了截断，避免把上下文撑爆 */
    private const val MAX_OUT = 2000

    /**
     * AgentLoop 拿工具的入口。
     *
     * 不直接返回固定列表，而是走注册表：包没启用、工具被 DENY、
     * 或者处于 ASK 档，都在那一层过滤掉。这样「模型能看到什么工具」
     * 只有一个决定点，不会出现某个调用路径绕过权限的情况。
     *
     * 首次调用时把内置包挂进注册表，之后直接读。注册是幂等的。
     */
    fun all(): List<Tool> {
        ensureRegistered()
        return PackageRegistry.activeTools()
    }

    @Volatile
    private var registered = false

    /** 把内置包装进注册表。重复调用无副作用。 */
    @Synchronized
    fun ensureRegistered() {
        if (registered) return
        PackageRegistry.installAll(BuiltinPackages.all())
        registered = true
        val (pkgs, tools) = PackageRegistry.stats()
        com.ajx.botforge.data.Logger.i("Pkg", "已注册 $pkgs 个可用包，$tools 个工具")
    }

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
            "在手机的 Termux 用户空间（proot 容器，Debian/Ubuntu 系 apt 包管理）里" +
                "执行一条或多行 shell 命令，返回标准输出与标准错误合并后的文本。" +
                "支持管道、重定向、&&、for 循环、heredoc；支持 apt install / python3 / pip / git / curl。" +
                "多条命令可直接换行写在一起，会按顺序执行。" +
                "宿主存储挂在 /sdcard，家目录是容器内的 /data/data/com.termux/files/home。" +
                "每条命令都有独立退出码；安装类慢命令可把 timeout 调到 300。" +
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

        override fun run(args: JSONObject): ToolResult {
            val cmd = args.optString("cmd").trim()
            if (cmd.isEmpty()) return ToolResult.fail("cmd 不能为空")
            val workdir = args.optString("workdir").takeIf { it.isNotBlank() }
            val timeout = args.optInt("timeout", 120).coerceIn(5, 300).toLong()

            // 走隐藏执行器：常驻 bash + 标记信封，不需要 PTY，
            // 也不依赖界面上的交互式会话是否还活着。
            val r = HiddenExec.run(App.instance, cmd, timeout, workdir)
            r.error?.let { err ->
                // 执行器层面的失败（环境没起来、超时、shell 挂了），
                // 与「命令跑了但退出码非 0」是两回事，要分开报。
                return ToolResult.fail("错误：$err", r.output)
            }
            if (r.timedOut) {
                return ToolResult.fail("命令超时（${timeout}s），已终止", r.output)
            }
            if (r.exitCode != 0) {
                // 退出码非 0 就算失败：以前一律当成功返回，
                // 模型拿到一段错误输出却以为执行成功了，接着往下编。
                val body = r.output.ifEmpty { "（无输出）" }
                return ToolResult.fail("命令退出码 ${r.exitCode}", body)
            }
            return ToolResult.ok(
                if (r.output.isEmpty()) "（无输出，退出码 ${r.exitCode}）" else r.output
            )
        }
    }

    // ------------------------------------------------- 终端环境自检
    /**
     * 排查 proot 容器为什么起不来。
     *
     * release 包的 logcat 被剥离、App 私有目录又被 SELinux 挡住，
     * 排查时外部完全看不到内部状态。这个工具把 rootfs / 原生库 / loader /
     * 环境变量，以及真正跑一次 proot 的退出码和输出，全部落盘到
     * /sdcard/Download/bf_terminal_diag.txt，供人工查看。
     */
    object DiagnoseTerminalTool : Tool {
        override val name = "diagnose_terminal"
        override val description =
            "诊断 Linux 容器为什么无法执行命令。会把环境状态与一次真实 proot 试跑的" +
                "退出码/输出写入 /sdcard/Download/bf_terminal_diag.txt。" +
                "当 run_command 报 execve 权限错误、找不到命令、或怀疑容器没起来时调用它。"

        override val parameters: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("required", JSONArray())

        override fun run(args: JSONObject): ToolResult {
            return try {
                val text = TerminalEnvironment.dumpDiagnostics(App.instance)
                ToolResult.ok(
                    "自检完成，已写入 /sdcard/Download/bf_terminal_diag.txt\n\n" +
                        text.take(1800)
                )
            } catch (e: Throwable) {
                ToolResult.fail(
                    "自检失败：" + e.javaClass.simpleName + " " + (e.message ?: "")
                )
            }
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

        override fun run(args: JSONObject): ToolResult {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return ToolResult.fail("path 不能为空")
            val max = args.optInt("max_chars", 8000).coerceIn(200, 40000)
            val f = File(path)
            if (!f.exists()) return ToolResult.fail("文件不存在：$path")
            if (f.isDirectory) {
                return ToolResult.fail("$path 是目录，请用 run_command 的 ls")
            }
            if (f.length() > 2L * 1024 * 1024) {
                return ToolResult.fail(
                    "文件太大（" + (f.length() / 1024) + " KB），请用 run_command 配合 head/sed 分段读。"
                )
            }
            return try {
                val text = f.readText()
                ToolResult.ok(
                    if (text.length <= max) text
                    else text.take(max) + "\n…（已截断，原文 ${text.length} 字符）"
                )
            } catch (e: Exception) {
                ToolResult.fail("读取失败：${e.message}")
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

        override fun run(args: JSONObject): ToolResult {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return ToolResult.fail("path 不能为空")
            val content = args.optString("content")
            val append = args.optBoolean("append", false)
            return try {
                val f = File(path)
                f.parentFile?.mkdirs()
                if (append) f.appendText(content) else f.writeText(content)
                ToolResult.ok("已写入 ${f.length()} 字节：$path")
            } catch (e: Exception) {
                ToolResult.fail(
                    "写入失败：${e.message}（若是 /sdcard 路径，需已授予存储权限）"
                )
            }
        }
    }

    // --------------------------------------------------------- 列出插件
    object ListPluginsTool : Tool {
        override val name = "list_plugins"
        override val description = "列出手机上已安装的 BotForge 插件及其可用指令。"

        override val parameters: JSONObject = noParams()

        override fun run(args: JSONObject): ToolResult {
            val list = PluginRegistry.all()
            if (list.isEmpty()) return ToolResult.ok("当前没有安装任何插件。")
            val sb = StringBuilder("已安装 ${list.size} 个插件：\n")
            for (p in list) {
                sb.append("\n- ${p.name} v${p.version} (${p.id})\n")
                sb.append("  ${p.description}\n")
                for (c in p.commands) {
                    sb.append("  指令：${c.usage} — ${c.description}\n")
                }
            }
            return ToolResult.ok(sb.toString().trim())
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

        override fun run(args: JSONObject): ToolResult {
            val name = args.optString("command").trim().removePrefix("/")
            if (name.isEmpty()) return ToolResult.fail("command 不能为空")

            val cmd: PluginCommand = PluginRegistry.find(name)
                ?: return ToolResult.fail("没有名为 $name 的指令", "可先调用 list_plugins 查看。")

            // 工具层只允许私聊安全的指令，群聊由上层拦截
            if (!cmd.allows("c2c")) {
                return ToolResult.fail("指令 $name 不允许以这种方式执行。")
            }

            val argList = args.optString("args").trim()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }

            val manifest = PluginRegistry.manifestOf(cmd)
                ?: return ToolResult.fail("插件未加载")
            val script = File(manifest.dir, cmd.entry)
            if (!script.exists()) return ToolResult.fail("入口脚本不存在：${cmd.entry}")

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
                    return ToolResult.fail("插件执行超时（${cmd.timeout}s），已终止")
                }
                reader.join(800)
                val out = synchronized(sb) { sb.toString() }.trim()
                if (proc.exitValue() != 0) {
                    // 插件脚本的退出码是它自己给出的成败信号，
                    // 以前被丢掉了，模型看不到失败，就会当作成功继续往下走。
                    return ToolResult.fail("插件退出码 ${proc.exitValue()}", out.ifEmpty { "（无输出）" })
                }
                if (out.isEmpty()) ToolResult.ok("（无输出）")
                else if (out.length > MAX_OUT) ToolResult.ok(out.take(MAX_OUT) + "\n…（已截断）")
                else ToolResult.ok(out)
            } catch (e: Exception) {
                Logger.w("Tool", "run_plugin 失败: ${e.message}")
                ToolResult.fail("插件执行出错：${e.message}")
            } finally {
                try { proc?.destroy() } catch (_: Exception) {}
            }
        }
    }
}