param(
  [Parameter(Mandatory = $true)][int]$ProcessId,
  [Parameter(Mandatory = $true)][string]$Path,
  [switch]$ToggleQr,
  [int]$ClickX,
  [int]$ClickY
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WindowCaptureApi {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT point);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int command);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, IntPtr extra);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@
$process = Get-Process -Id $ProcessId -ErrorAction Stop
if ($process.MainWindowHandle -eq 0) { throw "PID $ProcessId has no main window" }
[void][WindowCaptureApi]::ShowWindowAsync($process.MainWindowHandle, 9)
[void][WindowCaptureApi]::BringWindowToTop($process.MainWindowHandle)
[void][WindowCaptureApi]::SetForegroundWindow($process.MainWindowHandle)
Start-Sleep -Milliseconds 500
if ($ToggleQr) {
  $point = New-Object WindowCaptureApi+POINT
  $point.X = $ClickX
  $point.Y = $ClickY
  [void][WindowCaptureApi]::ClientToScreen($process.MainWindowHandle, [ref]$point)
  [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($point.X, $point.Y)
  [WindowCaptureApi]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero)
  [WindowCaptureApi]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 1000
}
$wr = New-Object WindowCaptureApi+RECT
[void][WindowCaptureApi]::GetWindowRect($process.MainWindowHandle, [ref]$wr)
$width = $wr.Right - $wr.Left
$height = $wr.Bottom - $wr.Top
$bmp = New-Object System.Drawing.Bitmap($width, $height)
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($wr.Left, $wr.Top, 0, 0, (New-Object System.Drawing.Size($width, $height)))
$bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose()
$bmp.Dispose()
Write-Output $Path

