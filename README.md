# BotForge

在 Android 上运行自有 QQ 机器人，附带一个可用的 Linux 环境与工具包机制。

[![构建](https://github.com/a2006-dev/BotForge/actions/workflows/build.yml/badge.svg)](https://github.com/a2006-dev/BotForge/actions/workflows/build.yml)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

---

## 这是什么

BotForge 把三件本来互相独立的事放在一个应用里：

1. **QQ 机器人**。接入官方 QQ 机器人开放平台，通过 WebSocket 收消息、调 HTTP 接口发消息。
   支持多机器人、人格设定、模型选择、群白名单与权限分级。
2. **模型接入**。任意兼容 OpenAI Chat Completions 的服务都能用，包括自建与中转。
   支持工具调用（function calling），模型可以主动调用内置工具（搜索、时间、设备信息等）。
3. **内置 Linux 环境**。基于 proot 与 Alpine rootfs，终端可直接使用。
   在这之上是工具包机制：写一个脚本加一份清单，就能在应用界面里变成带参数表单的小工具。

三者共用一套数据层与界面，不需要在几个应用之间来回切换。

---

## 功能

### 机器人

- 多机器人并存，每个机器人独立配置 AppID / AppSecret / Token
- 人格系统：系统提示、回复风格、可用工具集
- 模型管理：多套模型配置，按机器人或按人格指定
- 群管理：白名单、开关、权限分级（主人 / 管理员 / 成员）
- 指令：开关机器人、权限授予、状态查询、群登记
- 运行日志：收发消息、模型调用、错误，带级别与关键字过滤

### 内置环境

- Alpine 3.19 rootfs，首次进入终端页自动解包，约 3 MB
- 完整 PTY，Ctrl+C / Ctrl+D / Tab / 方向键可用
- 通过 `apk add` 安装更多软件包
- 挂载共享存储，`/sdcard` 在容器内可直接访问

### 工具包

工具包由一份 `tool.json` 清单与一个入口脚本组成。界面按清单生成参数表单，
参数以环境变量注入脚本，输出就地显示。

规范见 [docs/TOOLPKG.md](docs/TOOLPKG.md)。首次打开工具包页会写入一个示例。

---

## 安装

从 [Releases](https://github.com/a2006-dev/BotForge/releases) 下载最新 APK。

要求：

- Android 8.0（API 26）或更高
- `arm64-v8a` 架构（内置 Linux 环境只支持这一种）

安装包由 CI 从源码构建，签名密钥不随源码分发。

---

## 构建

```bash
git clone https://github.com/a2006-dev/BotForge.git
cd BotForge
./gradlew assembleDebug
```

需要 JDK 17+ 与 Android SDK（`compileSdk 34`）。Release 包由 CI 产出。

测试：

```bash
./gradlew testDebugUnitTest
```

---

## 权限说明

应用会根据功能需要申请下列权限。每一项都写明实际用途与能力边界。

| 权限 | 用途 | 能力边界 |
| --- | --- | --- |
| 网络 | 调用 QQ 开放平台与模型接口 | — |
| 通知 | 前台服务常驻提示 | 不会读取其他应用的通知内容；通知读取是独立权限，单独申请 |
| 悬浮窗 | 显示运行状态 | 仅绘制自身窗口，不读取屏幕上其他内容 |
| 前台服务 | 保持机器人在线 | Android 13+ 会常驻一条通知 |
| 电池白名单 | 防止后台被系统杀掉 | 由用户在系统设置中手动授予 |
| 文件访问 | 终端访问共享存储、工具包读写文件 | 应用不做沙箱隔离，见下文 |
| Shizuku / Root（可选） | 提升部分操作的权限 | 不授权时相关功能降级或不可用 |

**关于安全边界的诚实说明：**

- 工具包脚本以应用自身权限运行，能读写 `/sdcard` 下任意文件与应用私有目录。
  应用不提供沙箱。`risky` 标签只是提示，不是防线。
- 数据以明文存储在应用私有目录，依赖 Android 的应用隔离机制保护。
  已 Root 的设备上这层保护可以被绕过，应用无法防护。
- 集成 Shizuku 或 Root 意味着主动扩大攻击面。是否授予由用户判断。

这些问题不做美化。知道边界在哪里，才能做出正确的选择。

---

## 项目结构

```
app/src/main/
├── java/com/ajx/botforge/
│   ├── ai/         模型调用、工具定义、搜索
│   ├── data/       数据层（Store、实体、序列化）
│   ├── plugin/     插件（dex 加载）
│   ├── qq/         QQ 机器人协议与消息处理
│   ├── service/    前台服务、无障碍、通知
│   ├── term/       proot 环境、tar 解析、PTY 会话
│   ├── tools/      工具包清单与执行
│   └── ui/         Compose 界面
│       ├── components/  通用组件
│       ├── screens/     页面
│       ├── text/        全站文案常量
│       └── theme/       配色、间距、字号
└── assets/rootfs/  Alpine rootfs
```

---

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。提交前请确保单元测试通过。

工具包不需要改动应用代码即可分发，规范见 [docs/TOOLPKG.md](docs/TOOLPKG.md)。

---

## 已知限制

- 终端为逐行文本渲染，不是完整 VT100 模拟。`vim`、`top` 这类全屏程序显示会错乱。
- 内置环境只支持 `arm64-v8a`。
- 工具包没有沙箱隔离。
- 工具包导入尚未实现一键完成。

---

## 第三方组件

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| proot | GPL-2.0 | 无 root 环境下模拟 chroot |
| Alpine Linux minirootfs | MIT | 内置 Linux 用户空间 |
| AndroidX / Jetpack Compose | Apache-2.0 | 界面框架 |
| OkHttp | Apache-2.0 | HTTP 客户端 |
| kotlinx.serialization | Apache-2.0 | JSON 序列化 |

---

## 许可证

[Apache-2.0](LICENSE)

第三方组件的许可证见上表。内置的 proot 为 GPL-2.0，以二进制形式随包分发，
不修改、不链接，仅作为独立可执行程序调用。