# BotForge 已知架构风险清单

本文件记录**已核实**、但尚未修复的结构性缺陷。每条都注明证据位置与影响面，
供后续迭代排期。只写确认过的事实，不写猜测。

生成基准：HEAD `2c9a7c33`（M4 两个只读页合并后）。

---

## R1. v2 包导入成功但不会被加载（最高优先级）

**现象**：带 `manifest.json`（api=2）的 `.bfpack` 可以导入成功、预览、落盘，
但重启后插件列表里看不到它，日志里也没有明显报错。

**证据**：
- `pkg/PackageSpec.kt`（404 行）定义 v2 规范，`CURRENT_API = 2`
- `plugin/BfpackImporter.kt`（438 行）走的是 `pkg.PackageManifestReader`，
  支持 `manifest.json` / `plugin.json` / `tool.json` 三种格式归一
- **但** `plugin/PluginRegistry.kt` 的 `reload()` 只读 `plugin.json`，
  缺失即 `continue` 并 warn

**影响**：纯 v2 包（只有 `manifest.json`）导入成功却永远不被加载。
这是 M2「packages/ 迁移」留下的最大裂缝 —— 迁移做了一半：
写入口（导入器）已经认 v2，读入口（注册中心）还停在 v1。

**修法方向**：让 `PluginRegistry.reload()` 也走 `PackageManifestReader`，
而不是自己 `JSONObject(plugin.json)`。风险在于注册中心同时被
`handlePlugin` 依赖，改动要保证指令索引行为不变。

---

## R2. `PluginManifest.parse` 的 api=1 硬约束与 v2 并存

**证据**：
- `plugin/PluginManifest.kt`：`const val CURRENT_API = 1`，
  `parse(json: JSONObject, dir: File): PluginManifest?` 失败返回 `null`
  （**不抛异常**，这点很重要 —— 用 `runCatching{}.getOrElse{}` 接不住 null）
- `pkg/PackageSpec.kt`：`CURRENT_API = 2`
- `plugin/BfpackImporter.kt`：`MAX_API = 2`

**影响**：三处 API 版本阈值不一致，语义也不同：
导入器容忍 2，注册中心只认 1。同一个包在不同环节得到不同判定。

**另注**：`parse()` 返回 null 而非抛异常，所有调用点必须用
`?: return failed(...)` 而不是 `getOrElse`。已在
`BfpackImporter.kt` 修正，`PluginRegistry.kt` 用的是 `try/catch` + null 判断，也安全。
但后续新增调用点必须注意这条。

---

## R3. `PluginGuard.check()` 的群聊分支只判群、不判人

**证据**：`plugin/PluginGuard.kt:88-95`

```kotlin
val groups = allowedGroups()
if (groups.isEmpty()) return "未配置允许的群"
if (!groups.contains(groupOpenId)) return "本群不在插件白名单里"
return null        // ← 到此就放行了，完全没看发言者是谁
```

**影响**：`groupEnabled` 已开 + 本群在白名单 → 返回 null，
哪怕发言者是刚进群的陌生人。

**为什么不直接改它**：`check()` 有 3 处调用点，其中
`MessageHandler.handlePlugin`（约 563 行）拿它当群插件开关。
若在 `check()` 里加「主人放行」，会让主人在群里绕过 `groupEnabled`。

**当前缓解**：工具链路已在 `MessageHandler.botAllowedTools` 里
叠了第二层人维度判定（`AuthStore.isPrivileged`），
群聊工具需要「白名单群 + 特权人」双开才可用。
插件链路（`handlePlugin`）**尚未**加这层，仍是只判群。

**待办**：`handlePlugin` 也需要人维度校验，判定逻辑应与
`botAllowedTools` 共用一份，避免两处漂移。

---

## R4. `Memory` 存储层缺三个只读能力

**证据**：`ai/Memory.kt`（79 行）只有四个方法：
`load` / `append` / `clear` / `recent`。

**缺口**：
- `listSessions(ctx): List<MemSession>` —— 按会话枚举
- `clearAll(ctx)` —— 一次清空
- `sessionBytes(ctx, key): Long` —— 单会话体积

**当前绕过**：`MemoryScreen.kt`（584 行）用
`filesDir/mem/*.json` 目录扫描 + `Memory.load` 拼出会话列表，
文件内第 76 行留了注释说明这是临时读口。

**已知限制**：存储键经 `[^a-zA-Z0-9_\-] → _` 清洗，**不可逆**，
所以界面上显示的「会话对象」是清洗后的键，不是原始 openid。
要修得动写入路径（加 `mem/index.json`），风险高于收益，暂不动。

**另一个不一致**：`MAX_TURNS = 120` 是**条数**上限（约 60 轮），
而 `recent` 的 depth 参数只影响喂给模型的内容、不影响磁盘存量，
默认 20、UI 可设 200。UI 可设值超过 120 这个物理上限，属于
「设置项允许填一个永远达不到的值」。

---

## R5. `HiddenExec` 熔断状态未持久化

**证据**：`term/HiddenExec.kt`（338 行），第 66-88 行有完整的熔断注释与状态。

**影响**：熔断状态只在进程内存里。App 被系统杀掉重启后熔断清零，
会立刻再尝试拉起 proot 并再失败一次。在低内存设备上这会形成
「杀掉 → 重启 → 再卡一次」的循环。

**待办**：把熔断截止时间写进 SharedPreferences，启动时读取。

---

## R6. 签名配置在无 secret 时静默降级为不签名

**证据**：`app/build.gradle` 的 `signingConfigs.release` 与
`buildTypes.release/debug` 都写成
`if (rootProject.file('keystore.properties').exists())`。

**影响**：CI 未配置 `KEYSTORE_BASE64` 等 secret 时，
`Decode keystore` 步骤打印「release 将不签名」，
两个变体都不设 `signingConfig`，产出的 APK **v1/v2/v3 签名全缺**，
无法安装（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

**检测方法**：
- v1：APK 内是否存在 `META-INF/*.RSA`
- v2/v3：文件尾部是否有 `APK Sig Block 42` 魔数

**注意**：这意味着 CI 产出的 artifact 不能直接装。若要用，
必须本地重签，且**签名与上次不一致时覆盖安装会失败**，必须先卸载。

---

## R7. CI 单元测试与 Android 依赖的边界很窄

**证据**：`app/src/test/` 下所有测试都是纯 JVM 测试，
没有 `testOptions { unitTests.returnDefaultValues = true }`。

**影响**：任何测试路径只要碰到 `android.*` 或 `org.json`，
在 JVM 单测里就是 `not mocked` 直接抛异常。

**已遵守的约束**：`ai/AnthropicCodec.kt` 及配套测试刻意做到
零 `android.*` / 零 `org.json` 依赖，输入输出用普通 Kotlin 数据结构。

**未覆盖的地方**：`BfpackImporter.commit()` 的成功路径会调
`Logger.i` → `Config.logEnabled` → `Config.sp`（`lateinit`），
在 JVM 单测里必然抛 `UninitializedPropertyAccessException`。
所以现有的 `BfpackImporterTest` 只测 `preview()` 与 `uninstall()`，
`commit()` 的备份与回滚逻辑**零测试覆盖**。

---

## R8. `BfpackImporter` 覆盖安装的回滚路径缺保护

**证据**：`plugin/BfpackImporter.kt` 约 213-226 行

```kotlin
if (!staging.renameTo(dest)) {
    backup.renameTo(dest)              // ← 返回值被丢弃
    return "写入失败，已回滚到原版本。"   // ← 无条件报「已回滚」
}
backup.deleteRecursively()             // ← 失败无感知
```

**三个问题**：
1. `backup.renameTo(dest)` 失败时仍报「已回滚」，用户以为没事
2. 遗留的 `.backup_<id>` 目录里带着完整的旧 `plugin.json`，
   而 `PluginRegistry.reload()` 只按 `isDirectory` 过滤、**不跳过点目录**，
   会被当成插件加载。`.` 的字典序小于普通字符，备份会**排在前面**
   抢走 `commandIndex` → 新版本被标「指令冲突」丢弃 → 表现为
   「升级成功但行为没变」
3. `staging` 目录名用毫秒时间戳，同毫秒两次导入会撞同一个目录

**修法**：① 检查 `backup.renameTo` 返回值，失败要说真相；
② `PluginRegistry` 扫描时跳过 `.` 开头目录（一行改动，收益最大）；
③ staging 名加随机后缀。

---

## R9. 目录型 zip 条目不计入任何体积上限

**证据**：`plugin/BfpackImporter.kt` 的解压循环
（约 291-310 行）

```kotlin
if (entry.isDirectory) { out.mkdirs(); continue }   // ← 跳过整个计数块
FileOutputStream(out).use { fos -> ... 累计 written/total ... }
```

**影响**：`MAX_FILE_BYTES = 32MB`、`MAX_TOTAL_BYTES = 256MB`
都只对文件字节计数。目录条目走 `mkdirs()` 直接跳过。
一个 1.8MB 的 zip 可以展开成 2 万个空目录（实测 12 秒），
字节计数一次都不触发。手机 `/data` 分区 inode 被吃光是全局故障。

**另注**：`preview()` 阶段就完整解压落盘 —— 用户只是「点开看看」
就已经写满 256MB。上限对「预览」这个动作量级不对。

---

## R10. 两套 `isSafePackageId` 规则已经漂移

**证据**：
- `plugin/BfpackImporter.kt:331` 私有实现
- `pkg/PackageSpec.kt:274` 公开实现

后者多一条 `if (id != id.trim()) return "包 id 首尾不能有空白字符"`。

而 `PackageSpec.kt:270` 的注释写着「与 plugin/BfpackImporter.kt 里
那份私有实现**同规则**」—— 注释已经不成立了。

**影响**：`uninstall(root, " com.demo")` 被导入器侧放行、
被 PackageSpec 侧拒绝 → 装得上、读不出/卸不掉。

**修法**：删掉 `BfpackImporter` 里的私有实现，改用
`com.ajx.botforge.pkg.isSafePackageId`，并修正那条过时注释。

---

## 附：本轮已修复的（供对照，不要重复排查）

- **B1** `DashboardScreen` 写死 `"ONLINE"` 判在线 → 已改为枚举比较
- **B2** `AppNavHost` 未传 `onOpenTerminal` → 已补
- **B3** `AgentLoop` 超限收尾未注入 → 已补
- **B5** `Config.seedDefaults()` 硬编码真实 apiKey / appId → 已移除
- **B4** 原文断言「CI 必挂」**结论错误**：两个 tar 都在包内
  （`alpine-rootfs.tar` 3,218,795 B + `termux-rootfs.tar` 32,038,861 B），
  CI 历史 run 全部 success。问题真实（双 rootfs 冗余）但不阻塞
- `MemoryScreen` 与 `CapabilitiesScreen` 的 Compose 嵌套 lambda 编译错误 → 已修
