# 生成托盘/程序图标 app.ico（多尺寸 PNG 压缩 ICO）
Add-Type -AssemblyName System.Drawing

$outFile = Join-Path $PSScriptRoot 'app.ico'
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = @()

foreach ($size in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $rect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $radius = [float]($size * 0.24)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $radius * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()

    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml('#5B6BE8'),
        [System.Drawing.ColorTranslator]::FromHtml('#3A45B8'),
        45.0)
    $g.FillPath($brush, $path)

    # 耳机弧线
    $penWidth = [float]($size * 0.085)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, $penWidth)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $arcRect = New-Object System.Drawing.RectangleF(
        [float]($size * 0.24), [float]($size * 0.26),
        [float]($size * 0.52), [float]($size * 0.52))
    $g.DrawArc($pen, $arcRect, 185, 170)

    # 左右耳罩
    $cupW = [float]($size * 0.13)
    $cupH = [float]($size * 0.30)
    $cupY = [float]($size * 0.52)
    $cupBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $left = New-Object System.Drawing.RectangleF([float]($size * 0.205), $cupY, $cupW, $cupH)
    $right = New-Object System.Drawing.RectangleF([float]($size * 0.665), $cupY, $cupW, $cupH)
    $cupR = [float]($size * 0.06)
    foreach ($cup in @($left, $right)) {
        $cupPath = New-Object System.Drawing.Drawing2D.GraphicsPath
        $cd = $cupR * 2
        $cupPath.AddArc($cup.X, $cup.Y, $cd, $cd, 180, 90)
        $cupPath.AddArc($cup.Right - $cd, $cup.Y, $cd, $cd, 270, 90)
        $cupPath.AddArc($cup.Right - $cd, $cup.Bottom - $cd, $cd, $cd, 0, 90)
        $cupPath.AddArc($cup.X, $cup.Bottom - $cd, $cd, $cd, 90, 90)
        $cupPath.CloseFigure()
        $g.FillPath($cupBrush, $cupPath)
        $cupPath.Dispose()
    }

    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $images += , @{ Size = $size; Bytes = $ms.ToArray() }
    $ms.Dispose()
    $bmp.Dispose()
    $brush.Dispose()
    $pen.Dispose()
    $cupBrush.Dispose()
    $path.Dispose()
}

$fs = [System.IO.File]::Create($outFile)
$writer = New-Object System.IO.BinaryWriter($fs)

# ICONDIR
$writer.Write([uint16]0)
$writer.Write([uint16]1)
$writer.Write([uint16]$images.Count)

$offset = 6 + 16 * $images.Count
foreach ($image in $images) {
    $dim = if ($image.Size -ge 256) { 0 } else { $image.Size }
    $writer.Write([byte]$dim)
    $writer.Write([byte]$dim)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$image.Bytes.Length)
    $writer.Write([uint32]$offset)
    $offset += $image.Bytes.Length
}

foreach ($image in $images) {
    $writer.Write($image.Bytes)
}

$writer.Flush()
$writer.Dispose()
$fs.Dispose()
Write-Output "已生成 $outFile ($([System.IO.FileInfo]::new($outFile).Length) bytes, $($images.Count) 个尺寸)"
