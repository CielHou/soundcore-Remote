using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace AudcoreGatewaySetup;

internal static class Installer
{
    public const string InstallDirName = "AudcoreGateway";
    public const string TrayExeName = "声核网关.exe";
    public const string UninstallExeName = "卸载声核网关.exe";
    public const string ConfigName = "gateway.config.json";
    private const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\AudcoreGateway";
    private static string? _logPrefix;

    public static string InstallDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), InstallDirName);

    public static string TrayExePath => Path.Combine(InstallDir, TrayExeName);

    public static string DesktopShortcutPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Program.AppName + ".lnk");

    public static string StartMenuDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Programs), Program.AppName);

    public static string StartupShortcutPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Startup), Program.AppName + ".lnk");

    public static void SetLogPrefix(string prefix)
    {
        _logPrefix = string.IsNullOrWhiteSpace(prefix) ? null : prefix.Trim();
    }

    private static string LogFileName => _logPrefix == null ? "setup.log" : "setup-" + _logPrefix + ".log";

    private static string LogFilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        InstallDirName,
        LogFileName);

    /// <summary>界面上提示给用户的日志位置（优先安装程序同目录）。</summary>
    public static string LogFilePathForDisplay => Path.Combine(AppContext.BaseDirectory, LogFileName);

    public static void WriteLog(string message)
    {
        var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {message}{Environment.NewLine}";
        foreach (var candidate in new[]
                 {
                     Path.Combine(AppContext.BaseDirectory, LogFileName),
                     LogFilePath,
                     Path.Combine(Path.GetTempPath(), "audcore-gateway-setup.log"),
                 })
        {
            try
            {
                File.AppendAllText(candidate, line);
                return;
            }
            catch
            {
                // 换下一个位置
            }
        }
    }

    public static void Install(InstallOptions options, Action<int, string> progress)
    {
        var report = progress ?? ((_, _) => { });
        Directory.CreateDirectory(InstallDir);
        WriteLog($"开始安装到 {InstallDir}；提权={IsElevated()}");

        report(5, "停止正在运行的网关…");
        StopRunning();
        CleanupStaleStaging();

        report(15, "释放程序文件…");
        ExtractPayload();

        report(55, "创建快捷方式…");
        CreateShortcuts(options);

        report(70, "注册卸载信息…");
        RegisterUninstall();
        CopyUninstaller();

        report(85, "写入开机启动设置…");
        ApplyAutoStart(options.AutoStart);

        report(92, "启动网关…");
        if (options.RunAfterInstall)
        {
            StartTray();
        }

        report(100, "安装完成");
        WriteLog("安装完成");
    }

    /// <summary>普通用户安装目录被旧的提权进程或 ACL 锁住时，以本程序自身提权重启；用户取消时返回 false。</summary>
    public static bool TryElevate(string[] args, out int exitCode)
    {
        exitCode = 1;
        try
        {
            var self = Environment.ProcessPath;
            if (string.IsNullOrEmpty(self))
            {
                WriteLog("无法提权：找不到当前安装程序路径");
                return false;
            }

            var childArgs = args
                .Where(a => !a.Equals("--elevated", StringComparison.OrdinalIgnoreCase))
                .Append("--elevated")
                .Append("--log-prefix=child")
                .ToArray();
            var psi = new ProcessStartInfo(self)
            {
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = Path.GetDirectoryName(self) ?? Environment.CurrentDirectory,
                Arguments = string.Join(" ", childArgs.Select(QuoteArgument)),
            };
            WriteLog("正在请求管理员权限并启动提权安装进程");
            using var process = Process.Start(psi);
            if (process == null)
            {
                WriteLog("管理员权限安装进程启动失败：Process.Start 返回空");
                return false;
            }

            WriteLog("已请求管理员权限，提权进程 ID=" + process.Id);
            var exitedQuickly = process.WaitForExit(3000);
            if (exitedQuickly)
            {
                WriteLog("提权进程快速退出；ExitCode=" + process.ExitCode);
                exitCode = process.ExitCode == 0 ? 0 : 1;
                return false;
            }

            WriteLog("已进入提权安装等待；普通权限安装进程即将退出");
            exitCode = 0;
            return true;
        }
        catch (Exception ex)
        {
            WriteLog("请求管理员权限失败：" + ex.Message);
            return false;
        }
    }

    /// <summary>安装目录写入探针；返回 false 时安装到该目录会失败。</summary>
    public static bool CanWriteInstallDir()
    {
        // 探针文件用唯一名字，避免历史残留文件（可能由管理员进程创建）导致误判。
        var probe = Path.Combine(InstallDir, ".setup-permission-probe-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(InstallDir);
            File.WriteAllText(probe, DateTime.Now.ToString("O"));
            return true;
        }
        catch (Exception ex)
        {
            WriteLog($"普通权限安装目录探测失败：{ex.GetType().Name} {ex.Message}");
            // 探测失败不代表目录不可写（某些安全软件会拦截临时探针），
            // 真正写程序文件时还会再重试；这里返回 true 让安装继续，避免误弹提权。
            return true;
        }
        finally
        {
            try
            {
                if (File.Exists(probe))
                {
                    File.Delete(probe);
                }
            }
            catch
            {
                // 清理失败不影响安装
            }
        }
    }

    private static string QuoteArgument(string value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return "\"\"";
        }

        // Windows/C# 参数只处理反斜杠前的引号，反斜杠本身必须原样保留
        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }

    private static bool IsElevated()
    {
        try
        {
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var principal = new System.Security.Principal.WindowsPrincipal(identity);
            return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    public static void Uninstall(Action<int, string> progress)
    {
        var report = progress ?? ((_, _) => { });
        WriteLog("开始卸载");

        report(10, "停止网关进程…");
        StopRunning();

        report(30, "删除快捷方式…");
        DeleteFileQuiet(DesktopShortcutPath);
        DeleteFileQuiet(StartupShortcutPath);
        try
        {
            if (Directory.Exists(StartMenuDir))
            {
                Directory.Delete(StartMenuDir, true);
            }
        }
        catch (Exception ex)
        {
            WriteLog("删除开始菜单失败：" + ex.Message);
        }

        report(50, "清理注册表…");
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKeyPath, false);
        }
        catch (Exception ex)
        {
            WriteLog("删除注册表失败：" + ex.Message);
        }

        report(70, "删除安装目录…");
        // 卸载程序自身就在安装目录里，先复制到临时目录避免占用
        var selfPath = Environment.ProcessPath;
        var runningFromInstallDir = selfPath != null &&
                                    selfPath.StartsWith(InstallDir, StringComparison.OrdinalIgnoreCase);
        if (runningFromInstallDir)
        {
            var tempCopy = Path.Combine(Path.GetTempPath(), "audcore-gateway-uninstall-" + Guid.NewGuid().ToString("N") + ".exe");
            File.Copy(selfPath!, tempCopy, true);
            var psi = new ProcessStartInfo(tempCopy, "--uninstall --silent-cleanup")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            Process.Start(psi);
            report(100, "已在后台完成清理");
            return;
        }

        DeleteDirectoryWithRetry(InstallDir);
        report(100, "卸载完成");
        WriteLog("卸载完成");
    }

    /// <summary>卸载程序被复制到临时目录后执行的实际清理。</summary>
    public static void SilentCleanup()
    {
        StopRunning();
        DeleteDirectoryWithRetry(InstallDir);
        WriteLog("静默清理完成");
    }

    private static void ExtractPayload()
    {
        CleanupStaleStaging();
        var assembly = typeof(Installer).Assembly;
        var resources = assembly.GetManifestResourceNames()
            .Where(n => n.StartsWith("payload/", StringComparison.Ordinal) && n.EndsWith(".gz", StringComparison.Ordinal))
            .ToArray();

        if (resources.Length == 0)
        {
            throw new InvalidOperationException("安装包内没有程序文件（payload 为空）");
        }

        foreach (var resource in resources)
        {
            var fileName = resource["payload/".Length..^".gz".Length];
            var target = Path.Combine(InstallDir, fileName);

            // 配置文件只在缺失时写入，避免覆盖已有的端口/token 设置
            if (fileName.Equals(ConfigName, StringComparison.OrdinalIgnoreCase) && File.Exists(target))
            {
                WriteLog("保留已有配置 " + target);
                continue;
            }

            if (fileName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                StopIfTargetRunning(target);
            }

            using var stream = assembly.GetManifestResourceStream(resource);
            if (stream == null)
            {
                throw new InvalidOperationException("无法读取安装包资源：" + resource);
            }

            using var memory = new MemoryStream();
            stream.CopyTo(memory);
            memory.Position = 0;

            WritePayloadFile(target, memory);
            WriteLog("释放 " + target);
        }
    }

    /// <summary>写入载荷文件；被占用时先停止本目录程序、备份旧文件，再重试。</summary>
    /// <summary>写文件前的环境诊断：身份、目录探针、目标是否存在。</summary>
    private static void ProbeWriteAccess(string target, Stream source)
    {
        try
        {
            var identity = System.Security.Principal.WindowsIdentity.GetCurrent().Name;
            WriteLog($"诊断：身份={identity} 目标={target} 存在={File.Exists(target)} 长度={(File.Exists(target) ? new FileInfo(target).Length : -1)}");
        }
        catch (Exception ex)
        {
            WriteLog("诊断身份失败：" + ex.Message);
        }

        try
        {
            var probe = Path.Combine(InstallDir, ".setup-write-probe");
            File.WriteAllText(probe, DateTime.Now.ToString("O"));
            File.Delete(probe);
            WriteLog("诊断：安装目录可写");
        }
        catch (Exception ex)
        {
            WriteLog("诊断：安装目录不可写 " + ex.GetType().Name + " " + ex.Message);
        }

        if (!File.Exists(target))
        {
            return;
        }

        try
        {
            using var handle = new FileStream(target, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            WriteLog("诊断：可以独占打开目标文件（无进程占用）");
        }
        catch (Exception ex)
        {
            WriteLog($"诊断：目标文件被占用/不可写 {ex.GetType().Name} {ex.Message}");
        }
    }

    /// <summary>普通权限安装时，如果目标 exe 已被另一用户权限的运行实例占用，无法覆盖；先备份原文件再清理。</summary>
    private static void CleanupStaleStaging()
    {
        foreach (var suffix in new[] { ".old", ".new" })
        {
            var path = Path.Combine(InstallDir, "gateway-core.exe" + suffix);
            if (!File.Exists(path))
            {
                continue;
            }

            try
            {
                File.SetAttributes(path, FileAttributes.Normal);
                File.Delete(path);
                WriteLog("清理旧临时文件 " + path);
            }
            catch (Exception ex)
            {
                WriteLog("清理旧临时文件失败 " + path + "：" + ex.Message);
            }
        }
    }

    private static void WritePayloadFile(string target, Stream source)
    {
        ProbeWriteAccess(target, source);

        Exception lastError = null;
        for (var attempt = 1; attempt <= 8; attempt++)
        {
            try
            {
                using var gzip = new GZipStream(source, CompressionMode.Decompress, true);
                using var output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None);
                gzip.CopyTo(output);
                WriteLog("写入成功 " + target);
                return;
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException || ex is IOException)
            {
                lastError = ex;
                WriteLog($"写入 {target} 第 {attempt} 次失败：{ex.Message}");
                StopRunning();
                TryBackupAndRemove(target);
                if (source.CanSeek)
                {
                    source.Seek(0, SeekOrigin.Begin);
                }

                Thread.Sleep(600);
            }
        }

        if (lastError is UnauthorizedAccessException && !IsElevated())
        {
            // 旧版网关可能仍以管理员身份在运行，普通权限既杀不掉也覆盖不了；
            // 交给上层改为请求管理员权限后重装（与 1.3.x 行为一致）。
            throw new ElevationRequiredException("普通权限无法写入 " + target, lastError);
        }

        throw new InvalidOperationException("无法写入 " + target + "：" + lastError?.Message, lastError);
    }

    /// <summary>普通权限无法覆盖管理员启动的旧版程序时，把原文件改名，腾出目标路径；改名也会失败则保留旧文件。</summary>
    private static void TryBackupAndRemove(string target)
    {
        if (!File.Exists(target))
        {
            return;
        }

        var backup = target + ".old";
        try
        {
            File.Delete(backup);
        }
        catch
        {
            // 忽略旧备份删除失败，下面的 Move 会给出明确错误
        }

        try
        {
            File.Move(target, backup, true);
            WriteLog("已备份旧文件 " + target + " -> " + backup);
        }
        catch (Exception ex)
        {
            WriteLog("无法备份旧文件 " + target + "：" + ex.Message);
        }
    }

    private static void StopIfTargetRunning(string target)
    {
        var name = Path.GetFileNameWithoutExtension(target);
        var expectedPath = Path.GetFullPath(target);
        foreach (var process in Process.GetProcesses())
        {
            try
            {
                if (!process.ProcessName.Equals(name, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                string path = null;
                try
                {
                    path = process.MainModule?.FileName;
                }
                catch
                {
                    // 同上：无权读取模块时先保留，写入失败后由重试逻辑提升权限处理
                }

                if (!string.IsNullOrEmpty(path) &&
                    !string.Equals(Path.GetFullPath(path), expectedPath, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                try
                {
                    // 同名进程路径可能来自另一个用户或无法读取；先尝试优雅停止，无法停止时仍继续尝试提权安装。
                    process.CloseMainWindow();
                }
                catch
                {
                    // 无窗口或窗口消息失败
                }

                process.Kill(true);
                process.WaitForExit(3000);
            }
            catch
            {
                // 忽略无法结束的进程
            }
            finally
            {
                process.Dispose();
            }
        }
    }

    private static void StopRunning()
    {
        StopNamedProcess("声核网关");
        StopNamedProcess("gateway-core");
        // 托管方式读不到对方路径（例如对方以管理员运行）时，再用 taskkill 兜底
        KillByNameForce(TrayExeName);
        KillByNameForce("gateway-core.exe");
        Thread.Sleep(300);
    }

    /// <summary>taskkill 兜底：同名进程仍在运行时强制结束，避免旧文件被占用导致写入失败。</summary>
    private static void KillByNameForce(string exeName)
    {
        var baseName = Path.GetFileNameWithoutExtension(exeName);
        try
        {
            if (Process.GetProcessesByName(baseName).Length == 0)
            {
                return;
            }
        }
        catch
        {
            // 枚举失败也继续尝试 taskkill
        }

        try
        {
            var psi = new ProcessStartInfo("taskkill.exe", $"/F /T /IM \"{exeName}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(psi);
            if (process != null)
            {
                process.WaitForExit(8000);
                WriteLog($"taskkill {exeName}；ExitCode={process.ExitCode}");
            }
        }
        catch (Exception ex)
        {
            WriteLog($"taskkill {exeName} 失败：{ex.Message}");
        }
    }

    /// <summary>只结束本安装目录里的进程，避免管理员安装时误杀其他用户正在运行的副本。</summary>
    private static void StopNamedProcess(string expectedName)
    {
        var expectedPath = Path.GetFullPath(Path.Combine(InstallDir, expectedName + ".exe"));
        foreach (var process in Process.GetProcessesByName(expectedName))
        {
            try
            {
                if (!string.Equals(GetProcessPath(process), expectedPath, StringComparison.OrdinalIgnoreCase))
                {
                    WriteLog("跳过其他位置的进程 " + expectedName + "（PID " + process.Id + "）");
                    continue;
                }

                try
                {
                    process.CloseMainWindow();
                }
                catch
                {
                    // 无窗口或消息失败
                }

                process.Kill(true);
                if (!process.WaitForExit(3000))
                {
                    throw new TimeoutException("进程在 3 秒内未退出");
                }

                WriteLog("已停止进程 " + expectedName);
            }
            catch (Exception ex)
            {
                WriteLog($"停止 {expectedName} 失败：{ex.Message}");
            }
            finally
            {
                process.Dispose();
            }
        }
    }

    private static string GetProcessPath(Process process)
    {
        try
        {
            return Path.GetFullPath(process.MainModule?.FileName ?? string.Empty);
        }
        catch
        {
            return null;
        }
    }

    private static void CreateShortcuts(InstallOptions options)
    {
        if (options.DesktopShortcut)
        {
            CreateShortcut(DesktopShortcutPath, TrayExePath, "", InstallDir, "声核局域网网关");
        }

        Directory.CreateDirectory(StartMenuDir);
        CreateShortcut(Path.Combine(StartMenuDir, Program.AppName + ".lnk"), TrayExePath, "", InstallDir, "声核局域网网关");
        CreateShortcut(Path.Combine(StartMenuDir, "卸载" + Program.AppName + ".lnk"),
            Path.Combine(InstallDir, UninstallExeName), "--uninstall", InstallDir, "卸载声核网关");
    }

    private static void ApplyAutoStart(bool enabled)
    {
        if (enabled)
        {
            CreateShortcut(StartupShortcutPath, TrayExePath, "", InstallDir, "声核局域网网关");
        }
        else
        {
            DeleteFileQuiet(StartupShortcutPath);
        }

        // 兼容旧版注册表自启项，避免重复启动
        try
        {
            using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            run?.DeleteValue("AudcoreGatewayTray", false);
        }
        catch
        {
            // 忽略
        }
    }

    private static void CreateShortcut(string shortcutPath, string targetPath, string arguments, string workingDir, string description)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null)
        {
            throw new InvalidOperationException("无法创建快捷方式：WScript.Shell 不可用");
        }

        object shell = null;
        object shortcut = null;
        try
        {
            try
            {
                // 旧版安装包若以管理员运行，快捷方式可能带“强制提升”标记；重新创建普通快捷方式。
                File.Delete(shortcutPath);
            }
            catch (Exception ex)
            {
                WriteLog("清理旧快捷方式失败 " + shortcutPath + "：" + ex.Message);
            }

            shell = Activator.CreateInstance(shellType);
            shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcutPath });
            var type = shortcut!.GetType();
            type.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { targetPath });
            type.InvokeMember("Arguments", BindingFlags.SetProperty, null, shortcut, new object[] { arguments });
            type.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { workingDir });
            type.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { targetPath + ",0" });
            type.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut, new object[] { description });
            try
            {
                // 普通权限安装到用户目录时，显式清除强制提升标记；权限不足也不影响快捷方式创建。
                type.InvokeMember("SetRuntimePlatform", BindingFlags.InvokeMethod, null, shortcut, new object[] { "Windows" });
            }
            catch
            {
                // 旧版 WScript.Shell 不支持该方法时忽略
            }
            type.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
            WriteLog("创建快捷方式 " + shortcutPath);
        }
        finally
        {
            if (shortcut != null && Marshal.IsComObject(shortcut))
            {
                Marshal.ReleaseComObject(shortcut);
            }

            if (shell != null && Marshal.IsComObject(shell))
            {
                Marshal.ReleaseComObject(shell);
            }
        }
    }

    private static void RegisterUninstall()
    {
        using var key = Registry.CurrentUser.CreateSubKey(UninstallKeyPath);
        if (key == null)
        {
            throw new InvalidOperationException("无法写入卸载注册表项");
        }

        key.SetValue("DisplayName", Program.AppName);
        key.SetValue("DisplayVersion", Program.AppVersion);
        key.SetValue("Publisher", "AudCore Remote");
        key.SetValue("DisplayIcon", TrayExePath + ",0");
        key.SetValue("InstallLocation", InstallDir);
        key.SetValue("UninstallString", $"\"{Path.Combine(InstallDir, UninstallExeName)}\" --uninstall");
        key.SetValue("QuietUninstallString", $"\"{Path.Combine(InstallDir, UninstallExeName)}\" --uninstall --silent");
        key.SetValue("NoModify", 1);
        key.SetValue("NoRepair", 1);
        key.SetValue("EstimatedSize", EstimateSizeKb(), RegistryValueKind.DWord);
    }

    private static int EstimateSizeKb()
    {
        try
        {
            var bytes = Directory.EnumerateFiles(InstallDir, "*", SearchOption.AllDirectories)
                .Sum(f => new FileInfo(f).Length);
            return (int)Math.Max(1, bytes / 1024);
        }
        catch
        {
            return 1;
        }
    }

    private static void CopyUninstaller()
    {
        var self = Environment.ProcessPath;
        if (string.IsNullOrEmpty(self))
        {
            return;
        }

        var target = Path.Combine(InstallDir, UninstallExeName);
        if (string.Equals(self, target, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }

        try
        {
            File.Copy(self, target, true);
            WriteLog("复制卸载程序 " + target);
        }
        catch (Exception ex)
        {
            WriteLog("复制卸载程序失败：" + ex.Message);
        }
    }

    public static void StartTray()
    {
        if (!File.Exists(TrayExePath))
        {
            return;
        }

        try
        {
            // 提权安装时由 explorer 代启，让托盘/核心的属主是当前登录用户，
            // 否则下一次普通权限升级又会因为杀不掉管理员进程而被迫提权。
            if (IsElevated())
            {
                Process.Start(new ProcessStartInfo("explorer.exe", "\"" + TrayExePath + "\"")
                {
                    UseShellExecute = true,
                });
                WriteLog("已通过 explorer 启动托盘（保持普通用户权限）");
                return;
            }

            var psi = new ProcessStartInfo(TrayExePath)
            {
                WorkingDirectory = InstallDir,
                UseShellExecute = true,
            };
            Process.Start(psi);
        }
        catch (Exception ex)
        {
            WriteLog("启动托盘失败：" + ex.Message);
        }
    }

    private static void DeleteDirectoryWithRetry(string dir)
    {
        // 保留 .state 凭证目录，重装后手机连接地址不变
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                if (!Directory.Exists(dir))
                {
                    return;
                }

                foreach (var entry in Directory.EnumerateFileSystemEntries(dir))
                {
                    var name = Path.GetFileName(entry);
                    if (name.Equals(".state", StringComparison.OrdinalIgnoreCase))
                    {
                        WriteLog("保留凭证目录 " + entry);
                        continue;
                    }

                    try
                    {
                        if (Directory.Exists(entry))
                        {
                            Directory.Delete(entry, true);
                        }
                        else
                        {
                            File.SetAttributes(entry, FileAttributes.Normal);
                            File.Delete(entry);
                        }
                    }
                    catch (Exception ex)
                    {
                        WriteLog($"删除 {entry} 失败：{ex.Message}");
                        throw;
                    }
                }

                return;
            }
            catch (Exception ex)
            {
                WriteLog($"清理目录第 {attempt + 1} 次失败：{ex.Message}");
                Thread.Sleep(400);
            }
        }
    }

    private static void DeleteFileQuiet(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
                WriteLog("删除 " + path);
            }
        }
        catch (Exception ex)
        {
            WriteLog($"删除 {path} 失败：{ex.Message}");
        }
    }
}

/// <summary>普通权限写入安装目录被拒（旧进程仍以管理员运行等），需要改为请求管理员权限后重装。</summary>
internal sealed class ElevationRequiredException : Exception
{
    public ElevationRequiredException(string message, Exception? inner = null) : base(message, inner)
    {
    }
}
