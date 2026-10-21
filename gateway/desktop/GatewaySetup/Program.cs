using System.Runtime.InteropServices;

namespace AudcoreGatewaySetup;

internal static class Program
{
    public const string AppName = "声核网关";
    public const string AppVersion = "1.0.0";
    public const string Author = "Ciel";
    public const string Contact = "Apple_Hkc";

    [STAThread]
    private static int Main(string[] args)
    {
        var silent = args.Any(a => a.Equals("--silent", StringComparison.OrdinalIgnoreCase)
                                   || a.Equals("/S", StringComparison.OrdinalIgnoreCase));
        var uninstall = args.Any(a => a.Equals("--uninstall", StringComparison.OrdinalIgnoreCase)
                                      || a.Equals("/uninstall", StringComparison.OrdinalIgnoreCase));
        var cleanupOnly = args.Any(a => a.Equals("--silent-cleanup", StringComparison.OrdinalIgnoreCase));
        var elevated = args.Any(a => a.Equals("--elevated", StringComparison.OrdinalIgnoreCase));
        var logPrefix = args
            .FirstOrDefault(a => a.StartsWith("--log-prefix=", StringComparison.OrdinalIgnoreCase))
            ?.Substring("--log-prefix=".Length);

        ApplicationConfiguration.Initialize();
        Installer.SetLogPrefix(logPrefix);

        if (cleanupOnly)
        {
            Installer.SilentCleanup();
            return 0;
        }

        if (uninstall)
        {
            if (silent)
            {
                try
                {
                    Installer.Uninstall(null);
                    return 0;
                }
                catch (Exception ex)
                {
                    Installer.WriteLog("静默卸载失败：" + ex);
                    return 3;
                }
            }

            using var form = new UninstallForm();
            Application.Run(form);
            return form.Success ? 0 : 1;
        }

        if (silent)
        {
            try
            {
                Installer.Install(new InstallOptions
                {
                    DesktopShortcut = true,
                    AutoStart = true,
                    RunAfterInstall = false,
                }, null);
                return 0;
            }
            catch (ElevationRequiredException ex)
            {
                Installer.WriteLog("静默安装：普通权限写入被拒，改为请求管理员权限重试；" + ex.Message);
                if (!elevated && Installer.TryElevate(args, out var elevatedExitCode))
                {
                    return elevatedExitCode;
                }

                Installer.WriteLog("静默安装失败：" + ex);
                return 3;
            }
            catch (Exception ex)
            {
                Installer.WriteLog("静默安装失败：" + ex);
                return 3;
            }
        }

        using var setup = new SetupForm();
        Application.Run(setup);
        return setup.Success ? 0 : 1;
    }
}

internal sealed class InstallOptions
{
    public bool DesktopShortcut { get; set; } = true;
    public bool AutoStart { get; set; } = true;
    public bool RunAfterInstall { get; set; } = true;
}
