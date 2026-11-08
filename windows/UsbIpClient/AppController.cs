using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;

namespace UsbIpClient;

/** Owns the server/device list, refreshes it, and performs attach/detach and auto-attach. */
public sealed class AppController : Observable
{
    private static readonly TimeSpan RefreshInterval = TimeSpan.FromSeconds(4);
    private static readonly TimeSpan DiscoveryRestart = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan ForgetOfflineAfter = TimeSpan.FromMinutes(10);

    private readonly Dispatcher _ui;
    private readonly Discovery _discovery = new();
    private readonly DispatcherTimer _timer;
    private DateTime _lastDiscoveryRestart = DateTime.Now;
    private bool _refreshing;
    private string _statusText = "";
    private bool _driverInstalled = UsbIpCli.IsInstalled;

    public Settings Settings { get; } = Settings.Load();
    public ObservableCollection<ServerVm> Servers { get; } = new();

    /** Raised for tray notifications: (title, message). */
    public event Action<string, string>? Notify;

    public string StatusText { get => _statusText; private set => Set(ref _statusText, value); }

    public bool DriverInstalled { get => _driverInstalled; private set => Set(ref _driverInstalled, value); }

    public bool HasServers => Servers.Count > 0;

    public string DriverVersion => UsbIpCli.Version is { } v ? $"usbip-win2 {v}" : "usbip-win2 not installed";

    public AppController(Dispatcher ui)
    {
        _ui = ui;
        _timer = new DispatcherTimer(RefreshInterval, DispatcherPriority.Background, async (_, _) => await RefreshAsync(), ui);
        Servers.CollectionChanged += (_, _) => OnChanged(nameof(HasServers));
    }

    public void Start()
    {
        foreach (var entry in Settings.ManualServers) AddManualServerVm(entry);
        _discovery.Found += s => _ui.BeginInvoke(() => OnDiscovered(s));
        _discovery.Lost += id => _ui.BeginInvoke(() => OnLost(id));
        try
        {
            _discovery.Start();
        }
        catch (Exception e)
        {
            StatusText = $"Network discovery unavailable ({e.Message}). Add servers by address.";
        }
        _timer.Start();
        _ = RefreshAsync();
    }

    public void Stop()
    {
        _timer.Stop();
        _discovery.Dispose();
    }

    // ------------------------------------------------------------------ servers

    private void OnDiscovered(DiscoveredServer d)
    {
        var host = d.Addresses.FirstOrDefault() ?? d.HostName;
        // Already listed by address (manual) or under its name: just refresh the details.
        var existing = Servers.FirstOrDefault(s => s.DiscoveryId == d.Id || (!s.IsManual && s.Key == d.Name))
                       ?? Servers.FirstOrDefault(s => s.IsManual && d.Addresses.Any(s.Matches));
        if (existing != null)
        {
            existing.DiscoveryId = d.Id;
            if (!existing.IsManual)
            {
                existing.Addresses = d.Addresses;
                existing.Port = d.Port;
                if (!existing.Online || !d.Addresses.Contains(existing.Host)) existing.Host = host;
            }
            return;
        }
        Servers.Add(new ServerVm
        {
            Key = d.Name,
            IsManual = false,
            DiscoveryId = d.Id,
            Name = d.Name,
            Host = host,
            Port = d.Port,
            Addresses = d.Addresses,
        });
        _ = RefreshAsync();
    }

    private void OnLost(string id)
    {
        // mDNS goodbyes can be spurious; the refresh loop marks it offline and forgets it later.
        foreach (var s in Servers.Where(s => s.DiscoveryId == id)) s.DiscoveryId = null;
    }

    public string? AddManualServer(string text)
    {
        text = text.Trim();
        if (text.Length == 0) return "Enter an IP address or host name.";
        if (!TryParseEndpoint(text, out var host, out var port)) return "Use host or host:port, e.g. 192.168.1.50.";
        var key = port == 3240 ? host : $"{host}:{port}";
        if (Servers.Any(s => s.Matches(host) && s.Port == port)) return "That server is already listed.";
        Settings.ManualServers.Add(key);
        Settings.Save();
        AddManualServerVm(key);
        _ = RefreshAsync();
        return null;
    }

    private void AddManualServerVm(string entry)
    {
        if (!TryParseEndpoint(entry, out var host, out var port)) return;
        Servers.Add(new ServerVm { Key = entry, IsManual = true, Name = host, Host = host, Port = port });
    }

    public void RemoveServer(ServerVm s)
    {
        Servers.Remove(s);
        if (s.IsManual)
        {
            Settings.ManualServers.Remove(s.Key);
            Settings.AutoAttach.RemoveWhere(k => k.StartsWith(s.Key + "|", StringComparison.Ordinal));
            Settings.Save();
        }
    }

    private static bool TryParseEndpoint(string text, out string host, out int port)
    {
        host = text;
        port = 3240;
        var i = text.LastIndexOf(':');
        if (i > 0 && text.IndexOf(':') == i) // host:port (not a bare IPv6 address)
        {
            if (!int.TryParse(text[(i + 1)..], out port) || port is < 1 or > 65535) return false;
            host = text[..i];
        }
        return host.Length > 0 && Uri.CheckHostName(host.Trim('[', ']')) != UriHostNameType.Unknown;
    }

    // ------------------------------------------------------------------ refresh

    public async Task RefreshAsync()
    {
        if (_refreshing) return;
        _refreshing = true;
        try
        {
            DriverInstalled = UsbIpCli.IsInstalled;
            if (DateTime.Now - _lastDiscoveryRestart > DiscoveryRestart)
            {
                _lastDiscoveryRestart = DateTime.Now;
                try { _discovery.Restart(); } catch { /* keep the old results */ }
            }

            List<ImportedDevice> imported = new();
            if (DriverInstalled)
            {
                try { imported = await UsbIpCli.PortsAsync(); }
                catch (Exception e) { StatusText = $"Couldn't query attached devices: {e.Message}"; }
            }

            var servers = Servers.ToList();
            await Task.WhenAll(servers.Select(s => RefreshServerAsync(s, imported)));

            foreach (var s in servers.Where(s => !s.IsManual && !s.Online && s.DiscoveryId == null &&
                                                 DateTime.Now - s.LastSeen > ForgetOfflineAfter))
            {
                Servers.Remove(s);
            }

            if (DriverInstalled) await AutoAttachAsync();
        }
        finally
        {
            _refreshing = false;
        }
    }

    private async Task RefreshServerAsync(ServerVm s, List<ImportedDevice> imported)
    {
        List<RemoteDevice> list;
        RemoteInfo? info;
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            list = await UsbIpWire.ListAsync(s.Host, s.Port, cts.Token);
            info = null;
            if (DateTime.Now >= s.InfoRetryAt)
            {
                info = await UsbIpWire.InfoAsync(s.Host, s.Port, cts.Token);
                // Servers without the extension (older app versions, Linux usbipd) log every unknown
                // request, so only re-check occasionally.
                if (info == null) s.InfoRetryAt = DateTime.Now + TimeSpan.FromMinutes(10);
            }
        }
        catch (Exception e)
        {
            s.Online = false;
            s.Status = "Offline";
            foreach (var d in s.Devices)
            {
                // Something attached from a server that just vanished is about to be dropped by the driver.
                var att = imported.FirstOrDefault(p => s.Matches(p.Host) && p.TcpPort == s.Port && p.BusId == d.BusId);
                if (att != null) Apply(d, DeviceState.Attached, $"Attached (port {att.Port})", att.Port);
                else Apply(d, DeviceState.Offline, "Offline", null);
            }
            if (e is not (SocketException or OperationCanceledException or System.IO.IOException)) s.Status = $"Error: {e.Message}";
            return;
        }

        s.Online = true;
        s.LastSeen = DateTime.Now;
        if (info?.Name is { Length: > 0 } serverName && s.IsManual) s.Name = serverName;
        s.Status = list.Count switch { 0 => "Online", 1 => "1 shared device", var n => $"{n} shared devices" };

        var localAddrs = LocalAddresses();
        foreach (var gone in s.Devices.Where(d => list.All(r => r.BusId != d.BusId)).ToList())
        {
            if (gone.WasAttached) Notify?.Invoke("Device removed", $"{gone.Name} is no longer shared by {s.Name}.");
            s.Devices.Remove(gone);
        }

        foreach (var r in list)
        {
            var vm = s.Devices.FirstOrDefault(d => d.BusId == r.BusId);
            if (vm == null)
            {
                vm = new DeviceVm { Server = s, BusId = r.BusId };
                vm.SetAutoSilently(Settings.AutoAttach.Contains(vm.Key));
                vm.AutoAttachChanged += OnAutoAttachChanged;
                s.Devices.Add(vm);
            }

            RemoteDeviceInfo? di = null;
            info?.Devices.TryGetValue(r.BusId, out di);
            vm.Name = di?.Name is { Length: > 0 } n && n != $"{r.VendorId:x4}:{r.ProductId:x4}" ? n : UsbIpWire.ClassName(r);
            vm.Detail = $"{r.VendorId:x4}:{r.ProductId:x4} · bus {r.BusId} · {UsbIpWire.SpeedName(r.Speed)}";

            if (vm.State == DeviceState.Working) continue; // an attach/detach is in progress

            var att = imported.FirstOrDefault(p => s.Matches(p.Host) && p.TcpPort == s.Port && p.BusId == r.BusId);
            if (att != null)
            {
                Apply(vm, DeviceState.Attached, $"Attached (port {att.Port})", att.Port);
            }
            else if (di?.AttachedBy is { } by && !localAddrs.Contains(by))
            {
                Apply(vm, DeviceState.InUseElsewhere, $"In use by {by}", null);
            }
            else
            {
                Apply(vm, DeviceState.Available, "Available", null);
            }
        }
    }

    private void Apply(DeviceVm d, DeviceState state, string text, int? port)
    {
        if (d.WasAttached && state != DeviceState.Attached && state != DeviceState.Working)
        {
            d.WasAttached = false;
            Notify?.Invoke("Device disconnected", d.AutoAttach
                ? $"{d.Name} was disconnected. It will be re-attached when it's available."
                : $"{d.Name} was disconnected.");
        }
        if (state == DeviceState.Attached) d.WasAttached = true;
        d.State = state;
        d.StatusText = text;
        d.AttachedPort = port;
    }

    private async Task AutoAttachAsync()
    {
        var candidates = Servers.Where(s => s.Online)
            .SelectMany(s => s.Devices)
            .Where(d => d.AutoAttach && d.State == DeviceState.Available && DateTime.Now >= d.NextAutoAttempt)
            .ToList();
        foreach (var d in candidates) await AttachAsync(d, automatic: true);
    }

    private void OnAutoAttachChanged(DeviceVm d)
    {
        if (d.AutoAttach) Settings.AutoAttach.Add(d.Key);
        else Settings.AutoAttach.Remove(d.Key);
        Settings.Save();
        if (d.AutoAttach && d.State == DeviceState.Available)
        {
            d.NextAutoAttempt = DateTime.MinValue;
            _ = AttachAsync(d, automatic: true);
        }
    }

    // ------------------------------------------------------------------ actions

    public async Task ToggleAsync(DeviceVm d)
    {
        if (d.State == DeviceState.Attached) await DetachAsync(d);
        else if (d.State == DeviceState.Available) await AttachAsync(d, automatic: false);
    }

    public async Task AttachAsync(DeviceVm d, bool automatic)
    {
        if (!DriverInstalled || d.State == DeviceState.Working) return;
        var s = d.Server;
        Apply(d, DeviceState.Working, "Attaching…", null);
        var (port, error) = await UsbIpCli.AttachAsync(s.Host, s.Port, d.BusId);
        if (port != null)
        {
            d.AutoFailures = 0;
            Apply(d, DeviceState.Attached, port > 0 ? $"Attached (port {port})" : "Attached", port);
            StatusText = $"Attached {d.Name} from {s.Name}.";
            if (automatic) Notify?.Invoke("Device attached", $"{d.Name} from {s.Name}");
        }
        else
        {
            Apply(d, DeviceState.Available, "Available", null);
            StatusText = $"Couldn't attach {d.Name}: {error}";
            d.AutoFailures++;
            // Back off: 10 s, 20 s, 40 s … up to 5 minutes.
            d.NextAutoAttempt = DateTime.Now + TimeSpan.FromSeconds(Math.Min(300, 10 * Math.Pow(2, d.AutoFailures - 1)));
        }
        await RefreshAsync();
    }

    public async Task DetachAsync(DeviceVm d)
    {
        if (d.AttachedPort is not { } port || port <= 0) return;
        var s = d.Server;
        d.SetAutoSilently(false); // otherwise auto-attach would immediately undo this
        Settings.AutoAttach.Remove(d.Key);
        Settings.Save();
        d.WasAttached = false;
        Apply(d, DeviceState.Working, "Detaching…", null);
        var error = await UsbIpCli.DetachAsync(port);
        await UsbIpCli.StopAttemptsAsync(s.Host, s.Port, d.BusId);
        StatusText = error == null ? $"Detached {d.Name}." : $"Couldn't detach {d.Name}: {error}";
        Apply(d, DeviceState.Available, "Available", null);
        await RefreshAsync();
    }

    public async Task DetachAllAsync()
    {
        foreach (var d in Servers.SelectMany(s => s.Devices).Where(d => d.State == DeviceState.Attached).ToList())
            await DetachAsync(d);
    }

    private static HashSet<string> LocalAddresses()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            foreach (var a in nic.GetIPProperties().UnicastAddresses)
                if (a.Address.AddressFamily is AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)
                    set.Add(a.Address.ToString());
        }
        catch
        {
            // ignore
        }
        set.Add(IPAddress.Loopback.ToString());
        return set;
    }
}
