param(
    [Parameter(Mandatory = $true)][string]$OutputIcon,
    [Parameter(Mandatory = $true)][string]$OutputAndroidDir,
    [int]$CanvasSize = 1024
)

$ErrorActionPreference = 'Stop'
$canvas = New-Object System.Drawing.Bitmap($CanvasSize, $CanvasSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($canvas)
try {
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality

    $outer = New-Object System.Drawing.Drawing2D.GraphicsPath
    $outer.AddArc(40, 40, 190, 190, 180, 90)
    $outer.AddArc($CanvasSize - 230, 40, 190, 190, 270, 90)
    $outer.AddArc($CanvasSize - 230, $CanvasSize - 230, 190, 190, 0, 90)
    $outer.AddArc(40, $CanvasSize - 230, 190, 190, 90, 90)
    $outer.CloseFigure()
    $outerBounds = New-Object System.Drawing.Rectangle(24, 24, ($CanvasSize - 48), ($CanvasSize - 48))
    $outerBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($outerBounds, [System.Drawing.Color]::FromArgb(18, 84, 196), [System.Drawing.Color]::FromArgb(145, 58, 185), 45)
    $graphics.FillPath($outerBrush, $outer)
    $outerPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(80, 221, 255, 255), 18)
    $graphics.DrawPath($outerPen, $outer)

    $inner = New-Object System.Drawing.Drawing2D.GraphicsPath
    $inner.AddArc(74, 74, 156, 156, 180, 90)
    $inner.AddArc($CanvasSize - 230, 74, 156, 156, 270, 90)
    $inner.AddArc($CanvasSize - 230, $CanvasSize - 230, 156, 156, 0, 90)
    $inner.AddArc(74, $CanvasSize - 230, 156, 156, 90, 90)
    $inner.CloseFigure()
    $innerBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush((New-Object System.Drawing.Rectangle(64, 64, ($CanvasSize - 128), ($CanvasSize - 128))), [System.Drawing.Color]::FromArgb(28, 35, 98), [System.Drawing.Color]::FromArgb(62, 44, 154), 45)
    $graphics.FillPath($innerBrush, $inner)

    $cyan = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(58, 224, 242))
    $green = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(178, 255, 192))
    $purple = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(213, 126, 247))
    $blue = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(85, 154, 255))
    $panelPen = New-Object System.Drawing.Pen($cyan, 22)
    $graphics.DrawRectangle($panelPen, 330, 350, 364, 330)
    $graphics.FillRectangle([System.Drawing.Brushes]::White, 366, 386, 292, 258)

    foreach ($x in @(410, 474, 538, 602, 666)) {
        $graphics.FillRectangle($blue, $x, 420, 22, 190)
    }
    $graphics.FillRectangle($green, 398, 470, 46, 42)
    $graphics.FillRectangle($purple, 462, 522, 46, 42)
    $graphics.FillRectangle($cyan, 526, 448, 46, 42)
    $graphics.FillRectangle($blue, 590, 500, 46, 42)
    $graphics.FillRectangle($purple, 654, 552, 46, 42)

    $arcPen = New-Object System.Drawing.Pen($green, 24)
    $graphics.DrawArc($arcPen, (New-Object System.Drawing.Rectangle(320, 160, 384, 260)), 195, 150)
    $arcPen = New-Object System.Drawing.Pen($cyan, 24)
    $graphics.DrawArc($arcPen, (New-Object System.Drawing.Rectangle(390, 230, 244, 180)), 195, 150)

    $graphics.FillEllipse($cyan, 98, 392, 92, 92)
    $graphics.FillRectangle($cyan, 132, 220, 24, 190)
    $graphics.FillRectangle($green, 156, 220, 92, 24)
    $graphics.FillEllipse($purple, $CanvasSize - 190, 730, 92, 92)
    $graphics.FillRectangle($purple, ($CanvasSize - 166), 560, 24, 190)
    $graphics.FillRectangle($blue, ($CanvasSize - 258), 560, 92, 24)

    $wavePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(130, 164, 255), 24)
    foreach ($x in @(220, 280, 340, 400, 460, 520, 580, 640, 700, 760, 820)) {
        $height = @(80, 130, 190, 110, 240, 160, 90, 210, 140, 190, 100)[($x - 220) / 60]
        $y1 = [Math]::Round(($CanvasSize - 110 - ($height / 2)))
        $graphics.DrawLine($wavePen, $x, $y1, $x, ($y1 + $height))
    }
}
finally {
    $graphics.Dispose()
}

$iconDirectory = Split-Path $OutputIcon -Parent
New-Item -ItemType Directory -Path $iconDirectory -Force | Out-Null
$tempPng = Join-Path ([System.IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N') + '.png')
$canvas.Save($tempPng, [System.Drawing.Imaging.ImageFormat]::Png)
$pngBytes = [System.IO.File]::ReadAllBytes($tempPng)
Remove-Item $tempPng -Force

$sizes = @(16, 20, 24, 32, 40, 48, 64, 96, 128, 192, 256)
$entries = @()
$offset = 6 + (16 * $sizes.Count)
foreach ($size in $sizes) {
    $bitmap = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.DrawImage($canvas, 0, 0, $size, $size)
    } finally {
        $graphics.Dispose()
    }
    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    $bytes = $stream.ToArray()
    $stream.Dispose()
    $entries += [pscustomobject]@{ Size = $size; Offset = $offset; Bytes = $bytes }
    $offset += $bytes.Length
}

$writer = New-Object System.IO.BinaryWriter([System.IO.File]::Create([System.IO.Path]::GetFullPath($OutputIcon)))
try {
    $writer.Write([uint16]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]$sizes.Count)
    foreach ($entry in $entries) {
        $entryByte = if ($entry.Size -ge 256) { [byte]0 } else { [byte]$entry.Size }
        $writer.Write($entryByte)
        $writer.Write($entryByte)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]32)
        $writer.Write([uint32]$entry.Bytes.Length)
        $writer.Write([uint32]$entry.Offset)
    }
    foreach ($entry in $entries) { $writer.Write($entry.Bytes) }
} finally {
    $writer.Dispose()
}

$androidDirectory = [System.IO.Path]::GetFullPath($OutputAndroidDir)
New-Item -ItemType Directory -Path $androidDirectory -Force | Out-Null
$densityMap = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($entry in $densityMap.GetEnumerator()) {
    $densityDirectory = Join-Path $androidDirectory $entry.Key
    New-Item -ItemType Directory -Path $densityDirectory -Force | Out-Null
    $target = Join-Path $densityDirectory 'ic_launcher.png'
    $bitmap = New-Object System.Drawing.Bitmap($entry.Value, $entry.Value, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.DrawImage($canvas, 0, 0, $entry.Value, $entry.Value)
    } finally { $graphics.Dispose() }
    $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}
$canvas.Dispose()
