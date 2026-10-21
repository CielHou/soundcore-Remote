using System.Diagnostics;
using System.Drawing;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Net.Http;
using Microsoft.Win32;
using Timer = System.Windows.Forms.Timer;

namespace AudcoreGatewayTray;

/// <summary>托盘生命周期管理：拉起网关核心、承载主窗口和日志窗口。</summary>
internal sealed class TrayContext : ApplicationContext
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(2) };
    private readonly string[] _args;
    private static readonly string[] CoreNames = { "gateway-core", "声核网关核心" };
    private readonly int _uiPort = GatewayApi.ResolveUiPort();
    private readonly string _installDir = AppContext.BaseDirectory;
    private readonly Icon _icon = GatewayApi.LoadAppIcon();
    private readonly NotifyIcon _trayIcon;
    private readonly System.Windows.Forms.Timer _probeTimer;
    private readonly System.Windows.Forms.Timer _coreTimer;
    private readonly ContextMenuStrip _menu;
    private MainForm _form;
    private LogForm _logForm;
    private Process _core;
    private bool _stopping;

    public TrayContext(string[] args)
    {
        _args = args ?? Array.Empty<string>();
        Current = this;

        _menu = new ContextMenuStrip();
        _menu.Items.Add("打开控制面板", null, (_, _) => ShowForm());
        _menu.Items.Add("打开状态页面", null, (_, _) => GatewayApi.OpenUrlInBrowser($"http://127.0.0.1:{_uiPort}/"));
        _menu.Items.Add("运行日志", null, (_, _) => ShowLogWindow());
        _menu.Items.Add(new ToolStripSeparator());
        _menu.Items.Add("退出", null, (_, _) => Shutdown());

        _trayIcon = new NotifyIcon
        {
            Icon = _icon,
            Text = "声核网关",
            ContextMenuStrip = _menu,
            Visible = true,
        };
        _trayIcon.DoubleClick += (_, _) => ShowForm();

        _probeTimer = new System.Windows.Forms.Timer { Interval = 2500 };
        _probeTimer.Tick += (_, _) => EnsureSingleTray();
        _probeTimer.Start();

        _coreTimer = new System.Windows.Forms.Timer { Interval = 3000 };
        _coreTimer.Tick += (_, _) => EnsureCore();
        _coreTimer.Start();

        EnsureCore();
        if (!_args.Contains("--hidden", StringComparer.OrdinalIgnoreCase))
        {
            ShowForm();
        }
    }

    public static TrayContext Current { get; private set; }

    public void ShowForm()
    {
        if (_form == null || _form.IsDisposed)
        {
            _form = new MainForm();
        }

        if (!_form.Visible)
        {
            _form.Show();
        }

        _form.WindowState = FormWindowState.Normal;
        _form.Activate();
    }

    public void ShowLogWindow()
    {
        if (_logForm == null || _logForm.IsDisposed)
        {
            _logForm = new LogForm();
        }

        _logForm.Show();
        _logForm.Activate();
    }

    public void NotifyTip(string title, string text)
    {
        if (_trayIcon == null) return;
        _trayIcon.BalloonTipTitle = title;
        _trayIcon.BalloonTipText = text;
        _trayIcon.ShowBalloonTip(5000);
    }

    public void Shutdown()
    {
        if (_stopping) return;
        _stopping = true;

        _probeTimer?.Stop();
        _coreTimer?.Stop();
        _trayIcon.Visible = false;
        StopCore();

        if (_logForm != null && !_logForm.IsDisposed)
        {
            _logForm.Close();
        }

        Application.ExitThread();
    }

    private void EnsureSingleTray()
    {
        if (_trayIcon.Visible) return;
        try
        {
            _trayIcon.Visible = true;
        }
        catch
        {
            // Explorer 重启期间可能暂时无法恢复，下个周期再试
        }
    }

    private void EnsureCore()
    {
        if (_stopping) return;

        try
        {
            if (!CoreIsRunning())
            {
                StartCore();
            }
        }
        catch (Exception ex)
        {
            ShowLogWindow();
            _logForm?.Append("启动网关核心失败：" + ex.Message);
        }
    }

    private bool CoreIsRunning()
    {
        if (IsAlive(_core)) return true;
        foreach (var name in CoreNames)
        {
            try
            {
                if (Process.GetProcessesByName(name).Length > 0)
                {
                    return true;
                }
            }
            catch
            {
                // 忽略枚举失败
            }
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{_uiPort}/api/status");
            using var response = Http.Send(request);
            return response.IsSuccessStatusCode;
        }
        catch
        {
            return false;
        }
    }

    private void StartCore()
    {
        var corePath = Path.Combine(_installDir, "gateway-core.exe");
        if (!File.Exists(corePath))
        {
            throw new FileNotFoundException("找不到网关核心 gateway-core.exe，请重新安装声核网关。", corePath);
        }

        _core?.Dispose();
        _core = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = corePath,
                WorkingDirectory = _installDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            },
            EnableRaisingEvents = true,
        };
        _core.Exited += (_, _) =>
        {
            if (_logForm != null && !_logForm.IsDisposed)
            {
                _logForm.Append("网关核心已退出。");
            }
        };
        _core.Start();
    }

    private void StopCore()
    {
        var selfPath = Environment.ProcessPath;
        var isTrayFromInstallDir = selfPath != null && selfPath.StartsWith(_installDir, StringComparison.OrdinalIgnoreCase);
        foreach (var process in Process.GetProcesses())
        {
            try
            {
                var isCore = CoreNames.Contains(process.ProcessName, StringComparer.OrdinalIgnoreCase);
                var isCoreFromInstallDir = process.MainModule?.FileName?.StartsWith(_installDir, StringComparison.OrdinalIgnoreCase) == true;
                if (isCore || (isCoreFromInstallDir && !isTrayFromInstallDir))
                {
                    process.Kill(true);
                    process.WaitForExit(2000);
                }
            }
            catch
            {
                // 可能是自身进程或无权限进程，忽略
            }
            finally
            {
                process.Dispose();
            }
        }

        if (_core != null)
        {
            _core.Dispose();
            _core = null;
        }
    }

    private static bool IsAlive(Process process)
    {
        if (process == null) return false;
        try
        {
            return !process.HasExited;
        }
        catch
        {
            return false;
        }
    }
}

/// <summary>轻量运行日志窗口；未实现专门文件跟踪时，展示托盘与核心的生命周期信息。</summary>
internal sealed class LogForm : Form
{
    private readonly TextBox _textBox = new();

    public LogForm()
    {
        Text = "声核网关运行日志";
        ClientSize = new Size(640, 420);
        BackColor = Color.FromArgb(24, 25, 31);
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(420, 260);
        Font = new Font("Microsoft YaHei UI", 9.5F);
        ShowInTaskbar = false;

        _textBox.Multiline = true;
        _textBox.ReadOnly = true;
        _textBox.ScrollBars = ScrollBars.Vertical;
        _textBox.BorderStyle = BorderStyle.None;
        _textBox.BackColor = Color.FromArgb(33, 35, 43);
        _textBox.ForeColor = Color.FromArgb(236, 234, 242);
        _textBox.Font = new Font("Consolas", 9F);
        _textBox.Dock = DockStyle.Fill;
        Controls.Add(_textBox);

        Append("网关日志窗口已打开。");
    }

    public void Append(string line)
    {
        if (IsDisposed) return;
        _textBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {line}{Environment.NewLine}");
    }
}

internal static class Program
{
    private const string MutexName = @"Global\AudcoreGatewayTray_SingleInstance";
    internal const string ShowEventName = @"Global\AudcoreGatewayTray_ShowWindow";

    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, MutexName, out var isFirstInstance);
        if (!isFirstInstance)
        {
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new TrayContext(args));
    }
}
