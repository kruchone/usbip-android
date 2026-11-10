# USB/IP Server for Android

**Download:** https://kruchone.github.io/usbip/

Share USB devices plugged into an Android phone or tablet with a Windows (or Linux) PC over the network,
the way VirtualHere does. The app is a standard **USB/IP** server (TCP 3240, protocol 1.1.1), so the
Windows side uses the companion tray app [usbip-windows](https://github.com/kruchone/usbip-windows), which runs on the signed **usbip-win2** driver. Any standard USB/IP client also works.

```
[USB device] --OTG--> [Android phone: this app] ==Wi-Fi/TCP 3240==> [Windows: usbip-windows + usbip-win2] -> device appears locally
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

## Windows

Use [usbip-windows](https://github.com/kruchone/usbip-windows), a tray app that discovers this server, shows
device names and attaches devices with one click. Any standard USB/IP client works too, e.g. `usbip attach -r <ip> -b 1-1`
with usbip-win2.

### Protocol extension

Besides standard USB/IP, the Android server answers op `0x8F01` (OP_REQ_INFO) with
`0x0F01` + u32 length + JSON: `{"name", "devices":[{"busid","name","attachedBy"}]}`. Standard clients never
send it. usbip-windows uses it for friendly names and "in use by" status.

## Device names

Devices are named from, in order:
1. your own label (tap a device's name → Rename);
2. the device's USB product string;
3. the bundled [USB ID database](http://www.linux-usb.org/usb.ids) (`protocol/src/main/resources/usb.ids.gz`,
   GPL-2.0-or-later / BSD-3-Clause), looked up by VID:PID.

Generic strings like "USB Receiver" get the vendor added ("Logitech Unifying Receiver"). The second line shows the vendor,
what the device contains (Keyboard, Mouse, Audio, Storage, …) and its VID:PID and bus id. Labels are sent to clients through the
info extension, so a name set on the phone also shows up in usbip-windows. The naming logic (`DeviceNaming`) matches
usbip-windows' `DeviceNames`.

## Logs

The Activity screen (list icon) shows recent events, warnings and errors. They are also written to
files on the phone and kept for 7 days. Use **Share** (top right of the Activity screen) to send the
full log, including error details, by email, chat, Drive and so on. **Clear** deletes it. With adb you
can also run `adb logcat -s UsbIp`. Per-transfer tracing (logcat only) is turned on with
`adb shell setprop log.tag.UsbIpUrb VERBOSE`.

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
