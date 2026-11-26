# BotForge UI 重构审计报告 · Phase 0

> 依据：`UI Redesign & Refactoring Specification v1.0`
> 审计时间：2026-11-26
> 结论：**可以全量 Compose 重写 UI 层，业务层零改动**

---

## 一、技术基线（已实测）

| 项 | 值 |
|---|---|
| Kotlin | 1.9.22 |
| AGP | 8.1.4 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| jvmTarget | 17 |
| Gradle DSL | Groovy |
| namespace | `com.ajx.botforge` |
| versionName | 1.0.0 |

**Compose 兼容性**：Kotlin 1.9.22 对应 Compose Compiler **1.5.8**，与本任务书指定版本一致。`compileSdk 34` 满足 `compose-bom 2024.02.00` 及以上要求。`minSdk 26` 无阻碍（Compose 最低要求 21）。

**结论**：无需升级 Gradle / Kotlin / AGP，直接加 Compose 依赖即可。

---

## 二、现有页面清单（10 个 Activity）

| # | 页面 | 文件 | 行数 | 继承 | 业务调用 |
|---|---|---|---|---|---|
| 1 | 首页 | `ui/MainActivity.kt` | 470 | `AppCompatActivity` | `Config` / `BotService` / `BotManager` |
| 2 | 机器人列表 | `ui/BotListActivity.kt` | 173 | `BaseActivity` | `Config.loadBots/saveBots` |
| 3 | 机器人编辑 | `ui/BotEditActivity.kt` | 96 | `BaseActivity` | `Config.loadBots/saveBots/loadPersonas/loadModels` |
| 4 | 群管理 | `ui/GroupListActivity.kt` | 105 | `BaseActivity` | `Config.loadBots/saveBots` |
| 5 | 人设列表 | `ui/PersonaListActivity.kt` | 41 | `BaseActivity` | `Config.loadPersonas/savePersonas` |
| 6 | 人设编辑 | `ui/PersonaEditActivity.kt` | 68 | `BaseActivity` | `Config.loadPersonas/savePersonas` |
| 7 | 模型列表 | `ui/ModelListActivity.kt` | 54 | `BaseActivity` | `Config.loadModels/saveModels` + `AiEngine.ping` |
| 8 | 模型编辑 | `ui/ModelEditActivity.kt` | 68 | `BaseActivity` | `Config.loadModels/saveModels` |
| 9 | 运行日志 | `ui/LogActivity.kt` | 63 | `BaseActivity` | `Logger` / `BotManager.reload` |
| 10 | 基类 | `ui/BaseActivity.kt` | 194 | `AppCompatActivity` | 纯 UI 辅助，无业务 |

**没有 XML 布局文件**。`res/` 下只有 `drawable`、`values`、`xml`，**不存在 `layout/` 目录**。所有页面均为 Kotlin 代码拼装 View。

---

## 三、业务接口清单（必须保留，不得改动）

### 3.1 `data/Config.kt`（object，SharedPreferences + JSON）

```kotlin
// Bot
data class Bot(id, name, appId, appSecret, personaId, modelId,
               enabled, intents: Long = 1L shl 25, groups: List<GroupCfg>)
data class GroupCfg(groupOpenId, note, enabled, requireAt, cooldownSec = 5)
fun loadBots(): MutableList<Bot>
fun saveBots(list: List<Bot>)

// Persona
data class Persona(id, name, prompt, tone = "", address = "你",
                   memoryEnabled = true, memoryDepth = 20,
                   temperature = 0.8f, maxTokens = 400)
fun loadPersonas(): MutableList<Persona>
fun savePersonas(list: List<Persona>)

// Model
data class Model(id, name, baseUrl, apiKey, model,
                 type = "openai", timeout = 120, maxTokens = 400)
fun loadModels(): MutableList<Model>
fun saveModels(list: List<Model>)

// 全局开关（var，getter/setter 直读写 SharedPreferences）
autoStart / keepAlive / searchEnabled / searchTrigger
agentEnabled / logEnabled
fun newId(prefix: String): String
const val GROUP_INTENTS = 1L shl 25
```

**敏感字段机制（重要，UI 必须适配）**：
`appSecret` 与 `apiKey` 走 `SecretStore` 保险箱。`loadBots()` 返回的已经是**真值**（从保险箱取），`saveBots()` 内部自动写保险箱 + JSON 存占位符。**UI 层无需关心加密，直接读写真值即可**，但界面上默认要打码。

### 3.2 `data/Logger.kt`（object，内存环 + 落盘）

```kotlin
data class Line(val time: String, val level: Int, val text: String)
// level: 0=info 1=warn 2=error
var listener: ((Line) -> Unit)?     // 实时订阅钩子，当前无人使用
fun i(tag, msg) / fun w(tag, msg) / fun e(tag, msg)
fun snapshot(): List<Line>          // 最多 600 条
fun clear()
```

### 3.3 `qq/BotManager.kt`（object）

```kotlin
fun start(ctx: Context)
fun stop()
fun reload(ctx: Context)
fun status(): String   // "未运行" | "运行中 (n)" | "部分运行"
fun count(): Int
```

### 3.4 `service/BotService.kt`

```kotlin
companion object {
    const val NOTI_ID = 1001
    const val ACTION_STOP = "com.ajx.botforge.STOP"
    const val ACTION_RESTART_BOTS = "com.ajx.botforge.RELOAD"
    @Volatile var instance: BotService?   // internal set
    fun start(ctx: Context)
    fun stop(ctx: Context)
    fun scheduleRestart(ctx: Context, delayMs: Long)
}
// 实例方法
fun updateStatus(text: String)   // 更新前台通知文案
```

### 3.5 `ai/AiEngine.kt`

```kotlin
fun ping(m: Config.Model): String   // 连通性测试，返回结果文本
```

### 3.6 其余（本次不涉及 UI，但不得破坏）

`qq/BotClient.kt`、`qq/WsClient.kt`、`qq/QqApi.kt`、`qq/MessageHandler.kt`、
`ai/AiEngine.kt`、`ai/Memory.kt`、`ai/WebSearch.kt`、`ai/ImageUtil.kt`、`ai/Intent.kt`、
`ai/tool/AgentLoop.kt`、`ai/tool/BuiltinTools.kt`、`ai/tool/Tool.kt`、
`plugin/PluginGuard.kt`、`plugin/PluginRegistry.kt`、`plugin/PluginRunner.kt`、`plugin/PluginManifest.kt`、
`service/GuardService.kt`、`service/KeepAliveOverlay.kt`、`service/ShizukuKeepAlive.kt`、
`service/BootReceiver.kt`、`service/RestartReceiver.kt`、`data/DiskLog.kt`、`data/SecretStore.kt`、`App.kt`

---

## 四、可废弃的旧 UI 代码

| 文件 | 处置 |
|---|---|
| `ui/BaseActivity.kt` | **整体删除**。194 行 View 辅助方法（title/label/text/textOf/input/button/card/toast/confirm/dp/dpNum/spPx/color）在 Compose 下全部无意义 |
| `ui/MainActivity.kt` | 保留**类名与包名**（Launcher 入口），内部改为 `ComponentActivity` + `setContent` |
| `ui/BotListActivity.kt` | 同上，改为 Compose 壳 |
| `ui/BotEditActivity.kt` | 同上 |
| `ui/GroupListActivity.kt` | 同上 |
| `ui/PersonaListActivity.kt` | 同上 |
| `ui/PersonaEditActivity.kt` | 同上 |
| `ui/ModelListActivity.kt` | 同上 |
| `ui/ModelEditActivity.kt` | 同上 |
| `ui/LogActivity.kt` | 同上 |
| `ui/components/` | 空目录（v1 组件层已删），本次直接移除 |
| `res/drawable/sh_*.xml` (5 个) | Compose 用 Modifier/Shape 实现，删除 |
| `res/drawable/pill_*.xml` (4 个) | 同上，删除 |
| `res/drawable/ic_launcher.xml` | **保留**（应用图标） |
| `res/drawable/ic_stat.xml` | **保留**（通知栏小图标） |
| `res/values/colors.xml` | 内容迁移到 `ui/theme/Color.kt`，XML 版本删除 |
| `res/values/dimens.xml` | 内容迁移到 `ui/theme/Dimens.kt`，XML 版本删除 |
| `res/values/themes.xml` | **保留但改造**：只保留一个 `Theme.BotForge` 启动主题（`windowBackground` + 无 ActionBar + 透明系统栏），供 `setContent` 前的窗口使用 |

---

## 五、发现的两个真实 Bug（顺带修复）

### Bug 1：BotEditActivity 的 Intents 默认值错误（**严重**）

```kotlin
// 现状：表单默认填 1174409216
val intentsEt = input("1174409216", (exist?.intents ?: 1174409216L).toString())
```

而 `Config.Bot` 注释明确：

> 之前用的是 1174409216（额外含 1<<12 频道私信、1<<26 互动、1<<30 频道消息），
> 这三类都需要单独申请权限，必须去掉。

`Config.loadBots()` 已做归一化（强制 `rawIntents and GROUP_INTENTS`），但**表单提交时会用 1174409216 覆盖**，导致保存后再次触发无权限订阅，网关 Identify 后断连。

**修法**：Compose 版直接移除「Intents 手动输入」，改为**只读展示** `GROUP_INTENTS = 33554432`，不可编辑。这是正确做法——该项目唯一需要的就是这一个位，不该给用户改错的机会。

### Bug 2：模型「默认标记」缺失

任务书 4.4 要求模型列表有「默认模型标记」，但 `Config.Model` 无 `isDefault` 字段，且 `Bot` 通过 `modelId` 逐个指定。**当前没有全局默认模型概念**。

**处置**：不虚构字段。列表里改为显示**被引用次数**（`Config.loadBots().count { it.modelId == m.id }`），这是真实可算的信息。

---

## 六、功能缺口（任务书要求但代码不存在）

按任务书第九节「不要虚构尚未实现的功能」，以下页面**不建假入口**：

| 任务书要求 | 代码现状 | 处置 |
|---|---|---|
| 插件管理页 | `PluginRegistry` 有 `all()/find()/manifestOf()`，`PluginRunner.run()` 可执行，但**无插件安装来源、无 UI 曾暴露** | 可做**只读列表**（展示已扫描到的插件），不建"导入"按钮 |
| 插件详情页 | `PluginManifest` 存在，字段可读 | 可做**只读详情**，不做配置表单 |
| 执行权限设置 | `PluginGuard.audit()` 存在，但白名单/绑定码逻辑**未实现** | **不做**，跳过 |
| 终端/任务输出 | `BuiltinTools` 有 shell 执行，但无任务队列、无输出流缓冲 | **不做**，跳过 |
| 审计日志页 | `PluginGuard.audit()` 写 Logger，无独立存储 | 可用 `Logger.snapshot()` 过滤展示，标注"来自运行日志" |
| 记忆管理页 | `Memory` 对象存在，需确认公开接口 | 待确认后决定 |
| 设置中心 | 当前设置散落在首页，需独立成页 | **做**，纯重组现有开关 |

---

## 七、导航架构方案

**现状**：首页 `MainActivity` 是所有入口的 Hub（`navRow` 跳转到 8 个配置页），无底部导航。

**新方案**：保留「Hub + 子页」模型，**不引入底部 Tab 栏**。理由：

1. 现有 10 个页面中，8 个是「配置详情」性质，做成 Tab 无意义
2. 任务书 5.1 明确「不要未经分析就增加底部四栏或五栏导航」
3. 首页已经是运行管理中心，符合任务书 4.1 定位

**导航实现**：`Navigation Compose` + 单 `Activity`（`MainActivity`），其余 9 个 Activity 类**保留为空壳**（`ComponentActivity` + 一行 `setContent`）仅为兼容 `AndroidManifest` 和现有 `Intent` 跳转路径，内部统一走 `NavHost`。

**路由表**：

```
dashboard          首页 Dashboard
bots               机器人列表
bots/edit?id=     机器人编辑（id 可空=新建）
bots/groups?botId= 群管理
personas           人设列表
personas/edit?id=  人设编辑
models             模型列表
models/edit?id=    模型编辑
logs               日志中心
settings           设置中心
plugins            插件列表（只读）
plugins/detail?name= 插件详情（只读）
about              关于
```

---

## 八、Compose 迁移风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| Compose 首次编译慢（CI 每次约 +90s） | 中 | 接受；已确认 CI 有 Gradle 缓存步骤 |
| APK 体积增加约 2.5~3.5MB | 低 | 当前 6.9MB → 预计 9~10MB，可接受 |
| `Build debug` 步骤带 `continue-on-error`，失败的假象 | **高** | 沿用既有准则：只以 `Collect APKs` 为准 |
| 10 个 Activity 同时改造，中间态编译不过 | 高 | **分阶段推送**：每阶段结束必须 CI 绿 + 装机验证，不做大爆炸合并 |
| `SecretStore` 与 Compose 重组冲突 | 低 | ViewModel 层一次性读取，不让 Composable 直接碰 Config |
| 长文本输入（人设 prompt）键盘遮挡 | 中 | `imePadding()` + `verticalScroll` + `bringIntoViewRequester` |
| 手机存储剩余约 7.6G | 中 | 不做 `clean`，增量构建 |

---

## 九、执行计划（Phase 1 ~ 4）

### Phase 1：设计系统 + 导航骨架
- 加 Compose 依赖（BOM 2024.02.00 + compiler 1.5.8 + navigation-compose + viewmodel-compose + lifecycle-runtime-compose）
- `ui/theme/`：`Color.kt` / `Type.kt` / `Shape.kt` / `Dimen.kt` / `Theme.kt`
- `ui/components/`：按任务书 6.1/6.2/6.3 建 30 个组件
- `ui/navigation/`：`Destinations.kt` / `AppNavHost.kt`
- 输出 `docs/UI_DESIGN_SYSTEM.md`
- **验收**：CI 绿 + 装机后首页能显示新的深色骨架

### Phase 2：核心页面（顺序严格按任务书 10.3）
1. Dashboard
2. 机器人列表 / 编辑 / 群管理
3. 人设列表 / 编辑
4. 模型列表 / 编辑
5. 日志中心
6. 设置中心

**每页完成后立即 CI + 装机核对功能。**

### Phase 3：整合
- 统一 Dialog / Snackbar / 空态 / 加载态
- 清理旧 drawable、colors.xml、dimens.xml、BaseActivity
- 权限状态与系统实际状态同步

### Phase 4：验收
- 完整 Debug APK 构建
- 装机逐页核对任务书 10.4 的 15 项功能测试
- 输出最终交付文档

---

## 十、数据兼容性确认

| 项 | 结论 |
|---|---|
| SharedPreferences 文件名 | `botforge`，不变 |
| JSON 字段名 | `id/name/appId/appSecret/personaId/modelId/enabled/intents/groups` 等，不变 |
| 敏感字段保险箱键 | `SecretStore.botKey(id)` / `SecretStore.modelKey(id)`，不变 |
| 全局开关键 | `auto_start` / `keep_alive` / `search_enabled` / `search_trigger` / `agent_enabled` / `log_enabled`，不变 |
| 升级覆盖安装 | UI 层不触碰任何存储键，**旧配置可正常读取，不会丢失** |

---

**审计结论：具备全量 Compose 重写条件，业务层零改动，开始 Phase 1。**
