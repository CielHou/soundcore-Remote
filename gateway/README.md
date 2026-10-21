# 声核局域网网关

这是一个运行在**声核所在电脑上**的本地网关。它通过本机 WebSocket 连接声核，再向手机提供独立的局域网 WebSocket 协议，避免手机直接连接声核 `/api/v1` 时被声核判定为非本机客户端。

```text
声核 ← 本机 WebSocket → 网关程序 ← 局域网 WebSocket → 手机 App
```

网关状态页面显示：

- 电脑到声核的连接、授权、订阅、收包和错误；
- 手机到网关的连接数、认证状态和收发包；
- **通道状态**：声核当前所有通道（按声核显示顺序排列）、名称、静音/发声状态、独奏状态、音量与常规/右侧类型，每 2 秒随状态刷新自动更新；
- 最近的连接与转发事件；
- 电脑的物理/无线局域网地址和手机应使用的连接地址；常见 VMware、Hyper-V、WSL、VPN 等虚拟适配器不会列入部署下拉菜单；
- 当前所选局域网地址；在程序窗口点击「展示二维码」可按需显示，手机连上后不会自动收起，关闭需手动点「隐藏二维码」。

## 安装包（给最终用户）

双击 `安装包\声核网关安装程序.exe`（从 [Releases](../../releases/latest) 下载）→ 点「安装」，默认不需要管理员权限。安装程序会：

- 释放到 `%LOCALAPPDATA%\AudcoreGateway`（已有配置和 `.state` 凭证不会被覆盖）；
- 创建桌面快捷方式、开始菜单项（含卸载项）；
- 可选加入开机自动运行（默认勾选）；
- 在「设置 → 应用」写入卸载项，卸载保留 `.state` 凭证，重装后手机地址不变。

命令行安装/卸载（自动化用）：

```powershell
& '.\安装包\声核网关安装程序.exe' --silent
& "$env:LOCALAPPDATA\AudcoreGateway\卸载声核网关.exe" --uninstall --silent
```

安装程序是单文件（约 34 MB，内含 gzip 压缩的核心），首次启动需要机器已安装
.NET 8 桌面运行时（本机已有）。

## 桌面版（免安装目录形式）

`build.ps1` 的产物直接放在 `%LOCALAPPDATA%\AudcoreGateway`，也可以不装直接用：

```text
声核网关.exe         托盘主程序（双击启动，桌面有快捷方式，已加入开机启动）
gateway-core.exe     网关核心（Node 单文件，内联了状态页面，由托盘自动拉起）
gateway.config.json  端口配置（手机 18533 / 状态页 18534）
.state\              声核令牌与固定的手机令牌
使用说明.txt         给最终用户的操作手册
```

> 不要把程序放在受限权限目录（例如带沙箱 ACL 的文件夹）里运行：
> Windows 会拒绝注册托盘图标，表现为「进程在跑但托盘没有图标」。安装包默认安装到
> `%LOCALAPPDATA%` 就是为了避开这个问题。

托盘功能：打开控制面板、打开状态页面、复制手机连接地址、查看运行日志、重启网关、
**跟随声核自动启停**（声核开→网关自动开，声核关→网关自动关）、开机自动运行、退出。

重新打包：

```powershell
pwsh -File gateway\build\build.ps1        # 核心 + 托盘 → %LOCALAPPDATA%\AudcoreGateway
pwsh -File gateway\build\build-setup.ps1  # 再打成安装包 → 安装包\声核网关安装程序.exe
```

`build.ps1` 会：内联 `public/` 静态资源 → esbuild 打包 `src/server.js` → 生成 Node SEA 单文件
（`gateway-core.exe`）→ `dotnet publish` 构建托盘外壳（`desktop/GatewayTray`，.NET 8 WinForms）
→ 组装到 `%LOCALAPPDATA%\AudcoreGateway`（保留已有 `.state` 凭证）。
`build-setup.ps1` 会：把托盘、核心、配置、说明 gzip 压缩后内嵌进
`desktop/GatewaySetup`（.NET 8 WinForms 安装器，含卸载）→ 输出单文件安装程序。

## 源码方式启动（开发用）

在项目目录执行：

```powershell
cd gateway
npm install
npm start
```

浏览器打开手机 WebSocket 不使用的独立状态页面：

```text
http://127.0.0.1:18534/
```

页面中的「手机连接部署」区域会自动选择一个可用于局域网的电脑地址，并通过「网卡 IP」下拉菜单只列出已检测到的物理/无线网卡；常见 VMware、Hyper-V、WSL、VPN 与移动热点虚拟适配器不会作为手机连接地址。选择地址后，完整连接地址、复制内容与一键复制按钮会立即切换到该网卡对应的 `ws://` 地址；完整地址可直接粘贴到手机 App，也可以在程序窗口按需展示二维码扫描。

默认手机 WebSocket 端口是 `18533`，状态页面端口是 `18534`，可覆盖：

```powershell
$env:AUDCORE_GATEWAY_PORT = '18533'
$env:AUDCORE_API_PORT = '38533'
npm start
```

声核多开时，将 `AUDCORE_API_PORT` 改成对应实例的端口。也可以直接指定完整地址：

```powershell
$env:AUDCORE_API_URL = 'ws://127.0.0.1:38533/api/v1'
npm start
```

## 连接协议

手机连接：

```text
ws://<电脑局域网地址>:18533/api/v1?token=<网关 token>
```

网关接受 `?token=` 查询参数，也接受 `X-Gateway-Token` 请求头。连接后手机发送：

```json
{
  "id": "1",
  "method": "GET",
  "path": "/gateway/status"
}
```

支持的手机请求：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/gateway/status` | 声核和手机双向状态 |
| `GET` | `/gateway/network` | 网关地址与电脑局域网地址 |
| `GET` | `/channels` | 获取通道列表并建立通道订阅 |
| `GET` | `/solo` | 获取临时独奏状态（旧版声核返回 404 时忽略） |
| `PATCH` | `/channels/{id}` | 只开放 `mute`、`gain_db` 通道控制 |
| `PATCH` | `/solo` | 只开放 `channel` 与 `on` 的临时独奏控制 |
| `POST` | `/gateway/switch` | 切换目标通道；新版使用 `/solo`，旧版回退多通道静音 |

网关会向外推送：

- `gateway-ready`：手机已连上网关；
- `gateway-status`：双向状态更新；
- `auth-required`：要求提交局域网 token；
- `gateway-authorized`：手机已通过网关认证；
- `channels`、`channels-update`：通道快照与增量；
- `solo`、`solo-update`：临时独奏状态；
- `channel-updated`、`solo-updated`、`channel-switched`：控制结果；
- `gateway-error`：转发或参数错误。

## 安全边界

- 声核 token 只保存在电脑本地 `.state/audcore-token.txt`，不发送给手机。
- 手机 token 保存在电脑本地 `.state/client-token.txt`，**生成一次后重启网关不会改变**；状态页面会在本机读取并提供复制操作，不向手机之外的页面请求暴露声核 token。
- 默认只转发声核明确开放的通道、增益、静音和 `/solo` 操作，不开放 `/graph` 等结构编辑接口。
- 网关监听 `0.0.0.0` 是为了让局域网手机访问；不要在不可信网络使用，也不要暴露到公网。
- `/solo` 需要配套更新版声核；旧版会自动使用多通道静音回退。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AUDCORE_API_URL` | `ws://127.0.0.1:38533/api/v1` | 声核本机 API |
| `AUDCORE_GATEWAY_HOST` | `0.0.0.0` | 手机监听地址 |
| `AUDCORE_GATEWAY_PORT` | `18533` | 手机监听端口 |
| `AUDCORE_UI_PORT` | `18534` | 状态页面监听端口 |
| `AUDCORE_GATEWAY_CLIENT_TOKEN` | 自动生成 | 手机连接网关的 token |
| `AUDCORE_GATEWAY_REQUIRE_CLIENT_TOKEN` | `true` | 是否要求手机 token |
| `AUDCORE_GATEWAY_CONFIG` | `gateway.config.json` | 固定配置文件名（可选） |
| `AUDCORE_APP_NAME` | `声核局域网网关` | 在声核授权提示中显示的名称 |
| `AUDCORE_CONNECT_TIMEOUT_MS` | `15000` | 声核连接超时 |
| `AUDCORE_RECONNECT_DELAY_MS` | `1500` | 声核重连基础延迟 |

## 固定 token / 固定连接地址

网关的 token 只生成一次并写入 `.state/client-token.txt`，**重启网关后地址和 token 都不变**，手机里保存的地址可以一直用。

想换成自己记得住的固定密钥，任选一种：

1. 直接编辑 `.state/client-token.txt`，把内容改成自己的密钥（至少 8 个字符），保存后重启网关；
2. 复制 `gateway.config.example.json` 为 `gateway.config.json`，修改 `clientToken` 字段，然后重启网关：

```json
{
  "listenPort": 18533,
  "uiPort": 18534,
  "clientToken": "audcore-remote-2026",
  "requireClientToken": true
}
```

优先级：环境变量 > `gateway.config.json` > `.state/client-token.txt`（已有内容）> 自动生成。
`gateway.config.json` 已在 `.gitignore` 中，内含密钥，不要提交或分享。

固定 token 后手机地址形如：

```text
ws://192.168.31.15:18533/api/v1?token=audcore-remote-2026
```

如果只想省掉 token（不建议，同一局域网任何人都能控制声核），可在 `gateway.config.json` 里设 `"requireClientToken": false`。

## 状态页与控制面板

- 网关启动后会自己订阅声核的 `/channels` 与 `/solo`，所以**没有手机连接时**状态页也能显示完整通道状态。
- 状态页 `/api/status` 增加 `gateway.preferredAddress`：用 UDP 选路探测本机真正连网的网卡地址，复制手机地址时默认选它，避免误选 VMware / Hyper-V / 网络共享（192.168.137.x）等虚拟网卡。
- 网页状态页只保留连接地址；程序窗口点击「展示二维码」后显示当前地址，手机连上后不会自动收起。
- 托盘程序的「控制面板」窗口同样按这个地址复制连接地址，并带「刷新状态」按钮。

## 打包

```powershell
pwsh -File build\build.ps1         # 核心 gateway-core.exe + 托盘 声核网关.exe → %LOCALAPPDATA%\AudcoreGateway
pwsh -File build\build-setup.ps1   # 生成安装包 → 安装包\声核网关安装程序.exe
```
