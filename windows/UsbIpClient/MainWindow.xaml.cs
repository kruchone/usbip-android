using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace UsbIpClient;

public partial class MainWindow : Window
{
    private readonly AppController _controller;
    private bool _loadingStartup;

    public MainWindow(AppController controller)
    {
        _controller = controller;
        DataContext = controller;
        InitializeComponent();
        _loadingStartup = true;
        StartupBox.IsChecked = Startup.Enabled;
        _loadingStartup = false;
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if ((Application.Current as App)?.IsExiting == true) return;
        // Closing the window keeps the app running in the notification area.
        e.Cancel = true;
        Hide();
        if (!_controller.Settings.TrayHintShown)
        {
            _controller.Settings.TrayHintShown = true;
            _controller.Settings.Save();
            (Application.Current as App)?.ShowTrayHint();
        }
    }

    private async void Toggle_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is DeviceVm d) await _controller.ToggleAsync(d);
    }

    private void RemoveServer_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is ServerVm s) _controller.RemoveServer(s);
    }

    private void Add_Click(object sender, RoutedEventArgs e) => AddServer();

    private void AddressBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) AddServer();
    }

    private void AddServer()
    {
        var error = _controller.AddManualServer(AddressBox.Text);
        if (error == null) AddressBox.Clear();
        else MessageBox.Show(this, error, "Add server", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void Startup_Changed(object sender, RoutedEventArgs e)
    {
        if (_loadingStartup) return;
        try
        {
            Startup.Enabled = StartupBox.IsChecked == true;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Start with Windows", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    /** Renders the window content to a PNG (used for diagnostics over remote sessions). */
    public void SaveSnapshot(string path)
    {
        var dpi = VisualTreeHelper.GetDpi(this);
        var content = (FrameworkElement)Content;
        var w = content.ActualWidth;
        var h = content.ActualHeight;
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            var bg = TryFindResource("ApplicationBackgroundBrush") as Brush ?? Background ?? Brushes.White;
            dc.DrawRectangle(bg, null, new Rect(0, 0, w, h));
            dc.DrawRectangle(new VisualBrush(content), null, new Rect(0, 0, w, h));
        }
        var bmp = new RenderTargetBitmap((int)(w * dpi.DpiScaleX), (int)(h * dpi.DpiScaleY), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bmp.Render(visual);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var f = System.IO.File.Create(path);
        enc.Save(f);
    }

    private void GetDriver_Click(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("https://github.com/vadimgrn/usbip-win2/releases/latest") { UseShellExecute = true });
}

public static class Converters
{
    public static readonly IValueConverter InverseBoolToVisibility = new InverseBoolToVisibilityConverter();

    private sealed class InverseBoolToVisibilityConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
            value is true ? Visibility.Collapsed : Visibility.Visible;

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
            throw new NotSupportedException();
    }
}
