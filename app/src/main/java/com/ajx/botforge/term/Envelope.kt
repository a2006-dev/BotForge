package com.ajx.botforge.term

/**
 * 隐藏执行器的标记信封协议。
 *
 * 单独抽出来，一是为了让 [HiddenExec] 只管进程与流，二是为了能写纯 JVM
 * 单元测试——协议里最容易错的就是「拼出来的 shell 片段不合法」和
 * 「解析时没把信封自身的回显剥干净」，这两类问题以前只能等真机跑一遍
 * 才发现，现在由 [EnvelopeTest] 在 CI 的 testDebugUnitTest 阶段拦住。
 *
 * 协议正文见 [build]；解析见 [parse]。
 */
object Envelope {

    const val READY_MARKER = "__BF_READY__"

    const val BEGIN_PREFIX = "__BF_BEGIN__:"
    const val END_PREFIX = "__BF_END__:"
    const val PID_PREFIX = "__BF_PID__:"

    /** 输出截断阈值，防止模型上下文被海量输出撑爆。 */
    const val MAX_OUTPUT_CHARS = 60_000

    /** 一条命令的信封。所有片段都在**容器内 bash** 里执行。 */
    fun build(command: String, token: String): String {
        val normalized = command.replace("\r\n", "\n").replace('\r', '\n')
        val heredoc = heredocTag(token)
        return buildString {
            append("printf '%s\\n' '").append(BEGIN_PREFIX).append(token).append("'\n")
            append("__bf_script=\"\${TMPDIR:-/tmp}/bf_").append(token).append(".sh\"\n")
            // heredoc 用引号包住结束符，避免 $ 与反引号被提前展开。
            append("cat >\"\$__bf_script\" <<'").append(heredoc).append("'\n")
            append(normalized)
            if (!normalized.endsWith("\n")) append('\n')
            append(heredoc).append('\n')
            // setsid + </dev/null：命令彻底脱离外层 bash 的 stdin。
            // 否则 cat / python3 这类会读 stdin 的程序会抢走管道里后续的
            // 信封内容，表现为「命令跑完了但拿不到结束标记」。
            append("setsid /bin/bash --noprofile --norc \"\$__bf_script\" </dev/null &\n")
            append("__bf_pid=\$!\n")
            append("printf '%s:%s\\n' '").append(PID_PREFIX).append(token)
                .append("' \"\$__bf_pid\"\n")
            append("wait \"\$__bf_pid\"\n")
            append("__bf_rc=\$?\n")
            append("rm -f \"\$__bf_script\"\n")
            append("printf '%s:%s\\n' '").append(END_PREFIX).append(token)
                .append("' \"\$__bf_rc\"\n")
        }
    }

    /** heredoc 标签，只含 ASCII 安全字符。 */
    fun heredocTag(token: String): String = "__BF_CMD_${token}__"

    /** 解析结果：退出码 + 清理后的输出。 */
    data class Parsed(
        /** 是否找到结束标记。false 表示命令还没跑完（超时/中断）。 */
        val finished: Boolean,
        /** 退出码；未 finished 时为 null。 */
        val exitCode: Int?,
        /** 剥掉信封回显后的输出，已做空行裁剪与长度截断。 */
        val output: String
    )

    /**
     * 从原始输出里剥出结果。
     *
     * 需要剥掉的东西：
     * - begin 行（`__BF_BEGIN__:<token>`）
     * - pid 行（`__BF_PID__:<token>:<pid>`）
     * - end 行（`__BF_END__:<token>:<code>`）
     * - 就绪标记（只可能在第一个信封里出现）
     *
     * 注意 token 必须精确匹配，否则 A 命令的输出可能把 B 命令的结束标记
     * 当成自己的——这也是每条命令都生成新 token 的原因。
     */
    fun parse(raw: String, token: String): Parsed {
        val endRegex = Regex(Regex.escape(END_PREFIX + token) + ":(\\d+)")
        val endMatch = endRegex.find(raw)

        var out = raw
        out = out.replace(BEGIN_PREFIX + token, "")
        out = out.replace(Regex(Regex.escape(PID_PREFIX + token) + ":\\d+"), "")
        out = endRegex.replace(out, "")
        out = out.replace(READY_MARKER, "")

        val cleaned = out
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .let {
                if (it.length > MAX_OUTPUT_CHARS) {
                    it.take(MAX_OUTPUT_CHARS) + "\n…（输出过长已截断）"
                } else it
            }

        return Parsed(
            finished = endMatch != null,
            exitCode = endMatch?.groupValues?.get(1)?.toIntOrNull(),
            output = cleaned
        )
    }

    /** 把工作目录表达成命令前缀。 */
    fun withWorkdir(command: String, workdir: String?): String {
        if (workdir.isNullOrBlank()) return command.trim()
        return "cd ${shellQuote(workdir)} || exit 1\n${command.trim()}"
    }

    /** 单引号转义，容器内路径用。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}