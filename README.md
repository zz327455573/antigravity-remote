# Antigravity Remote APP（反重力手机遥控器）

[中文](#-中文说明) | [English](#-english-documentation)

> 如果你需要通过 **Gemini 手机 APP** 直接远程指挥云电脑里的 Antigravity，请移步配套项目：[**antigravity-gemini-mcp**](https://github.com/zz327455573/antigravity-gemini-mcp)  
> Looking to control Antigravity directly from the **Gemini Mobile App** via Remote MCP? See the companion repo: [**antigravity-gemini-mcp**](https://github.com/zz327455573/antigravity-gemini-mcp)

---

## 🇨🇳 中文说明

**Antigravity Remote APP** 是一套原生 Android 手机遥控客户端 + Windows 本地 FastAPI 网关，让你随时随地在手机上操控桌面端 **Google Antigravity IDE**。

### ⚠️ 部署环境硬性要求（必看）
本系统不是单机玩具，要实现手机随时随地远程控制，**必须具备两套环境配合**：
1. **一台 24 小时在线的 Windows 云电脑 / 桌面主机**：运行 Google Antigravity IDE 本体、`agentapi.bat` 以及本项目 `server/gateway.py`（监听 `127.0.0.1:18900`）。
2. **公网穿透 / 公网服务器（二选一，或主备双通道）**：
   - **方案 A（推荐）：Cloudflare Tunnel**（需自有域名托管在 Cloudflare），通过 `cloudflared` 将本地 `18900` 端口安全映射到 HTTPS 域名。
   - **方案 B：一台具备公网 IP 的云服务器（VPS）+ FRP 内网穿透**，将本地 `18900` 端口转发到公网服务器供手机直连。

### 核心功能
- **项目与会话管理**：新建/切换工作区项目、展开项目历史会话、新建独立会话。
- **真实模型实时切换**：自动同步桌面端可用模型列表与剩余额度。
- **流式上传与全盘文件互传**：对话 `+` 流式带进度上传附件到 `<project>/_mobile_uploads/`；底部 `文件` 支持浏览云电脑桌面、C 盘、D 盘等所有目录并双向传文件。
- **内置前台服务断点下载（`DownloadService.java`）**：自带鉴权头与断点续传，切后台/锁屏不断连，保存至手机 `Download/反重力遥控/`。
- **对话体验优化**：气泡支持长按自由选字、一键复制，自动识别 AI 回复中的 `file:///` 链接并渲染为图片预览与下载卡片。

### 快速部署
1. **云电脑端启动网关**：
   ```bash
   cd server
   pip install -r requirements.txt
   copy config.example.json config.json
   ```
   编辑 `server/config.json`，修改强密码 `auth_token` 和你的工作区路径 `workspace_roots`，确保桌面 Antigravity IDE 已启动后运行 `python gateway.py`（可选配合 `watch_gateway.py`、`watch_cloudflared.py` 做后台无黑框保活）。
2. **配置公网穿透**：将 Cloudflare Tunnel 或公网服务器 FRP 指向本地 `http://127.0.0.1:18900`。
3. **编译安装 Android APK**：
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
   安装 APK 后，在 APP 右上角设置中填入你的公网地址和 `auth_token` 即可使用。

---

## 🇺🇸 English Documentation

**Antigravity Remote APP** is a native Android remote control client paired with a Windows FastAPI gateway, enabling you to control your desktop **Google Antigravity IDE** from anywhere on your phone.

### ⚠️ Required Infrastructure (Must Read)
Remote mobile access requires **two environments working together**:
1. **An Always-On Windows Cloud PC / Desktop Host**: Runs the Google Antigravity IDE, `agentapi.bat`, and `server/gateway.py` (listening on `127.0.0.1:18900`).
2. **Public Tunnel / Public Server (Choose one, or use both as primary + fallback)**:
   - **Option A (Recommended): Cloudflare Tunnel** (requires a domain on Cloudflare) mapping local `18900` to an HTTPS endpoint via `cloudflared`.
   - **Option B: A Public VPS Server + FRP** forwarding `18900` to a public IP/port.

### Key Features
- **Project & Conversation Management**: Create/switch projects, expand project sessions, or start standalone conversations.
- **Live Model Switching**: Fetches real-time available models and remaining quotas from the desktop IDE.
- **Streaming Upload & Full-Disk File Manager**: Upload files/images with live progress bars (`+`), browse Cloud PC drives/Desktop, and transfer files bidirectionally.
- **Built-in Foreground Resumable Downloader (`DownloadService.java`)**: Authenticated chunked downloads that survive backgrounding/screen-off, saved to `Download/反重力遥控/`.
- **Chat UX**: Long-press partial text selection, one-tap copy, and automatic inline image preview / file download cards for `file:///` links in AI replies.

### Quick Start
1. **Start the Gateway on Windows Cloud PC**:
   ```bash
   cd server
   pip install -r requirements.txt
   copy config.example.json config.json
   ```
   Set a strong `auth_token` and your `workspace_roots` in `server/config.json`. Start Antigravity IDE, then run `python gateway.py` (optionally use `watch_gateway.py` & `watch_cloudflared.py` for silent background keepalive).
2. **Expose via Public Tunnel / VPS**: Point Cloudflare Tunnel or FRP to `http://127.0.0.1:18900`.
3. **Build & Install Android APK**:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```
   Install the APK and enter your public URL + `auth_token` in the app's settings dialog.

---

## 🔒 安全提醒 / Security Notice
请勿将 `server/config.json`、真实域名、公网 IP 或任何密钥提交到公开仓库。  
Never commit `server/config.json`, private domains, public IPs, or tokens to a public repository.
