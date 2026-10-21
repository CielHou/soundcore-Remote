using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;
using ZXing.QrCode.Internal;


namespace AudcoreGatewayTray;

internal static class GatewayApi
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(2) };

    public static int ResolveUiPort()
    {
        try
        {
            var configPath = Path.Combine(AppContext.BaseDirectory, "gateway.config.json");
            if (!File.Exists(configPath)) return 18534;
            using var doc = JsonDocument.Parse(File.ReadAllText(configPath));
            if (doc.RootElement.TryGetProperty("uiPort", out var uiPort) && uiPort.ValueKind == JsonValueKind.Number)
            {
                return uiPort.GetInt32();
            }
        }
        catch { }
        return 18534;
    }

    public static async Task<JsonDocument> FetchStatusAsync(int uiPort)
    {
        try
        {
            using var response = await Http.GetAsync($"http://127.0.0.1:{uiPort}/api/status");
            if (!response.IsSuccessStatusCode) return null;
            var text = await response.Content.ReadAsStringAsync();
            return JsonDocument.Parse(text);
        }
        catch
        {
            return null;
        }
    }

    public static (bool audcoreAuthorized, string audcoreStatus, int phones, string bestUrl) Summarize(JsonDocument doc)
    {
        if (doc == null) return (false, "unknown", 0, string.Empty);
        var root = doc.RootElement;
        var authorized = false;
        var status = "unknown";
        if (root.TryGetProperty("audcore", out var audcore))
        {
            if (audcore.TryGetProperty("authorized", out var auth)) authorized = auth.ValueKind == JsonValueKind.True;
            if (audcore.TryGetProperty("status", out var st) && st.ValueKind == JsonValueKind.String) status = st.GetString() ?? "unknown";
        }
        var phones = 0;
        if (root.TryGetProperty("mobile", out var mobile) &&
            mobile.TryGetProperty("authorized", out var count) &&
            count.ValueKind == JsonValueKind.Number)
        {
            phones = count.GetInt32();
        }
        return (authorized, status, phones, PickPhoneUrl(root));
    }

    /// <summary>返回本机真正连网（有默认网关）的那块网卡的 IPv4 地址；找不到返回空串。</summary>
    public static string PrimaryLanAddress()
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                var props = nic.GetIPProperties();
                var hasGateway = false;
                foreach (var gateway in props.GatewayAddresses)
                {
                    var address = gateway.Address;
                    if (address == null) continue;
                    if (address.Equals(IPAddress.Any) || address.Equals(IPAddress.IPv6Any)) continue;
                    hasGateway = true;
                    break;
                }
                if (!hasGateway) continue;

                foreach (var unicast in props.UnicastAddresses)
                {
                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(unicast.Address)) continue;
                    if (unicast.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal)) continue;
                    return unicast.Address.ToString();
                }
            }
        }
        catch
        {
            // 忽略：取不到就退回按地址段评分
        }
        return string.Empty;
    }

    public static string PickPhoneUrl(JsonElement root)
    {
        if (!root.TryGetProperty("gateway", out var gateway)) return string.Empty;
        if (!gateway.TryGetProperty("connectionUrls", out var urls) || urls.ValueKind != JsonValueKind.Array) return string.Empty;

        var primary = PrimaryLanAddress();
        var serverPreferred = gateway.TryGetProperty("preferredAddress", out var preferredProp) && preferredProp.ValueKind == JsonValueKind.String
            ? preferredProp.GetString()
            : string.Empty;
        var best = string.Empty;
        var bestScore = -1;
        foreach (var entry in urls.EnumerateArray())
        {
            if (!entry.TryGetProperty("url", out var urlProp)) continue;
            var url = urlProp.GetString();
            if (string.IsNullOrEmpty(url)) continue;
            var address = entry.TryGetProperty("address", out var addressProp) ? addressProp.GetString() : string.Empty;
            var name = entry.TryGetProperty("name", out var nameProp) ? nameProp.GetString() : string.Empty;
            var score = ScoreAddress(address, name);
            if (!string.IsNullOrEmpty(serverPreferred) && string.Equals(address, serverPreferred, StringComparison.Ordinal))
            {
                score += 2000;
            }
            if (!string.IsNullOrEmpty(primary) && string.Equals(address, primary, StringComparison.Ordinal))
            {
                score += 1000;
            }
            if (score > bestScore)
            {
                bestScore = score;
                best = url;
            }
        }
        return best;
    }

    public static int ScoreAddress(string address, string name)
    {
        if (string.IsNullOrEmpty(address)) return 0;
        if (name != null &&
            (name.Contains("VMware", StringComparison.OrdinalIgnoreCase) ||
             name.Contains("vEthernet", StringComparison.OrdinalIgnoreCase) ||
             name.Contains("VirtualBox", StringComparison.OrdinalIgnoreCase) ||
             name.Contains("Loopback", StringComparison.OrdinalIgnoreCase)))
        {
            return 1;
        }
        // Windows 移动热点/网络共享用的 192.168.137.x 一般不是真实局域网地址
        if (address.StartsWith("192.168.137.", StringComparison.Ordinal)) return 5;
        if (name != null && name.Contains("本地连接", StringComparison.Ordinal)) return 5;
        if (address.StartsWith("169.254.", StringComparison.Ordinal)) return 1;
        if (address.StartsWith("192.168.", StringComparison.Ordinal)) return 100;
        if (address.StartsWith("10.", StringComparison.Ordinal)) return 90;
        if (address.StartsWith("172.16.", StringComparison.Ordinal) ||
            address.StartsWith("172.17.", StringComparison.Ordinal) ||
            address.StartsWith("172.18.", StringComparison.Ordinal) ||
            address.StartsWith("172.19.", StringComparison.Ordinal) ||
            address.StartsWith("172.2", StringComparison.Ordinal) ||
            address.StartsWith("172.30.", StringComparison.Ordinal) ||
            address.StartsWith("172.31.", StringComparison.Ordinal))
        {
            return 2;
        }
        return 50;
    }

    private static readonly string[] BrowserCandidates =
    {
        @"Microsoft\Edge\Application\msedge.exe",
        @"Google\Chrome\Application\chrome.exe",
        @"BraveSoftware\Brave-Browser\Application\brave.exe",
    };

    /// <summary>
    /// 打开状态页面。优先用「独立配置目录」启动浏览器，避免请求被转发到
    /// 已经以管理员权限运行的浏览器实例（那种情况下浏览器会弹“未响应/是否重启”）。
    /// </summary>
    public static bool OpenUrlInBrowser(string url)
    {
        var profile = Path.Combine(Path.GetTempPath(), "audcore-gateway-browser");
        foreach (var relative in BrowserCandidates)
        {
            var path = ResolveBrowser(relative);
            if (path == null) continue;
            try
            {
                Directory.CreateDirectory(profile);
                var info = new ProcessStartInfo
                {
                    FileName = path,
                    Arguments = $"--user-data-dir=\"{profile}\" --new-window \"{url}\" --no-first-run",
                    UseShellExecute = false,
                };
                Process.Start(info);
                return true;
            }
            catch
            {
                // 换下一个浏览器
            }
        }

        try
        {
            Process.Start(new ProcessStartInfo { FileName = url, UseShellExecute = true });
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string ResolveBrowser(string relative)
    {
        var roots = new[]
        {
            Environment.GetEnvironmentVariable("ProgramFiles(x86)"),
            Environment.GetEnvironmentVariable("ProgramFiles"),
            Environment.GetEnvironmentVariable("LOCALAPPDATA"),
        };
        foreach (var root in roots)
        {
            if (string.IsNullOrEmpty(root)) continue;
            try
            {
                var candidate = Path.Combine(root, relative);
                if (File.Exists(candidate)) return candidate;
            }
            catch
            {
                // 忽略
            }
        }
        return null;
    }

    public static Icon LoadAppIcon()
    {
        try
        {
            var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("AudcoreGatewayTray.app.ico");
            if (stream != null) return new Icon(stream);
            var exe = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exe) && File.Exists(exe))
            {
                var extracted = Icon.ExtractAssociatedIcon(exe);
                if (extracted != null) return extracted;
            }
        }
        catch { }
        return SystemIcons.Application;
    }
}

/// <summary>在桌面窗口里直接绘制当前手机连接地址二维码，不依赖浏览器。</summary>
internal static class QrPanel
{
    private const int QuietZone = 1;

    public static void Draw(Graphics graphics, Rectangle bounds, string connectionUrl)
    {
        if (string.IsNullOrWhiteSpace(connectionUrl))
        {
            DrawPlaceholder(graphics, bounds, "等待网关生成连接地址");
            return;
        }

        try
        {
            var modules = QrEncode(connectionUrl);
            DrawCode(graphics, bounds, modules, "#000000", "#ffffff");
        }
        catch (Exception ex)
        {
            DrawPlaceholder(graphics, bounds, "二维码生成失败\n" + ex.Message);
        }
    }

    private static bool[,] QrEncode(string text)
    {
        var writer = new QRCodeWriter();
        var options = new QrCodeEncodingOptions
        {
            ErrorCorrection = ErrorCorrectionLevel.M,
            Margin = QuietZone,
            CharacterSet = "UTF-8",
        };
        var bitmap = writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, options.Hints);
        var matrix = new bool[bitmap.Width, bitmap.Height];
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                matrix[x, y] = bitmap[x, y];
            }
        }
        return matrix;
    }

    private static void DrawCode(Graphics graphics, Rectangle bounds, bool[,] modules, string dark, string light)
    {
        var width = modules.GetLength(0);
        var height = modules.GetLength(1);
        var scale = Math.Max(1, Math.Min((double)bounds.Width / width, (double)bounds.Height / height));
        var codeWidth = (int)Math.Round(width * scale);
        var codeHeight = (int)Math.Round(height * scale);
        var left = bounds.Left + (bounds.Width - codeWidth) / 2;
        var top = bounds.Top + (bounds.Height - codeHeight) / 2;
        using var darkBrush = new SolidBrush(ColorTranslator.FromHtml(dark));
        using var lightBrush = new SolidBrush(ColorTranslator.FromHtml(light));

        graphics.Clear(ColorTranslator.FromHtml(light));
        graphics.SmoothingMode = SmoothingMode.None;
        graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
        graphics.PixelOffsetMode = PixelOffsetMode.Half;
        graphics.FillRectangle(lightBrush, new Rectangle(left, top, codeWidth, codeHeight));
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                if (modules[x, y])
                {
                    graphics.FillRectangle(darkBrush,
                        left + (int)Math.Round(x * scale),
                        top + (int)Math.Round(y * scale),
                        Math.Max(1, (int)Math.Round(scale)),
                        Math.Max(1, (int)Math.Round(scale)));
                }
            }
        }
    }

    private static void DrawPlaceholder(Graphics graphics, Rectangle bounds, string text)
    {
        using var background = new SolidBrush(Color.White);
        using var foreground = new SolidBrush(Color.FromArgb(90, 90, 90));
        using var font = new Font("Microsoft YaHei UI", 11F, FontStyle.Regular, GraphicsUnit.Pixel);
        using var format = new StringFormat
        {
            Alignment = StringAlignment.Center,
            LineAlignment = StringAlignment.Center,
        };
        graphics.Clear(Color.White);
        graphics.DrawString(text, font, foreground, bounds, format);
    }
}

/// <summary>主窗口：桌面图标点开后看到的控制面板。</summary>
internal sealed class MainForm : Form
{
    private static readonly Color Bg = Color.FromArgb(24, 25, 31);
    private static readonly Color Card = Color.FromArgb(33, 35, 43);
    private static readonly Color TextColor = Color.FromArgb(236, 234, 242);
    private static readonly Color Muted = Color.FromArgb(154, 151, 165);
    private static readonly Color Accent = Color.FromArgb(91, 107, 232);
    private static readonly Color Good = Color.FromArgb(74, 222, 128);
    private static readonly Color Warn = Color.FromArgb(251, 191, 36);

    private readonly int _uiPort = GatewayApi.ResolveUiPort();
    private readonly Label _audcoreValue;
    private readonly Label _mobileValue;
    private readonly Label _addressValue;
    private readonly Label _hint;
    private readonly Label _updateLabel;
    private readonly Panel _qrPanel;
    private readonly Label _qrTitle;
    private readonly Button _btnPage;
    private readonly Button _btnCopy;
    private readonly Button _btnQr;
    private System.Windows.Forms.Timer _qrTimer;
    private string _phoneUrl;
    private bool _qrVisible;
    private readonly System.Windows.Forms.Timer _timer;
    private bool _busy;

    public bool Quitting { get; set; }

    public MainForm()
    {
        Text = "声核网关";
        ClientSize = new Size(460, 550);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Bg;
        Font = new Font("Microsoft YaHei UI", 9.5F);
        Icon = GatewayApi.LoadAppIcon();

        var title = new Label
        {
            Text = "声核网关",
            ForeColor = TextColor,
            Font = new Font("Microsoft YaHei UI", 17F, FontStyle.Bold),
            Bounds = new Rectangle(22, 18, 280, 38),
        };
        Controls.Add(title);

        var subtitle = new Label
        {
            Text = "手机遥控 · 局域网翻译层（关闭窗口后仍在托盘运行）",
            ForeColor = Muted,
            Font = new Font("Microsoft YaHei UI", 9F),
            Bounds = new Rectangle(24, 56, 424, 20),
        };
        Controls.Add(subtitle);

        var card = new Panel { Bounds = new Rectangle(22, 86, 424, 156), BackColor = Card };
        Controls.Add(card);
        _qrPanel = new Panel { Bounds = new Rectangle(306, 310, 140, 0), BackColor = Color.White, Visible = false };
        _qrPanel.Paint += (_, e) =>
        {
            QrPanel.Draw(e.Graphics, new Rectangle(0, _qrTitle.Height, _qrPanel.Width, Math.Max(0, _qrPanel.Height - _qrTitle.Height)),
                _qrVisible ? _phoneUrl : string.Empty);
            e.Graphics.DrawLine(new Pen(Color.FromArgb(220, 222, 228)), 0, _qrTitle.Height - 1, _qrPanel.Width, _qrTitle.Height - 1);
        };

        _audcoreValue = AddRow(card, "声核连接", 16, Muted);
        _mobileValue = AddRow(card, "手机连接", 62, Muted);
        _addressValue = AddRow(card, "手机地址", 108, Muted);
        _addressValue.Font = new Font("Consolas", 8.5F);

        _qrTitle = new Label
        {
            Text = "手机扫码连接",
            ForeColor = Color.FromArgb(75, 78, 88),
            BackColor = Color.White,
            Font = new Font("Microsoft YaHei UI", 10F, FontStyle.Bold),
            Bounds = new Rectangle(0, 0, 140, 28),
            TextAlign = ContentAlignment.MiddleCenter,
        };
        _qrPanel.Controls.Add(_qrTitle);
        Controls.Add(_qrPanel);
        _qrPanel.BringToFront();

        _btnPage = MakeButton("打开状态页面", 22, 258, 134, 46, Accent, Color.White);
        _btnPage.Click += (_, __) => OpenStatusPage();
        Controls.Add(_btnPage);

        _btnCopy = MakeButton("复制手机连接地址", 164, 258, 134, 46, Color.FromArgb(48, 60, 143), TextColor);
        _btnCopy.Click += (_, __) => CopyPhoneUrl();
        Controls.Add(_btnCopy);

        _btnQr = MakeButton("展示二维码", 306, 258, 140, 46, Color.FromArgb(58, 48, 72), TextColor);
        _btnQr.Click += (_, __) => ToggleQrPanel();
        Controls.Add(_btnQr);

        var btnLogs = MakeButton("运行日志", 22, 310, 134, 40, Card, TextColor);
        btnLogs.Click += (_, __) => TrayContext.Current?.ShowLogWindow();
        Controls.Add(btnLogs);

        var btnHide = MakeButton("最小化到托盘", 164, 310, 134, 40, Card, TextColor);
        btnHide.Click += (_, __) => HideToTray();
        Controls.Add(btnHide);

        var btnQuit = MakeButton("退出网关", 306, 310, 140, 40, Color.FromArgb(70, 32, 36), Color.FromArgb(255, 180, 171));
        btnQuit.Click += (_, __) =>
        {
            Quitting = true;
            Close();
            TrayContext.Current?.Shutdown();
        };
        Controls.Add(btnQuit);

        var btnRefresh = MakeButton("刷新状态", 22, 358, 120, 36, Card, TextColor);
        btnRefresh.Click += (_, __) => { _ = RefreshAsync(); };
        Controls.Add(btnRefresh);

        _updateLabel = new Label
        {
            Text = "最近刷新：—",
            ForeColor = Muted,
            Font = new Font("Microsoft YaHei UI", 8.5F),
            Bounds = new Rectangle(152, 366, 294, 22),
        };
        Controls.Add(_updateLabel);

        _hint = new Label
        {
            ForeColor = Muted,
            Font = new Font("Microsoft YaHei UI", 8.5F),
            Bounds = new Rectangle(24, 398, 424, 74),
            Text = "手机 App 里粘贴上面的地址即可连接。\n若托盘区看不到图标，请点任务栏右下角的 ^ 展开，或右键任务栏 → 任务栏设置 → 其他系统托盘图标 → 打开「声核网关」。",
        };
        Controls.Add(_hint);

        var aboutLabel = new Label
        {
            ForeColor = Muted,
            Font = new Font("Microsoft YaHei UI", 8F),
            Bounds = new Rectangle(24, 482, 424, 48),
            Text = "关于本程序\n版本 1.0.0 · 作者 Ciel · 微信 Apple_Hkc\n手机通过局域网遥控电脑上的声核调音台。",
        };
        Controls.Add(aboutLabel);

        FormClosing += (_, e) =>
        {
            if (Quitting) return;
            e.Cancel = true;
            HideToTray();
        };

        _timer = new System.Windows.Forms.Timer { Interval = 2500 };
        _timer.Tick += (_, __) => { _ = RefreshAsync(); };
        _timer.Start();
        _ = RefreshAsync();
    }

    private static Label AddRow(Panel card, string caption, int top, Color captionColor)
    {
        var label = new Label
        {
            Text = caption,
            ForeColor = captionColor,
            Font = new Font("Microsoft YaHei UI", 9F),
            Bounds = new Rectangle(16, top, 82, 22),
        };
        card.Controls.Add(label);
        var value = new Label
        {
            Text = "读取中…",
            ForeColor = TextColor,
            AutoEllipsis = true,
            Font = new Font("Microsoft YaHei UI", 9.5F),
            Bounds = new Rectangle(16, top + 20, 392, 24),
        };
        card.Controls.Add(value);
        return value;
    }

    private void ToggleQrPanel()
    {
        if (_qrVisible)
        {
            _qrVisible = false;
            _btnQr.Text = "展示二维码";
            AnimateQrPanel(false);
            return;
        }

        _qrVisible = true;
        _btnQr.Text = "隐藏二维码";
        AnimateQrPanel(true);
        _qrPanel.Invalidate();
    }

    private void AnimateQrPanel(bool expand)
    {
        if (_qrTimer != null)
        {
            _qrTimer.Stop();
            _qrTimer.Dispose();
        }

        _qrTimer = new System.Windows.Forms.Timer { Interval = 12 };
        var target = expand ? 174 : 0;
        _qrTimer.Tick += (_, __) =>
        {
            var current = _qrPanel.Height;
            var step = expand ? 18 : -18;
            var next = Math.Clamp(current + step, Math.Min(0, target), Math.Max(0, target));
            _qrPanel.Height = next;
            _qrPanel.Top = 310;
            _qrPanel.BringToFront();
            if (next == target)
            {
                _qrTimer.Stop();
                _qrTimer.Dispose();
                _qrTimer = null;
                if (!expand)
                {
                    _qrPanel.Visible = false;
                }
            }
        };
        _qrPanel.Visible = true;
        _qrTimer.Start();
    }

    private static Button MakeButton(string text, int x, int y, int width, int height, Color back, Color fore)
    {
        var button = new Button
        {
            Text = text,
            Bounds = new Rectangle(x, y, width, height),
            FlatStyle = FlatStyle.Flat,
            BackColor = back,
            ForeColor = fore,
            Font = new Font("Microsoft YaHei UI", 9.5F, FontStyle.Bold),
            Cursor = Cursors.Hand,
        };
        button.FlatAppearance.BorderSize = 0;
        return button;
    }

    private async Task RefreshAsync()
    {
        if (_busy) return;
        _busy = true;
        try
        {
            using var doc = await GatewayApi.FetchStatusAsync(_uiPort);
            _updateLabel.Text = "最近刷新：" + DateTime.Now.ToString("HH:mm:ss");
            if (doc == null)
            {
                _audcoreValue.Text = "网关核心未运行";
                _audcoreValue.ForeColor = Warn;
                _mobileValue.Text = "—";
                _mobileValue.ForeColor = Muted;
                _addressValue.Text = "—";
                UpdateQr(string.Empty, phones: 0);
                return;
            }

            var (authorized, status, phones, url) = GatewayApi.Summarize(doc);
            if (authorized)
            {
                _audcoreValue.Text = "已连接并授权";
                _audcoreValue.ForeColor = Good;
            }
            else if (status == "open" || status == "connecting")
            {
                _audcoreValue.Text = "已连接，等待授权（请在声核里点允许）";
                _audcoreValue.ForeColor = Warn;
            }
            else
            {
                _audcoreValue.Text = "未连接（请先打开声核）";
                _audcoreValue.ForeColor = Warn;
            }

            _mobileValue.Text = phones > 0 ? $"已连接 {phones} 台手机" : "暂无手机连接";
            _mobileValue.ForeColor = phones > 0 ? Good : Muted;

            _addressValue.Text = string.IsNullOrEmpty(url) ? "—" : url;
            _addressValue.ForeColor = TextColor;
            UpdateQr(url, phones);
            _hint.ForeColor = Muted;
        }
        finally
        {
            _busy = false;
        }
    }

    private void UpdateQr(string url, int phones)
    {
        if (!string.Equals(_phoneUrl, url, StringComparison.Ordinal))
        {
            _phoneUrl = url;
        }
        _qrPanel.Invalidate();
    }
    private void HideToTray()
    {
        Hide();
        TrayContext.Current?.NotifyTip(
            "声核网关仍在托盘运行",
            "图标可能收在任务栏右下角的 ^ 折叠区里；双击图标可以再次打开这个面板。");
    }

    private void OpenStatusPage()
    {
        if (!GatewayApi.OpenUrlInBrowser($"http://127.0.0.1:{_uiPort}/"))
        {
            MessageBox.Show("打不开浏览器，请手动访问 http://127.0.0.1:" + _uiPort + "/", "声核网关");
        }
    }

    private async void CopyPhoneUrl()
    {
        try
        {
            using var doc = await GatewayApi.FetchStatusAsync(_uiPort);
            var (_, _, _, url) = GatewayApi.Summarize(doc);
            if (string.IsNullOrEmpty(url))
            {
                MessageBox.Show("暂时拿不到连接地址，请确认声核已打开、网关已启动。", "声核网关");
                return;
            }
            Clipboard.SetText(url);
            _hint.Text = "已复制：\n" + url;
            _hint.ForeColor = Good;
        }
        catch (Exception ex)
        {
            MessageBox.Show("复制失败：" + ex.Message, "声核网关");
        }
    }
}
