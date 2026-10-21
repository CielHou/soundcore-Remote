# 构建网关单文件核心 + 托盘外壳，并组装发布目录
# 用法：pwsh -File build\build.ps1   （在 gateway 目录下执行）

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot   # gateway/
Set-Location $root

Write-Host '[1/5] 生成内联静态资源' -ForegroundColor Cyan
$assetKeys = @('app.js', 'index.html', 'style.css')
$assetOk = Test-Path (Join-Path $root 'src/embedded-assets.js')
if ($assetOk) {
    $assetContent = Get-Content (Join-Path $root 'src/embedded-assets.js') -Raw
    $assetOk = $assetKeys | ForEach-Object { $assetContent.Contains(('"{0}"' -f $_)) } | Where-Object { -not $_ } | Measure-Object | Select-Object -ExpandProperty Count
    $assetOk = $assetOk -eq 0
}
if ($assetOk) {
    Write-Host '使用仓库中已生成的嵌入资源（跳过重写）'
} else {
    node build/embed-assets.js
}

Write-Host '[2/5] 打包服务端为单文件' -ForegroundColor Cyan
$bundlePath = Join-Path $root 'build/server.bundle.cjs'
& "$root\node_modules\@esbuild\win32-x64\esbuild.exe" src/server.js `
    --bundle --platform=node --target=node22 --format=cjs --outfile=$bundlePath
if ($LASTEXITCODE -ne 0) { throw 'esbuild 打包失败' }

Write-Host '[3/5] 生成 SEA 准备块' -ForegroundColor Cyan
node --experimental-sea-config build/sea-config.json

Write-Host '[4/5] 注入 Node 运行时生成 gateway-core.exe' -ForegroundColor Cyan
Copy-Item (Get-Command node).Source build/core-base.exe -Force
node node_modules/postject/dist/cli.js build/core-base.exe NODE_SEA_BLOB build/sea-prep.blob `
    --sentinel-fuse NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2
if ($LASTEXITCODE -ne 0) { throw 'SEA 注入失败' }

Write-Host '[5/5] 构建托盘外壳' -ForegroundColor Cyan
Push-Location desktop/GatewayTray
dotnet publish -c Release -o ./publish --nologo -v quiet
if ($LASTEXITCODE -ne 0) { Pop-Location; throw '托盘外壳构建失败' }
Pop-Location

$dist = Join-Path $env:LOCALAPPDATA 'AudcoreGateway'

# 覆盖发布前先停掉正在运行的托盘与核心，否则文件被占用
$wasRunning = @(Get-Process -Name '声核网关' -ErrorAction SilentlyContinue).Count -gt 0
if ($wasRunning) {
    Write-Host '检测到托盘正在运行，先停止以便覆盖更新' -ForegroundColor Yellow
    Get-Process -Name '声核网关','gateway-core' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $dist '.state') | Out-Null
Copy-Item build/core-base.exe (Join-Path $dist 'gateway-core.exe') -Force
Copy-Item desktop/GatewayTray/publish/声核网关.exe (Join-Path $dist '声核网关.exe') -Force
Copy-Item desktop/GatewayTray/使用说明.txt (Join-Path $dist '使用说明.txt') -Force -ErrorAction SilentlyContinue

# 首次发布时生成默认配置（端口、声核地址、令牌要求）
$configPath = Join-Path $dist 'gateway.config.json'
if (-not (Test-Path $configPath)) {
    @'
{
  "listenHost": "0.0.0.0",
  "listenPort": 18533,
  "uiPort": 18534,
  "audcoreUrl": "ws://127.0.0.1:38533/api/v1",
  "appName": "声核局域网网关",
  "requireClientToken": true
}
'@ | Set-Content -Path $configPath -Encoding UTF8
    Write-Host "已生成默认配置：$configPath"
}

# 保留已固定的手机令牌与声核授权，避免重装后手机地址变化
$srcState = Join-Path $root '.state'
$distState = Join-Path $dist '.state'
foreach ($name in @('client-token.txt', 'audcore-token.txt')) {
    $target = Join-Path $distState $name
    if (-not (Test-Path $target) -and (Test-Path (Join-Path $srcState $name))) {
        Copy-Item (Join-Path $srcState $name) $target -Force
        Write-Host "已迁移凭证：$name"
    }
}

Write-Host "完成：$dist" -ForegroundColor Green
Get-ChildItem $dist -Recurse | Select-Object FullName, Length | Format-Table -AutoSize

# 若发布前托盘在运行，更新后自动重新启动（用 WMI 分离启动，避免占用当前控制台句柄）
if ($wasRunning) {
    $exePath = Join-Path $dist '声核网关.exe'
    try {
        Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "`"$exePath`"" } | Out-Null
    } catch {
        Start-Process -FilePath $exePath -WorkingDirectory $dist
    }
    Write-Host '已重新启动托盘程序' -ForegroundColor Green
}
