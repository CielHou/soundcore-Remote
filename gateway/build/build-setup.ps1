$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 打包「声核网关」安装程序：把托盘、网关核心、配置、说明压进单个安装包 exe
# 用法：pwsh -File build\build-setup.ps1
$ErrorActionPreference = 'Stop'

$gatewayRoot = Split-Path -Parent $PSScriptRoot
$setupProject = Join-Path $gatewayRoot 'desktop\GatewaySetup'
$payloadDir = Join-Path $setupProject 'payload'
$dist = Join-Path $env:LOCALAPPDATA 'AudcoreGateway'
$outputDir = (Join-Path $repo '安装包')
$version = (Get-Content (Join-Path $PSScriptRoot '.installer-version') -Raw).Trim()

if (-not (Test-Path (Join-Path $dist 'gateway-core.exe'))) {
    throw "未找到已发布的应用：$dist。请先运行 build\build.ps1"
}

Write-Host "== 1/5 准备载荷 ==" -ForegroundColor Cyan
if (Test-Path $payloadDir) { Remove-Item $payloadDir -Recurse -Force }
New-Item -ItemType Directory -Path $payloadDir -Force | Out-Null

$payloadFiles = @(
    @{ Source = (Join-Path $dist '声核网关.exe'); Name = '声核网关.exe' },
    @{ Source = (Join-Path $dist '声核网关.dll'); Name = '声核网关.dll' },
    @{ Source = (Join-Path $dist '声核网关.deps.json'); Name = '声核网关.deps.json' },
    @{ Source = (Join-Path $dist '声核网关.runtimeconfig.json'); Name = '声核网关.runtimeconfig.json' },
    @{ Source = (Join-Path $dist 'zxing.dll'); Name = 'zxing.dll' },
    @{ Source = (Join-Path $dist 'gateway-core.exe'); Name = 'gateway-core.exe' },
    @{ Source = (Join-Path $dist 'gateway.config.json'); Name = 'gateway.config.json' },
    @{ Source = (Join-Path $gatewayRoot 'desktop\GatewayTray\使用说明.txt'); Name = '使用说明.txt' }
)

foreach ($file in $payloadFiles) {
    if (-not (Test-Path $file.Source)) {
        throw "缺少文件：$($file.Source)"
    }
}

# 版本信息
$versionFile = Join-Path $payloadDir '版本.txt'
@(
    "声核网关 $version",
    "打包时间 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    '',
    '网关核心：gateway-core.exe（Node 单文件运行时，内含状态页）',
    '托盘程序：声核网关.exe',
    '配置文件：gateway.config.json（保留已有配置，不覆盖）',
    '状态目录：.state（授权凭证，安装与升级都不会删除）'
) | Set-Content -Path $versionFile -Encoding UTF8
$payloadFiles += @{ Source = $versionFile; Name = '版本.txt' }

foreach ($file in $payloadFiles) {
    $source = $file.Source
    $target = Join-Path $payloadDir ($file.Name + '.gz')
    $input = [System.IO.File]::OpenRead($source)
    try {
        $output = [System.IO.File]::Create($target)
        try {
            $gzip = New-Object System.IO.Compression.GZipStream($output, [System.IO.Compression.CompressionLevel]::Optimal)
            try { $input.CopyTo($gzip) } finally { $gzip.Dispose() }
        } finally { $output.Dispose() }
    } finally { $input.Dispose() }
    $sourceSize = (Get-Item $source).Length
    $targetSize = (Get-Item $target).Length
    $ratio = if ($sourceSize -gt 0) { [math]::Round($targetSize / $sourceSize * 100) } else { 0 }
    Write-Host ("   {0}  {1:N1} MB -> {2:N1} MB ({3}%)" -f $file.Name,
        ($sourceSize / 1MB), ($targetSize / 1MB), $ratio)
}
Remove-Item $versionFile -Force

Write-Host "== 2/5 准备图标 ==" -ForegroundColor Cyan
$iconSource = Join-Path $gatewayRoot 'desktop\GatewayTray\app.ico'
$iconTarget = Join-Path $setupProject 'app.ico'
Copy-Item $iconSource $iconTarget -Force

Write-Host "== 3/5 编译安装程序 ==" -ForegroundColor Cyan
Push-Location $setupProject
try {
    dotnet publish GatewaySetup.csproj -c Release -r win-x64 --self-contained false `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
        -o (Join-Path $setupProject 'obj\publish') | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'dotnet publish 失败' }
} finally {
    Pop-Location
}

Write-Host "== 4/5 汇总输出 ==" -ForegroundColor Cyan
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$built = Join-Path $setupProject 'obj\publish\声核网关安装程序.exe'
if (-not (Test-Path $built)) { throw "未找到编译产物：$built" }
$final = Join-Path $outputDir '声核网关安装程序.exe'
Copy-Item $built $final -Force
Copy-Item (Join-Path $gatewayRoot 'desktop\GatewayTray\使用说明.txt') (Join-Path $outputDir '使用说明.txt') -Force

Write-Host "== 5/5 清除下载来源并校验 ==" -ForegroundColor Cyan
foreach ($file in @($final, (Join-Path $outputDir '使用说明.txt'))) {
    try { Unblock-File -Path $file -ErrorAction Stop } catch { }
}
$item = Get-Item $final
$hash = (Get-FileHash $final -Algorithm SHA256).Hash
Write-Host ("安装程序：{0}  {1:N1} MB" -f $item.FullName, ($item.Length / 1MB)) -ForegroundColor Green
Write-Host "SHA256：$hash" -ForegroundColor Green