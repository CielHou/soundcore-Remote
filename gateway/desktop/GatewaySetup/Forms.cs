namespace AudcoreGatewaySetup;

internal sealed class SetupForm : Form
{
    private readonly Label _status;
    private readonly RoundProgressBar _progress;
    private readonly Button _installButton;
    private readonly Button _cancelButton;
    private readonly CheckBox _desktop;
    private readonly CheckBox _autoStart;
    private readonly CheckBox _runNow;

    public bool Success { get; private set; }

    public SetupForm()
    {
        SharedUi.Apply(this);
        Text = Program.AppName + " 安装程序";
        ClientSize = new Size(520, 420);
        ShowIcon = true;

        var icon = new PictureBox
        {
            Size = new Size(56, 56),
            Location = new Point(28, 26),
            SizeMode = PictureBoxSizeMode.Zoom,
        };
        try
        {
            icon.Image = Icon.ExtractAssociatedIcon(Environment.ProcessPath!)?.ToBitmap();
        }
        catch
        {
            // 图标读取失败不影响安装
        }

        var title = SharedUi.Title(Program.AppName + " 安装程序");
        title.Location = new Point(96, 26);

        var version = SharedUi.Body($"版本 {Program.AppVersion} · 手机通过局域网遥控电脑上的声核调音台");
        version.Location = new Point(98, 62);
        version.Size = new Size(400, 24);

        var author = SharedUi.Body($"作者：{Program.Author} · 微信：{Program.Contact}");
        author.Location = new Point(98, 82);
        author.Size = new Size(400, 20);

        var target = SharedUi.Body("安装位置\n" + Installer.InstallDir);
        target.Location = new Point(28, 100);
        target.Size = new Size(464, 52);

        var summary = SharedUi.Body(
            "安装内容：网关托盘程序、网关核心（本机连接声核并转发到局域网）、状态页面、使用说明。" +
            "已有配置和授权凭证会保留，升级不会改变手机连接地址。");
        summary.Location = new Point(28, 156);
        summary.Size = new Size(464, 72);

        _desktop = SharedUi.Check("创建桌面快捷方式");
        _desktop.Location = new Point(28, 236);
        _autoStart = SharedUi.Check("开机自动运行");
        _autoStart.Location = new Point(200, 236);
        _runNow = SharedUi.Check("安装完成后立即运行");
        _runNow.Location = new Point(352, 236);

        _status = SharedUi.Body("准备就绪");
        _status.Location = new Point(28, 272);
        _status.Size = new Size(464, 24);

        _progress = new RoundProgressBar
        {
            Location = new Point(28, 300),
            Width = 464,
            Value = 0,
        };

        _installButton = SharedUi.PrimaryButton("安装");
        _installButton.Location = new Point(248, 336);
        _installButton.Click += (_, _) => StartInstall();

        _cancelButton = SharedUi.GhostButton("取消");
        _cancelButton.Location = new Point(392, 336);
        _cancelButton.Click += (_, _) => Close();

        Controls.AddRange(new Control[]
        {
            icon, title, version, author, target, summary,
            _desktop, _autoStart, _runNow,
            _status, _progress, _installButton, _cancelButton,
        });
    }

    private void StartInstall()
    {
        _installButton.Enabled = false;
        _cancelButton.Enabled = false;
        _desktop.Enabled = false;
        _autoStart.Enabled = false;
        _runNow.Enabled = false;
        _status.Text = "正在安装…";

        var options = new InstallOptions
        {
            DesktopShortcut = _desktop.Checked,
            AutoStart = _autoStart.Checked,
            RunAfterInstall = _runNow.Checked,
        };

        Task.Run(() =>
        {
            try
            {
                Installer.Install(options, Report);
                Installer.WriteLog("安装流程已结束；成功=" + true);
                BeginInvoke(() => Finish(true, "安装完成，网关已就绪。"));
            }
            catch (ElevationRequiredException ex)
            {
                Installer.WriteLog("普通权限写入被拒，改为请求管理员权限重试：" + ex.Message);
                var args = Environment.GetCommandLineArgs().Skip(1).ToArray();
                if (Installer.TryElevate(args, out _))
                {
                    BeginInvoke(() =>
                    {
                        MessageBox.Show(
                            "旧版网关仍在运行，普通权限无法覆盖它的文件。\n\n" +
                            "已为你打开「管理员安装」窗口，请在 Windows 用户账户控制提示中点「是」继续。",
                            Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
                        Close();
                    });
                    return;
                }

                Installer.WriteLog("安装流程已结束；成功=false");
                BeginInvoke(() => Finish(false, "安装被取消：需要管理员权限才能覆盖正在运行的旧版网关。\n详细日志：" + Installer.LogFilePathForDisplay));
            }
            catch (Exception ex)
            {
                Installer.WriteLog("安装异常：" + ex);
                Installer.WriteLog("安装流程已结束；成功=false");
                BeginInvoke(() => Finish(false, "安装失败：" + ex.Message + "\n详细日志：" + Installer.LogFilePathForDisplay));
            }
        });
    }

    private void Report(int percent, string message)
    {
        if (IsDisposed)
        {
            return;
        }

        BeginInvoke(() =>
        {
            _progress.Value = percent;
            _status.Text = message;
        });
    }

    private void Finish(bool success, string message)
    {
        if (IsDisposed)
        {
            return;
        }

        Success = success;
        _progress.Value = success ? 100 : _progress.Value;
        _status.Text = message;
        _installButton.Text = success ? "完成" : "重试";
        _installButton.Enabled = true;
        _cancelButton.Text = "关闭";
        _cancelButton.Enabled = true;

        if (success)
        {
            _installButton.Click += (_, _) => Close();
            MessageBox.Show(this,
                "声核网关已安装完成。\n\n安装位置：" + Installer.InstallDir +
                "\n\n可以从桌面图标启动，也可以直接打开状态页面查看连接情况。",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            Close();
        }
        else
        {
            MessageBox.Show(this,
                message + "\n\n详细日志：C:\\Ai-Work\\app\\安装包\\setup.log",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}

internal sealed class UninstallForm : Form
{
    private readonly Label _status;
    private readonly RoundProgressBar _progress;
    private readonly Button _uninstallButton;
    private readonly Button _cancelButton;

    public bool Success { get; private set; }

    public UninstallForm()
    {
        SharedUi.Apply(this);
        Text = "卸载" + Program.AppName;
        ClientSize = new Size(500, 300);
        ShowIcon = false;

        var title = SharedUi.Title("卸载" + Program.AppName);
        title.Location = new Point(28, 26);

        var info = SharedUi.Body("将删除程序文件、快捷方式和开机启动项，手机将无法再通过局域网控制声核。\n" +
                                 "授权凭证（.state 目录）会保留，重新安装后手机连接地址不变；" +
                                 "如需彻底清除，卸载后手动删除该目录。");
        info.Location = new Point(28, 70);
        info.Size = new Size(444, 60);

        var path = SharedUi.Body("安装位置\n" + Installer.InstallDir);
        path.Location = new Point(28, 138);
        path.Size = new Size(444, 52);

        _status = SharedUi.Body("准备就绪");
        _status.Location = new Point(28, 196);
        _status.Size = new Size(444, 24);

        _progress = new RoundProgressBar
        {
            Location = new Point(28, 222),
            Width = 444,
            Value = 0,
        };

        _uninstallButton = SharedUi.PrimaryButton("卸载");
        _uninstallButton.Location = new Point(228, 240);
        _uninstallButton.Click += (_, _) => StartUninstall();

        _cancelButton = SharedUi.GhostButton("取消");
        _cancelButton.Location = new Point(372, 240);
        _cancelButton.Click += (_, _) => Close();

        Controls.AddRange(new Control[]
        {
            title, info, path, _status, _progress, _uninstallButton, _cancelButton,
        });
    }

    private void StartUninstall()
    {
        var confirm = MessageBox.Show(this,
            "确定要卸载" + Program.AppName + "吗？",
            "确认卸载", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
        if (confirm != DialogResult.Yes)
        {
            return;
        }

        _uninstallButton.Enabled = false;
        _cancelButton.Enabled = false;

        Task.Run(() =>
        {
            try
            {
                Installer.Uninstall((percent, message) =>
                {
                    if (!IsDisposed)
                    {
                        BeginInvoke(() =>
                        {
                            _progress.Value = percent;
                            _status.Text = message;
                        });
                    }
                });
                BeginInvoke(() =>
                {
                    Success = true;
                    MessageBox.Show(this, "已卸载完成。", Program.AppName,
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                    Close();
                });
            }
            catch (Exception ex)
            {
                Installer.WriteLog("卸载异常：" + ex);
                BeginInvoke(() =>
                {
                    _status.Text = "卸载失败：" + ex.Message;
                    _uninstallButton.Enabled = true;
                    _cancelButton.Enabled = true;
                });
            }
        });
    }
}

