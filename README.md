<div align="center">

# BotForge

**在手机上跑一个能真正操作设备的 QQ 机器人。**

QQ 官方机器人接口 · 大模型工具调用 · 内置 Alpine Linux

[![Build](https://github.com/a2006-dev/BotForge/actions/workflows/build.yml/badge.svg)](https://github.com/a2006-dev/BotForge/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/a2006-dev/BotForge?label=release)](https://github.com/a2006-dev/BotForge/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20arm64-green.svg)](#安装)

</div>

---

## 这是什么

BotForge 是一个 Android 应用。装上它，你的手机就是一台 QQ 机器人服务器：

你在 QQ 里发消息，模型理解你的意图，调用它需要的能力——执行命令、读写文件、运行你自己写的脚本，然后把结果发回来。

不需要电脑，不需要服务器，不需要 root。

和"套壳 ChatGPT 的聊天机器人"的区别在于最后一步：**它真的会去做**，而不只是告诉你怎么做。

---

## 能力

### 机器人

- 接入 QQ 官方机器人开放平台，WebSocket 收发消息
- 多机器人并存，各自独立配置 AppID / AppSecret / Token
- 主人 / 管理员 / 成员三级权限，绑定走一次性验证码
- 人格系统：每个机器人可配独立的说话风格与可用能力
- 群白名单，可精确到群

### 模型

- 任意兼容 OpenAI Chat Completions 的服务，自建与中转都行
- 多 Key 池，单 Key 失败自动切换
- 模型连通性探测，配完当场验证
- 支持 function calling，模型可主动调用工具

### 工具

内置 7 个工具，按"包"组织，每个工具单独设权限：

| 包 | 工具 |
|---|---|
| 系统 | `run_command` `system_command` `diagnose_terminal` |
| 文件 | `read_file` `write_file` |
| 插件 | `list_plugins` `run_plugin` |

三档权限：**允许**（直接调）· **需确认**（弹窗等你点）· **禁用**（模型看不到）。

### 终端

- 内置 Alpine Linux，proot 实现，无需 root
- 完整 PTY，`Ctrl+C` / `Tab` / 方向键可用
- `/sdcard` 直接挂载，和手机文件互通
- `apk add` 装你需要的任何包

### 插件

写一个脚本加一份清单，它就成为机器人可调用的指令。不用改应用代码，不用重新编译。

插件可声明适用场景（私聊 / 群聊）、超时上限、是否需要二次确认。
执行走 openid 白名单与审计日志。

---

## 安装

从 [Releases](https://github.com/a2006-dev/BotForge/releases/latest) 下载 `app-release.apk`。

| 要求 | 说明 |
|---|---|
| Android 8.0+ | API 26 起 |
| arm64-v8a | 内置环境只编译了这一种架构 |

APK 由 GitHub Actions 从源码构建。签名密钥不随源码分发，因此自行编译的包无法覆盖安装官方包。

---

## 快速开始

1. **拿凭据** — 在 [QQ 机器人开放平台](https://bot.q.qq.com/wiki/) 创建机器人，取 AppID / AppSecret / Token
2. **配机器人** — 打开 BotForge → 机器人 → 新增，填入凭据
3. **配模型** — 模型 → 新增，填 API 地址与 Key，用"测试"确认连通
4. **绑主人** — 权限中心 → 生成验证码 → 复制命令 → 在 QQ 里发给机器人
5. **调权限** — 设置 → 工具包与权限，按需要开关每个工具

第 4 步完成后，你就能在 QQ 里指挥它了。试试发 `/help`。

---

## 安全边界

这一节不做美化。知道边界在哪，才能决定要不要装。

**工具的真实能力**

- `system_command` 通过 Shizuku 获取 `shell:2000`，能启动应用、改系统设置、模拟点击
- `run_command` 在 proot 里执行任意命令
- 终端环境把整个 `/sdcard` 以读写方式挂载进容器

**这意味着**：模型（或诱导模型的人）理论上可以读写你的照片、文档，以及手机上任何应用能碰到的文件。

**当前状态**

- 7 个工具默认全部为"允许"，装完即生效
- "需确认"档位的界面已完成，但执行层的确认流程尚未接通，选了会直接失败
- 应用不做沙箱隔离

**如果你在意这些**

装完后第一件事去"工具包与权限"，把 `system_command` 和 `run_plugin` 关掉。只留文件读写也能完成大部分任务。

**数据传输**

| 去向 | 内容 |
|---|---|
| QQ 开放平台 | 收发消息 |
| 你配置的模型服务 | 对话内容、工具调用参数与结果 |
| 联网搜索（可选） | 搜索关键词 |

凭据以明文存储在应用私有目录，依赖 Android 应用隔离保护。已 root 的设备上这层保护可被绕过。

---

## 已知限制

- 终端是逐行文本渲染，不是完整 VT100。`vim`、`top` 这类全屏程序显示会错乱
- 只支持 arm64-v8a
- 插件需要手动拷贝目录，还没有一键安装
- 版本号目前不跟 tag 走，装完看到的版本号可能低于实际构建

---

## 参与

**QQ 群：1012917975** — 问题、建议、插件分享都可以在这里

- [Issues](https://github.com/a2006-dev/BotForge/issues) — 报告缺陷、提需求
- [Releases](https://github.com/a2006-dev/BotForge/releases) — 版本历史
- [CONTRIBUTING.md](CONTRIBUTING.md) — 贡献指南
- [docs/](docs/) — 工具包规范、架构说明

插件不需要改动应用代码即可分发。规范见 [docs/TOOLPKG.md](docs/TOOLPKG.md)。

---

## 构建

```bash
git clone https://github.com/a2006-dev/BotForge.git
cd BotForge
./gradlew assembleDebug
```

需要 JDK 17+ 与 Android SDK（`compileSdk 34`）。

---

## 许可

[Apache-2.0](LICENSE)

内置的 proot 为 GPL-2.0，以独立可执行程序形式随包分发，不修改、不链接。

第三方组件清单见 [NOTICE.md](NOTICE.md)。

---

<div align="center">
<sub>BotForge 与腾讯 / QQ 无隶属关系。请遵守 QQ 用户协议与适用法律。</sub>
</div>
