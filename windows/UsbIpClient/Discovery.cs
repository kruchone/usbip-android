using System;
using System.Collections.Generic;
using System.Linq;
using Windows.Devices.Enumeration;

namespace UsbIpClient;

public sealed record DiscoveredServer(string Id, string Name, string HostName, int Port, IReadOnlyList<string> Addresses);

/**
 * Finds USB/IP servers that announce "_usbip._tcp" over mDNS (the Android app does), using the
 * Windows DNS-SD device watcher. Events fire on a background thread.
 */
public sealed class Discovery : IDisposable
{
    private const string Aqs =
        "System.Devices.AepService.ProtocolId:={4526e8c1-8aac-4153-9b16-55e86ada0e54} AND " +
        "System.Devices.Dnssd.Domain:=\"local\" AND System.Devices.Dnssd.ServiceName:=\"_usbip._tcp\"";

    private static readonly string[] Props =
    {
        "System.Devices.Dnssd.HostName",
        "System.Devices.Dnssd.InstanceName",
        "System.Devices.Dnssd.PortNumber",
        "System.Devices.IpAddress",
    };

    private readonly object _lock = new();
    private readonly Dictionary<string, DeviceInformation> _infos = new();
    private DeviceWatcher? _watcher;

    public event Action<DiscoveredServer>? Found;
    public event Action<string>? Lost;

    public void Start()
    {
        lock (_lock)
        {
            var w = DeviceInformation.CreateWatcher(Aqs, Props, DeviceInformationKind.AssociationEndpointService);
            w.Added += (_, info) =>
            {
                lock (_lock) _infos[info.Id] = info;
                Raise(info);
            };
            w.Updated += (_, update) =>
            {
                DeviceInformation? info;
                lock (_lock)
                {
                    if (!_infos.TryGetValue(update.Id, out info)) return;
                    info.Update(update);
                }
                Raise(info);
            };
            w.Removed += (_, update) =>
            {
                lock (_lock) _infos.Remove(update.Id);
                Lost?.Invoke(update.Id);
            };
            _watcher = w;
            w.Start();
        }
    }

    /** Re-runs the mDNS query (the watcher only reports changes after its first enumeration). */
    public void Restart()
    {
        Stop();
        Start();
    }

    private void Stop()
    {
        lock (_lock)
        {
            var w = _watcher;
            _watcher = null;
            if (w is { Status: DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted })
            {
                try { w.Stop(); } catch { /* already stopping */ }
            }
        }
    }

    private void Raise(DeviceInformation info)
    {
        var p = info.Properties;
        var host = p.TryGetValue("System.Devices.Dnssd.HostName", out var h) ? h as string ?? "" : "";
        var name = p.TryGetValue("System.Devices.Dnssd.InstanceName", out var n) && n is string s && s.Length > 0 ? s : info.Name;
        var port = p.TryGetValue("System.Devices.Dnssd.PortNumber", out var pn) && pn is IConvertible c ? c.ToInt32(null) : 3240;
        var ips = p.TryGetValue("System.Devices.IpAddress", out var ip) && ip is string[] arr ? arr : Array.Empty<string>();
        // Prefer IPv4: it's what people recognize, and link-local IPv6 needs a scope id.
        var ordered = ips.OrderBy(a => a.Contains(':') ? 1 : 0).ToList();
        if (ordered.Count == 0 && host.Length == 0) return;
        Found?.Invoke(new DiscoveredServer(info.Id, name, host, port, ordered));
    }

    public void Dispose() => Stop();
}
