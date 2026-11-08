using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace UsbIpClient;

public sealed record RemoteInterface(byte Class, byte SubClass, byte Protocol);

/** One entry of OP_REP_DEVLIST. */
public sealed record RemoteDevice(
    string BusId, string Path, int BusNum, int DevNum, int Speed,
    ushort VendorId, ushort ProductId, ushort BcdDevice,
    byte DeviceClass, byte DeviceSubClass, byte DeviceProtocol,
    IReadOnlyList<RemoteInterface> Interfaces);

public sealed record RemoteDeviceInfo(string Name, string? AttachedBy);

/** Reply to the OP_REQ_INFO extension served by the Android app (absent on other servers). */
public sealed record RemoteInfo(string? Name, IReadOnlyDictionary<string, RemoteDeviceInfo> Devices);

/** Talks the USB/IP handshake directly, for structured device lists (attach/detach go through usbip.exe). */
public static class UsbIpWire
{
    private const ushort Version = 0x0111;
    private const ushort OpReqDevList = 0x8005, OpRepDevList = 0x0005;
    private const ushort OpReqInfo = 0x8F01, OpRepInfo = 0x0F01;

    public static async Task<List<RemoteDevice>> ListAsync(string host, int port, CancellationToken ct)
    {
        using var tcp = await ConnectAsync(host, port, ct);
        var s = tcp.GetStream();
        await s.WriteAsync(Header(OpReqDevList), ct);
        var hdr = await ReadAsync(s, 8, ct);
        var code = BinaryPrimitives.ReadUInt16BigEndian(hdr.AsSpan(2));
        var status = BinaryPrimitives.ReadUInt32BigEndian(hdr.AsSpan(4));
        if (code != OpRepDevList || status != 0) throw new IOException($"unexpected reply 0x{code:x4}, status {status}");
        var n = BinaryPrimitives.ReadUInt32BigEndian(await ReadAsync(s, 4, ct));
        if (n > 1024) throw new IOException($"implausible device count {n}");

        var list = new List<RemoteDevice>((int)n);
        for (var i = 0; i < n; i++)
        {
            var b = await ReadAsync(s, 312, ct);
            var numIf = b[311];
            var ifs = new List<RemoteInterface>(numIf);
            for (var j = 0; j < numIf; j++)
            {
                var x = await ReadAsync(s, 4, ct);
                ifs.Add(new RemoteInterface(x[0], x[1], x[2]));
            }
            list.Add(ParseDevice(b, ifs));
        }
        return list;
    }

    private static RemoteDevice ParseDevice(byte[] b, List<RemoteInterface> ifs)
    {
        var span = b.AsSpan();
        return new RemoteDevice(
            BusId: CString(b, 256, 32),
            Path: CString(b, 0, 256),
            BusNum: (int)BinaryPrimitives.ReadUInt32BigEndian(span[288..]),
            DevNum: (int)BinaryPrimitives.ReadUInt32BigEndian(span[292..]),
            Speed: (int)BinaryPrimitives.ReadUInt32BigEndian(span[296..]),
            VendorId: BinaryPrimitives.ReadUInt16BigEndian(span[300..]),
            ProductId: BinaryPrimitives.ReadUInt16BigEndian(span[302..]),
            BcdDevice: BinaryPrimitives.ReadUInt16BigEndian(span[304..]),
            DeviceClass: b[306], DeviceSubClass: b[307], DeviceProtocol: b[308],
            Interfaces: ifs);
    }

    /** Returns null if the server doesn't implement the extension (e.g. Linux usbipd). */
    public static async Task<RemoteInfo?> InfoAsync(string host, int port, CancellationToken ct)
    {
        try
        {
            using var tcp = await ConnectAsync(host, port, ct);
            var s = tcp.GetStream();
            await s.WriteAsync(Header(OpReqInfo), ct);
            var hdr = await ReadAsync(s, 8, ct);
            if (BinaryPrimitives.ReadUInt16BigEndian(hdr.AsSpan(2)) != OpRepInfo) return null;
            var len = BinaryPrimitives.ReadUInt32BigEndian(await ReadAsync(s, 4, ct));
            if (len > 1 << 20) return null;
            var json = await ReadAsync(s, (int)len, ct);

            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var devices = new Dictionary<string, RemoteDeviceInfo>();
            if (root.TryGetProperty("devices", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                foreach (var d in arr.EnumerateArray())
                {
                    var busId = d.GetProperty("busid").GetString();
                    if (busId == null) continue;
                    var name = d.TryGetProperty("name", out var n) ? n.GetString() ?? busId : busId;
                    var by = d.TryGetProperty("attachedBy", out var a) && a.ValueKind == JsonValueKind.String ? a.GetString() : null;
                    devices[busId] = new RemoteDeviceInfo(name, by);
                }
            }
            var serverName = root.TryGetProperty("name", out var sn) ? sn.GetString() : null;
            return new RemoteInfo(serverName, devices);
        }
        catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or JsonException or InvalidOperationException or KeyNotFoundException)
        {
            return null;
        }
    }

    private static byte[] Header(ushort code)
    {
        var b = new byte[8];
        BinaryPrimitives.WriteUInt16BigEndian(b, Version);
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(2), code);
        return b;
    }

    private static async Task<TcpClient> ConnectAsync(string host, int port, CancellationToken ct)
    {
        var tcp = new TcpClient { NoDelay = true };
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(3));
        try
        {
            await tcp.ConnectAsync(host, port, cts.Token);
            return tcp;
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }

    private static async Task<byte[]> ReadAsync(NetworkStream s, int n, CancellationToken ct)
    {
        var b = new byte[n];
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(5));
        await s.ReadExactlyAsync(b, cts.Token);
        return b;
    }

    private static string CString(byte[] b, int offset, int size)
    {
        var end = Array.IndexOf(b, (byte)0, offset, size);
        return Encoding.ASCII.GetString(b, offset, (end < 0 ? offset + size : end) - offset);
    }

    public static string SpeedName(int speed) => speed switch
    {
        1 => "Low speed",
        2 => "Full speed",
        3 => "High speed",
        4 => "Wireless",
        5 => "SuperSpeed",
        6 => "SuperSpeed+",
        _ => "Unknown speed",
    };

    /** A rough label from the USB class codes, used when the server has no friendly name. */
    public static string ClassName(RemoteDevice d)
    {
        var cls = d.DeviceClass != 0 ? d.DeviceClass : (d.Interfaces.Count > 0 ? d.Interfaces[0].Class : (byte)0);
        return cls switch
        {
            0x01 => "Audio device",
            0x02 or 0x0A => "Serial / communications device",
            0x03 => "Input device",
            0x06 => "Camera / imaging device",
            0x07 => "Printer",
            0x08 => "Storage device",
            0x09 => "USB hub",
            0x0B => "Smart card reader",
            0x0E => "Video device",
            0xE0 => "Wireless adapter",
            0xEF => "USB device",
            0xFF => "Vendor-specific device",
            _ => "USB device",
        };
    }
}
