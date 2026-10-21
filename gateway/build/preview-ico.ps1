$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile((Join-Path $repo 'gateway\desktop\GatewayTray\app.ico'))
try {
    $png = New-Object System.Drawing.Bitmap($source.Width, $source.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($png)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.DrawImage($source, 0, 0, $source.Width, $source.Height)
    } finally {
        $graphics.Dispose()
    }
    $png.Save((Join-Path $repo 'gateway\build\tray-icon-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $png.Dispose()
} finally {
    $source.Dispose()
}