$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Add-Type -AssemblyName System.Drawing

$sourcePath = (Join-Path $repo 'gateway\build\icon-original.png')
$icoTargets = @(
    (Join-Path $repo 'gateway\desktop\GatewayTray\app.ico'),
    (Join-Path $repo 'gateway\desktop\GatewaySetup\app.ico')
)
$androidMipmapDir = (Join-Path $repo 'app\src\main\res\mipmap')

$source = [System.Drawing.Image]::FromFile($sourcePath)
try {
    # Build multi-size ICO (PNG-compressed entries)
    $sizes = @(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
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
            $graphics.DrawImage($source, 0, 0, $size, $size)
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

    foreach ($target in $icoTargets) {
        $writer = New-Object System.IO.BinaryWriter([System.IO.File]::Create($target))
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
        Write-Host "ICO written: $target"
    }

    # Android raster icons
    $densityMap = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
    foreach ($entry in $densityMap.GetEnumerator()) {
        $densityDirectory = Join-Path $androidMipmapDir $entry.Key
        New-Item -ItemType Directory -Path $densityDirectory -Force | Out-Null
        foreach ($name in @('ic_launcher.png', 'ic_launcher_round.png')) {
            $target = Join-Path $densityDirectory $name
            $bitmap = New-Object System.Drawing.Bitmap($entry.Value, $entry.Value, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.DrawImage($source, 0, 0, $entry.Value, $entry.Value)
            } finally { $graphics.Dispose() }
            $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
            $bitmap.Dispose()
        }
    }
    Write-Host "Android raster icons written."
} finally {
    $source.Dispose()
}