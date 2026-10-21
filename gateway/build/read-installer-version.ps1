$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class SetupVersionReader {
  [DllImport("version.dll", CharSet = CharSet.Unicode)]
  public static extern bool GetFileVersionInfo(string path, int handle, int size, byte[] data);
  [DllImport("version.dll", CharSet = CharSet.Unicode)]
  public static extern int GetFileVersionInfoSize(string path, out int handle);
  [DllImport("version.dll", CharSet = CharSet.Unicode)]
  public static extern bool VerQueryValue(byte[] data, string subBlock, out IntPtr buffer, out int length);
}
"@
$path = (Join-Path $repo '安装包\声核网关安装程序.exe')
$handle = 0
$size = [SetupVersionReader]::GetFileVersionInfoSize($path, [ref]$handle)
if ($size -le 0) { throw "无法读取版本资源" }
$data = New-Object byte[] $size
if (-not [SetupVersionReader]::GetFileVersionInfo($path, 0, $size, $data)) { throw "无法读取版本资源" }
$buffer = [IntPtr]::Zero; $length = 0
if (-not [SetupVersionReader]::VerQueryValue($data, '\', [ref]$buffer, [ref]$length)) { throw "无法查询版本信息" }
$structure = [Runtime.InteropServices.Marshal]::PtrToStructure($buffer, [type][Runtime.InteropServices.StructLayout]::Sequential -as [type])
[pscustomobject]@{FileVersion=([Runtime.InteropServices.Marshal]::PtrToStringUni($buffer + 8));ProductVersion=([Runtime.InteropServices.Marshal]::PtrToStringUni($buffer + 18));Length=(Get-Item $path).Length;Modified=(Get-Item $path).LastWriteTime;SHA256=(Get-FileHash $path -Algorithm SHA256).Hash}