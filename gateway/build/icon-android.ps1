param(
    [string]$SourcePng = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'gateway\build\icon-original.png'),
    [string]$ResRoot   = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app\src\main\res')
)

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Image]::FromFile($SourcePng)
try {
    function Write-Icon([string]$dir, [string]$name, [int]$size) {
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.Clear([System.Drawing.Color]::Transparent)
            $g.DrawImage($source, 0, 0, $size, $size)
        } finally { $g.Dispose() }
        $path = Join-Path $dir $name
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        Write-Host ("  {0}  {1}px" -f $path, $size)
    }

    # 旧的非法嵌套目录（res/mipmap/mdpi 等）必须移除，AAPT2 会忽略它们
    $legacyNested = Join-Path $ResRoot 'mipmap'
    if (Test-Path $legacyNested) {
        Remove-Item $legacyNested -Recurse -Force
        Write-Host "removed invalid nested dir: $legacyNested"
    }

    $densities = @(
        @{ Dir = 'mipmap-mdpi';    Size = 48 },
        @{ Dir = 'mipmap-hdpi';    Size = 72 },
        @{ Dir = 'mipmap-xhdpi';   Size = 96 },
        @{ Dir = 'mipmap-xxhdpi';  Size = 144 },
        @{ Dir = 'mipmap-xxxhdpi'; Size = 192 }
    )
    foreach ($d in $densities) {
        $dir = Join-Path $ResRoot $d.Dir
        Write-Icon $dir 'ic_launcher.png' $d.Size
        Write-Icon $dir 'ic_launcher_round.png' $d.Size
    }

    # 无密度限定的兜底图标
    Write-Icon (Join-Path $ResRoot 'drawable') 'ic_launcher_full.png' 512
}
finally {
    $source.Dispose()
}