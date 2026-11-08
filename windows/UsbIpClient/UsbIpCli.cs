using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace UsbIpClient;

public sealed record CliResult(int ExitCode, string StdOut, string StdErr)
{
    public bool Ok => ExitCode == 0;

    /** usbip.exe logs errors via spdlog, e.g. "[2026-11-07 23:10:00.123] [error] ..."; keep just the message. */
    public string Message
    {
        get
        {
            var text = (StdErr + "\n" + StdOut).Trim();
            var lines = text.Split('\n').Select(l => Regex.Replace(l.Trim(), @"^\[[^\]]*\]\s*(\[[^\]]*\]\s*)?", ""))
                .Where(l => l.Length > 0);
            var msg = string.Join(" ", lines);
            return msg.Length > 0 ? msg : $"usbip.exe exited with code {ExitCode}";
        }
    }
}

/** A device currently attached to this PC, from `usbip port`. */
public sealed record ImportedDevice(int Port, string Host, int TcpPort, string BusId, string Speed, string Product);

/** Wraps the usbip-win2 command-line tool. */
public static class UsbIpCli
{
    public static string ExePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "USBip", "usbip.exe");

    public static bool IsInstalled => File.Exists(ExePath);

    public static string? Version
    {
        get
        {
            try { return IsInstalled ? FileVersionInfo.GetVersionInfo(ExePath).ProductVersion : null; }
            catch { return null; }
        }
    }

    public static async Task<CliResult> RunAsync(params string[] args)
    {
        var psi = new ProcessStartInfo(ExePath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        using var p = Process.Start(psi) ?? throw new InvalidOperationException("could not start usbip.exe");
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(45));
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(entireProcessTree: true); } catch { /* already gone */ }
            return new CliResult(-1, "", "usbip.exe timed out");
        }
        return new CliResult(p.ExitCode, await stdout, await stderr);
    }

    /** Attaches a remote device; returns the virtual hub port on success. */
    public static async Task<(int? port, string? error)> AttachAsync(string host, int tcpPort, string busId)
    {
        // --once: don't leave the driver retrying in the background if this fails; the app does its own retries.
        var r = await RunAsync("--tcp-port", tcpPort.ToString(), "attach", "-r", host, "-b", busId, "--terse", "--once");
        if (!r.Ok) return (null, r.Message);
        var last = r.StdOut.Split('\n').Select(l => l.Trim()).LastOrDefault(l => l.Length > 0);
        return int.TryParse(last, out var port) ? (port, null) : (0, null);
    }

    public static async Task<string?> DetachAsync(int port)
    {
        var r = await RunAsync("detach", "-p", port.ToString());
        return r.Ok ? null : r.Message;
    }

    /** Cancels any background re-attach attempts the driver has for this device. */
    public static Task StopAttemptsAsync(string host, int tcpPort, string busId) =>
        RunAsync("--tcp-port", tcpPort.ToString(), "attach", "-r", host, "-b", busId, "--stop");

    public static async Task<List<ImportedDevice>> PortsAsync()
    {
        var r = await RunAsync("port");
        if (!r.Ok) throw new InvalidOperationException(r.Message);
        return ParsePorts(r.StdOut);
    }

    // Port 01: device in use at High Speed(480Mbps)
    //          Arduino SA : Uno R3 (CDC ACM) (2341:0043)
    //            -> usbip://10.0.20.221:3240/1-1
    private static readonly Regex PortRx = new(
        @"^Port\s+(\d+):\s*device in use at\s*(.*)\n\s*(.*)\n\s*->\s*usbip://(.+):(\d+)/(\S+)",
        RegexOptions.Multiline);

    public static List<ImportedDevice> ParsePorts(string text) =>
        PortRx.Matches(text.Replace("\r\n", "\n"))
            .Select(m => new ImportedDevice(
                int.Parse(m.Groups[1].Value), m.Groups[4].Value.Trim('[', ']'), int.Parse(m.Groups[5].Value),
                m.Groups[6].Value, m.Groups[2].Value.Trim(), m.Groups[3].Value.Trim()))
            .ToList();
}
