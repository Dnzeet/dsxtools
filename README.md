# DS Tools Android

An Android app that runs [DualShock Calibration GUI](https://dualshock-tools.github.io), the web tool for calibrating the analog sticks on PlayStation controllers, over a USB cable.

The original tool is a web page that talks to the controller through WebHID. Android browsers and WebView don't have WebHID, so the page can't see a USB controller on a phone. This app ships the site's files in `web/` and puts a small bridge between the page and Android's USB host API. The tool's own code is not modified.

## What you can do with it

Same as the web version, over USB:

- Stick center calibration and range calibration
- Live input view, stick and button testing
- Controller info such as model, firmware and battery
- Save the result to the controller's memory

The original project lists DualShock 4, DualSense, DualSense Edge and the PS VR2 Sense controller as supported. On this Android build only the DualShock 4 (CUH-ZCT1, `054c:05c4`) has actually been tried.

## Requirements

- Android 7.0 or newer with USB host (OTG) support
- An OTG adapter and a USB cable that carries data, not just power
- An internet connection, since the page loads Bootstrap, jQuery and Font Awesome from CDNs like the original site

## How it works

`web/` is the unchanged site. When the app loads `index.html` it injects `res/raw/hid_polyfill.js`, which defines `navigator.hid` before any of the page's scripts run. Calls on that object go through a JavaScript interface to `UsbHidBridge.java`:

- the HID report descriptor is read from the device and parsed, and the page gets the same report layout it would get from WebHID
- feature reports are sent and received as HID control transfers
- input reports are read from the interrupt IN endpoint and pushed to the page as `inputreport` events
- report sizes are checked against the descriptor, and anything longer than the descriptor allows is rejected

## Write mode

Calibration changes what is stored on the controller, so the app has a Write switch in the top bar. It is on by default. Turning it off puts the app in read-only mode: info, input and stick tests still work, while calibration commands and the NVS unlock are blocked.

The Log button shows the commands the app has sent to the controller (reads are not listed) along with any errors, and the text can be copied.

## Limitations

- USB only. The tool itself doesn't work over Bluetooth.
- The bridge is new and has had little testing. Use a solid cable and don't unplug during a calibration. If a calibration goes wrong, run it again and save again.
- The screen stays on while the app is open so a calibration doesn't get cut off.

## Credits

The calibration tool is by the_al and Mathias Malmqvist and is MIT licensed, see `web/LICENSE.txt` and `web/CREDITS.md`. This repository only adds the Android wrapper around it.
