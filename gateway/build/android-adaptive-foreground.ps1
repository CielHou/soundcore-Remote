param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Add-Type -AssemblyName System.Drawing

$sourcePath = (Join-Path $repo 'gateway\build\icon-original.png')
$image = [System.Drawing.Image]::FromFile($sourcePath)
try {
    $canvasSize = 432
    $bitmap = New-Object System.Drawing.Bitmap($canvasSize, $canvasSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.Clear([System.Drawing.Color]::Transparent)

        $foregroundSize = 288
        $offset = ($canvasSize - $foregroundSize) / 2
        $graphics.DrawImage($image, [float]$offset, [float]$offset, $foregroundSize, $foregroundSize)
    } finally { $graphics.Dispose() }

    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host "Adaptive foreground written: $OutputPath"
} finally {
    $image.Dispose()
}