# 工具包规范（.bfpack v2）

本文定义 BotForge 扩展包 v2 的目录结构、清单字段、能力声明、权限模型与分发格式。

- 规范版本：`api = 2`
- 代码基准：`app/src/main/java/com/ajx/botforge/pkg/PackageSpec.kt`、`app/src/main/java/com/ajx/botforge/pkg/PackageManifestReader.kt`、`app/src/main/java/com/ajx/botforge/plugin/BfpackImporter.kt`
- 本文的字段名、枚举值、默认值与阈值均逐条取自上述源码；与源码注释不一致时，以代码行为为准并在文中注明

---

## 1. 概览：为什么需要 v2

v1 时期仓库里同时存在两套互不相识的包：

- **指令包**：清单 `plugin.json`，模型 `com.ajx.botforge.plugin.PluginManifest`，提供 QQ 斜杠指令
- **工具包**：清单 `tool.json`，模型 `com.ajx.botforge.tools.ToolManifest`，提供 App 内的参数表单动作

这套结构有三个问题。

**问题一：两套清单位于同一类目录，靠文件名区分。** 用户的 `plugins/` 目录下既可能躺着 `plugin.json` 包，也可能躺着 `tool.json` 包，格式判定只能依赖目录里出现了哪个文件名。同一个目录里同时存在两份清单时，v1 没有任何仲裁规则。

**问题二：一个包不能同时提供指令和 AI 工具。** v1 的两个解析器各自只认自己那一种能力，指令包解析不出 App 内表单，工具包解析不出斜杠指令。要同时提供两种能力，只能装两个包，并让两者共享目录、重复脚本。

**问题三：v1 解析器硬性要求指令非空。** `PluginManifest.parse()` 里有 `if (cmds.isEmpty()) return null`，`BfpackImporter.preview()` 也依赖这个判据。于是一个「只提供 AI 工具」「只提供 App 内面板」的包在 v1 里根本无法安装——而这两种恰恰是最自然的形态。

v2 的做法是把三种清单收敛成同一份模型 `PackageSpec`：

- 新格式只读 `manifest.json`，三类能力 `commands` / `ai_tools` / `ui_actions` 可任意组合，允许其中两类为空甚至只有一类非空
- 「三类全空」这种真正无意义的包由读取层 `PackageManifestReader.read()` 统一拒绝，而不是由模型构造器拦截
- 权限独立建模为 `permissions`，每项带风险等级，用于设置页排序与展示
- 包的来源记在 `Origin`（`V1_PLUGIN` / `V1_TOOL` / `V2`），旧包按兼容分支读成同一份模型后仍然如实标记来源

> 现状说明：`pkg/PackageSpec.kt` 与 `pkg/PackageManifestReader.kt` 已实现，但仓库中没有其他代码 `import com.ajx.botforge.pkg`，即 v2 读取器尚未接入导入流程与界面；`.bfpack` 导入器 `BfpackImporter` 目前仍然只认 `plugin.json`（见第 7 节）。本文描述的是 v2 的**目标规范**与**已实现的读取语义**，两者在本文覆盖的范围内一致。

---

## 2. 包结构

一个包就是一个目录，目录名即包 id。

```
plugins/
└── com.example.echo/        ← 目录名 = manifest.json 的 id
    ├── manifest.json        ← 唯一必需文件
    ├── main.sh              ← commands 的默认入口
    ├── run.sh               ← ai_tools / ui_actions 的默认入口
    └── ...                  ← 其余文件随意，脚本可用相对路径引用
```

清单探测顺序由 `PackageManifestReader.read(dir)` 固定为：

1. `manifest.json`（v2）
2. `plugin.json`（v1 指令包）
3. `tool.json`（v1 工具包）

三者都不存在时返回失败：「目录里没有 manifest.json，也没有 plugin.json 或 tool.json，无法判断这是什么格式的包。」

目录里同时存在多份清单时，以探测顺序靠前的那份为准，其余每份各产生一条警告：「目录里同时存在 manifest.json 与 plugin.json，已按 manifest.json 读取，另一份请删掉以免混淆。」

| 文件 | 必需 | 说明 |
| --- | --- | --- |
| `manifest.json` | 是 | UTF-8 编码的 JSON 对象；缺少 `id` 直接失败 |
| 入口脚本 | 否（读取期不校验） | 由各能力的 `entry` 字段指定，默认 `main.sh` 或 `run.sh` |
| 其他资源 | 否 | 脚本用相对路径引用；读取层不限制文件类型与数量 |

> 读取层当前不检查入口脚本是否存在。`PackageManifestReader` 的类注释把「入口文件不存在」列为警告之一，但 `readCommands` / `readAiTools` / `readUiActions` 都没有做这项检查，`warnings` 实际只会产出四类内容：清单被遮蔽、旧协议、未知权限标签、v1 迁移提示。入口缺失要到执行期才会表现为运行失败。

---

## 3. manifest.json 完整字段表

顶层字段共 13 个来自 JSON，外加 1 个运行时填充字段。

| JSON 字段 | 模型字段（`PackageSpec`） | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `id` | string | **是** | 无 | 包唯一标识，同时作为目录名。缺失或空白即失败（「manifest.json 缺少 id 字段」） |
| `name` | `name` | string | 否 | 等于 `id` | 显示名。读取时按 `optString("name", id).ifBlank { id }` 处理，空串回落到 `id` |
| `version` | `version` | string | 否 | `"0.0.0"` | 语义化版本。空串回落 `"0.0.0"` |
| `api` | `api` | int | 否 | `2` | 协议版本，默认取 `PackageSpec.CURRENT_API` |
| `author` | `author` | string | 否 | `""` | 作者 |
| `description` | `description` | string | 否 | `""` | 一句话说明 |
| `homepage` | `homepage` | string | 否 | `""` | 项目主页或源码地址 |
| `license` | `license` | string | 否 | `""` | 许可证标识，建议 SPDX 短名 |
| `runtime` | `runtime` | string | 否 | `"android_sh"` | 运行时。JSON 中是字符串，模型中是 `Runtime` 枚举；解析见第 5 节 |
| `commands` | `commands` | array | 否 | `[]` | QQ 斜杠指令列表，元素见 3.1 |
| `ai_tools` | `aiTools` | array | 否 | `[]` | 暴露给模型的工具列表，元素见 3.2。**JSON 字段名是 `ai_tools`** |
| `ui_actions` | `uiActions` | array | 否 | `[]` | App 内面板动作列表，元素见 3.3。**JSON 字段名是 `ui_actions`** |
| `permissions` | `permissions` | array | 否 | `[]` | 权限声明列表，元素见 3.4 与第 6 节 |
| （无） | `dir` | `File` | — | `File(".")` | 运行时填充的包目录，不来自 JSON |

字段名约定：JSON 一律使用 snake_case（`ai_tools`、`ui_actions`），Kotlin 模型使用 camelCase（`aiTools`、`uiActions`）。其余 11 个字段两种写法相同。写成 `aiTools` 不会被识别——解析器只读 `ai_tools`，该字段会被静默忽略。

顶层其余字段（`origin`、`capabilities`、`timeoutSec`）均不由清单提供：

- `origin` 由读取路径决定（读 `manifest.json` 恒为 `Origin.V2`）
- `capabilities` 是计算属性，按「指令、AI工具、面板」的固定顺序输出非空能力
- `PackageCommand.timeoutSec` 是计算属性，不来自 JSON

### 3.1 commands 元素

| 字段 | 模型字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `command` | `command` | string | **是** | 无 | 指令名。读取时 `trim()` → 去掉前缀 `/` → 再 `trim()` → 转小写；结果为空则该条被**跳过**（不导致整包失败） |
| `description` | `description` | string | 否 | `""` | 说明 |
| `usage` | `usage` | string | 否 | `"/<command>"` | 用法示例；默认值用转小写后的指令名拼接 |
| `entry` | `entry` | string | 否 | `"main.sh"` | 入口脚本，相对包目录。空白时回落 `"main.sh"` |
| `timeout` | `timeout` | int | 否 | `15` | 超时秒数。**声明值原样保存**，真正生效的是 `timeoutSec`，钳在 `1..120` |
| `scope` | `scope` | array 或 string | 否 | `["c2c"]` | 允许触发的场景，见下方解析规则 |
| `confirm` | `confirm` | bool | 否 | `false` | 是否需要二次确认 |

`scope` 的解析规则（与 v1 `PluginManifest.parseScope` 同义）：

1. 若 `scope` 是数组：逐项 `trim().lowercase()`，丢弃空项；结果非空即采用
2. 否则按字符串处理：`both` → `["c2c", "group"]`；`group` → `["group"]`；`c2c` → `["c2c"]`
3. 其余（含缺失）→ 默认 `["c2c"]`，即只允许私聊

超时钳制常量：`MIN_TIMEOUT_SEC = 1`、`MAX_TIMEOUT_SEC = 120`、`DEFAULT_TIMEOUT_SEC = 15`。清单里写 `0` 或 `999` 不会报错，但生效值是 `1` 和 `120`。

### 3.2 ai_tools 元素

| 字段 | 模型字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `name` | `name` | string | **是** | 无 | 工具名。`trim()` 后为空则该条被跳过 |
| `description` | `description` | string | 否 | `""` | 给模型看的说明 |
| `parameters` | `parametersJson` | 任意 JSON | 否 | `{}` | JSON Schema 原文。模型层存的是**字符串**（`parametersJson`），读取层用 `params.toString()` 序列化；缺失时为 `"{}"`。代码不校验其结构 |
| `permission` | `permission` | string | 否 | `"ALLOW"` | 三档 `ALLOW` / `ASK` / `DENY`，大小写宽容；**未知写法一律归为 `ALLOW`** |
| `entry` | `entry` | string | 否 | `"run.sh"` | 入口脚本，空白时回落 `"run.sh"` |

### 3.3 ui_actions 元素

| 字段 | 模型字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `id` | `id` | string | **是** | 无 | 动作 id。`trim()` 后为空则该条被跳过 |
| `label` | `label` | string | 否 | 等于 `id` | 按钮文案，空串回落 `id` |
| `description` | `description` | string | 否 | `""` | 说明 |
| `entry` | `entry` | string | 否 | `"run.sh"` | 入口脚本，空白时回落 `"run.sh"` |
| `params` | `params` | array | 否 | `[]` | 参数声明，元素见 3.4 |

### 3.4 params 元素

`params` 同时用于 `ui_actions`，字段与 v1 `ToolParam` 一一对齐。

| 字段 | 模型字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `name` | `name` | string | **是** | 无 | 参数名。`trim()` 后为空则跳过该条 |
| `label` | `label` | string | 否 | 等于 `name` | 表单标签，空串回落 `name` |
| `type` | `type` | string | 否 | `"text"` | 常量取值 `text` / `number` / `boolean` / `select`；读取层不做取值校验，空白时回落 `"text"` |
| `default` | `default` | string | 否 | `""` | 默认值 |
| `required` | `required` | bool | 否 | `false` | 是否必填 |
| `hint` | `hint` | string | 否 | `""` | 提示文字 |
| `options` | `options` | array | 否 | `[]` | `select` 的候选值；读取时丢弃空字符串项 |

### 3.5 permissions 元素

`permissions` 支持两种写法，读取层会自动识别：

```json
"permissions": ["network", "storage_read"]
```

```json
"permissions": [
  { "tag": "network", "reason": "联网查询天气" }
]
```

| 字段 | 模型字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tag` | `tag` | string | **是** | 无 | 必须是第 6 节的枚举字符串；无法识别时该条被跳过并产生警告 |
| `reason` | `reason` | string | 否 | `""` | 面向用户的理由文本，原样展示 |

---

## 4. 四种能力

四类声明彼此独立，可任意组合，但三类能力不能同时为空。

### 4.1 commands：QQ 斜杠指令

```json
"commands": [
  {
    "command": "echo",
    "description": "把参数原样回显",
    "usage": "/echo <文本>",
    "entry": "main.sh",
    "timeout": 20,
    "scope": ["c2c", "group"],
    "confirm": false
  }
]
```

指令名不带斜杠、全小写。`scope` 缺省只允许私聊：群里触发破坏性动作的代价比私聊大一个量级。

### 4.2 ai_tools：暴露给模型的工具

```json
"ai_tools": [
  {
    "name": "get_weather",
    "description": "查询指定城市的当前天气",
    "parameters": {
      "type": "object",
      "properties": {
        "city": { "type": "string", "description": "城市名" }
      },
      "required": ["city"]
    },
    "permission": "ASK",
    "entry": "run.sh"
  }
]
```

`parameters` 建议按 JSON Schema 写，但读取层只做原样保存，不做校验。`permission` 为 `ASK` 时表示每次调用都需要用户确认。

### 4.3 ui_actions：App 内面板动作

```json
"ui_actions": [
  {
    "id": "run",
    "label": "立即执行",
    "description": "在面板上手动执行一次",
    "entry": "run.sh",
    "params": [
      {
        "name": "target",
        "label": "目标地址",
        "type": "text",
        "default": "example.com",
        "required": true,
        "hint": "主机名或完整 URL"
      },
      {
        "name": "mode",
        "label": "模式",
        "type": "select",
        "default": "fast",
        "required": false,
        "hint": "",
        "options": ["fast", "full"]
      }
    ]
  }
]
```

v1 的 `tool.json` 包在兼容路径上会被整包折叠成这个形态的一条动作，`id` 固定为 `run`（见第 8 节）。

### 4.4 permissions：权限声明

```json
"permissions": [
  { "tag": "network", "reason": "联网查询天气" },
  { "tag": "storage_write", "reason": "把结果写入下载目录" }
]
```

读取完成后，`permissions` 列表会按 `risk` **降序**重排（`sortedByDescending`，稳定排序，同档内保持作者声明顺序），设置页顺序渲染即可把最该看的排在最前。

---

## 5. runtime 字段

`runtime` 声明这份脚本靠什么跑，模型中是枚举 `Runtime`，JSON 中是字符串。

| 枚举值 | JSON 写法（任一） | 含义 |
| --- | --- | --- |
| `ANDROID_SH` | `android_sh`、`android-sh`、`android`、`sh` | 交给系统 `sh` 直接执行，不起容器 |
| `PROOT` | `proot` | 在 Alpine proot 环境内执行，可调用 `apk` 安装的二进制 |

解析规则（`Runtime.parse`）：

1. 大小写、首尾空白都宽容（`"  PROOT  "` → `PROOT`）
2. 未知值、空串、缺失一律回落到默认 `ANDROID_SH`，**不报错**。一个字段拼错就让整个包装不上，对用户是纯粹的挫折
3. 该函数支持显式传入 `fallback`，但读取层调用时未传，因此实际回落值恒为 `ANDROID_SH`

选择建议：

- **默认用 `android_sh`**。绝大多数指令只是拼一段文本回消息，为它拉起容器不值得——系统 `sh` 冷启动是几十毫秒级
- 只有脚本明确依赖 `apk` 安装的包（`curl`、`python3`、`node` 之类）时才用 `proot`，代价是要先用 proot rootfs，且首次启动明显更慢
- v1 迁移的取舍与此一致：`plugin.json` 固定 `ANDROID_SH`（当年就是用系统 `sh` 跑 `main.sh`，不能凭空要求用户先装 rootfs），`tool.json` 固定 `PROOT`（入口默认 `run.sh`，当年在 Alpine 里跑，可能调用 `apk` 装出来的二进制）

> 现状说明：`PackageSpec.runtime` 目前只在模型与读取层存在，仓库中没有任何执行路径读取该字段（`grep` 全仓无 `.runtime` 使用点）。也就是说 `runtime: "proot"` 现在只是被正确解析并保存，尚未真正切换到 proot 执行分支。

---

## 6. 权限（permissions）

权限标签是枚举 `PermissionTag`，共 6 个，每项带 `risk` 值（`0..3`）。

| 枚举 | JSON 字符串 | risk | 风险含义 |
| --- | --- | --- | --- |
| `NETWORK` | `network` | 1 | 只读外界：联网 |
| `STORAGE_READ` | `storage_read` | 1 | 只读外界：读文件 |
| `STORAGE_WRITE` | `storage_write` | 2 | 改变设备状态：写文件 |
| `SHIZUKU` | `shizuku` | 2 | 改变设备状态：借 Shizuku 执行 |
| `LINUX_ROOT_CMD` | `linux_root_cmd` | 3 | 取得 root 级能力 |
| `SENSITIVE_DATA` | `sensitive_data` | 3 | 读取敏感数据 |

`risk` 的四档语义（取自枚举定义）：

- `0` 无副作用（纯计算）
- `1` 只读外界（联网、读文件）
- `2` 会改变用户设备状态（写文件、借 Shizuku 执行）
- `3` 能拿到 root 级能力或读取敏感数据

当前枚举中没有 `risk = 0` 的成员。用排序值而不是布尔值，是为了让设置页能把最该看的排在前面，也让「有任意一项 `risk >= 2` 就默认不启用」这类策略有依据。

解析行为：

- 大小写无关，首尾空白宽容（`"  NETWORK  "` 可解析）
- 未知标签返回 `null` 并**跳过该条**，同时记录警告：「忽略了无法识别的权限标签：xxx」。不整包拒绝的理由是权限是加法——漏掉一条按「未知即风险」保守处理即可，但整包装不上会让用户连本来能用的功能都拿不到
- 权限项写错位置（例如把标签直接写成顶层字段）不会被识别，也不会报错

> 现状说明：`PermissionDecl.reason` 为空时，读取层直接保留空串，没有补默认文案。模型层注释提到「为空时由读取层补默认文案」，代码中未实现，界面需要自行处理空理由。

---

## 7. .bfpack 打包

`.bfpack` 是一个标准 **zip** 文件，导入器为 `com.ajx.botforge.plugin.BfpackImporter`。选 zip 而不是 tar/tar.gz 的原因：系统文件选择器能直接识别、`java.util.zip` 零依赖。

### 7.1 体积上限

| 常量 | 数值 | 触发后的错误 |
| --- | --- | --- |
| `MAX_TOTAL_BYTES` | `256L * 1024 * 1024` = 268435456 字节（256 MB） | 「解压后总体积超过 256MB」 |
| `MAX_FILE_BYTES` | `32L * 1024 * 1024` = 33554432 字节（32 MB） | 「包内单个文件超过 32MB：<条目名>」 |

限制在解压写入过程中逐块校验，超限抛 `IllegalStateException`，从而阻断 zip bomb。正常插件是几 KB 到几百 KB 脚本，上限相当宽松。

### 7.2 路径规则

每个 zip 条目名先过 `isSafeEntryName`，不通过则抛 `SecurityException("包内包含非法路径：<name>")`：

- 空名拒绝
- 以 `/` 或 `\` 开头（绝对路径）拒绝
- 第 2 个字符是 `:`（`C:\` 之类的盘符）拒绝
- 把 `\` 归一成 `/` 后按 `/` 切段，任何一段等于 `..` 就拒绝

通过名称校验后还有一次 canonical 校验：解析完符号链接与 `..` 之后，目标路径必须仍位于暂存目录内，否则抛 `SecurityException("包内路径越界：<name>")`。

另外两类系统垃圾会被静默跳过，不计数：`__MACOSX/` 开头的条目、以 `.DS_Store` 结尾的条目。

### 7.3 包 id 规则

`commit()` 与 `uninstall()` 都拿 `id` 去拼 `File(root, id)`，两处共用同一套规则。v2 把规则公开成了 `packageIdProblem(id): String?`（返回中文原因，合法返回 `null`）与 `isSafePackageId(id)`：

| 条件 | 返回原因 |
| --- | --- |
| 空白 | 包 id 不能为空 |
| 首尾有空白 | 包 id 首尾不能有空白字符 |
| 含 `/` | 包 id 不能包含斜杠，否则会被拼成越界路径 |
| 含 `\` | 包 id 不能包含反斜杠 |
| 含 `..` | 包 id 不能包含两个连续的点 |
| 以 `.` 开头 | 包 id 不能以点开头，会与隐藏目录混淆 |

注意 `packageIdProblem` 只拦上述六类，**中间含空格**（如 `x y`）可以通过校验——代码没有字符白名单。

### 7.4 导入流程

导入分两步：

1. `preview(pack, targetRoot)`：解压到 `targetRoot/.staging_<时间戳>`，解析清单，返回 `Preview`。`blockReason` 为 `null` 表示可以安装；非 `null` 是拒绝原因。`warnings` 不影响安装。无论成败，`finally` 都删除暂存目录
2. `commit(pack, targetRoot, expectedId)`：**重新解压一次**，确认清单里的 `id` 与 `expectedId` 一致（防「确认页看的是 A、装的是 B」），再落盘

落盘细节：目标已存在时，先把旧目录改名为 `.backup_<id>`，再把暂存目录改名为正式目录，成功则删备份；改名失败则回滚到原版本并返回「写入失败，已回滚到原版本。」

`preview` 阶段会做的检查与产生的警告：

- 解压统计：返回 `(总字节, 文件数)` 供确认页展示
- id 冲突：「已存在同 id 的包（xxx），继续安装会覆盖它。」
- 指令入口缺失：「指令 /xxx 声明的入口 yyy 不在包里，运行时会失败。」

### 7.5 现阶段的格式限制

`BfpackImporter` 目前把清单文件名硬编码为 `plugin.json`，并用 `PluginManifest.parse()` 解析：

- 包里没有 `plugin.json` → 「包里没有 plugin.json」
- `MAX_API` 直接取 `PluginManifest.CURRENT_API`，其值为 **1**
- 因为 v1 解析器要求指令非空，纯 `ai_tools` / 纯 `ui_actions` 的包会被判「manifest 字段不完整」

结论：**v2 的 `manifest.json` 包目前不能通过 `.bfpack` 导入**。打包成 v2 形态的包需要等导入器切到 `PackageManifestReader`（该类的注释已把这条列为后续动作）。在此之前，`.bfpack` 的实际含义仍是「v1 指令包的单文件封装」。

---

## 8. v1 兼容

`PackageManifestReader` 把三份清单统一读成 `PackageSpec`，来源记在 `Origin`。

| 清单 | Origin | runtime | 能力落点 | 关键约束 |
| --- | --- | --- | --- | --- |
| `manifest.json` | `V2` | 按 `runtime` 字段解析 | commands + ai_tools + ui_actions | 三类全空即失败 |
| `plugin.json` | `V1_PLUGIN` | 固定 `ANDROID_SH` | commands | 指令为空即失败 |
| `tool.json` | `V1_TOOL` | 固定 `PROOT` | 折叠成一条 ui_action | — |

### 8.1 plugin.json → commands

读取规则：

- `id` 缺失 → 失败「plugin.json 缺少 id 字段」
- `commands` 数组缺失 → 失败「plugin.json 缺少 commands 数组」
- `commands` 解析后为空 → 失败「plugin.json 里没有一条有效指令。纯 AI 工具包请改用 manifest.json 描述。」
  v1 的 registry 按指令建索引，零指令的 v1 包在旧路径上本来就不存在，放行只会制造「装上了但列表里什么都没有」的幽灵条目
- `api` 默认 `1`，因此必然触发 `ApiCheck.LEGACY`，产生警告「该包使用旧协议 v1，已按兼容模式读取；建议作者升级到 v2。」
- `runtime` 固定 `ANDROID_SH`
- 恒有一条迁移提示：「这是 v1 指令包，已按兼容模式导入；建议迁移到 manifest.json。」

字段映射与 v1 `PluginManifest` 的差异：v1 有 `name` / `version` / `api` / `author` / `description` / `commands` / `dir`，全部有对应字段；v1 解析时对 `timeout` 直接 `coerceIn(1, 120)`，v2 读取层保存原值并在 `timeoutSec` 计算属性里钳制，因此**经 v2 读取后 `PackageCommand.timeout` 可能是 0 或 999，而生效值仍是 1..120**。

### 8.2 tool.json → 一条 ui_action

读取规则：

- `id` 缺失 → 失败「tool.json 缺少 id 字段」
- 整个包折叠成**一条** `PackageUiAction`，`id` 固定为私有常量 `RUN_ACTION_ID = "run"`
- `label` 取 `name`（空则取 `id`），`description` 取 `description`，`entry` 取 `entry`（默认 `run.sh`），`params` 取 `params`
- `runtime` 固定 `PROOT`：入口默认 `run.sh`，当年就在 Alpine 里跑，可能调用 `apk` 装出来的二进制；谎称能用系统 `sh` 跑，装完就是一片 `command not found`
- `api` 默认 `1` → `LEGACY` 警告
- 恒有一条迁移提示：「这是 v1 工具包，已按兼容模式导入为一条面板动作；建议迁移到 manifest.json。」

已知信息损失，转为警告而不是硬塞进不相干字段：

| v1 字段 | 现状 | 处理 |
| --- | --- | --- |
| `timeoutSec`（默认 `60`） | `PackageSpec` 与 `PackageUiAction` 都没有超时字段 | 丢弃，不警告（代码未就此产生提示） |
| `risky`（默认 `false`） | 模型里没有对应字段 | 为真时追加警告「该包在原 tool.json 里声明了 risky，启用前请确认它要做什么。」 |

> 之所以不把 `risky` 编码成一条假权限，是因为那会让设置页显示作者从未声明的权限项。
> 注意 `PackageUiAction` 本身没有 `timeout` 字段，所以 v1 工具包迁移后**超时时间不再可表达**，这不是疏漏而是当前模型的字段缺口。

### 8.3 v1 字段对照

`tool.json`（`ToolManifest`）字段：`id`、`name`、`version`、`description`、`entry`、`author`、`homepage`、`license`、`api`、`params`、`timeoutSec`、`risky`。

`plugin.json`（`PluginManifest`）字段：`id`、`name`、`version`、`api`、`author`、`description`、`commands`、`dir`；`commands` 元素字段：`command`、`description`、`usage`、`entry`、`timeout`、`scope`、`confirm`。

`tool.json` 的 `params` 元素（`ToolParam`）字段：`name`、`label`、`type`、`default`、`required`、`hint`、`options`——与 v2 `PackageParam` 逐一相同，因此 v1 参数迁移不缺字段。

---

## 9. 完整可运行示例

目录：

```
plugins/com.example.echo/
├── manifest.json
├── main.sh
└── run.sh
```

`manifest.json`（同时具备 commands 与 ai_tools，另带一条 ui_action 与两项权限）：

```json
{
  "id": "com.example.echo",
  "name": "回声与时间",
  "version": "1.0.0",
  "api": 2,
  "author": "example",
  "description": "演示 v2 包：一条斜杠指令、一个 AI 工具、一条面板动作与两项权限声明",
  "homepage": "https://example.com/echo",
  "license": "Apache-2.0",
  "runtime": "android_sh",
  "commands": [
    {
      "command": "echo",
      "description": "把参数原样回显",
      "usage": "/echo <文本>",
      "entry": "main.sh",
      "timeout": 20,
      "scope": ["c2c", "group"],
      "confirm": false
    }
  ],
  "ai_tools": [
    {
      "name": "get_time",
      "description": "读取当前时间",
      "parameters": {
        "type": "object",
        "properties": {
          "timezone": {
            "type": "string",
            "description": "IANA 时区名，例如 Asia/Shanghai"
          }
        },
        "required": []
      },
      "permission": "ASK",
      "entry": "run.sh"
    }
  ],
  "ui_actions": [
    {
      "id": "run",
      "label": "立即执行",
      "description": "在面板上手动执行一次",
      "entry": "run.sh",
      "params": [
        {
          "name": "target",
          "label": "目标地址",
          "type": "text",
          "default": "example.com",
          "required": true,
          "hint": "主机名或完整 URL",
          "options": []
        },
        {
          "name": "mode",
          "label": "模式",
          "type": "select",
          "default": "fast",
          "required": false,
          "hint": "",
          "options": ["fast", "full"]
        }
      ]
    }
  ],
  "permissions": [
    { "tag": "network", "reason": "查询天气与时间需要联网" },
    { "tag": "storage_read", "reason": "读取本地缓存文件" }
  ]
}
```

`main.sh`：

```sh
#!/bin/sh
# 回显指令参数。参数由应用以环境变量注入，工作目录为包目录本身。
TEXT="${BF_PARAM_text:-}"
if [ -z "$TEXT" ]; then
    echo "用法：/echo <文本>"
    exit 1
fi
echo "$TEXT"
```

`run.sh`：

```sh
#!/bin/sh
# AI 工具与面板动作共用入口。BF_PARAM_* 之外的输入由调用方按需注入。
TARGET="${BF_PARAM_target:-example.com}"
echo "target=$TARGET"
date -u +"%Y-%m-%dT%H:%M:%SZ"
```

读取该包的结果：`origin = V2`、`runtime = ANDROID_SH`、`capabilities = ["指令", "AI工具", "面板"]`、权限按风险降序排列为 `storage_read`(1) 与 `network`(1)（同档保持声明顺序），无警告。

---

## 10. 常见错误

以下失败原因全部取自 `PackageManifestReader.read()` 与 `BfpackImporter` 的实际返回文案。

| 报错文案 | 触发条件 | 出处 |
| --- | --- | --- |
| 包目录不存在：xxx | `dir.exists()` 为假 | Reader |
| xxx 不是目录，无法作为包读取 | 传入的是文件 | Reader |
| 目录里没有 manifest.json，也没有 plugin.json 或 tool.json，无法判断这是什么格式的包。 | 三种清单都不存在 | Reader |
| manifest.json 不是合法 JSON：xxx | 清单语法错误 | Reader |
| manifest.json 缺少 id 字段 | `id` 缺失或 `trim()` 后为空 | Reader |
| 包 id 不合法（xxx）：<原因> | 命中 7.3 的六条规则之一 | Reader |
| 这个包要求协议 v2.0 之类的提示：`这个包要求协议 vN，当前只支持到 v2。请更新 BotForge 后再装。` | 清单 `api` 大于 2（`ApiCheck.TOO_NEW`） | Reader |
| 这个包没有任何能力：commands、ai_tools、ui_actions 三类都是空的。 | 三类能力解析后全空 | Reader |
| plugin.json 缺少 commands 数组 | v1 指令包没有 `commands` | Reader |
| plugin.json 里没有一条有效指令。纯 AI 工具包请改用 manifest.json 描述。 | v1 指令包的 `commands` 全部无效 | Reader |
| tool.json 缺少 id 字段 | v1 工具包没有 `id` | Reader |
| 包里没有 plugin.json | `.bfpack` 里缺 `plugin.json` | BfpackImporter |
| manifest 字段不完整：缺少 id 或 commands，或 commands 全为空 | `.bfpack` 走 v1 解析失败 | BfpackImporter |
| 这个包要求协议 vN，当前 App 只支持到 v1。请更新 BotForge 后再装。 | `.bfpack` 包 `api > 1` | BfpackImporter（`MAX_API = PluginManifest.CURRENT_API = 1`） |
| 包 id 非法（xxx）：不能包含路径分隔符或 .. | `.bfpack` 的 id 命中导入器私有校验 | BfpackImporter |
| 包内容已变化（期望 A，实际 B），请重新选择文件。 | `commit` 时 id 与预览不符 | BfpackImporter |
| 包内包含非法路径：xxx | zip 条目名越界 | BfpackImporter |
| 包内路径越界：xxx | canonical 校验失败 | BfpackImporter |
| 包内单个文件超过 32MB：xxx | 单文件超 `MAX_FILE_BYTES` | BfpackImporter |
| 解压后总体积超过 256MB | 总体积超 `MAX_TOTAL_BYTES` | BfpackImporter |
| 无法替换已安装的包（可能是文件被占用），请重启 App 后重试。 | 旧目录改名失败 | BfpackImporter |
| 写入失败，已回滚到原版本。 | 新目录改名失败并回滚 | BfpackImporter |
| 写入失败，请检查存储空间。 | 首次安装改名失败 | BfpackImporter |
| 非法的包 id：xxx / 没有找到这个包（xxx） / xxx 不是一个目录 / 删除失败，请检查权限 | 卸载路径的四类失败 | BfpackImporter |

### 10.1 不导致失败的「静默忽略」

以下写法不会报错，但对应内容不会生效，排查时容易误判：

- `aiTools` / `uiActions` 用了 camelCase：字段不被识别，能力列表为空
- `commands` 元素缺 `command`：该条被跳过，其余条照常
- `ai_tools` 元素缺 `name`：该条被跳过
- `ui_actions` 元素缺 `id`：该条被跳过
- `params` 元素缺 `name`：该条被跳过
- `permissions` 里出现未知标签：该条被跳过并给一条警告
- `runtime` 写了不存在的值：回落到 `android_sh`，无警告
- `scope` 写了无法识别的字符串：回落到只允许私聊
- `ai_tools[].permission` 写了 `deny` 之外无法识别的值（含拼错）：归为 `ALLOW`
- `timeout` 写了 `0` 或极大值：不报错，生效值钳到 `1` 或 `120`

### 10.2 排查顺序

1. 看清单文件名是否恰好是 `manifest.json`（v2 只认这个名字）
2. 看 `id` 是否存在且不含 `/`、`\`、`..`、不以 `.` 开头
3. 看 `api` 是否大于 2
4. 看 `ai_tools` / `ui_actions` 是否确实使用了 snake_case
5. 看三类能力是否至少有一类解析出了非空结果
6. 看 `permissions` 的标签是否在 6 个枚举之内

### 10.3 已知的文档与实现落差

- 读取层不检查入口脚本是否存在（类注释声称会产生警告，代码未实现）
- `PermissionDecl.reason` 为空时不补默认文案（模型注释声称读取层会补）
- `PackageUiAction` 没有超时字段，v1 `tool.json` 的 `timeoutSec` 迁移后不可表达，且不产生警告
- `.bfpack` 导入器仍只认 `plugin.json` 且 `MAX_API = 1`，v2 包无法打包导入
- `PackageSpec.runtime` 无任何执行层消费者，`proot` 分支尚未接线
