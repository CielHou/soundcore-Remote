$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FocusGateway {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int command);
}
"@
$p = Get-Process -Name '声核网关' -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { throw 'Gateway window not found' }
[void][FocusGateway]::ShowWindow($p.MainWindowHandle, 9)
[void][FocusGateway]::SetForegroundWindow($p.MainWindowHandle)
Start-Sleep -Seconds 1
$source = Get-Process -Name '声核网关' | Where-Object { $_.Id -ne $p.Id } | Select-Object -First 1
if ($source) { Stop-Process -Id $source.Id -Force }
Start-Sleep -Seconds 1
Get-Process -Name '声核网关','gateway-core' -ErrorAction SilentlyContinue | Select-Object ProcessName,Id,MainWindowTitle
