using System;
using System.Drawing;
using System.Linq;
using WinForms = System.Windows.Forms;

namespace UsbIpClient;

/** Notification-area icon: left-click opens the window, right-click lists devices to attach/detach. */
public sealed class TrayIcon : IDisposable
{
    private readonly AppController _controller;
    private readonly Action _show;
    private readonly Action _exit;
    private readonly WinForms.NotifyIcon _icon;

    public TrayIcon(AppController controller, Action show, Action exit)
    {
        _controller = controller;
        _show = show;
        _exit = exit;
        _icon = new WinForms.NotifyIcon
        {
            Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application,
            Text = "USB/IP Client",
            Visible = true,
            ContextMenuStrip = new WinForms.ContextMenuStrip(),
        };
        _icon.ContextMenuStrip.Opening += (_, e) =>
        {
            BuildMenu();
            e.Cancel = false;
        };
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left) _show();
        };
        _icon.BalloonTipClicked += (_, _) => _show();
        controller.Notify += (title, text) => _icon.ShowBalloonTip(4000, title, text, WinForms.ToolTipIcon.Info);
    }

    public void ShowHint(string title, string text) => _icon.ShowBalloonTip(4000, title, text, WinForms.ToolTipIcon.Info);

    private void BuildMenu()
    {
        var menu = _icon.ContextMenuStrip!;
        menu.Items.Clear();

        var servers = _controller.Servers.ToList();
        if (servers.Count == 0)
        {
            menu.Items.Add(new WinForms.ToolStripMenuItem("No servers found") { Enabled = false });
        }
        foreach (var s in servers)
        {
            menu.Items.Add(new WinForms.ToolStripMenuItem($"{s.Name} — {(s.Online ? s.Endpoint : "offline")}") { Enabled = false });
            if (s.Devices.Count == 0)
            {
                menu.Items.Add(new WinForms.ToolStripMenuItem("    (no shared devices)") { Enabled = false });
            }
            foreach (var d in s.Devices.ToList())
            {
                var item = new WinForms.ToolStripMenuItem("    " + d.Name)
                {
                    Checked = d.IsAttached,
                    Enabled = d.CanAct,
                    ToolTipText = d.StatusText,
                };
                if (d.State == DeviceState.InUseElsewhere) item.Text += $"  ({d.StatusText.ToLowerInvariant()})";
                item.Click += async (_, _) => await _controller.ToggleAsync(d);
                menu.Items.Add(item);
            }
        }

        menu.Items.Add(new WinForms.ToolStripSeparator());
        var detachAll = new WinForms.ToolStripMenuItem("Detach all")
        {
            Enabled = servers.SelectMany(s => s.Devices).Any(d => d.IsAttached),
        };
        detachAll.Click += async (_, _) => await _controller.DetachAllAsync();
        menu.Items.Add(detachAll);
        var open = new WinForms.ToolStripMenuItem("Open USB/IP Client") { Font = new Font(menu.Font, FontStyle.Bold) };
        open.Click += (_, _) => _show();
        menu.Items.Add(open);
        var exit = new WinForms.ToolStripMenuItem("Exit");
        exit.Click += (_, _) => _exit();
        menu.Items.Add(exit);
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
