param(
  [Parameter(Mandatory = $true)][string]$Path,
  [switch]$ToggleQr
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class NativeCapture {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT point);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, IntPtr extra);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@
$p = Get-Process -Name '声核网关' -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { throw 'No gateway window found.' }
[void][NativeCapture]::SetForegroundWindow($p.MainWindowHandle)
Start-Sleep -Milliseconds 500
if ($ToggleQr) {
  $rect = New-Object NativeCapture+RECT
  [void][NativeCapture]::GetClientRect($p.MainWindowHandle, [ref]$rect)
  $point = New-Object NativeCapture+POINT
  $point.X = 376
  $point.Y = 281
  [void][NativeCapture]::ClientToScreen($p.MainWindowHandle, [ref]$point)
  [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($point.X, $point.Y)
  [NativeCapture]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
  [NativeCapture]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 800
}
$wr = New-Object NativeCapture+RECT
[void][NativeCapture]::GetWindowRect($p.MainWindowHandle, [ref]$wr)
$width = $wr.Right - $wr.Left
$height = $wr.Bottom - $wr.Top
if ($width -le 0 -or $height -le 0) { throw 'Invalid window rectangle.' }
$bmp = New-Object System.Drawing.Bitmap($width, $height)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($wr.Left, $wr.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
$bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose()
$bmp.Dispose()
Write-Output $Path
