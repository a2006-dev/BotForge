# BotForge UI 设计系统

> 本文档记录 UI 重构后的设计规范与实际代码位置。
> 所有页面必须复用本文档中的令牌与组件，不得自造颜色、字号、圆角。

## 1. 架构总览

```
ui/
├── theme/            设计令牌（颜色/尺寸/圆角/字体/主题包装）
│   ├── Color.kt      颜色体系
│   ├── Dimen.kt      间距/圆角/字号/行高/尺寸
│   ├── Shape.kt      Material3 Shapes 覆盖
│   ├── Type.kt       Material3 Typography 覆盖
│   └── Theme.kt      BotForgeTheme 入口
├── components/       可复用组件（无业务逻辑）
│   ├── Indicators.kt StatusTone / StatusDot / StatusBadge / AppCard / SectionHeader
│   ├── Buttons.kt    PrimaryButton / SecondaryButton / DangerButton / SmallButton / TextButton / IconTextButton
│   ├── Fields.kt     表单字段 / 密码字段 / 多行输入 / 搜索框
│   ├── Layout.kt     PageTopBar / ListRow / RowGroup / EmptyState / LoadingState / ErrorState / InfoRow / ScreenColumn
│   └── Dialogs.kt    BotDialog / ConfirmDialog / PickDialog / SnackBar
├── navigation/
│   ├── Routes.kt     路由常量与参数构造
│   └── AppNavHost.kt 单 Activity 导航图
├── data/
│   └── BotForgeRepository.kt  UI 层唯一数据入口
├── viewmodel/
│   ├── States.kt          各页面状态数据类
│   ├── BotListViewModel.kt
│   ├── ListViewModels.kt  PersonaList / ModelList
│   └── LogViewModel.kt
└── screens/          页面级 Composable
    ├── DashboardScreen.kt
    ├── BotListScreen.kt
    ├── PersonaListScreen.kt
    ├── ModelListScreen.kt
    ├── LogScreen.kt
    ├── SettingsScreen.kt
    └── PlaceholderScreen.kt
```

**分层约束**

1. `theme` 不依赖 `components`，`components` 不依赖 `screens`。
2. `screens` 只通过 `viewmodel` 取数据，不直接调用 `Config` / `Logger`
   （例外：`SettingsScreen` 直接读写 `Config` 的 6 个全局开关，因为它们是
   无状态、无并发风险的简单属性，加一层 ViewModel 只会增加样板）。
3. `data` 层只调用既有业务静态接口，不修改业务逻辑。

## 2. 颜色（Color.kt）

### 背景层级

| 令牌 | 色值 | 用途 |
|---|---|---|
| `Bg0` | `#0D1117` | 窗口最底层、系统栏、首页品牌区 |
| `Bg1` | `#151A22` | 页面背景 |
| `Bg2` | `#1C2330` | 卡片、分组容器 |
| `Bg3` | `#232B39` | 输入框、日志行 |
| `Bg4` | `#2C3546` | 按下态、关闭状态的开关轨道 |

**规则**：层次只靠背景明度拉开，不叠加阴影。卡片允许 1px `Line` 描边。

### 文字

| 令牌 | 色值 | 用途 |
|---|---|---|
| `TextHigh` | `#F0F2F5` | 标题、主要信息 |
| `TextMid` | `#929BAA` | 副标题、说明 |
| `TextLow` | `#626D7C` | 辅助信息、占位符 |
| `TextInverse` | `#0D1117` | 深底上的文字（品牌色按钮内） |

### 品牌

| 令牌 | 色值 | 用途 |
|---|---|---|
| `Brand` | `#6B96F5` | 主按钮、选中态、焦点边框 |
| `BrandPressed` | `#4F7EE0` | 按下态 |
| `BrandDisabled` | `#2E3849` | 禁用态 |
| `BrandGhost` | `#1A6B96F5` | 品牌色 10% 透明底 |

**克制原则**：品牌蓝只出现在主操作与选中态，不用于装饰性元素。

### 状态

| 语义 | 主色 | Ghost 底 | 用途 |
|---|---|---|---|
| Success | `#4ECB8C` | `#1F4ECB8C` | 已启用、运行中、密钥已配置 |
| Warning | `#E8A94C` | `#1FE8A94C` | 未绑定人设/模型、密钥未配置 |
| Danger | `#E8696F` | `#1FE8696F` | 删除、错误日志 |
| Neutral | `#5A6474` | `#1F5A6474` | 已禁用、未使用 |
| Info | `#5AA9E6` | `#1F5AA9E6` | 引用计数、选中筛选 |

### 分割线

| 令牌 | 色值 | 用途 |
|---|---|---|
| `Line` | `#232A36` | 常规分割线 |
| `LineStrong` | `#323B4B` | 输入框默认描边 |
| `Scrim` | `#B3000000` | 遮罩 |

## 3. 尺寸（Dimen.kt）

### 间距 `Spacing`

`xxs 2` / `xs 4` / `sm 8` / `md 12` / `lg 16` / `xl 20` / `xxl 24` / `huge 32`

**规则**：页面外边距统一 `md(12)`，卡片内边距 `md(12)`，卡片之间 `md(12)`。
不用奇数间距。

### 圆角 `Radius`

`xs 6` / `sm 8` / `md 12` / `lg 16` / `xl 20` / `full 999`

**规则**：卡片 `lg(16)`，分组容器 `lg`，输入框 `md(12)`，按钮由 `Shape.kt` 统一。

### 字号 `FontSize`

| 令牌 | 值 | 用途 |
|---|---|---|
| `micro` | 10sp | 日志时间戳、极小标注 |
| `caption` | 11sp | 状态标签、辅助说明 |
| `small` | 12sp | 副标题、说明文字 |
| `body` | 14sp | 正文、列表项标题 |
| `bodyLarge` | 16sp | 强调正文 |
| `title` | 18sp | 卡片标题、页面标题 |
| `heading` | 22sp | 区块大标题 |
| `display` | 28sp | 首页品牌名 |
| `metric` | 40sp | 仪表盘数值 |

### 尺寸 `Sizes`

`buttonHeight 50` / `buttonSmall 38` / `rowMin 56` / `inputMin 52` /
`inputMultiline 160` / `dot 9` / `hairline 1` / `icon 20` / `iconLarge 24` /
`topBar 56` / `touchTarget 48`

## 4. 组件 API

### Indicators.kt

```kotlin
enum class StatusTone { Success, Warning, Danger, Neutral, Info }
    // 每个枚举项自带 fg / bg

StatusDot(tone, modifier, size = Sizes.dot)
StatusBadge(text, tone, modifier)          // 胶囊状态标签
StatusIndicator(text, tone, modifier)      // 点 + 文字
AppCard(modifier, bordered = false, background = Bg2, content: ColumnScope.() -> Unit)
SectionHeader(text, modifier)              // 区块小标题
```

### Buttons.kt

```kotlin
PrimaryButton(text, modifier, enabled = true, loading = false, icon, onClick)
SecondaryButton(text, modifier, enabled = true, icon, onClick)
DangerButton(text, modifier, enabled = true, onClick)     // 实心危险
SmallButton(text, modifier, primary = false, enabled = true, onClick)  // 38dp 高
TextButton(text, modifier, color = Brand, enabled = true, onClick)    // 纯文字
IconTextButton(icon, contentDescription, modifier, tint, enabled, onClick)
```

**规则**：一屏最多一个 `PrimaryButton`。破坏性操作（删除）用 `TextButton(color = Danger)`
或 `DangerButton`，不用品牌色。

### Fields.kt

```kotlin
FormField(label, value, onValueChange, ...)        // 单行，带 label 与错误提示
AppTextField(...)                                  // 基础输入
PasswordField(label, value, onValueChange, ...)    // 自带显隐切换
MultilineField(label, value, onValueChange, minHeight = Sizes.inputMultiline)
SearchField(value, onValueChange, placeholder)     // 带清除按钮
```

**规则**：所有输入框默认 `Bg3` 底 + `LineStrong` 描边，聚焦变 `Brand`，
错误变 `Danger`。敏感字段默认打码。

### Layout.kt

```kotlin
PageTopBar(title, modifier, subtitle, showBack = true, onBack, actions)
ListRow(title, modifier, subtitle, leading, trailing, onClick)
RowGroup(modifier, content)      // 卡片内多行分组
RowDivider(modifier, indent)
EmptyState(title, modifier, description, icon, action)
LoadingState(modifier, message)
ErrorState(message, modifier, onRetry)
InfoRow(label, value, modifier, valueColor, mono)
ScreenColumn(modifier, verticalArrangement, content)
```

### Dialogs.kt

```kotlin
BotDialog(onDismiss, modifier, title, content)      // 自绘外壳
ConfirmDialog(title, message, confirmText, dismissText, danger, onConfirm, onDismiss)
PickDialog(title, options, selectedIndex, onPick, onDismiss)
SnackBar(message, modifier, tone)
```

**规则**：不使用 Material `AlertDialog`，避免其默认排版与主题色干扰。

## 5. 页面规范

### 抽屉式导航策略

不引入底部 Tab（本项目 10 个页面中 8 个是配置详情，Tab 会造成层级混乱）。
采用 **单 Activity + Navigation Compose + Hub 页**：

- `dashboard` 作为首页 Hub，提供分组入口
- 详情页通过 `popBackStack()` 返回
- Manifest 中其余 8 个旧 Activity 保留空壳，仅作兼容

### 每页必须包含

1. `PageTopBar`（标题 + 可选副标题 + 返回）
2. 三态处理：`LoadingState` / `ErrorState` / 内容
3. 空态用 `EmptyState`，带可操作按钮
4. 列表用 `LazyColumn`，`key` 必须稳定（用数据 id）

### 禁用行为

- 不显示虚构数据
- 不放「点了没反应」的假入口
- 不在 UI 层明文展示 `appSecret` / `apiKey`
- 不自行拼接业务字段（如 intents），只读展示

## 6. 数据层约定

`BotForgeRepository` 是 UI 唯一数据入口：

```kotlin
bots() / findBot(id) / saveBot(bot) / deleteBot(id) / setBotEnabled(id, enabled)
saveGroups(botId, groups)
personas() / findPersona(id) / savePersona(p) / deletePersona(id)
models() / findModel(id) / saveModel(m) / deleteModel(id) / modelRefCount(id)
logs() / clearLogs()
serviceStatus() / runningCount() / reloadBots(context)
```

全部 `suspend` 函数在 `Dispatchers.IO` 执行，返回不可变 `List`。

**敏感字段**：`appSecret` / `apiKey` 由 `Config` + `SecretStore` 处理，
`loadXxx()` 返回真值、`saveXxx()` 自动加密并落占位符。
Repository 不做加解密，UI 只需默认打码。

## 7. 已知限制

| 项 | 说明 |
|---|---|
| 模型「默认」标记 | `Config.Model` 无此字段，改为显示被引用次数 |
| 主题父级 | `Theme.BotForge` 仍继承 `Theme.AppCompat.NoActionBar`，Compose 页面不受影响 |
| 旧 Activity | 8 个旧页面仍在仓库中，已无法从新 UI 进入，待逐个删除 |
| 图标 | 未引入自定义图标库，语义图标用 `material-icons-extended`，装饰性符号用文字 |
