using System;
using System.Linq;
using System.Threading;
using System.Windows;

namespace UsbIpClient;

public partial class App : Application
{
    private const string InstanceName = "UsbIpClient.SingleInstance";
    private Mutex? _mutex;
    private EventWaitHandle? _showSignal;
    private TrayIcon? _tray;
    private AppController? _controller;

    protected override void OnStartup(StartupEventArgs e)
    {
        _mutex = new Mutex(true, InstanceName, out var first);
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Show");
        if (!first)
        {
            // Already running: bring the existing window forward instead.
            _showSignal.Set();
            Shutdown();
            return;
        }

        base.OnStartup(e);
        _controller = new AppController(Dispatcher);
        var window = new MainWindow(_controller);
        MainWindow = window;
        _tray = new TrayIcon(_controller, ShowMainWindow, ExitApp);

        var signal = _showSignal;
        new Thread(() =>
        {
            while (signal.WaitOne()) Dispatcher.BeginInvoke(ShowMainWindow);
        }) { IsBackground = true, Name = "show-signal" }.Start();

        if (!e.Args.Contains("--tray", StringComparer.OrdinalIgnoreCase)) window.Show();
        _controller.Start();

        // Diagnostics: --snapshot <file.png> renders the window to an image after it settles, then exits.
        var snap = Array.FindIndex(e.Args, a => a.Equals("--snapshot", StringComparison.OrdinalIgnoreCase));
        if (snap >= 0 && snap + 1 < e.Args.Length)
        {
            var path = e.Args[snap + 1];
            var delay = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
            delay.Tick += (_, _) =>
            {
                delay.Stop();
                window.SaveSnapshot(path);
                ExitApp();
            };
            delay.Start();
        }
    }

    public void ShowMainWindow()
    {
        if (MainWindow == null) return;
        MainWindow.Show();
        if (MainWindow.WindowState == WindowState.Minimized) MainWindow.WindowState = WindowState.Normal;
        MainWindow.Activate();
    }

    public bool IsExiting { get; private set; }

    public void ShowTrayHint() =>
        _tray?.ShowHint("Still running", "USB/IP Client keeps your devices attached from the notification area.");

    public void ExitApp()
    {
        IsExiting = true;
        _tray?.Dispose();
        _controller?.Stop();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _tray?.Dispose();
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
