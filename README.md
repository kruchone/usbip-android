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

## Windows setup

1. Install **usbip-win2**: https://github.com/vadimgrn/usbip-win2/releases (run its signed installer).
2. From an elevated terminal:
   ```
   usbip list -r 192.168.1.50                # devices the phone exports
   usbip attach -r 192.168.1.50 -b 1-1       # attach one; it shows up in Device Manager
   usbip port                                # attached devices
   usbip detach -p 1                         # detach
   ```
   usbip-win2 also has a GUI and can re-attach devices automatically after reconnects.

Bus IDs (`1-1`, `1-2`, …) stay the same for a given device across re-plugs, so saved attachments keep working.

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
