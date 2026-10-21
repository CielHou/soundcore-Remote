# 电脑端 WebSocket 桥接说明

当前推荐通过 `gateway` 目录中的网关连接手机。网关在电脑上先连接本机声核，再把受控协议提供给手机，避免手机直接连接声核 `/api/v1` 时被判定为非本机客户端。

网关手机端口默认是：

```text
18533
```

如果手机 TCP 连接超时，应放行网关端口，而不是放行声核的 `38533`。下面的脚本可创建对应的防火墙规则；**手机不需要也没有必要把 `38533` 转发到公网地址**。

## 1. 使用管理员 PowerShell 执行

把电脑当前局域网 IP 填到 `ListenAddress`：

```powershell
cd 电脑桥接

# 只创建网关防火墙规则，不创建任何端口转发：
.\netsh-port-proxy.ps1 -ListenPort 18533 -EnableFirewall
```

`EnableFirewall` 会创建或更新一条只允许**专用网络、TCP 18533** 的 Windows 防火墙入站规则。网关本身已经监听 `0.0.0.0:18533`，通常不需要 `netsh portproxy`。

## 2. 验证转发和声核监听

```powershell
Get-NetFirewallRule -DisplayName 'AudcoreRemote gateway WebSocket 18533' |
  Format-List DisplayName, Enabled, Direction, Action, Profile

Get-NetTCPConnection -LocalPort 18533 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalAddress, LocalPort, State, OwningProcess
```

应看到一条已启用的入站允许规则，以及网关进程的 `0.0.0.0:18533`（或 `:::18533`）监听。

## 3. 手机连接

保持电脑声核本地 API 开启，手机上使用：

从网关状态页「手机连接部署」复制完整地址。默认形式为：

```text
ws://192.168.31.15:18533/api/v1?token=<网关 token>
```

这个地址连接的是电脑网关，不是声核的 `38533`；复制完整地址后，手机 App 不需要再单独填写 Token 查询参数。

## 4. 清理

```powershell
cd 电脑桥接
.\netsh-port-proxy.ps1 -Remove -ListenPort 18533
```

如果历史上为了直连声核创建过 `38533` 转发，也可以移除它：

```powershell
netsh interface portproxy delete v4tov4 listenaddress=192.168.31.15 listenport=38533
Remove-NetFirewallRule -DisplayName 'AudcoreRemote local WebSocket proxy' -ErrorAction SilentlyContinue
Remove-NetFirewallRule -DisplayName 'AudcoreRemote gateway WebSocket 18533' -ErrorAction SilentlyContinue
```

## 常见问题

### 手机 TCP 连接 18533 超时

手机日志出现下面的错误时，说明连接没有完成 TCP 握手：

```text
failed to connect to /192.168.31.15 (port 18533) ... after 3000ms
```

按顺序检查：

1. 电脑上状态页和 `Get-NetTCPConnection -LocalPort 18533 -State Listen` 都能看到网关监听；
2. 手机与电脑连接到同一个局域网，手机地址选择下拉菜单中的以太网 IP，而不是虚拟网卡；
3. 以管理员身份运行上面的 `-EnableFirewall` 命令，确认专用网络入站规则已启用；
4. 路由器未开启 AP 隔离/访客网络隔离；
5. 不要使用历史 `netsh portproxy` 规则，也不要把 `38533` 直接暴露给手机。

### 手机连接 38533 后收到 HTTP 403

这表示声核收到了直接来自手机的请求，但**声核主动拒绝了非本机客户端**。这不是端口转发或 Android App 的问题。手机应改为连接网关的 `18533` 完整地址。


- 这是为**可信局域网**的个人使用而设置的桥接，不是声核官方提供的局域网部署方式。
- 不要把 38533 端口转发到公网、云服务器或不信任的路由器端口映射。
- 手机和电脑必须处于你控制的同一可信局域网。
- WebSocket 使用明文 `ws://`；不要在公共 Wi-Fi 或不可信网络使用此桥接。
- 防火墙规则只按私有网络和指定 TCP 端口创建，撤销时应同时移除端口转发与规则。
