package com.ajx.botforge.tools

import android.content.Context
import com.ajx.botforge.term.TerminalEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具包管理与执行。
 *
 * ## 双层结构
 *
 * 第一层是 Alpine rootfs 里预装的命令行工具（git / curl / python3 / node 等），
 * 由 `apk add` 安装，用户直接在终端里用。
 *
 * 第二层是这里的工具包：一个目录 + 一份 `tool.json` + 一个入口脚本。
 * 界面按清单自动生成参数表单，执行时把参数以环境变量注入，再在
 * rootfs 内跑脚本，把标准输出作为结果返回。
 *
 * ## 安全边界
 *
 * 工具包脚本以应用自身权限运行，因此能够读写 /sdcard 与应用私有目录。
 * 清单里的 `risky` 字段用于在启用前提示；应用不做沙箱隔离，这一点在
 * 文档与界面里都明确写出，避免给出虚假的安全感。
 */
object ToolPackage {

    /** 当前清单格式版本。清单 api 高于此值的工具包不予加载。 */
    const val API_VERSION = 1

    /** 工具包根目录。 */
    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "plugins").apply { mkdirs() }

    /** 读取全部工具包清单。解析失败的单条会被跳过，不影响其他条目。 */
    suspend fun scan(context: Context): List<ToolManifest> = withContext(Dispatchers.IO) {
        val root = dir(context)
        val list = mutableListOf<ToolManifest>()
        root.listFiles()?.sortedBy { it.name }?.forEach { sub ->
            if (!sub.isDirectory) return@forEach
            val manifest = readManifest(sub)
            if (manifest != null) list += manifest
        }
        list
    }

    /** 解析单个插件目录的清单。 */
    fun readManifest(sub: File): ToolManifest? {
        val f = File(sub, "tool.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val params = mutableListOf<ToolParam>()
            o.optJSONArray("params")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    params += ToolParam(
                        name = p.optString("name"),
                        label = p.optString("label", p.optString("name")),
                        type = p.optString("type", "text"),
                        default = p.optString("default", ""),
                        required = p.optBoolean("required", false),
                        hint = p.optString("hint", ""),
                        options = p.optJSONArray("options")?.toStringList() ?: emptyList()
                    )
                }
            }
            ToolManifest(
                id = o.optString("id", sub.name),
                name = o.optString("name", sub.name),
                version = o.optString("version", "0.0.0"),
                description = o.optString("description", ""),
                entry = o.optString("entry", "run.sh"),
                author = o.optString("author", ""),
                homepage = o.optString("homepage", ""),
                license = o.optString("license", ""),
                api = o.optInt("api", 1),
                params = params,
                timeoutSec = o.optInt("timeoutSec", 60),
                risky = o.optBoolean("risky", false)
            )
        }.getOrNull()
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until length()) out += optString(i)
        return out
    }

    /**
     * 执行工具包。
     *
     * 参数以 `BF_PARAM_<name>` 注入环境，脚本可用 `$BF_PARAM_xxx` 读取；
     * 入口脚本路径作为 `BF_ENTRY` 传入，工作目录设为插件目录。
     */
    suspend fun run(
        context: Context,
        manifest: ToolManifest,
        values: Map<String, String>
    ): ToolResult = withContext(Dispatchers.IO) {
        if (manifest.api > API_VERSION) {
            return@withContext ToolResult(
                ok = false, output = "", error = "清单格式版本不支持"
            )
        }
        if (!TerminalEnvironment.isReady(context)) {
            return@withContext ToolResult(
                ok = false, output = "", error = "运行环境未初始化"
            )
        }

        val pluginDir = File(dir(context), manifest.id)
        val entryFile = File(pluginDir, manifest.entry)
        if (!entryFile.exists()) {
            return@withContext ToolResult(
                ok = false, output = "", error = "入口脚本不存在"
            )
        }

        // 容器内路径：插件目录通过 /sdcard 映射可见
        val containerDir = toContainerPath(context, pluginDir)
        val exports = buildString {
            append("cd '").append(containerDir).append("' && ")
            // 参数经单引号包裹并转义，避免空格与特殊字符注入
            values.forEach { (k, v) ->
                if (k.matches(Regex("[A-Za-z0-9_]+"))) {
                    append("export BF_PARAM_").append(k).append('=')
                        .append(shellQuote(v)).append(" && ")
                }
            }
            append("sh '").append(manifest.entry).append("'")
        }

        val started = System.currentTimeMillis()
        val result = TerminalEnvironment.exec(context, exports, manifest.timeoutSec.toLong())
        val cost = System.currentTimeMillis() - started
        ToolResult(
            ok = result.ok,
            output = result.output.trim(),
            exitCode = result.code,
            durationMs = cost,
            error = result.error
        )
    }

    /**
     * 把应用私有路径映射成容器内路径。
     *
     * proot 只绑定了几处宿主目录，插件目录位于外部存储的应用子目录，
     * 在容器内等价路径是 /sdcard/Android/data/<pkg>/files/plugins/<id>。
     */
    fun toContainerPath(context: Context, file: File): String {
        val ext = context.getExternalFilesDir(null)
        if (ext != null && file.absolutePath.startsWith(ext.absolutePath)) {
            val rel = file.absolutePath.removePrefix(ext.absolutePath).trimStart('/')
            return "/sdcard/Android/data/${context.packageName}/files/$rel"
        }
        return file.absolutePath
    }

    /** 单引号包裹，内部单引号用 '\'' 转义。 */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /** 首次运行写入示例工具包，给用户一个可照抄的模板。 */
    suspend fun ensureSample(context: Context) = withContext(Dispatchers.IO) {
        val sampleDir = File(dir(context), "system-info")
        if (sampleDir.exists()) return@withContext
        sampleDir.mkdirs()
        File(sampleDir, "tool.json").writeText(
            """
            {
              "id": "system-info",
              "name": "环境信息",
              "version": "1.0.0",
              "description": "输出内置 Linux 环境的系统标识、内核版本与磁盘占用",
              "entry": "run.sh",
              "author": "BotForge",
              "license": "Apache-2.0",
              "api": 1,
              "timeoutSec": 20,
              "params": []
            }
            """.trimIndent()
        )
        File(sampleDir, "run.sh").writeText(
            """
            #!/bin/sh
            # 示例工具包：把结果写到标准输出即可，退出码 0 表示成功。
            echo "系统: ${'$'}(cat /etc/alpine-release 2>/dev/null || echo unknown)"
            echo "内核: ${'$'}(uname -m)"
            echo "目录: ${'$'}(pwd)"
            echo "磁盘: ${'$'}(df -h / | tail -n 1)"
            """.trimIndent()
        )
    }
}