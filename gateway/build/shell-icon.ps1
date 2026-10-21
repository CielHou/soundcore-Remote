param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Output
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class ShellIconApi {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
  public struct SHFILEINFO {
    public IntPtr hIcon;
    public int iIcon;
    public uint dwAttributes;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szDisplayName;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string szTypeName;
  }
  [DllImport("shell32.dll", CharSet = CharSet.Auto)]
  public static extern IntPtr SHGetFileInfo(string path, uint attrs, ref SHFILEINFO info, uint size, uint flags);
  [DllImport("user32.dll")]
  public static extern bool DestroyIcon(IntPtr hIcon);
}
"@

$info = New-Object ShellIconApi+SHFILEINFO
$size = [uint32][System.Runtime.InteropServices.Marshal]::SizeOf($info)
$flags = [uint32]0x100
$result = [ShellIconApi]::SHGetFileInfo($Path, 0, [ref]$info, $size, $flags)
if ($info.hIcon -eq [IntPtr]::Zero) {
    Write-Host "no icon returned for $Path"
    exit 1
}

$icon = [System.Drawing.Icon]::FromHandle($info.hIcon)
$bitmap = $icon.ToBitmap()
$bitmap.Save($Output, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host ("saved {0} ({1}x{2})" -f $Output, $bitmap.Width, $bitmap.Height)
$bitmap.Dispose()
$icon.Dispose()
[void][ShellIconApi]::DestroyIcon($info.hIcon)
