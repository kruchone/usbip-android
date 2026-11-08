using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;

namespace UsbIpClient;

public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnChanged(name);
        return true;
    }

    protected void OnChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> _run;
    private readonly Func<object?, bool>? _can;

    public RelayCommand(Action<object?> run, Func<object?, bool>? can = null)
    {
        _run = run;
        _can = can;
    }

    public event EventHandler? CanExecuteChanged
    {
        add => CommandManager.RequerySuggested += value;
        remove => CommandManager.RequerySuggested -= value;
    }

    public bool CanExecute(object? p) => _can?.Invoke(p) ?? true;
    public void Execute(object? p) => _run(p);
}

public enum DeviceState { Available, Attached, InUseElsewhere, Working, Offline }

public sealed class ServerVm : Observable
{
    private string _name = "";
    private string _host = "";
    private int _port = 3240;
    private bool _online;
    private string _status = "Connecting…";

    /** Stable identity: the mDNS instance name for discovered servers, "host:port" for manual ones. */
    public required string Key { get; init; }
    public required bool IsManual { get; init; }
    public string? DiscoveryId { get; set; }
    public IReadOnlyList<string> Addresses { get; set; } = Array.Empty<string>();
    public DateTime LastSeen { get; set; } = DateTime.Now;

    public string Name { get => _name; set => Set(ref _name, value); }

    /** Address used to talk to the server (and recorded by the driver for attached devices). */
    public string Host
    {
        get => _host;
        set { if (Set(ref _host, value)) OnChanged(nameof(Endpoint)); }
    }

    public int Port
    {
        get => _port;
        set { if (Set(ref _port, value)) OnChanged(nameof(Endpoint)); }
    }

    public string Endpoint => Port == 3240 ? Host : $"{Host}:{Port}";

    public bool Online
    {
        get => _online;
        set { if (Set(ref _online, value)) OnChanged(nameof(EmptyText)); }
    }

    public string Status { get => _status; set => Set(ref _status, value); }

    public ObservableCollection<DeviceVm> Devices { get; } = new();

    public string EmptyText => Online
        ? "No shared devices. In the phone app, plug in a USB device and switch on Share."
        : "";

    public bool Matches(string host) =>
        string.Equals(host, Host, StringComparison.OrdinalIgnoreCase) ||
        Addresses.Contains(host, StringComparer.OrdinalIgnoreCase);
}

public sealed class DeviceVm : Observable
{
    private string _name = "";
    private string _detail = "";
    private DeviceState _state = DeviceState.Available;
    private string _statusText = "Available";
    private int? _attachedPort;
    private bool _autoAttach;

    public required ServerVm Server { get; init; }
    public required string BusId { get; init; }
    public string Key => $"{Server.Key}|{BusId}";

    public string Name { get => _name; set => Set(ref _name, value); }
    public string Detail { get => _detail; set => Set(ref _detail, value); }

    public DeviceState State
    {
        get => _state;
        set
        {
            if (!Set(ref _state, value)) return;
            OnChanged(nameof(ActionText));
            OnChanged(nameof(CanAct));
            OnChanged(nameof(IsAttached));
        }
    }

    public string StatusText { get => _statusText; set => Set(ref _statusText, value); }

    public int? AttachedPort { get => _attachedPort; set => Set(ref _attachedPort, value); }

    public bool IsAttached => State == DeviceState.Attached;

    public string ActionText => State == DeviceState.Attached ? "Detach" : "Attach";

    public bool CanAct => State is DeviceState.Available or DeviceState.Attached;

    /** Two-way bound to the "Auto" checkbox; the controller persists changes. */
    public bool AutoAttach
    {
        get => _autoAttach;
        set { if (Set(ref _autoAttach, value)) AutoAttachChanged?.Invoke(this); }
    }

    public event Action<DeviceVm>? AutoAttachChanged;

    public void SetAutoSilently(bool value)
    {
        if (_autoAttach == value) return;
        _autoAttach = value;
        OnChanged(nameof(AutoAttach));
    }

    /** Earliest time the next automatic attach may be tried (backs off after failures). */
    public DateTime NextAutoAttempt { get; set; } = DateTime.MinValue;
    public int AutoFailures { get; set; }

    /** Set when we attached it, so an unexpected disappearance can be reported. */
    public bool WasAttached { get; set; }
}
