package com.ajx.botforge.data

import com.ajx.botforge.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 结构化审计中心。
 *
 * ## 为什么不能继续用 Logger
 *
 * `Logger` 是给人看的：一行文本，600 条后覆盖，按天切文件。
 * 它回答不了「上周三谁在哪个群让我删了什么文件」这种问题——
 * 因为字段是混在字符串里的，检索只能靠 grep。
 *
 * 审计要的是**可机读、可聚合、不可篡改**：
 *  - 每条一个 JSON 对象，字段固定，能直接 group by packageId / decision；
 *  - append-only，写出去就不再改，避免事后被「整理」掉；
 *  - 与普通日志分开存放，清日志不会顺手清掉审计。
 *
 * ## 为什么是 JSONL 而不是 SQLite
 *
 * 任务书给的是「SQLite 或 JSONL」。选 JSONL 的理由：
 *  - 这条链路在服务进程里，写失败绝不能影响主流程，JSONL 追加失败
 *    只是少一条记录，SQLite 事务失败可能拖住锁；
 *  - 用户要取证时可以直接把文件发给别人，不需要 sqlite3；
 *  - 按天轮转天然就是文件切分，和现有 DiskLog 的心智一致。
 *
 * 代价是查询要自己扫，但对「一天几万条」这个量级完全够用。
 */
object AuditStore {

    /** 一次决策的结果。与 [AuditRecord.decision] 取值一致。 */
    enum class Decision {
        /** 直接放行 */
        ALLOW,

        /** 需要确认且用户同意了 */
        ASK_OK,

        /** 需要确认但用户拒绝，或超时未确认 */
        ASK_DENY,

        /** 权限档位为 DENY，或白名单/场景校验没过，根本没执行 */
        BLOCKED
    }

    /**
     * 一条审计记录。
     *
     * 字段名直接就是 JSON key，故意用短名：这类文件会长期累积，
     * 每条省 10 字节，一年下来是几十 MB 的差别。
     * 但**含义必须写清楚**，所以每个字段都有注释。
     */
    data class AuditRecord(
        /** 发生时间，毫秒时间戳 */
        val timestamp: Long,
        /** 哪个机器人（多机器人时用于区分） */
        val botId: String,
        /** 场景：c2c / group */
        val scene: String,
        /** 触发者的 openid。可空：系统自发动作没有发起人 */
        val userOpenId: String?,
        /** 来自哪个扩展包；内置工具为 null */
        val packageId: String?,
        /** 工具名或指令名 */
        val toolOrCmd: String,
        /** 参数摘要，已脱敏。不存原文，避免把密钥写进审计 */
        val argsDigest: String,
        /** 最终决策 */
        val decision: Decision,
        /** 进程退出码；未执行或非进程类工具为 null */
        val exitCode: Int?,
        /** 从开始到结束的毫秒数 */
        val durationMs: Long,
        /** 失败原因摘要。成功为 null */
        val error: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ts", timestamp)
            .put("bot", botId)
            .put("scene", scene)
            .put("user", userOpenId ?: JSONObject.NULL)
            .put("pkg", packageId ?: JSONObject.NULL)
            .put("tool", toolOrCmd)
            .put("args", argsDigest)
            .put("decision", decision.name)
            .put("exit", exitCode ?: JSONObject.NULL)
            .put("ms", durationMs)
            .put("err", error ?: JSONObject.NULL)
    }

    private const val KEEP_DAYS = 30
    private const val MAX_BYTES = 4 * 1024 * 1024L

    /** 参数摘要最大长度。够定位问题，又不至于把整段脚本写进来。 */
    private const val ARGS_MAX = 240

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 最近一次写入失败的原因，供设置页展示；成功则为 null */
    @Volatile
    var lastError: String? = null
        private set

    private val dir: File?
        get() = runCatching {
            File(App.instance.filesDir, "audit").apply { mkdirs() }
        }.getOrNull()

    /**
     * 写一条审计。
     *
     * **绝不抛异常**：审计是旁路，不能因为它把主流程搞挂。
     * 失败只记在 [lastError] 里，由设置页提示「审计写入异常」。
     */
    fun record(r: AuditRecord) {
        val d = dir ?: return
        runCatching {
            val name = dayFmt.format(Date(r.timestamp))
            val f = File(d, "$name.jsonl")
            rotateIfNeeded(d, name, f)
            // 单行 JSON + 换行。JSONL 的约定：一条记录一行，不能有裸换行。
            val line = r.toJson().toString().replace("\n", "\\n")
            f.appendText(line + "\n")
            lastError = null
        }.onFailure {
            lastError = it::class.java.simpleName + ": " + (it.message ?: "")
        }
    }

    /**
     * 便捷入口：包装一次执行并记录结果，异常也会落一条 BLOCKED/错误记录。
     *
     * 用 `inline` 是为了让调用侧不必关心 try/catch，
     * 同时保证「无论成功失败都有审计」——这是审计的意义所在。
     */
    inline fun <T> trace(
        botId: String,
        scene: String,
        userOpenId: String?,
        packageId: String?,
        toolOrCmd: String,
        argsDigest: String,
        decision: Decision,
        block: () -> T
    ): T {
        val started = System.currentTimeMillis()
        var exit: Int? = null
        var err: String? = null
        try {
            val out = block()
            if (out is Int) exit = out
            return out
        } catch (t: Throwable) {
            err = t::class.java.simpleName + ": " + (t.message ?: "")
            throw t
        } finally {
            record(
                AuditRecord(
                    timestamp = started,
                    botId = botId,
                    scene = scene,
                    userOpenId = userOpenId,
                    packageId = packageId,
                    toolOrCmd = toolOrCmd,
                    argsDigest = argsDigest,
                    decision = decision,
                    exitCode = exit,
                    durationMs = System.currentTimeMillis() - started,
                    error = err
                )
            )
        }
    }

    /**
     * 参数脱敏 + 截断。
     *
     * 要挡住的三类东西：显式密钥字段、内联 token、超长脚本。
     * 这里不做「猜哪些像密钥」，只按字段名和已知前缀处理，
     * 因为误杀正常参数会让审计失去排查价值。
     */
    fun digestArgs(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // 先落到非空局部变量：raw 是 String?，直接 `var s = raw` 会让 s
        // 保持可空类型，后续 s.replace 全部报「可空接收者不允许调用」。
        // 智能转换只对 val 或不再被改写的变量成立，所以这里显式取非空值。
        val input: String = raw
        var s: String = input
        // 1) key=value / "key": "value" 形式里的敏感字段
        val sensitive = listOf(
            "api[_-]?key", "apikey", "token", "secret", "password", "passwd",
            "authorization", "cookie", "session[_-]?key"
        )
        // 不用 forEach { s = ... }：s 是被闭包捕获且会被改写的局部变量，
        // Kotlin 因此无法对 s 做智能转换（编译报 Smart cast is impossible）。
        // 普通 for 循环里改的是同一个局部变量，类型仍然确定。
        for (k in sensitive) {
            s = s.replace(
                Regex("(?i)(\"?$k\"?\\s*[:=]\\s*\"?)([^\"\\s,}]{4,})"),
                // 用 ${'$'}1 而不是 $1：Kotlin 字符串里 $1 会被当作模板起始
                "${'$'}1***"
            )
        }
        // 2) 常见 token 前缀直接抹掉
        s = s.replace(Regex("ghp_[A-Za-z0-9]{20,}"), "ghp_***")
        s = s.replace(Regex("sk-[A-Za-z0-9]{20,}"), "sk-***")
        s = s.replace(Regex("Bearer\\s+[A-Za-z0-9._-]{16,}"), "Bearer ***")
        // 3) 压掉换行并截断
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length <= ARGS_MAX) s else s.take(ARGS_MAX) + "…"
    }

    // ---------------- 读取 ----------------

    /** 当天文件 */
    fun todayFile(): File? {
        val d = dir ?: return null
        return File(d, dayFmt.format(Date()) + ".jsonl")
    }

    /** 所有审计文件，新的在前 */
    fun list(): List<File> =
        dir?.listFiles()
            ?.filter { it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /**
     * 倒序读取最近 [limit] 条。
     *
     * 从最新文件往旧文件读，凑够就停——审计文件可能几十 MB，
     * 全量 parse 会卡住 UI。
     */
    fun recent(limit: Int = 200): List<JSONObject> {
        val out = ArrayList<JSONObject>(limit)
        for (f in list()) {
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            for (i in lines.indices.reversed()) {
                val ln = lines[i].trim()
                if (ln.isEmpty()) continue
                runCatching { out.add(JSONObject(ln)) }
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** 按天导出成一段可复制的文本，用于反馈问题 */
    fun exportText(days: Int = 7, maxRecordsPerDay: Int = 500): String {
        val sb = StringBuilder()
        list().take(days).forEach { f ->
            sb.append("══════ ${f.name} ══════\n")
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            lines.takeLast(maxRecordsPerDay).forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 清理超过 [KEEP_DAYS] 的文件 */
    fun prune() {
        runCatching {
            list().drop(KEEP_DAYS).forEach { it.delete() }
        }
    }

    fun clearAll() {
        runCatching { list().forEach { it.delete() } }
    }

    /** 单文件超限时切成 `日期_1.jsonl`，只保留一份旧档 */
    private fun rotateIfNeeded(d: File, name: String, f: File) {
        if (!f.exists() || f.length() <= MAX_BYTES) return
        val idx = File(d, "${name}_1.jsonl")
        if (idx.exists()) idx.delete()
        f.renameTo(idx)
    }
}