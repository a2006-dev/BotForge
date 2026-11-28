package com.ajx.botforge.ui.text

/**
 * 全站界面文案。
 *
 * ## 为什么集中管理
 *
 * 1. 文案散落在 20 个文件里时，改一次措辞要全项目搜，必然漏；
 * 2. 集中后可以一次性校验用词一致性（同一概念不能有三种说法）；
 * 3. 将来做多语言只需换一个实现，页面代码不动。
 *
 * ## 写作规范（强制）
 *
 * 界面文案一律为**系统描述式**，不使用以下成分：
 *  - 语气词：啦、哦、呢、吧、呀、啊
 *  - 感叹号：！
 *  - 表情符号：✅ ❌ ⛔ 🎉 等
 *  - 劝诱表述："快来"、"试试看"、"你一定会"
 *  - 拟人表述："我"、"你"（除非确指用户操作）、"主人"在非权限语境下
 *  - 主观评价："很棒"、"非常简单"、"最强大"
 *
 * 统一采用：
 *  - 陈述句，主语为系统或对象本身（例："服务未运行"而非"服务没跑起来哦"）
 *  - 操作指引用动宾结构（例："点击生成验证码"）
 *  - 状态用「对象 + 状态」二元结构（例："机器人 3 个在线"）
 *  - 数量与单位之间留空格（例："3 个"、"2 分钟前"）
 *  - 冒号统一全角「：」，括号统一全角「（）」
 */
object Txt {

    // ---------------------------------------------------------------- 通用
    const val OK = "确定"
    const val CANCEL = "取消"
    const val SAVE = "保存"
    const val DELETE = "删除"
    const val EDIT = "编辑"
    const val ADD = "添加"
    const val CLOSE = "关闭"
    const val RETRY = "重试"
    const val REFRESH = "刷新"
    const val MORE = "更多"
    const val NONE = "无"
    const val UNKNOWN = "未知"
    const val ENABLED = "已启用"
    const val DISABLED = "已停用"
    const val LOADING = "加载中"
    const val EMPTY = "暂无数据"

    // ---------------------------------------------------------------- 顶栏标题
    const val TITLE_DASHBOARD = "BotForge"
    const val TITLE_BOTS = "机器人"
    const val TITLE_CAPABILITIES = "能力中心"
    const val TITLE_LOGS = "日志"
    const val TITLE_SETTINGS = "设置"
    const val TITLE_PERSONAS = "人设"
    const val TITLE_MODELS = "模型"
    const val TITLE_PLUGINS = "插件"
    const val TITLE_GROUPS = "群管理"
    const val TITLE_PERMISSIONS = "权限中心"
    const val TITLE_TOOLS = "工具集"
    const val TITLE_TERMINAL = "终端"
    const val TITLE_AUDIT = "审计日志"
    const val TITLE_MEMORY = "记忆管理"
    const val TITLE_TESTCHAT = "模型测试"
    const val TITLE_ABOUT = "关于"

    // ---------------------------------------------------------------- 首页
    const val DASH_SUBTITLE = "服务状态与配置概览"
    const val DASH_SERVICE_STOPPED = "服务未运行"
    const val DASH_SERVICE_STOPPED_HINT = "点击下方按钮启动服务"
    const val DASH_NO_BOT = "服务运行中，未配置机器人"
    const val DASH_CONNECTING = "%d 个机器人连接中"
    const val DASH_ALL_ONLINE = "%d 个机器人在线"
    const val DASH_PARTIAL = "%d/%d 在线，%d 个未连接"
    const val DASH_METRIC_UPTIME = "运行时长"
    const val DASH_METRIC_LAST_EVENT = "最近事件"
    const val DASH_METRIC_RECONNECT = "重连次数"
    const val DASH_ACTION_START = "启动服务"
    const val DASH_ACTION_STOP = "停止服务"
    const val DASH_SECTION_BOTS = "机器人"
    const val DASH_SECTION_EVENTS = "最近事件"
    const val DASH_SECTION_MORE = "功能入口"
    const val DASH_NO_EVENT = "无事件记录。服务运行后此处显示最新日志。"
    const val DASH_EMPTY_BOT_TITLE = "未配置机器人"
    const val DASH_EMPTY_BOT_DESC = "需在 QQ 开放平台创建应用并获取 AppID 与 AppSecret"
    const val DASH_EMPTY_BOT_ACTION = "添加机器人"
    const val DASH_EMPTY_BOT_PERM = "检查权限"

    // ---------------------------------------------------------------- 首次引导
    const val ONBOARD_TITLE = "初始配置"
    const val ONBOARD_DESC = "本应用通过 QQ 官方开放接口收发消息，并调用用户自行配置的模型接口生成回复。完成以下三项配置后服务可用。"
    const val ONBOARD_STEP1_TITLE = "配置机器人"
    const val ONBOARD_STEP1_DESC = "在 QQ 开放平台创建机器人应用，获取 AppID 与 AppSecret 后填入。"
    const val ONBOARD_STEP1_ACTION = "配置机器人"
    const val ONBOARD_STEP2_TITLE = "配置模型"
    const val ONBOARD_STEP2_DESC = "填写 OpenAI 兼容接口地址与密钥。未配置模型时仅内置指令可用，无法生成回复。"
    const val ONBOARD_STEP2_ACTION = "配置模型"
    const val ONBOARD_STEP3_TITLE = "授予运行权限"
    const val ONBOARD_STEP3_DESC = "通知权限、电池优化白名单、后台运行权限。缺失任一项会导致服务被系统终止。"
    const val ONBOARD_STEP3_ACTION = "检查权限"
    const val ONBOARD_SKIP = "跳过，进入首页"

    // ---------------------------------------------------------------- 授权
    const val AUTH_OWNER_BOUND = "主人已绑定"
    const val AUTH_OWNER_NONE = "未绑定主人"
    const val AUTH_OWNER_NONE_HINT = "点击生成验证码完成绑定"
    const val AUTH_OWNER_SUMMARY = "%s · 管理员 %d 人"
    const val AUTH_ROLE_OWNER = "主人"
    const val AUTH_ROLE_ADMIN = "管理员"
    const val AUTH_ROLE_USER = "普通用户"
    const val AUTH_SCOPE_GLOBAL = "全局"
    const val AUTH_SCOPE_GROUP = "限群"

    // ---------------------------------------------------------------- 权限页
    const val PERM_SUB_OWNER = "主人与管理员"
    /** 验证码区标题 */
    const val PERM_CODE_TITLE = "绑定验证码"
    /** 未生成验证码时的说明 */
    const val PERM_CODE_EMPTY = "未生成验证码。生成后在手机 QQ 里私聊机器人发送该码即可完成绑定。"
    /** 生成按钮 */
    const val PERM_CODE_GENERATE = "生成验证码"
    /** 重新生成按钮 */
    const val PERM_CODE_REGENERATE = "重新生成"
    /** 有效期说明，参数为剩余秒数 */
    fun PERM_CODE_REMAIN(seconds: Long): String = "剩余有效期 $seconds 秒"
    /** 验证码过期 */
    const val PERM_CODE_EXPIRED = "验证码已过期，请重新生成"
    /** 使用方法 */
    const val PERM_CODE_USAGE = "在手机 QQ 里私聊机器人，直接发送这 8 位验证码。验证码一次性使用，绑定成功后立即失效。"
    const val PERM_CODE_COPY = "复制命令（/绑定主人 验证码）"
    /** 管理员区标题 */
    const val PERM_ADMIN_TITLE = "管理员"
    /** 管理员为空 */
    const val PERM_ADMIN_EMPTY = "还没有管理员。可在 QQ 里用指令授予，也可在此移除已有管理员。"
    /** 移除按钮 */
    const val PERM_ADMIN_REMOVE = "移除"
    /** 解除绑定按钮 */
    const val PERM_UNBIND = "解除主人绑定"
    /** 解除绑定确认标题 */
    const val PERM_UNBIND_TITLE = "确认解除主人绑定"
    /** 解除绑定确认正文 */
    const val PERM_UNBIND_BODY = "解除后，原主人将失去全部管理权限，需要重新生成验证码才能再次绑定。"
    /** 确认 */
    const val PERM_CONFIRM = "确认解除"
    /** 取消 */
    const val PERM_CANCEL = "取消"
    /** 未绑定主人时无解除按钮的含义说明 */
    const val PERM_UNBIND_UNAVAILABLE = "当前未绑定主人，无需解除。"
    /** 权限说明 */
    const val PERM_NOTE_TITLE = "权限说明"
    const val PERM_NOTE_BODY = "主人拥有全部权限；管理员可执行管理类指令，但不能转移主人身份。权限校验在机器人侧进行，此处界面仅用于查看与撤销。"

    // ---------------------------------------------------------------- 机器人
    const val BOT_STATE_ONLINE = "在线"
    const val BOT_STATE_CONNECTING = "连接中"
    const val BOT_STATE_RECONNECTING = "重连中"
    const val BOT_STATE_STOPPED = "已停止"
    const val BOT_STATE_ERROR = "异常"
    const val BOT_LABEL_HEARTBEAT = "心跳"
    const val BOT_LABEL_UPTIME = "在线"
    const val BOT_LABEL_RECONNECT = "重连"

    // ---------------------------------------------------------------- 功能入口
    const val LINK_PERSONA = "人设"
    const val LINK_PERSONA_DESC = "提示词、称呼、记忆深度、生成参数"
    const val LINK_MODEL = "模型"
    const val LINK_MODEL_DESC = "接口地址、密钥、连通性测试"
    const val LINK_PLUGIN = "插件"
    const val LINK_PLUGIN_DESC = "导入导出、指令白名单"
    const val LINK_CAPABILITY = "能力中心"
    const val LINK_CAPABILITY_DESC = "联网搜索、工具调用、记忆"
    const val LINK_TOOLS = "工具集"
    const val LINK_TOOLS_DESC = "本地工具包与命令行环境"
    const val LINK_TERMINAL = "终端"
    const val LINK_TERMINAL_DESC = "内置 Linux 环境"
    const val LINK_PERMISSION = "权限中心"
    const val LINK_PERMISSION_DESC = "系统权限、主人绑定、管理员"
    /** 首页快捷入口：关于 */
    const val LINK_ABOUT = "关于"
    const val LINK_ABOUT_DESC = "版本信息、开源许可与第三方组件"
    /** 首页快捷入口：权限 */
    const val LINK_PERMISSIONS = "权限"
    const val LINK_PERMISSIONS_DESC = "主人绑定与管理员管理"
    const val LINK_SETTINGS = "设置"
    const val LINK_SETTINGS_DESC = "主题、保活、日志、关于"

    // ---------------------------------------------------------------- 提示
    const val HINT_REQUIRED = "必填"
    const val HINT_OPTIONAL = "选填"
    const val HINT_NO_PERMISSION = "当前账号无此权限"
    const val HINT_NEED_OWNER = "需要主人权限"
    const val HINT_FEATURE_UNAVAILABLE = "功能未启用"
    const val HINT_COPY_SUCCESS = "已复制到剪贴板"
    const val HINT_SAVE_SUCCESS = "已保存"
        const val HINT_DELETE_SUCCESS = "已删除"

    // ---------------------------------------------------------------- 错误
    const val ERR_GENERIC = "操作失败"
    const val ERR_NETWORK = "网络请求失败"
    const val ERR_EMPTY_INPUT = "输入不能为空"
    const val ERR_INVALID_FORMAT = "格式不正确"
    // ---------------------------------------------------------------- 关于页
    const val ABOUT_TAGLINE = "QQ 开放平台机器人 · 本地运行实例"
    const val ABOUT_VERSION_FMT = "版本 %s（%d）"
    const val ABOUT_INTRO = "通过 QQ 官方开放接口收发消息，调用本机配置的模型接口生成回复。" +
            "不依赖第三方协议实现，不读取用户 QQ 账号数据，配置与消息记录均保存在本机。"
    const val ABOUT_PRIVACY_TITLE = "隐私与数据"
    const val ABOUT_CRED_STORE = "凭据存储"
    const val ABOUT_CRED_STORE_V = "AppSecret 与 API Key 加密后存于本机"
    const val ABOUT_CHAT = "消息记录"
    const val ABOUT_CHAT_V = "仅存本机数据库"
    const val ABOUT_EGRESS = "网络出站"
    const val ABOUT_EGRESS_V = "仅访问 QQ 开放平台接口与已配置的模型接口"
    const val ABOUT_EXPORT = "数据导出"
    const val ABOUT_EXPORT_V = "设置页可导出全部配置与日志"
    const val ABOUT_LIMIT_TITLE = "安全边界"
    const val ABOUT_LIMIT_BODY = "本机存储在未获得 root 权限的设备上受系统沙箱保护。" +
            "已取得 root 权限的设备上，第三方程序可主动提取应用私有目录，本应用不对该情形提供防护。"
    const val ABOUT_HELP_TITLE = "帮助与反馈"
    const val ABOUT_HELP_OFFICIAL = "QQ 开放平台文档"
    const val ABOUT_HELP_REPO = "代码仓库"
    const val ABOUT_HELP_ISSUE = "问题反馈"
    
    /** 官方交流群。关于页与 README 共用同一份群号，改这里即可。 */
    const val ABOUT_QQ_GROUP = "1012917975"

    const val ABOUT_OSS_TITLE = "开源组件"
    const val ABOUT_ENV_TITLE = "运行环境"
    const val ABOUT_ENV_MODEL = "设备型号"
    const val ABOUT_ENV_ANDROID = "系统版本"
    const val ABOUT_ENV_ABI = "指令集"
    const val ABOUT_LEGAL_TITLE = "法律声明"
    const val ABOUT_LEGAL_BODY = "本应用为独立开发的开源软件，与腾讯公司无隶属关系，不代表 QQ 官方立场。" +
            "接入 QQ 开放平台时须遵守《QQ 机器人运营规范》及所在地法律法规。" +
            "因使用方式不当产生的账号风险由使用者自行承担。"
    const val ABOUT_FOOTER = "Kotlin · Jetpack Compose"

    // ---------------------------------------------------------------- 插件页
    const val PLUGIN_LOADED_FMT = "%d 个已加载"
    const val PLUGIN_EMPTY_TITLE = "未发现插件"
    const val PLUGIN_EMPTY_DESC = "插件目录：Android/data/com.ajx.botforge/files/plugins/\n" +
            "每个插件为一个子目录，目录内需包含 plugin.json。\n" +
            "首次启动时自动生成 hello 示例插件。"
    const val PLUGIN_API_INCOMPATIBLE = "API 不兼容"
    const val PLUGIN_AUTHOR = "作者"
    const val PLUGIN_CMD_FMT = "指令 %d 条"

    // ---------------------------------------------------------------- 列表 / 编辑页
    const val LIST_EMPTY_PERSONA = "未配置人设"
    const val LIST_EMPTY_MODEL = "未配置模型"
    const val LIST_EMPTY_BOT = "未配置机器人"
    const val LIST_EMPTY_GROUP = "未登记群"
    const val LIST_COUNT_PERSONA_FMT = "%d 条"
    const val LIST_COUNT_MODEL_FMT = "%d 个"
    const val LIST_COUNT_GROUP_FMT = "%d 个群 · %d 个已启用"
    const val LIST_BOT_SUMMARY_FMT = "共 %d 个 · %d 个已启用"
    const val LIST_BOT_USED_FMT = "%d 个机器人引用"

    // ---------------------------------------------------------------- 日志页
    const val LOG_COUNT_FMT = "%d 条 · 上限 600"
    const val LOG_EMPTY = "无日志记录"
    const val LOG_ERROR_HINT_FMT = "存在 %d 条错误记录"

    // ---------------------------------------------------------------- 确认对话框
    const val CONFIRM_DELETE_PERSONA = "删除人设「%s」。此操作不可撤销。"
    const val CONFIRM_DELETE_MODEL = "删除模型「%s」。此操作不可撤销。"
    const val CONFIRM_DELETE_GROUP = "删除群「%s」。此操作不可撤销。"
    const val CONFIRM_DELETE_BOT = "删除机器人「%s」及其 %d 条群配置。此操作不可撤销。"
    const val CONFIRM_REF_PERSONA_FMT = "「%s」正被 %d 个机器人引用，删除后这些机器人将失去人设。"
    const val CONFIRM_REF_MODEL_FMT = "「%s」正被 %d 个机器人引用，删除后这些机器人将无法调用模型。"

    // ---------------------------------------------------------------- 表单字段说明
    const val FIELD_APPID_HELP = "QQ 开放平台分配的 AppID，纯数字"
    const val FIELD_APPSECRET_HELP = "QQ 开放平台分配的 AppSecret"
    const val FIELD_BASEURL_HELP = "不含 /chat/completions，末尾斜杠自动去除"
    const val FIELD_APIKEY_HELP = "请求头 Authorization 使用的密钥"
    const val FIELD_MODEL_HELP = "请求体 model 字段的取值"
    const val FIELD_PROMPT_HELP = "作为 system prompt 发送给模型"
    const val FIELD_GROUPID_HELP = "群会话 openid，可在消息回调日志中获取"
    const val FIELD_NOTE_HELP = "备注名，仅本机可见"
    const val FIELD_TEMP_HELP = "取值 0 ~ 2，数值越大输出越发散"
    const val FIELD_TOKENS_HELP = "单条回复的最大 token 数"
    const val FIELD_TIMEOUT_HELP = "单次请求超时时间"
    const val FIELD_COOLDOWN_HELP = "同一用户两次响应的最短间隔"

    // ---------------------------------------------------------------- 状态提示
    const val STATE_NEED_AT = "需 @ 机器人"
    const val STATE_NO_AT = "免 @"
    const val STATE_CONNECTING_FMT = "%d 个机器人连接中"
    const val STATE_STOPPED_HAS_BOT_FMT = "已配置 %d 个机器人，服务未启动"
    const val STATE_RECONNECT_WARN = "24 小时内重连 5 次，建议检查网络"
    const val STATE_ISSUES_FMT = "%d 个问题待处理"
    const val STATE_ABNORMAL_FMT = "%d 个机器人连接异常"
    const val UNIT_TIMES = "次"
    const val UNIT_ITEMS = "条"
    const val UNIT_CHARS = "字"
    // ---------------------------------------------------------------- 列表通用
    const val NEW = "新建"
    const val NOT_SET = "未配置"
    const val NOT_BOUND = "未绑定"
    const val IN_USE = "使用中"
    const val UNUSED = "未使用"
    const val ENABLE = "启用"
    const val DISABLE = "禁用"
    const val PAUSE = "暂停"
    const val RESUME = "继续"
    const val CLEAR = "清空"
    const val SAVE_FAILED = "保存失败"
    const val READ_FAILED = "读取失败"

    // ---------------------------------------------------------------- 机器人列表
    const val BOT_LIST_EMPTY_TITLE = "未配置机器人"
    const val BOT_LIST_EMPTY_DESC = "添加 QQ 开放平台机器人应用，填写 AppID 与 AppSecret 后接入"
    const val BOT_DELETE_TITLE = "删除机器人"
    const val BOT_APPID_FMT = "AppID %s"
    const val BOT_NO_GROUP = "未配置群"
    const val BOT_MISSING_FMT = "缺少%s"
    const val BOT_MANAGE_GROUP = "群管理"
    const val LABEL_PERSONA = "人设"
    const val LABEL_MODEL = "模型"

    // ---------------------------------------------------------------- 人设
    const val PERSONA_TITLE = "人设"
    const val PERSONA_EMPTY_TITLE = "未配置人设"
    const val PERSONA_EMPTY_DESC = "人设包含人格提示词、称呼、温度与记忆深度"
    const val PERSONA_NEW = "新建人设"
    const val PERSONA_DELETE_TITLE = "删除人设"
    const val PERSONA_UNTITLED = "未命名人设"
    const val PERSONA_SUMMARY_FMT = "称呼「%s」· 温度 %s · 上限 %d 字"
    const val PERSONA_MEMORY_FMT = "记忆开启 · 保留 %d 条"
    const val PERSONA_MEMORY_OFF = "记忆关闭"
    const val PERSONA_REF_FMT = "%d 个机器人引用"

    // ---------------------------------------------------------------- 模型
    const val MODEL_TITLE = "模型"
    const val MODEL_EMPTY_TITLE = "未配置模型"
    const val MODEL_EMPTY_DESC = "支持本机反代、官方接口与本地模型，统一使用 OpenAI 兼容协议"
    const val MODEL_NEW = "新建模型"
    const val MODEL_DELETE_TITLE = "删除模型"
    const val MODEL_SUMMARY_FMT = "协议 %s · 超时 %ds · 上限 %d 字"
    const val MODEL_KEY_MISSING = "密钥未配置"
    const val MODEL_KEY_SAVED = "密钥已保存"

    // ---------------------------------------------------------------- 群配置
    const val GROUP_TITLE = "群配置"
    const val GROUP_EMPTY_TITLE = "未配置群"
    const val GROUP_EMPTY_DESC = "配置群后，机器人在该群响应 @ 消息。\n群 openid 可从消息回调日志获取。"
    const val GROUP_ADD = "添加群"
    const val GROUP_EDIT = "编辑群"
    const val GROUP_DELETE_TITLE = "删除群配置"
    const val GROUP_UNTITLED = "未命名群"
    const val GROUP_COOLDOWN_FMT = "冷却 %ds"
    const val GROUP_OPENID_REQUIRED = "group_openid 为必填项"
    const val GROUP_OPENID_LABEL = "group_openid"
    const val GROUP_OPENID_PLACEHOLDER = "群会话 openid"
    const val GROUP_NOTE_LABEL = "备注名"
    const val GROUP_NOTE_PLACEHOLDER = "备注名称"
    const val GROUP_COOLDOWN_LABEL = "冷却秒数"
    const val GROUP_COOLDOWN_PLACEHOLDER = "单位 秒"
    const val GROUP_TOGGLE_ENABLED = "启用"
    const val GROUP_TOGGLE_REQUIRE_AT = "仅响应 @ 消息"

    // ---------------------------------------------------------------- 日志
    const val LOG_TITLE = "运行日志"
    const val LOG_TAILING_ON = "暂停跟随"
    const val LOG_TAILING_OFF = "继续跟随"
    const val LOG_LEVEL_ALL = "全部"
    const val LOG_LEVEL_INFO = "信息"
    const val LOG_LEVEL_WARN = "警告"
    const val LOG_LEVEL_ERROR = "错误"
    const val LOG_EMPTY_DESC = "服务启动后，连接、消息处理与模型调用记录显示在此处"
    const val LOG_CLEAR = "清空"
    // ---------------------------------------------------------------- 补充：表单与机器人编辑
    const val FIELD_APPID_LABEL = "AppID"
    const val FIELD_APPSECRET_LABEL = "AppSecret"
    const val BOT_ENABLED_DESC = "服务启动时建立连接"
    const val BOT_DISABLED_DESC = "服务跳过此机器人"
    const val BOT_INTENTS_DESC = "固定为 GROUP_AND_C2C_EVENT（1<<25）。" +
            "旧版本写入 1174409216，含 3 个无权限位，" +
            "会导致网关在 Identify 后立即断开，因此该项不开放手工填写。"
    const val BOT_CREATE = "创建机器人"
    const val BOT_SAVE = "保存修改"
    // ---------------------------------------------------------------- 补充：编辑页 / 设置页
    const val ON = "开"
    const val OFF = "关"
    const val MODEL_ADD = "添加模型"
    const val MODEL_EDIT = "编辑模型"
    const val MODEL_NAME_PLACEHOLDER = "模型名称"
    const val MODEL_BASEURL_LABEL = "Base URL"
    const val MODEL_APIKEY_LABEL = "API Key"
    const val MODEL_KEY_KEEP = "已保存，留空不修改"
    const val PERSONA_EDIT = "编辑人设"
    const val PERSONA_CREATE = "创建人设"
    const val PERSONA_PROMPT_LABEL = "人格描述（Prompt）"
    const val SET_SEARCH_MODEL = "由模型判定"
    const val SET_SEARCH_KEYWORD = "关键词匹配"
    const val SET_SHIZUKU = "Shizuku 增强"
    // ---------------------------------------------------------------- 补充：状态与无障碍
    const val BOT_PAUSED_DESC = "已手动停止，不参与自动拉起"
    const val BOT_OFFLINE_DESC = "服务未运行，机器人不响应消息"
    const val ISSUE_NO_NOTIFY = "通知权限未授予，后台运行受限"
    const val CD_HIDE = "隐藏"
    const val CD_SHOW = "显示"
    const val CHAR_COUNT_FMT = "%d 字"
    // ---------------------------------------------------------------- 补充：机器人状态说明
    const val BOT_STATE_PAUSED = "已暂停"
    const val BOT_STATE_OFFLINE = "离线"
    const val BOT_HINT_ONLINE = "服务运行中，机器人正常响应"
    const val BOT_HINT_CONNECTING = "正在建立网关连接"
    const val BOT_HINT_RECONNECTING = "连接中断，正在自动重连"
    const val BOT_HINT_ERROR = "连接异常，需要处理"

    // ==================== 终端 ====================

    /** 终端副标题：会话运行中 */
    const val TERM_SUB_RUNNING = "会话运行中"

    /** 终端副标题：会话未启动 */
    const val TERM_SUB_IDLE = "会话未启动"

    /** 解包进度标题 */
    const val TERM_UNPACKING = "正在解包运行环境"

    /** 解包失败 */
    const val TERM_UNPACK_FAILED = "运行环境解包失败"

    /** 缺少可执行文件 */
    const val TERM_NO_BINARY = "缺少运行环境组件"

    /** 缺少可执行文件说明 */
    const val TERM_NO_BINARY_DESC = "点「安装内置环境」从安装包内解出 Linux 用户空间（Termux 布局）；点「跳过」则只在 Android 本机执行命令。"

    /** 终端空态 */
    const val TERM_EMPTY = "点击下方启动按钮开启会话"

    const val TERM_NOT_INSTALLED = "运行环境尚未安装"

    const val TERM_INSTALL = "安装内置环境"

    const val TERM_INSTALL_AGAIN = "改为安装内置环境"

    const val TERM_SKIP = "跳过"

    const val TERM_SKIPPED_TITLE = "当前使用 Android 本机环境"

    const val TERM_SKIPPED_DESC = "未安装 Linux 环境。机器人执行终端命令时只在本机 sh 中运行，" +
        "可以使用 ls、cat、pm、am 等系统命令，但没有 python3、apk、git 这类 Linux 工具。"

    /** 会话启动失败 */
    const val TERM_START_FAILED = "会话启动失败"

    /** 启动按钮 */
    const val TERM_START = "启动会话"

    /** 重启按钮 */
    const val TERM_RESTART = "重启会话"

    /** 清屏按钮 */
    const val TERM_CLEAR = "清屏"

    /** 发送按钮 */
    const val TERM_SEND = "发送"

    /** 输入提示 */
    const val TERM_INPUT_HINT = "输入命令"

    // ==================== 工具包 ====================

    /** 工具页副标题，参数为工具数量 */
    fun TOOL_SUB(count: Int): String = "已安装 $count 个"

    /** 刷新按钮 */
    const val TOOL_REFRESH = "刷新"

    /** 说明区标题 */
    const val TOOL_INTRO_TITLE = "工具包机制"

    /** 说明区正文 */
    const val TOOL_INTRO_DESC = "工具包由一份清单与一个入口脚本组成，在应用内置的 Linux 环境中执行。界面按清单自动生成参数表单，参数以环境变量传入脚本，标准输出作为结果显示。"

    /** 安全边界声明 */
    const val TOOL_SECURITY_NOTE = "工具包脚本以应用自身权限运行，可读写共享存储与应用私有目录。应用不提供沙箱隔离，启用前请确认来源可信。"

    /** 目录说明 */
    const val TOOL_DIR_NOTE = "工具包目录：Android/data/com.ajx.botforge/files/plugins"

    /** 空态标题 */
    const val TOOL_EMPTY_TITLE = "尚无工具包"

    /** 空态说明 */
    const val TOOL_EMPTY_DESC = "将工具包目录放入上述路径后点击刷新"

    /** 高风险标记 */
    const val TOOL_RISKY = "高风险"

    /** 未标注作者 */
    const val TOOL_UNKNOWN = "未标注"

    /** 工具元信息，参数为版本、作者、超时秒数 */
    fun TOOL_META(version: String, author: String, timeout: Int): String =
        "v$version · $author · 超时 ${timeout}s"

    /** 执行按钮 */
    const val TOOL_RUN = "执行"

    /** 执行中按钮 */
    const val TOOL_RUNNING = "执行中"

    /** 清空结果按钮 */
    const val TOOL_CLEAR_RESULT = "清空结果"

    /** 无输出 */
    const val TOOL_NO_OUTPUT = "无输出"

    /** 结果元信息，参数为退出码与耗时毫秒 */
    fun TOOL_RESULT_META(exitCode: Int, durationMs: Long): String =
        "退出码 $exitCode · ${durationMs}ms"



    // ------------------------------------------------------------ 模型探测与 API 池
    const val TITLE_API_POOL = "API 池"
    const val MP_TEST_BTN = "测试连通性"
    const val MP_TESTING = "测试中…"
    const val MP_FETCH_BTN = "获取可选模型"
    const val MP_FETCHING = "正在读取模型列表…"
    const val MP_FETCH_EMPTY = "没有获取到模型，检查接口地址与密钥"
    const val MP_PICK_TITLE = "选择模型"
    const val MP_PICK_HINT = "点击任意一项填入模型名"
    const val MP_SECTION_POOL = "故障转移"
    const val MP_POOL_EMPTY = "还没有候选。添加后，主配置失败时会自动切换。"
    const val MP_POOL_ADD = "添加候选"
    const val MP_POOL_MAIN = "主配置"
    const val MP_POOL_INHERIT = "留空则继承主配置"
    const val MP_POOL_NOTE = "备注（可选）"
    const val MP_POOL_BASEURL = "接口地址"
    const val MP_POOL_MODEL = "模型名"
    const val MP_POOL_KEY = "密钥"
    const val MP_POOL_DEL = "删除"
    const val MP_POOL_FAILS = "连续失败"
    const val MP_POOL_HEALTHY = "正常"
    const val MP_POOL_RESET = "重置池状态"
    const val MP_POOL_HINT = "请求失败时会按顺序自动尝试下一条，成功即返回。"
    const val MP_COPIED = "已复制"

    // ==================== 记忆管理（任务书 3.3.4） ====================

    /** 记忆管理页副标题 */
    const val MEM_SUBTITLE = "本地对话记忆与会话占用"

    /** 顶栏刷新按钮 */
    const val MEM_REFRESH = "刷新"

    /** 空态标题：一个会话文件都没有 */
    const val MEM_EMPTY_TITLE = "暂无本地记忆"

    /** 空态说明：讲清什么时候才会产生记忆文件 */
    const val MEM_EMPTY_DESC = "机器人收到消息并开启记忆后，这里会按会话列出对话记录"

    /**
     * 概览第一行：会话数 · 总轮数 · 总占用。
     *
     * 用非定位格式符（%d/%s）而非 %1$d：Kotlin 字符串模板把 `$` 当插值起始，
     * 写成 %1$d 时 `$d` 会被解析成变量引用，直接编译不过。
     * 仓库既有文案（如 DASH_PARTIAL）同样只用非定位符。
     */
    const val MEM_SUMMARY_FMT = "%d 个会话 · 共 %d 轮 · %s"

    /** 概览第二行：累计 %d 条消息 */
    const val MEM_SUMMARY_SUB_FMT = "累计 %d 条消息"

    /** 单个机器人下的会话数 */
    const val MEM_BOT_COUNT_FMT = "%d 个会话"

    /** 场景：群聊 */
    const val MEM_SCENE_GROUP = "群聊"

    /** 场景：私聊 */
    const val MEM_SCENE_C2C = "私聊"

    /** 展开预览 */
    const val MEM_EXPAND = "预览"

    /** 收起预览 */
    const val MEM_COLLAPSE = "收起"

    /** 会话元信息一行：轮数 · 条数 · 占用 */
    const val MEM_ROW_META_FMT = "%d 轮 · %d 条 · %s"

    /** 最后更新时间前缀 */
    const val MEM_LAST_UPDATED_FMT = "最后更新 %s"

    /** 存储键标签 */
    const val MEM_KEY_LABEL = "存储键"

    /** 预览为空 */
    const val MEM_PREVIEW_EMPTY = "该会话尚无对话记录"

    /** 预览里的用户角色 */
    const val MEM_ROLE_USER = "用户"

    /** 预览里的机器人角色 */
    const val MEM_ROLE_BOT = "机器人"

    /** 单会话清空按钮 */
    const val MEM_CLEAR_ONE = "清空该会话"

    /** 单会话清空后果 */
    const val MEM_CLEAR_ONE_DESC = "该会话的全部本地对话记忆会被删除，模型将不再记得此前聊过什么。删除后无法恢复。"

    /** 事实行：机器人 */
    const val MEM_FACT_BOT = "机器人"

    /** 事实行：会话对象 */
    const val MEM_FACT_PEER = "会话对象"

    /** 事实行：将失去多少轮 / 多少条 / 多少空间 */
    const val MEM_FACT_LOST_FMT = "将失去 %d 轮对话、%d 条消息，释放 %s"

    /** 全部清空按钮 */
    const val MEM_CLEAR_ALL = "清空全部记忆"

    /** 全部清空后果 */
    const val MEM_CLEAR_ALL_DESC = "所有机器人的全部本地对话记忆都会被删除。机器人配置、人设、群白名单不受影响，但每段对话的上下文将从零开始。"

    /** 全部清空的核对词。用固定短词而非名称：清的是「全部」，没有单一对象名可核对 */
    const val MEM_CLEAR_ALL_WORD = "清空全部"

    /** 事实行：涉及 %d 个会话 */
    const val MEM_FACT_ALL_SESSIONS_FMT = "涉及 %d 个会话"

    /** 事实行：共 %d 轮 */
    const val MEM_FACT_ALL_ROUNDS_FMT = "共 %d 轮对话"

    /** 事实行：占用 %s */
    const val MEM_FACT_ALL_SIZE_FMT = "共占用 %s"

    /** 事实行：影响范围 */
    const val MEM_FACT_ALL_SCOPE = "影响范围：本机全部机器人与全部群聊、私聊会话"

    /** 底部提示：保留上限 */
    const val MEM_LIMIT_HINT = "每个会话最多保留最近 120 条消息，超出后最早的记录被自动丢弃。清空只删本地记忆文件，不影响机器人配置与人设。"
    // ==================== 能力清单（只读看板） ====================
    // 本页只做「陈述事实」：有哪些包、包开没开、每个工具什么权限档。
    // 编辑入口在 设置 → 工具包与权限，两处文案不要互相抄，改了这里不用改那边。

    /** 能力清单页副标题 */
    const val CAP_SUBTITLE = "当前可用的工具与权限档"

    /** 空态标题：注册表里一个包都没有 */
    const val CAP_EMPTY_TITLE = "未发现可用工具"

    /** 空态说明：给出唯一可执行的下一步，不编造原因 */
    const val CAP_EMPTY_DESC = "内置工具包尚未注册。可点右上角刷新重试，或重启应用后重新进入"

    /**
     * 顶部总览：已启用包数 · 工具总数 · 需确认数。
     *
     * 只用非定位格式符（%d），不用 %1$d：Kotlin 字符串模板把 `$` 当插值起始，
     * `%1$d` 里的 `$d` 会被解析成变量引用，直接编译不过。
     */
    const val CAP_OVERVIEW_FMT = "已启用 %d 个包 · 共 %d 个工具 · 其中 %d 个需要确认"

    /** 总览补充：解释「需要确认」在无人值守场景下的真实含义 */
    const val CAP_OVERVIEW_SUB = "需要确认的工具不会真的执行：机器人跑在后台，没有可以点确认的界面，这一类按未授权处理"

    /** 核心包标记：避免用户去设置里找一个不存在的开关 */
    const val CAP_LOCKED = "核心包，不可关闭"

    /** 包内工具数量 */
    const val CAP_TOOL_COUNT_FMT = "%d 个工具"

    /** 权限档：允许 */
    const val CAP_PERM_ALLOW = "允许"

    /** 权限档：需确认 */
    const val CAP_PERM_ASK = "需确认"

    /** 权限档：已禁用 */
    const val CAP_PERM_DENY = "已禁用"

    /** 整包停用时的尾注，参数为包内工具数 */
    const val CAP_PKG_OFF_NOTE_FMT = "此包已停用，包内 %d 个工具都不会交给模型"

    /** 包内存在禁用工具时的尾注，参数为被禁用的工具数 */
    const val CAP_DENY_NOTE_FMT = "其中 %d 个工具已禁用，不会交给模型"

    /** 页面底部：需确认档的说明 */
    const val CAP_ASK_NOTE = "「需确认」的工具仍会出现在模型可见的工具列表里，但执行前会被拦下，模型收到的是未授权提示，而不是静默执行。"

    /** 页面底部：本页只读，编辑在别处 */
    const val CAP_READONLY_NOTE = "本页只读。开关包与调整单个工具的权限在 设置 → 工具包与权限，改动立即生效。"

    /** 设置页入口说明 */
    const val CAP_ENTRY_DESC = "只读查看当前启用的包与工具，以及每个工具的权限档"

    /** 设置页入口按钮 */
    const val CAP_ENTRY_ACTION = "查看"

    // ---------------------------------------------------------------- 审计日志
    // 命名统一 AUDIT_ 前缀，避开其它子代理同时新增的常量。

    /** 设置页入口说明 */
    const val AUDIT_ENTRY_DESC = "查看模型调用过哪些工具、每次决策与结果"

    /** 设置页入口按钮 */
    const val AUDIT_ENTRY_ACTION = "查看"

    /** 页面副标题：共 N 条记录 */
    const val AUDIT_SUBTITLE_FMT = "共 %d 条记录"

    /** 筛选档位：全部 */
    const val AUDIT_FILTER_ALL_FMT = "全部 %d"

    /** 筛选档位：放行 */
    const val AUDIT_FILTER_ALLOW_FMT = "放行 %d"

    /** 筛选档位：待确认 */
    const val AUDIT_FILTER_ASK_FMT = "待确认 %d"

    /** 筛选档位：拒绝 */
    const val AUDIT_FILTER_DENY_FMT = "拒绝 %d"

    /** 决策：直接放行 */
    const val AUDIT_DECISION_ALLOW = "放行"

    /** 决策：询问后同意 */
    const val AUDIT_DECISION_ASK_OK = "确认同意"

    /** 决策：询问后拒绝或超时 */
    const val AUDIT_DECISION_ASK_DENY = "确认拒绝"

    /** 决策：权限档位拒绝，未执行 */
    const val AUDIT_DECISION_BLOCKED = "已拦截"

    /** 决策无法识别的兼容文案 */
    const val AUDIT_DECISION_UNKNOWN = "未知决策"

    /** 结果：执行成功 */
    const val AUDIT_RESULT_SUCCESS = "成功"

    /** 结果：已执行但失败 */
    const val AUDIT_RESULT_FAILED = "失败"

    /** 结果：未执行，被拦截 */
    const val AUDIT_RESULT_BLOCKED = "未执行"

    /** 结果：未执行，用户拒绝 */
    const val AUDIT_RESULT_DENIED = "已拒绝"

    /** 调用来源：机器人 · 场景 · 对象尾号 */
    const val AUDIT_SOURCE_FMT = "%s · %s · %s"

    /** 耗时：N ms */
    const val AUDIT_COST_FMT = "%d ms"

    /** 工具名缺失的兼容文案 */
    const val AUDIT_UNKNOWN_TOOL = "未知工具"

    /** 机器人名缺失的兼容文案 */
    const val AUDIT_UNKNOWN_BOT = "未知机器人"

    /** 系统自发动作没有发起人 */
    const val AUDIT_NO_USER = "无发起人"

    /** 未知或非进程类工具没有退出码 */
    const val AUDIT_NO_EXIT = "不适用"

    /** 内置工具不属于任何扩展包 */
    const val AUDIT_BUILTIN_PACKAGE = "内置工具"

    /** 展开区标签：场景 */
    const val AUDIT_LABEL_SCENE = "场景"

    /** 展开区标签：发起人完整 openid */
    const val AUDIT_LABEL_USER = "发起人"

    /** 展开区标签：来源扩展包 */
    const val AUDIT_LABEL_PACKAGE = "来源包"

    /** 展开区标签：退出码 */
    const val AUDIT_LABEL_EXIT = "退出码"

    /** 空态标题：完全没有记录 */
    const val AUDIT_EMPTY_TITLE = "暂无审计记录"

    /** 空态说明：完全没有记录 */
    const val AUDIT_EMPTY_DESC = "工具调用发生后，这里会显示时间、来源、决策与参数摘要。审计文件按天存放在应用私有目录，不会被普通日志清理。"

    /** 空态标题：当前筛选无结果 */
    const val AUDIT_EMPTY_FILTER_TITLE = "当前筛选无记录"

    /** 空态说明：当前筛选无结果 */
    const val AUDIT_EMPTY_FILTER_DESC = "已加载的记录中没有匹配该决策类型的条目。切回「全部」可查看全部记录。"
}
