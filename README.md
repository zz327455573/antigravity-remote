# Antigravity Remote（反重力手机遥控器 + Gemini MCP 远程通道）

一套用于在手机端远程控制 **Windows 桌面版 Google Antigravity IDE** 的双通道开源方案，包含：

1. **反重力手机遥控 APP（`android/`）**：原生 Android 客户端，随时随地在手机上查看/新建项目与会话、切换真实模型、发指令、全盘双向传文件、内置断点下载、对话内直接预览图片与下载产物。
2. **Gemini 手机 APP ↔ Antigravity Remote MCP 桥接（`server/mcp_bridge.py`）**：将本地 Antigravity 封装为标准的 Streamable HTTP Remote MCP Server（内置 OAuth 2.1 + PKCE + RFC 7591 动态客户端注册），可直接接入手机端/网页端 Gemini App 的「+ 自定义」工具，让 Gemini 直接指挥云电脑里的 Antigravity 干活。

---

## ⚠️ 部署环境硬性说明（必看）

本系统**不是纯本地单机玩具**，要实现手机端随时随地远程控制，**必须具备两套环境配合**：

1. **一台 24 小时在线的 Windows 云电脑 / 桌面主机**
   - 用于运行 **Google Antigravity 桌面端**、`agentapi.bat` 以及本项目的 **FastAPI 网关（`server/gateway.py`）**。
   - 因为 Antigravity 本身是桌面端 IDE，必须依赖这台 Windows 主机提供真实的本地工作区、终端和运行环境。
2. **公网穿透 / 公网服务器（以下二选一，或主备双通道）**
   - **方案 A（推荐）：Cloudflare Tunnel（需自有域名托管在 Cloudflare）**
     - 无需公网独立 IP，通过 `cloudflared` 将云电脑本地 `127.0.0.1:18900` 穿透到你自己的 HTTPS 域名（如 `https://ctl.example.com`）。
     - **注意**：如果要接入 **Gemini 手机 APP 的自定义 MCP**，**必须提供带有效 HTTPS 证书的公网域名**，Cloudflare Tunnel 是最省心的方案。
   - **方案 B：一台具备公网 IP 的云服务器（VPS）+ FRP 内网穿透**
     - 如果不使用 Cloudflare Tunnel，你需要一台自己的公网服务器运行 `frps`（搭配 Nginx HTTPS 反代，或直接暴露端口给 Android APP 作为备用直连通道）。

### 整体架构图

```text
[手机端]
  ├─ 反重力遥控 APP (Android) ──────(HTTPS / x-auth-token)──┐
  └─ Gemini 手机 APP («+ 自定义» MCP) ──(OAuth 2.1 / Bearer)──┤
                                                              ▼
                  公网 HTTPS 入口（Cloudflare Tunnel 或 公网服务器+FRP）
                                                              │
                                                              ▼
[Windows 云电脑] FastAPI 网关 (server/gateway.py @ 127.0.0.1:18900)
  ├─ /api/*  ── 反重力遥控 APP 接口（项目/会话/模型/上传/全盘文件/停止/健康）
  ├─ /mcp    ── Gemini Remote MCP Server (server/mcp_bridge.py)
  └─ 调度底层：Antigravity hub_*.json LanguageServer RPC + agentapi.bat
```

---

## 核心功能

### 1. 反重力手机遥控 APP（`android/`）
- **项目与会话管理**：新建项目、切换工作区、展开项目历史会话、新建独立会话。
- **真实模型实时切换**：自动拉取桌面端可用模型列表与剩余额度。
- **流式上传与全盘文件互传**：
  - 对话底部 `+` 支持流式带进度条上传图片/文件到云电脑 `<project>/_mobile_uploads/`。
  - 底部 `文件` 支持浏览云电脑桌面、C 盘、D 盘等所有目录，双向传文件。
- **内置前台服务断点下载（`DownloadService.java`）**：绕开系统下载器无鉴权/易断流问题，切后台或锁屏不断连，自动保存至手机 `Download/反重力遥控/`。
- **对话交互优化**：支持气泡长按自由选字、一键复制、自动识别 AI 回复中的 `file:///` 链接并渲染为图片预览/下载卡片。

### 2. Gemini 手机 APP Remote MCP（`server/mcp_bridge.py`）
- **标准认证**：内置 OAuth 2.1（Authorization Code + PKCE + RFC 7591 动态注册），自动暴露 `/.well-known/oauth-protected-resource` 与 `/.well-known/oauth-authorization-server`，Access Token 默认 30 天、Refresh Token 180 天。
- **暴露的 9 个 MCP 工具**：
  - `list_projects` / `list_conversations` / `check_server_health`
  - `create_and_send_task` / `send_message` / `get_task_result` / `stop_task`
  - `list_files` / `read_file_snippet`
- **适配移动端长短任务**：
  - 快速任务（<50s）可传 `wait_seconds=45`（上限 50s）单回合同步拿到结果；
  - 长耗时任务传 `wait_seconds=0` 异步派发拿 `conversation_id`，后续用 `get_task_result` 分段读取。

---

## 快速部署

### 第一步：云电脑启动网关
1. 进入 `server/` 目录，安装依赖：
   ```bash
   pip install -r requirements.txt
   ```
2. 复制配置模板并修改强密码与工作区根目录：
   ```bash
   copy config.example.json config.json
   ```
   编辑 `server/config.json`，将 `auth_token` 改为你的强密码，`workspace_roots` 改为你的实际工作区路径。
3. 确保桌面上 **Antigravity IDE 已启动**，运行网关：
   ```bash
   python gateway.py
   ```
   *(可选：配合 Windows 计划任务定时运行 `watch_gateway.py` 与 `watch_cloudflared.py`，实现无黑框后台保活。)*

### 第二步：打通公网穿透（以 Cloudflare Tunnel 为例）
1. 在 Cloudflare Zero Trust 创建 Tunnel（如 `agy-remote`），将你的 HTTPS 子域名（如 `https://ctl.example.com`）指向 `http://localhost:18900`。
2. 在云电脑运行 `cloudflared tunnel run agy-remote`。
3. 接入 Gemini App：在 Gemini 手机/网页端添加自定义 MCP，地址填 `https://你的域名/mcp`，按提示跳转到授权页输入 `config.json` 中的 `auth_token` 即可完成 OAuth 绑定。

### 第三步：编译安卓端（`android/`）
1. 使用 Android Studio 或 Gradle 打开 `android/` 目录：
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
2. 将生成的 APK 安装到手机，打开右上角设置填入你的服务器地址（`https://你的域名` 或公网服务器地址）与 `auth_token` 即可使用。

---

## 安全提醒
- **切勿提交敏感文件**：`server/config.json`、`server/oauth_store.json` 已被 `.gitignore` 忽略，请勿将真实域名、公网 IP、网关密码或 OAuth 令牌提交到公开仓库。
- **远程自改保护**：请勿在通过网关或 Gemini MCP 派发的远程会话中让 AI 直接修改或重启 `gateway.py` / `mcp_bridge.py`，否则会中断当前连接。
