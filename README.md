# Pixel / Jetson JetLink preparation

Reusable setup and evidence for running the TGCv2 model on a Pixel's Google
Tensor NPU through JetLink. Pixel 10 Pro XL requires a Tensor G5 artifact;
Pixel 11 Pro XL requires a separate Tensor G6 artifact. The existing Jetson
TensorRT deployment remains a separate backend.

**Current milestone: 120 synthetic recurrent frames passed over direct
Pixel-to-comma USB. Driving qualification is not complete.** The USB exchange
averaged 55.584 ms (p95 63.561 ms), exceeding the 50 ms frame budget before
camera processing is included. See [measured results](docs/results.md).

## Mount-and-plug workflow

The Android app registers for the comma's JetLink USB device and can launch on
attachment. On the first attachment, choose **JetLink Pixel Test** and Android's
**Always use** option if shown. Android owns that one-time consent; the app does
not bypass it. Subsequent matching attachments should launch the chosen default
and grant access. This still needs an unplug/replug verification on the phone.
The manual Connect button remains a fallback and never opens repeated dialogs
automatically.

The dashboard separates phone work from the complete client-measured USB
exchange. It shows average, p95, frame count and a 50 ms budget verdict. An
average below budget does not hide a p95 above it. Measurements are from the
isolated synthetic test, not camera-to-controls latency. With no measurements,
the display says waiting rather than declaring a pass.

**The app's automatic launch does not enable driving.** The normal driving
client is refused while model accuracy is unqualified. The comma's isolated
parked test currently supplies the frames; it must be started separately.
The app is an Activity prototype, not a persistent background driving service.
Its display stays awake and may show over the lock screen; first unlock after
reboot and USB/security settings may still require interaction.

## Setup and use

1. [Prepare a Pixel and development machine](docs/setup.md).
2. Compile for the exact phone chip, build and install the app.
3. [Run the isolated parked USB test](docs/parked-test.md).
4. Validate automatic launch and remembered permission by unplugging/replugging.
5. Qualify numerical accuracy, the full frame budget, thermal endurance and
   fallback behavior before considering driving integration.

Source layout:

- `android/`: native LiteRT bridge, Android UI/USB protocol and host test clients.
- `model-tools/`: conversion, fixture, shape specialization and compiler scripts.
- `results/`: sanitized measurements, without phone identifiers or private logs.
- `docs/`: reusable setup, test steps, results and known limitations.

The repository excludes the licensed Tensor SDK archive/compiler, model weights,
runtime binaries, APKs, credentials, phone serials and private device logs. Obtain
the SDK through your own Google authorization. The app verifies the original
model identity, compiled model checksum and phone chip before accepting work.

Android's supported attachment/default behavior is documented in the
[USB host guide](https://developer.android.com/develop/connectivity/usb/host)
and [default-handler implementation](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java).
