package com.ajx.botforge.ai.tool

/**
 * 内置包的声明表。
 *
 * 这里只放「有哪些包、包里有哪些工具、各自默认什么权限」。
 * 真正的工具实现仍在各自文件里，包机制只负责分组与开关。
 *
 * 分批策略：包不要求一次搬全。先声明现有 7 个工具，
 * 后续搬来的包（daily_life / system_tools / extended_file_tools …）
 * 在对应文件里加一个 `xxxPackage()` 然后往 [all] 里挂一行即可。
 */
object BuiltinPackages {

    /**
     * 核心包。永远启用，关不掉。
     *
     * 判定标准不是「重要」，而是「没了它机器人就不是机器人了」：
     * 工具轮数内的基本执行能力、以及读自己配置的能力。
     */
    fun corePackage() = ToolPackage(
        id = "core",
        displayName = "核心",
        description = "执行命令、读写文件、自检终端环境。关掉这一包，机器人只剩聊天。",
        category = "基础",
        defs = listOf(
            ToolDef(
                BuiltinTools.RunCommandTool,
                ToolPermission.ALLOW,
                "在 proot 容器里执行 shell 命令"
            ),
            ToolDef(
                BuiltinTools.ReadFileTool,
                ToolPermission.ALLOW,
                "读取手机上任意可访问的文本文件"
            ),
            ToolDef(
                BuiltinTools.WriteFileTool,
                ToolPermission.ALLOW,
                "写入或覆盖文件，会改动磁盘内容"
            ),
            ToolDef(
                BuiltinTools.DiagnoseTerminalTool,
                ToolPermission.ALLOW,
                "环境自检，只读并落一份诊断文件"
            ),
        )
    )

    /**
     * 系统包。
     *
     * system_command 借 Shizuku 拿 shell:2000，能 am start、改设置、模拟点击，
     * 是全应用权限最高的能力。但这里默认仍是 ALLOW —— 理由见 [ToolPermission.ASK]：
     * 设置页 UI 还没做，用户此刻没有任何入口把 ASK 改成 ALLOW，
     * 那样等于直接把这个功能关死，属于退步。
     * 等设置页能改权限了，再把默认收紧到 ASK 才有意义。
     */
    fun systemPackage() = ToolPackage(
        id = "system",
        displayName = "系统控制",
        description = "通过 Shizuku 以 ADB 身份执行系统命令。权限很高，建议按需在本页关闭。",
        category = "高级",
        defs = listOf(
            ToolDef(
                SystemCommandTool,
                ToolPermission.ALLOW,
                "以 shell:2000 身份执行系统命令，可打开应用、改系统设置、模拟点击"
            ),
        )
    )

    /**
     * 插件包。
     *
     * run_plugin 会执行第三方脚本，脚本以应用自身权限运行，
     * 也就是能读写 /sdcard 和私有目录。同样先保持 ALLOW，
     * 与 1.8.7 行为一致，避免升级即功能消失。
     */
    fun pluginPackage() = ToolPackage(
        id = "plugin",
        displayName = "插件",
        description = "列出并执行已安装插件。插件脚本以应用权限运行。",
        category = "扩展",
        defs = listOf(
            ToolDef(
                BuiltinTools.ListPluginsTool,
                ToolPermission.ALLOW,
                "只列出插件清单"
            ),
            ToolDef(
                BuiltinTools.RunPluginTool,
                ToolPermission.ALLOW,
                "执行第三方插件脚本，脚本拥有本应用的读写权限"
            ),
        )
    )

    /** 全部内置包。新增包在这里挂一行。 */
    fun all(): List<ToolPackage> = listOf(
        corePackage(),
        systemPackage(),
        pluginPackage(),
    )
}