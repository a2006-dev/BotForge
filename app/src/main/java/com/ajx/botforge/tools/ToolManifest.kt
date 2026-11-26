package com.ajx.botforge.tools

/**
 * 工具包清单模型。
 *
 * ## 为什么用 JSON 而不是代码
 *
 * 工具包需要能被第三方编写、分发、审阅。若用 Kotlin 代码，安装即执行，
 * 无法在安装前判断它要做什么；用声明式 JSON 则可以先读清单再决定是否启用，
 * 这也是 Koishi 插件市场把「不安全」单独标注的前提。
 *
 * ## 目录结构
 *
 * ```
 * plugins/<id>/
 *   tool.json      清单（本文件描述的结构）
 *   run.sh         入口脚本，在 Alpine 环境内执行
 * ```
 *
 * 清单字段见 [ToolManifest]；`run.sh` 通过环境变量接收参数，
 * 用标准输出返回结果，退出码表示成败。
 */
data class ToolManifest(
    /** 唯一标识，小写字母数字与短横线，同时作为目录名 */
    val id: String,
    /** 展示名称，允许中文 */
    val name: String,
    /** 语义化版本 */
    val version: String,
    /** 一句话说明用途 */
    val description: String,
    /** 入口脚本文件名，相对插件目录 */
    val entry: String = "run.sh",
    /** 作者，可为空 */
    val author: String = "",
    /** 项目主页或源码地址，可为空 */
    val homepage: String = "",
    /** 许可证标识，如 MIT / Apache-2.0 */
    val license: String = "",
    /** 兼容的清单格式版本，与 [ToolPackage.API_VERSION] 比对 */
    val api: Int = 1,
    /** 参数声明，供界面自动生成输入框 */
    val params: List<ToolParam> = emptyList(),
    /** 建议的超时秒数 */
    val timeoutSec: Int = 60,
    /** 是否需要在启用前二次确认 */
    val risky: Boolean = false
)

/** 工具参数声明。 */
data class ToolParam(
    /** 变量名，会以 BF_PARAM_<name> 形式注入环境 */
    val name: String,
    /** 展示标签 */
    val label: String,
    /** text / number / boolean / select */
    val type: String = "text",
    /** 默认值 */
    val default: String = "",
    /** 是否必填 */
    val required: Boolean = false,
    /** 说明 */
    val hint: String = "",
    /** type=select 时的候选值 */
    val options: List<String> = emptyList()
)

/** 一次工具执行的结果。 */
data class ToolResult(
    val ok: Boolean,
    val output: String,
    val exitCode: Int = 0,
    val durationMs: Long = 0,
    val error: String? = null
)