package com.ajx.botforge.ai.tool

import com.ajx.botforge.App
import com.ajx.botforge.data.Logger
import com.ajx.botforge.service.ShizukuKeepAlive
import org.json.JSONObject

/**
 * 在 Android 系统 shell（ADB 调试身份 shell:2000）里执行命令。
 *
 * 与 [BuiltinTools.RunCommandTool] 的分工：
 *  - run_command 走 proot 容器，能 apt / python3，但**没有 Android 系统权限**，
 *    调不动 am / pm / cmd 这些，因为这些命令依赖 binder 身份。
 *  - 本工具走 Shizuku，拿到的是 shell:2000，能 am start 打开 App、pm 查包、
 *    settings 改设置、dumpsys 看系统状态。代价是必须有 Shizuku 授权，
 *    且**没有 Linux 用户空间**（没有 apt、没有容器里的 python3）。
 *
 * 两个工具刻意分开而不是合并：模型需要明确知道「我现在是在系统里干活，
 * 还是在 Linux 里干活」，否则会拿着 apt 的命令去系统 shell 里跑，
 * 然后误判成环境坏了。
 */
object SystemCommandTool : Tool {
    override val name = "system_command"

    override val description =
        "在 Android 系统 shell（ADB 调试身份）里执行命令。需要用户已安装并授权 Shizuku。" +
            "能做的事：am start 打开 App、pm list packages 查已装应用、" +
            "settings get/put 读写系统设置、dumpsys 查看系统服务状态、" +
            "input tap/swipe 模拟操作、screencap 截图。" +
            "这里没有 Linux 用户空间：没有 apt、没有 python3、没有容器里的家目录，" +
            "要装软件或用 Python 请用 run_command。" +
            "Shizuku 未运行或未授权时会直接返回错误说明，不要反复重试。"

    override val parameters: JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "cmd",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "要执行的 shell 命令，可多行，按顺序执行")
                )
                .put(
                    "timeout",
                    JSONObject()
                        .put("type", "integer")
                        .put("description", "可选，超时秒数，默认 30，最大 180")
                )
        )
        .put("required", org.json.JSONArray().put("cmd"))

    override fun run(args: JSONObject): String {
        val cmd = args.optString("cmd").trim()
        if (cmd.isEmpty()) return "错误：cmd 不能为空"

        val ctx = App.instance

        // 先给出精确的状态说明，而不是让模型去猜「为什么失败」。
        // 分三种失败分别讲清楚，模型才能给出对用户有意义的提示，
        // 而不是笼统地说一句「执行失败」然后开始编。
        val st = ShizukuKeepAlive.status(ctx)
        if (!st.installed) {
            return "Shizuku 未安装，无法使用系统命令。" +
                "需要打开应用 / 改系统设置这类操作时，请告诉用户先安装 Shizuku。"
        }
        if (!st.running) {
            return "Shizuku 已安装但服务没有运行。" +
                "请告诉用户打开 Shizuku App 重新激活（多数机型需要连一次电脑或开无线调试），" +
                "激活后本工具即可使用。这不是命令写错。"
        }
        if (!st.granted) {
            return "Shizuku 正在运行，但 BotForge 没有获得授权。" +
                "请告诉用户在 BotForge 设置页点「请求 Shizuku 授权」并允许。"
        }

        Logger.i("SysCmd", "执行系统命令: ${cmd.take(120)}")
        val out = try {
            ShizukuKeepAlive.exec(cmd)
        } catch (e: Throwable) {
            Logger.w("SysCmd", "系统命令抛异常: ${e.message}")
            return "执行出错：${e.message ?: e.javaClass.simpleName}"
        }

        val text = out.trim()
        if (text.isEmpty()) return "（命令执行完成，没有输出；退出码可能为 0）"
        return if (text.length > 2000) text.take(2000) + "\n…（已截断）" else text
    }
}