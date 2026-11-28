package com.ajx.botforge.pkg

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次清单读取的结果。
 *
 * 用密封类而不是「PackageSpec? 加一句错误字符串」：调用方必须同时处理两种结局，
 * 编译器会强制它去看 [Failure] 分支，不会被顺手写成 `?.` 链然后安静地漏掉错误。
 */
sealed class ReadResult {
    /**
     * 读取成功。
     *
     * [warnings] 是不影响安装、但用户该知道的事（未知权限标签被跳过、
     * 入口文件不存在、声明的 runtime 当前尚未生效等）。
     */
    data class Success(
        val spec: PackageSpec,
        val warnings: List<String> = emptyList()
    ) : ReadResult()

    /** 读取失败，[reason] 是可以直接显示给用户的中文原因 */
    data class Failure(val reason: String) : ReadResult()
}

/**
 * 包清单读取器（.bfpack v2）。
 *
 * ## 它解决的问题
 *
 * 仓库里现在有两套互不相识的包：`plugin.json` 的斜杠指令包，和 `tool.json`
 * 的 App 内表单包。用户在设置页看到两个入口、两套安装流程、两种能力，
 * 而它们的技术形态其实一样——一个目录加一份 JSON 加几个脚本。
 *
 * 本读取器把三种清单统一读成 [PackageSpec]，来源记在 [Origin] 里。
 * 它不是「又多了一个解析器」，而是收口：新路径只读 v2 的 manifest.json，
 * 老的两种按兼容分支读出来、升级成同一份模型。
 *
 * ## 为什么这里可以 import org.json，而 PackageSpec.kt 不行
 *
 * 单测（app/src/test）跑在 android.jar 桩上，org.json 的方法全都会抛
 * 「not mocked」。所以模型层与纯函数保持零依赖，可被单测覆盖；
 * 而本文件承担全部 JSON 触碰，代价是它只能在设备或 Robolectric 下验证。
 * 这是有意的取舍：把「不可测的 IO」压缩到一层。
 *
 * 因此，凡是「文案要不要出现」这种值得被测试钉住的判断，都尽量下沉成
 * PackageSpec.kt 里的纯函数（例如 [runtimeWarnings]），本文件只负责把它们
 * 的结果追加进 warnings。这样规则唯一的家在没有 JSON 依赖的那一侧。
 *
 * ## 关于 null
 *
 * 历史教训：`runCatching { nullableParse() }.getOrElse { ... }` 接不住 null，
 * 因为 null 不是异常，它会安静地流到下一行，然后在访问成员时炸成编译错误
 * 或者运行期可空接收者问题。因此本文件的解析函数一律不返回 null 表示失败，
 * 而是抛 [ManifestException]，由 [read] 统一转成 [ReadResult.Failure]。
 * 唯一的 null 语义留给了「单个可选字段缺失」，用显式的 if 判断处理。
 */
object PackageManifestReader {

    /** v2 清单文件名 */
    const val MANIFEST_JSON = "manifest.json"

    /** v1 指令包清单文件名 */
    const val PLUGIN_JSON = "plugin.json"

    /** v1 工具包清单文件名 */
    const val TOOL_JSON = "tool.json"

    /** v1 tool.json 迁移后那条面板动作的 id */
    private const val RUN_ACTION_ID = "run"

    private const val DEFAULT_ENTRY_SH = "main.sh"
    private const val DEFAULT_ENTRY_RUN = "run.sh"

    /**
     * 解析过程中发现清单不成立时抛出。
     *
     * 用私有异常而不是 IllegalStateException：它只在「这是坏包」时出现，
     * 不应该和真正的程序 bug 混在同一个 catch 里被吞掉。
     */
    private class ManifestException(message: String) : Exception(message)

    /**
     * 读一个包目录。
     *
     * 尝试顺序固定：manifest.json → plugin.json → tool.json。
     * 三者同时存在时以 manifest.json 为准（v2 优先），其余的存在只会作为
     * 警告提示——同一个目录里有两份清单本身就是需要人看一眼的状态。
     */
    fun read(dir: File): ReadResult {
        if (!dir.exists()) return ReadResult.Failure("包目录不存在：${dir.name}")
        if (!dir.isDirectory) return ReadResult.Failure("${dir.name} 不是目录，无法作为包读取")

        val manifestFile = File(dir, MANIFEST_JSON)
        val pluginFile = File(dir, PLUGIN_JSON)
        val toolFile = File(dir, TOOL_JSON)

        val primary = when {
            manifestFile.isFile -> manifestFile
            pluginFile.isFile -> pluginFile
            toolFile.isFile -> toolFile
            else -> return ReadResult.Failure(
                "目录里没有 $MANIFEST_JSON，也没有 $PLUGIN_JSON 或 $TOOL_JSON，" +
                    "无法判断这是什么格式的包。"
            )
        }

        val warnings = ArrayList<String>(4)

        val shadowed = listOf(manifestFile, pluginFile, toolFile)
            .filter { it.isFile && it.name != primary.name }
            .map { it.name }
        if (shadowed.isNotEmpty()) {
            warnings.add(
                "目录里同时存在 ${primary.name} 与 ${shadowed.joinToString("、")}，" +
                    "已按 ${primary.name} 读取，另一份请删掉以免混淆。"
            )
        }

        val json = try {
            JSONObject(primary.readText())
        } catch (e: Exception) {
            return ReadResult.Failure("${primary.name} 不是合法 JSON：${e.message ?: "未知原因"}")
        }

        val parsed = try {
            when (primary.name) {
                MANIFEST_JSON -> parseV2(json, dir)
                PLUGIN_JSON -> parseV1Plugin(json, dir)
                else -> parseV1Tool(json, dir)
            }
        } catch (e: ManifestException) {
            return ReadResult.Failure(e.message ?: "清单解析失败")
        } catch (e: Exception) {
            return ReadResult.Failure("解析 ${primary.name} 时出错：${e.message ?: "未知原因"}")
        }

        val idProblem = packageIdProblem(parsed.spec.id)
        if (idProblem != null) {
            return ReadResult.Failure("包 id 不合法（${parsed.spec.id}）：$idProblem")
        }

        when (checkApi(parsed.spec.api, PackageSpec.CURRENT_API)) {
            ApiCheck.TOO_NEW -> return ReadResult.Failure(
                "这个包要求协议 v${parsed.spec.api}，当前只支持到 v${PackageSpec.CURRENT_API}。" +
                    "请更新 BotForge 后再装。"
            )
            ApiCheck.LEGACY -> warnings.add(
                "该包使用旧协议 v${parsed.spec.api}，已按兼容模式读取；" +
                    "建议作者升级到 v${PackageSpec.CURRENT_API}。"
            )
            ApiCheck.OK -> Unit
        }

        if (parsed.spec.commands.isEmpty() &&
            parsed.spec.aiTools.isEmpty() &&
            parsed.spec.uiActions.isEmpty()
        ) {
            return ReadResult.Failure(
                "这个包没有任何能力：commands、ai_tools、ui_actions 三类都是空的。"
            )
        }

        // 权限按风险降序：设置页直接顺序渲染即可，把最该看的放在最上面。
        // sortedByDescending 在 Kotlin 里是稳定排序，同一风险档内保持作者的声明顺序。
        val spec = parsed.spec.copy(
            permissions = parsed.spec.permissions.sortedByDescending { it.tag.risk }
        )

        return ReadResult.Success(spec, warnings + parsed.warnings)
    }

    private class Parsed(val spec: PackageSpec, val warnings: List<String>)

    // ------------------------------------------------------------ v2

    /**
     * v2：manifest.json。
     *
     * 与 v1 的关键差别：**三类能力都允许为空**。
     * 老 PluginManifest.parse 里有 `if (cmds.isEmpty()) return null`，
     * 那是 v1「插件就是一组斜杠指令」时代的判据。若把它照搬到这里，
     * 一个只声明 ai_tools 的包会被判非法，而纯 AI 工具包恰恰是 v2 最想支持的形态。
     * 因此这里对 commands 只做「有就解析」，不做数量校验；
     * 「三类全空」这种真正无意义的包由 [read] 统一拒绝。
     */
    private fun parseV2(json: JSONObject, dir: File): Parsed {
        val id = json.optString("id").trim()
        if (id.isEmpty()) throw ManifestException("$MANIFEST_JSON 缺少 id 字段")

        val warnings = ArrayList<String>(4)

        val commands = readCommands(json.optJSONArray("commands"))
        val aiTools = readAiTools(json.optJSONArray("ai_tools"))
        val uiActions = readUiActions(json.optJSONArray("ui_actions"))
        val permissions = readPermissions(json.optJSONArray("permissions"), warnings)

        val runtime = Runtime.parse(json.optString("runtime", ""))

        // runtime 目前没有消费者：执行层一律走 android_sh，不会按这里分流。
        // 这个事实必须说出来，否则作者写了 runtime=proot 会以为已经切过去了，
        // 然后在 android_sh 里对着 command not found 一脸茫然。
        // 文案与判断都来自 [runtimeWarnings]，那是个可被 JVM 单测覆盖的纯函数。
        warnings.addAll(runtimeWarnings(runtime))

        val spec = PackageSpec(
            id = id,
            name = json.optString("name", id).ifBlank { id },
            version = json.optString("version", "0.0.0").ifBlank { "0.0.0" },
            api = json.optInt("api", PackageSpec.CURRENT_API),
            author = json.optString("author", ""),
            description = json.optString("description", ""),
            homepage = json.optString("homepage", ""),
            license = json.optString("license", ""),
            runtime = runtime,
            origin = Origin.V2,
            commands = commands,
            aiTools = aiTools,
            uiActions = uiActions,
            permissions = permissions,
            dir = dir
        )
        return Parsed(spec, warnings)
    }

    // ------------------------------------------------------------ v1

    /**
     * v1 指令包：plugin.json。
     *
     * 照搬 commands，runtime 固定 [Runtime.ANDROID_SH]——当年的插件就是用
     * 系统 sh 跑 main.sh，不能凭空给它们升到 proot，那会要求用户先装 rootfs。
     *
     * 这里保留「指令为空即非法」：v1 的 registry 是按指令建索引的，
     * 零指令的 v1 包在旧路径上本来就不存在，允许它通过只会制造一个
     * 「装上了但列表里什么都没有」的幽灵条目。新形态请改用 manifest.json。
     */
    private fun parseV1Plugin(json: JSONObject, dir: File): Parsed {
        val id = json.optString("id").trim()
        if (id.isEmpty()) throw ManifestException("$PLUGIN_JSON 缺少 id 字段")

        val arr = json.optJSONArray("commands")
            ?: throw ManifestException("$PLUGIN_JSON 缺少 commands 数组")

        val commands = readCommands(arr)
        if (commands.isEmpty()) {
            throw ManifestException(
                "$PLUGIN_JSON 里没有一条有效指令。纯 AI 工具包请改用 $MANIFEST_JSON 描述。"
            )
        }

        val warnings = mutableListOf<String>()
        warnings.add("这是 v1 指令包，已按兼容模式导入；建议迁移到 $MANIFEST_JSON。")

        val spec = PackageSpec(
            id = id,
            name = json.optString("name", id).ifBlank { id },
            version = json.optString("version", "0.0.0").ifBlank { "0.0.0" },
            api = json.optInt("api", 1),
            author = json.optString("author", ""),
            description = json.optString("description", ""),
            runtime = Runtime.ANDROID_SH,
            origin = Origin.V1_PLUGIN,
            commands = commands,
            dir = dir
        )
        return Parsed(spec, warnings)
    }

    /**
     * v1 工具包：tool.json。
     *
     * 迁移规则：整个包落成**一条面板动作**，runtime 固定 [Runtime.PROOT]。
     * 为什么是 PROOT：tool.json 的入口默认叫 run.sh，当年就是在 Alpine 里跑的，
     * 它可能调用 apk 装出来的二进制；谎称能用系统 sh 跑，装完就是一片
     * command not found。
     *
     * 字段对齐：
     *  - `timeoutSec` 原样带进 [PackageUiAction.timeoutSec]，不再丢弃。
     *    v1 里这个值是真在用的（旧执行层直接拿它当进程超时），丢掉等于把作者
     *    调好的数字悄悄改回 60 秒，属于「清单里的值与运行行为对不上」这类最难查的
     *    问题，所以选择保留而不是只警告。
     *  - `risky` 在 [PackageSpec] 里确实没有对应字段，也不硬塞进不相干的字段
     *    （例如编成一条假权限），而是转成警告让用户知情。
     *
     * 另外，迁移出来的 PROOT 同样没有消费者（见 [Runtime] 的现状说明），
     * 这种情况也要明说，不能让「已按 proot 迁移」这句话被读成「已按 proot 执行」。
     */
    private fun parseV1Tool(json: JSONObject, dir: File): Parsed {
        val id = json.optString("id").trim()
        if (id.isEmpty()) throw ManifestException("$TOOL_JSON 缺少 id 字段")

        val displayName = json.optString("name", id).ifBlank { id }
        val entry = json.optString("entry", DEFAULT_ENTRY_RUN).ifBlank { DEFAULT_ENTRY_RUN }
        val params = readParams(json.optJSONArray("params"))
        // 与 v1 解析侧同一默认值（tools/ToolPackage.kt 读的也是 60），
        // 缺失时不给 0 之类的哨兵值：0 秒会在执行层被当成「立刻超时」。
        val timeoutSec = json.optInt("timeoutSec", PackageUiAction.DEFAULT_TIMEOUT_SEC)

        val action = PackageUiAction(
            id = RUN_ACTION_ID,
            label = displayName,
            description = json.optString("description", ""),
            entry = entry,
            params = params,
            timeoutSec = timeoutSec
        )

        val warnings = mutableListOf<String>()
        warnings.add("这是 v1 工具包，已按兼容模式导入为一条面板动作；建议迁移到 $MANIFEST_JSON。")
        if (json.optBoolean("risky", false)) {
            warnings.add("该包在原 $TOOL_JSON 里声明了 risky，启用前请确认它要做什么。")
        }
        // 这里的 proot 是迁移规则强行给的，不是作者写的，所以不能套用
        // 「该包声明 runtime=proot」那句文案，否则会误导用户去找一行并不存在的声明。
        warnings.add(
            "v1 工具包已按 proot 迁移，但当前版本尚未实现按包切换运行环境，" +
                "实际仍以 android_sh 方式运行。"
        )

        val spec = PackageSpec(
            id = id,
            name = displayName,
            version = json.optString("version", "0.0.0").ifBlank { "0.0.0" },
            api = json.optInt("api", 1),
            author = json.optString("author", ""),
            description = json.optString("description", ""),
            homepage = json.optString("homepage", ""),
            license = json.optString("license", ""),
            runtime = Runtime.PROOT,
            origin = Origin.V1_TOOL,
            uiActions = listOf(action),
            dir = dir
        )
        return Parsed(spec, warnings)
    }

    // ------------------------------------------------------------ 字段级

    /**
     * 解析指令数组。单条缺 command 名的直接跳过——不是拒绝整包。
     * 一条写坏的指令不该让同一个包里其余五条都用不了。
     */
    private fun readCommands(arr: JSONArray?): List<PackageCommand> {
        if (arr == null) return emptyList()
        val out = ArrayList<PackageCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) continue
            val raw = o.optString("command").trim().removePrefix("/").trim()
            if (raw.isEmpty()) continue
            val name = raw.lowercase()
            out.add(
                PackageCommand(
                    command = name,
                    description = o.optString("description", ""),
                    usage = o.optString("usage", "/$name"),
                    entry = o.optString("entry", DEFAULT_ENTRY_SH).ifBlank { DEFAULT_ENTRY_SH },
                    timeout = o.optInt("timeout", PackageCommand.DEFAULT_TIMEOUT_SEC),
                    scope = readScope(o),
                    confirm = o.optBoolean("confirm", false)
                )
            )
        }
        return out
    }

    /**
     * scope 允许数组或单个字符串，与旧 PluginManifest.parseScope 完全同义：
     * both → c2c + group，group → group，c2c → c2c，其余（含缺失）→ 只私聊。
     *
     * 规则复制了一份而不是去调旧解析器：旧的那个是 private，
     * 而且 v1 解析器迟早会被删掉。复制时保持语义一字不差，是为了让
     * 「同一个 plugin.json 走新旧两条路径得到同样的 scope」可被对照验证。
     */
    private fun readScope(o: JSONObject): List<String> {
        val arr = o.optJSONArray("scope")
        if (arr != null) {
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim().lowercase()
                if (s.isNotEmpty()) out.add(s)
            }
            if (out.isNotEmpty()) return out
        }
        return when (o.optString("scope", "").trim().lowercase()) {
            "both" -> listOf("c2c", "group")
            "group" -> listOf("group")
            "c2c" -> listOf("c2c")
            else -> PackageCommand.DEFAULT_SCOPE
        }
    }

    /**
     * AI 工具数组。
     *
     * permission 的处理全部委托给 [PackageAiTool.normalizePermission]：
     * 那是本文件里最需要被测试盯住的一条规则（未知值回落 ASK），而它待在
     * 无 JSON 依赖的模型层里，才能被 JVM 单测直接断言。这里只负责把原始
     * 字符串原样递过去，不自己再 trim 一次——两处归一化迟早会写出差异。
     */
    private fun readAiTools(arr: JSONArray?): List<PackageAiTool> {
        if (arr == null) return emptyList()
        val out = ArrayList<PackageAiTool>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val params = o.opt("parameters")
            out.add(
                PackageAiTool(
                    name = name,
                    description = o.optString("description", ""),
                    parametersJson = if (params == null) "{}" else params.toString(),
                    permission = PackageAiTool.normalizePermission(o.optString("permission")),
                    entry = o.optString("entry", DEFAULT_ENTRY_RUN).ifBlank { DEFAULT_ENTRY_RUN }
                )
            )
        }
        return out
    }

    private fun readUiActions(arr: JSONArray?): List<PackageUiAction> {
        if (arr == null) return emptyList()
        val out = ArrayList<PackageUiAction>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val actionId = o.optString("id").trim()
            if (actionId.isEmpty()) continue
            out.add(
                PackageUiAction(
                    id = actionId,
                    label = o.optString("label", actionId).ifBlank { actionId },
                    description = o.optString("description", ""),
                    entry = o.optString("entry", DEFAULT_ENTRY_RUN).ifBlank { DEFAULT_ENTRY_RUN },
                    params = readParams(o.optJSONArray("params")),
                    timeoutSec = o.optInt(
                        "timeoutSec",
                        PackageUiAction.DEFAULT_TIMEOUT_SEC
                    )
                )
            )
        }
        return out
    }

    private fun readParams(arr: JSONArray?): List<PackageParam> {
        if (arr == null) return emptyList()
        val out = ArrayList<PackageParam>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val opts = ArrayList<String>()
            val optArr = o.optJSONArray("options")
            if (optArr != null) {
                for (k in 0 until optArr.length()) {
                    val v = optArr.optString(k)
                    if (v.isNotEmpty()) opts.add(v)
                }
            }
            out.add(
                PackageParam(
                    name = name,
                    label = o.optString("label", name).ifBlank { name },
                    type = o.optString("type", PackageParam.TYPE_TEXT).ifBlank { PackageParam.TYPE_TEXT },
                    default = o.optString("default", ""),
                    required = o.optBoolean("required", false),
                    hint = o.optString("hint", ""),
                    options = opts
                )
            )
        }
        return out
    }

    /**
     * 权限声明。
     *
     * 写法宽容：既接受 `"permissions": ["network", "shizuku"]` 这样的字符串数组，
     * 也接受 `{"tag": "...", "reason": "..."}` 对象数组。
     * 两种都支持是因为写手写清单的人天然会想写短的那种。
     *
     * 未知标签跳过并记一条警告，不抛异常——见 [PermissionTag.parse] 的理由。
     */
    private fun readPermissions(arr: JSONArray?, warnings: MutableList<String>): List<PermissionDecl> {
        if (arr == null) return emptyList()
        val out = ArrayList<PermissionDecl>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            val rawTag = if (obj != null) obj.optString("tag") else arr.optString(i)
            val tag = PermissionTag.parse(rawTag)
            if (tag == null) {
                val shown = rawTag.trim().ifEmpty { "#$i" }
                warnings.add("忽略了无法识别的权限标签：$shown")
                continue
            }
            out.add(PermissionDecl(tag = tag, reason = obj?.optString("reason", "") ?: ""))
        }
        return out
    }
}
