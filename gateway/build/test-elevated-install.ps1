$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$ErrorActionPreference = 'Stop'
$installer = (Join-Path $repo '安装包\声核网关安装程序.exe')
$log = 'C:\Users\houku\AppData\Local\AudcoreGateway\setup.log'
$tempLog = Join-Path $env:TEMP 'audcore-gateway-setup.log'
if (Test-Path $log) { Remove-Item $log -Force }
if (Test-Path $tempLog) { Remove-Item $tempLog -Force }

$installedCore = 'C:\Users\houku\AppData\Local\AudcoreGateway\gateway-core.exe'
$installedCoreBytes = (Get-Item $installedCore).Length
$coreProcess = Get-Process -Name 'gateway-core' -ErrorAction SilentlyContinue
if (-not $coreProcess) { throw 'gateway-core 未运行，无法验证提权回退' }
Write-Host "预检 gateway-core PID=$($coreProcess.Id) size=$installedCoreBytes"

$child = Start-Process $installer -ArgumentList '--silent','--elevated' -PassThru
$finished = $child.WaitForExit(30000)
if (-not $finished) {
    Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue
    throw '提权安装等待超时'
}
if ($child.ExitCode -ne 0) { throw "提权安装退出码 $($child.ExitCode)" }
if (-not (Test-Path $log)) { throw '未找到提权安装日志' }
$logText = Get-Content $log -Raw
if ($logText -notmatch '开始安装到 .+；提权=True') { throw '日志未证明管理员权限安装' }
if ($logText -notmatch '写入成功 .+gateway-core.exe') { throw '日志未证明 gateway-core 写入成功' }
if ($logText -notmatch '安装完成') { throw '日志未记录安装完成' }

$newCoreBytes = (Get-Item $installedCore).Length
$tray = Get-Process -Name '声核网关' -ErrorAction SilentlyContinue
$trayDll = Get-Item 'C:\Users\houku\AppData\Local\AudcoreGateway\声核网关.dll'
[pscustomobject]@{
    SetupExitCode = $child.ExitCode
    SetupLogBytes = (Get-Item $log).Length
    OriginalCoreBytes = $installedCoreBytes
    UpdatedCoreBytes = $newCoreBytes
    CoreChanged = ($newCoreBytes -ne $installedCoreBytes)
    TrayPid = if($tray){$tray.Id}else{0}
    TrayDllBytes = $trayDll.Length
    TrayDllModified = $trayDll.LastWriteTime
    SHA256 = (Get-FileHash $installer -Algorithm SHA256).Hash
}