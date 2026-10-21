param(
    [string]$ListenAddress = '192.168.31.15',
    [int]$ListenPort = 18533,
    [string]$ConnectAddress = '127.0.0.1',
    [int]$ConnectPort = 18533,
    [switch]$Remove,
    [switch]$EnableFirewall
)

$ErrorActionPreference = 'Stop'

if ($Remove) {
    netsh interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$ListenPort
    Write-Host "已移除端口转发：${ListenAddress}:$ListenPort -> ${ConnectAddress}:$ConnectPort"
} else {
    netsh interface portproxy add v4tov4 listenaddress=$ListenAddress listenport=$ListenPort connectaddress=$ConnectAddress connectport=$ConnectPort
    Write-Host "已设置端口转发：${ListenAddress}:$ListenPort -> ${ConnectAddress}:$ConnectPort"
}

if ($EnableFirewall -and -not $Remove) {
    $ruleName = "AudcoreRemote gateway WebSocket ${ListenPort}"
    $rules = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if (-not $rules) {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $ListenPort -Profile Private | Out-Null
        Write-Host "已创建私有网络入站防火墙规则：TCP $ListenPort"
    } else {
        $rules | Set-NetFirewallRule -Enabled True -Action Allow -Profile Private
        Set-NetFirewallPortFilter -NetFirewallRule $rules -Protocol TCP -LocalPort $ListenPort
        Write-Host "防火墙规则已更新：TCP $ListenPort"
    }
}

Write-Host "`n当前端口转发配置："
netsh interface portproxy show all

Write-Host "`n当前 ${ListenPort} 监听："
Get-NetTCPConnection -LocalPort $ListenPort -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess |
    Format-Table -AutoSize
