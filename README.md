# USB/IP Server for Android

Share USB devices plugged into an Android phone or tablet with a Windows (or Linux) PC over the network,
the way VirtualHere does. The app is a standard **USB/IP** server (TCP 3240, protocol 1.1.1), so the
Windows side uses the existing signed **usbip-win2** client; no custom PC software is needed.

```
[USB device] --OTG--> [Android phone: this app] ==Wi-Fi/TCP 3240==> [Windows: usbip-win2] -> device appears locally
```

Supported: control, bulk and interrupt transfers. That covers USB-serial adapters, Arduino/ESP/MCU
programmers, license dongles, keyboards, mice, gamepads, flash drives, printers and scanners.
Not supported: isochronous transfers (audio interfaces, webcams), because Android's public USB API
can't do them.

## Phone setup

1. Install the APK: `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy it to the phone and open it.
2. Plug the USB device in through an OTG adapter. Use a **powered** OTG hub for several devices or power-hungry ones.
   Android asks whether to open USB/IP Server. Tick **Always** to make the permission permanent.
3. In the app, switch **Share** on for the device and start the **Server**. The app shows the phone's IP address
   and a ready-made `usbip attach` command for each shared device.

The server keeps running in the background as a foreground service, with a notification that has a Stop button.
While a device is attached it holds wake and Wi-Fi locks so transfers continue with the screen off.

## Windows app (`windows/UsbIpClient`)

USB/IP Client is a WPF tray app (.NET 10) that works on top of the signed **usbip-win2** driver.
Install the driver once with `winget install vadimgrn.usbip-win2`. What the app does:

- **Finds phones:** it discovers phones running the server over mDNS (`_usbip._tcp`). You can also add a server
  by address, e.g. over Tailscale.
- **Shows devices:** each shared device is listed with its real name, VID:PID, bus speed, and whether it's free or in use by another PC.
- **Attaches:** one click attaches or detaches a device. An **Auto** checkbox re-attaches it whenever it becomes available,
  backing off after failures. You get tray notifications when devices attach or disconnect.
- **Runs in the background:** closing the window keeps it in the notification area, and it can **start with Windows**.
- **No admin rights:** the driver lets any user attach devices, so the app never asks for elevation.

To build on Windows:

```
cd windows\UsbIpClient
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o %LOCALAPPDATA%\Programs\UsbIpClient
```

Advanced users can drive usbip-win2 directly with `usbip list -r <ip>`, `usbip attach -r <ip> -b 1-1`, and `usbip port`.

### Protocol extension

Besides standard USB/IP, the Android server answers op `0x8F01` (OP_REQ_INFO) with
`0x0F01` + u32 length + JSON: `{"name", "devices":[{"busid","name","attachedBy"}]}`. Standard clients never
send it. The Windows app uses it for friendly names and "in use by" status.

## Security

USB/IP has no authentication or encryption. Only run it on a trusted network, or set
**Allowed client IPs** in the app. To use it over the internet, put it behind a VPN such as WireGuard or Tailscale.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :protocol:test        # protocol + server unit tests (JVM)
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting without Windows

`tools/usbip_probe.py` is a tiny independent USB/IP client:

```
python3 tools/usbip_probe.py <phone-ip>                 # list exported devices (speed, classes)
python3 tools/usbip_probe.py <phone-ip> --busid 1-1     # import and read the device/config descriptors
```

`./gradlew :protocol:demoServer` runs the server on your computer with a fake device, for testing clients.
Set `USBIP_PORT` to change the port.

## Layout

- `protocol/`: pure Kotlin/JVM USB/IP implementation.
  - `UsbIpCodec`: wire format.
  - `UsbIpServer`: handshake, URB submit/unlink, reply ordering.
  - `UsbDescriptors`: descriptor parsing and speed guess.
  - It talks to devices only through the `UsbIpBackend` interface, and is unit-tested with a fake device.
- `app/`: the Android app.
  - `usb/AndroidUsbBackend`: chooses which devices are exported and gives them stable bus IDs.
  - `usb/AndroidDeviceSession`: maps URBs to Android calls. Control transfers use `controlTransfer`. SET_CONFIGURATION, SET_INTERFACE and CLEAR_HALT are mapped to the matching Android calls. Bulk and interrupt transfers use async `UsbRequest`s, which can be cancelled.
  - `service/UsbIpService`: the foreground service.
  - `ui/MainActivity`: Compose UI.

## Known limitations

- Android's `UsbRequest` doesn't report a URB's status, so a stall on a bulk or interrupt **IN** endpoint shows up as
  a zero-length read rather than `-EPIPE`. A future NDK backend using usbdevfs directly would fix this
  and also make isochronous transfers possible.
- Android has no API for bus speed. The app reads sysfs when SELinux allows it, and otherwise guesses from
  the descriptors.
