using System.Drawing.Drawing2D;

namespace AudcoreGatewaySetup;

internal static class SharedUi
{
    public static readonly Color Background = Color.FromArgb(18, 19, 24);
    public static readonly Color Surface = Color.FromArgb(30, 31, 38);
    public static readonly Color Primary = Color.FromArgb(91, 107, 232);
    public static readonly Color TextColor = Color.FromArgb(228, 225, 233);
    public static readonly Color MutedText = Color.FromArgb(154, 151, 165);

    public static void Apply(Form form)
    {
        form.BackColor = Background;
        form.ForeColor = TextColor;
        form.Font = new Font("Microsoft YaHei UI", 9.5f, FontStyle.Regular);
        form.FormBorderStyle = FormBorderStyle.FixedSingle;
        form.MaximizeBox = false;
        form.StartPosition = FormStartPosition.CenterScreen;
        form.AutoScaleMode = AutoScaleMode.Dpi;
    }

    public static Label Title(string text) => new()
    {
        Text = text,
        Font = new Font("Microsoft YaHei UI", 17f, FontStyle.Bold),
        ForeColor = TextColor,
        AutoSize = true,
    };

    public static Label Body(string text) => new()
    {
        Text = text,
        ForeColor = MutedText,
        AutoSize = false,
        AutoEllipsis = false,
    };

    public static Button PrimaryButton(string text)
    {
        var button = new Button
        {
            Text = text,
            Height = 44,
            Width = 132,
            FlatStyle = FlatStyle.Flat,
            BackColor = Primary,
            ForeColor = Color.White,
            Font = new Font("Microsoft YaHei UI", 10.5f, FontStyle.Bold),
            Cursor = Cursors.Hand,
        };
        button.FlatAppearance.BorderSize = 0;
        return button;
    }

    public static Button GhostButton(string text)
    {
        var button = new Button
        {
            Text = text,
            Height = 44,
            Width = 100,
            FlatStyle = FlatStyle.Flat,
            BackColor = Surface,
            ForeColor = TextColor,
            Cursor = Cursors.Hand,
        };
        button.FlatAppearance.BorderColor = Color.FromArgb(70, 71, 79);
        button.FlatAppearance.BorderSize = 1;
        return button;
    }

    public static CheckBox Check(string text, bool value = true) => new()
    {
        Text = text,
        Checked = value,
        ForeColor = TextColor,
        AutoSize = true,
    };

    public static void PaintRounded(Graphics graphics, Rectangle bounds, Color color, int radius)
    {
        using var path = RoundedRect(bounds, radius);
        using var brush = new SolidBrush(color);
        graphics.FillPath(brush, path);
    }

    public static GraphicsPath RoundedRect(Rectangle bounds, int radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(bounds.X, bounds.Y, d, d, 180, 90);
        path.AddArc(bounds.Right - d, bounds.Y, d, d, 270, 90);
        path.AddArc(bounds.Right - d, bounds.Bottom - d, d, d, 0, 90);
        path.AddArc(bounds.X, bounds.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}

/// <summary>安装进度条：自绘圆角，避免系统进度条样式。</summary>
internal sealed class RoundProgressBar : Control
{
    private int _value;

    public int Value
    {
        get => _value;
        set
        {
            _value = Math.Clamp(value, 0, 100);
            Invalidate();
        }
    }

    public RoundProgressBar()
    {
        DoubleBuffered = true;
        Height = 10;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var graphics = e.Graphics;
        graphics.SmoothingMode = SmoothingMode.AntiAlias;

        var track = new Rectangle(0, 0, Width - 1, Height - 1);
        SharedUi.PaintRounded(graphics, track, SharedUi.Surface, Height / 2);

        if (_value <= 0)
        {
            return;
        }

        var width = Math.Max(Height, (int)Math.Round(Width * (_value / 100.0)));
        var fill = new Rectangle(0, 0, width - 1, Height - 1);
        SharedUi.PaintRounded(graphics, fill, SharedUi.Primary, Height / 2);
    }
}
