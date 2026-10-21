using System.Drawing;
using System.Runtime.InteropServices;

namespace AudcoreGatewayTray;

/// <summary>
/// 直接调用 Shell_NotifyIcon 的诊断工具：用于确认资源管理器是否接受本进程的托盘图标注册。
/// </summary>
internal static class TrayProbeApi
{
    private const uint NIM_ADD = 0x00000000;
    private const uint NIM_DELETE = 0x00000002;
    private const uint NIF_MESSAGE = 0x00000001;
    private const uint NIF_ICON = 0x00000002;
    private const uint NIF_TIP = 0x00000004;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize;
        public IntPtr hWnd;
        public uint uID;
        public uint uFlags;
        public uint uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public uint dwState;
        public uint dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public uint uVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public uint dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool Shell_NotifyIcon(uint message, ref NOTIFYICONDATA data);

    public static string TryRegister(Icon icon, IntPtr windowHandle)
    {
        try
        {
            var data = new NOTIFYICONDATA
            {
                cbSize = Marshal.SizeOf<NOTIFYICONDATA>(),
                hWnd = windowHandle,
                uID = 0x4AD0,
                uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP,
                uCallbackMessage = 0x0400 + 1024,
                hIcon = icon?.Handle ?? IntPtr.Zero,
                szTip = "声核网关诊断",
                szInfo = string.Empty,
                szInfoTitle = string.Empty,
                hBalloonIcon = IntPtr.Zero,
                guidItem = Guid.Empty,
            };

            var added = Shell_NotifyIcon(NIM_ADD, ref data);
            var error = Marshal.GetLastWin32Error();
            Shell_NotifyIcon(NIM_DELETE, ref data);
            return $"直接注册返回值={added} 错误码={error}";
        }
        catch (Exception ex)
        {
            return "直接注册异常：" + ex.Message;
        }
    }
}
