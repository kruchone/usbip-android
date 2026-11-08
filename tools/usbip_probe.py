#!/usr/bin/env python3
"""Minimal USB/IP client for checking a server without installing usbip.

  python3 usbip_probe.py PHONE_IP                 # list exported devices
  python3 usbip_probe.py PHONE_IP --busid 1-1     # import it and read its descriptors

Importing claims the device until this script exits.
"""
import argparse, socket, struct, sys

SPEEDS = {0: "unknown", 1: "low (1.5M)", 2: "full (12M)", 3: "high (480M)", 4: "wireless", 5: "super (5G)", 6: "super+ (10G)"}
STATUS = {0: "OK", 1: "NA", 2: "device busy", 3: "device error", 4: "no such device", 5: "error"}


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"connection closed after {len(buf)}/{n} bytes")
        buf += chunk
    return buf


def parse_device(b):
    path, busid = b[:256].split(b"\0")[0].decode(), b[256:288].split(b"\0")[0].decode()
    busnum, devnum, speed, vid, pid, bcd, cls, sub, proto, cfg, ncfg, nif = struct.unpack(">IIIHHHBBBBBB", b[288:312])
    return dict(path=path, busid=busid, busnum=busnum, devnum=devnum, speed=speed, vid=vid, pid=pid, bcd=bcd,
                cls=cls, sub=sub, proto=proto, cfg=cfg, ncfg=ncfg, nif=nif)


def devlist(host, port):
    with socket.create_connection((host, port), timeout=10) as s:
        s.sendall(struct.pack(">HHI", 0x0111, 0x8005, 0))
        ver, code, status = struct.unpack(">HHI", recv_exact(s, 8))
        assert code == 0x0005, hex(code)
        (n,) = struct.unpack(">I", recv_exact(s, 4))
        devs = []
        for _ in range(n):
            d = parse_device(recv_exact(s, 312))
            d["ifaces"] = [struct.unpack("BBBx", recv_exact(s, 4)) for _ in range(d["nif"])]
            devs.append(d)
        return devs


def control_in(s, seq, bm, req, value, index, length):
    setup = struct.pack("<BBHHH", bm, req, value, index, length)
    s.sendall(struct.pack(">IIIII", 1, seq, 0x10002, 1, 0) + struct.pack(">IiiiI", 0, length, 0, -1, 0) + setup)
    hdr = recv_exact(s, 48)
    cmd, rseq = struct.unpack(">II", hdr[:8])
    status, actual = struct.unpack(">ii", hdr[20:28])
    assert cmd == 3 and rseq == seq, (cmd, rseq)
    return status, recv_exact(s, actual) if actual > 0 else b""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--port", type=int, default=3240)
    ap.add_argument("--busid")
    a = ap.parse_args()

    devs = devlist(a.host, a.port)
    print(f"{len(devs)} exported device(s) on {a.host}:{a.port}")
    for d in devs:
        print(f"  {d['busid']:6} {d['vid']:04x}:{d['pid']:04x}  speed={SPEEDS.get(d['speed'], d['speed'])}  "
              f"class={d['cls']:02x}  ifaces={['%02x/%02x/%02x' % i for i in d['ifaces']]}  ({d['path']})")
    if not a.busid:
        return 0

    with socket.create_connection((a.host, a.port), timeout=10) as s:
        s.sendall(struct.pack(">HHI", 0x0111, 0x8003, 0) + a.busid.encode().ljust(32, b"\0"))
        ver, code, status = struct.unpack(">HHI", recv_exact(s, 8))
        if status != 0:
            print(f"import {a.busid}: {STATUS.get(status, status)}")
            return 1
        d = parse_device(recv_exact(s, 312))
        print(f"imported {d['busid']} ({d['vid']:04x}:{d['pid']:04x})")
        st, desc = control_in(s, 1, 0x80, 6, 0x0100, 0, 18)
        print(f"  device descriptor (status {st}): {desc.hex(' ')}")
        if st != 0 or len(desc) < 18:
            return 1
        bcd_usb, mps0 = struct.unpack("<H", desc[2:4])[0], desc[7]
        print(f"  bcdUSB={bcd_usb:04x} bMaxPacketSize0={mps0} idVendor={struct.unpack('<H', desc[8:10])[0]:04x}")
        st, cfg = control_in(s, 2, 0x80, 6, 0x0200, 0, 9)
        if st == 0 and len(cfg) >= 4:
            total = struct.unpack("<H", cfg[2:4])[0]
            st, cfg = control_in(s, 3, 0x80, 6, 0x0200, 0, total)
            print(f"  configuration descriptor, {len(cfg)} bytes (status {st}): {cfg.hex(' ')}")
    print("ok (device released)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
