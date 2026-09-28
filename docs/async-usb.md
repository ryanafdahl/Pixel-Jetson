# Queued USB candidate

`queued-v1` replaces synchronous per-chunk USB calls with Android `UsbRequest`.
Four 16 KiB direct receive buffers stay queued, including while the NPU runs.
A dedicated thread drains completions for both endpoints and preserves receive
submission order. A receive buffer is not requeued until its contents have been
consumed. Replies use a reusable direct buffer and asynchronous OUT request.

The synchronous implementation remains recoverable from commit `c12f73d`.
The last direct-tested dashboard APK has SHA256
`c045c123f8f19a5cdebdd95f3591d32aff8a927f2048ee20e244b6dd3970140c`.
The desk-tested candidate APK has SHA256
`1594b11fffc233029a56be7d1b97be11621cda590a181748304ff5ad22520682`.
Keep the former locally for `adb install -r` rollback with the same signing key.
No SDK, compiled model, precision, recurrent-state ownership or production
driving handshake changes accompany this optimization.

## Allocation and copying changes

- Reuse the fixed-size inference payload, message header and padding scratch.
- Reuse the NPU output FloatArray through a checked JNI output parameter.
- Bulk-pack floats into a reusable complete reply packet instead of calling
  `putFloat` for every output and then allocating/copying another framed packet.
- Keep that packet independent from the reusable NPU array so duplicate requests
  return identical bytes without advancing history.

This is not zero-copy: data still moves between USB buffers, model inputs and
outputs. Control messages and telemetry still allocate. The purpose is to reduce
gaps and allocation pressure without changing the model's numerical behavior.

## Failure handling and verification

The overall reply deadline remains one second. A failed/short asynchronous write
or timeout poisons the session; it is never retried because the kernel might
already have sent part of the reply. Closing cancels requests and releases the
interface/connection. The USB 2/3 endpoint-specific short-packet padding remains.

15 JVM tests pass, including out-of-order completions, reads pending while a
reply completes, no receive-buffer reuse before consumption, stalled/short writes,
byte-exact reply packing, duplicate preservation and packet-aligned padding.
The installed app passed 120 recurrent desk frames, wrong-model/ordinary-client
rejection and malformed-request checks. All 54 history hashes match; the fixture
and recurrent outputs at frames 0 and 119 exactly match the previous G6 build.

Desk ADB/TCP exchange averaged 50.841 ms, p95 54.485 ms; phone work averaged
34.016 ms. These measurements do not exercise Android's USB host requests and
do not establish a USB speedup. The original ONNX mismatch remains unchanged.
See [sanitized desk report](../results/async-v1-desk.json).

## Next hardware test

Use a known USB 3 data cable and a new output directory such as
`/data/pixel-jetlink-test/usb-g6-async-v1`. The test now records the controller's
`current_speed` and the phone's `usb_io` label after HELLO. Do not infer cable
speed from its connector or charging rating.

After saving the complete result, the test pings for up to 60 seconds while the
dashboard remains visible. Unplugging ends that hold early; the original JetLink
setting is restored. `--hold-seconds 0` disables this viewing period.

Changing both cable and software measures their combined effect. If it improves,
an A/B run of the previous APK with the same new cable is needed to separate
software gains from link-speed gains. This candidate still needs its first
physical USB test; camera-to-controls timing and driving accuracy are unqualified.

API reference: [Android UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest).

## Readable dashboard update

The installed UI update uses a dark navy background, bright text, cyan phone
metrics, amber waiting status, pink-red over-budget status, and green within-budget
status. Written labels accompany the colors.

Final installed APK SHA256:
`19493fb7c24db082e18718e1fcd48403c263e0a9e3d8cb7cec26efa389eee6d1`.
Its only code difference from the desk-tested candidate is the dashboard styling.
All 15 JVM tests also pass on this build; the 120-frame desk measurements above
belong to the earlier APK, not a repeated measurement of the UI update.

### First direct result

The queued-v1 build subsequently passed all 120 direct USB frames on the same
cable, as confirmed by the user. Mean exchange 51.140 ms, p95 56.111 ms;
negotiated high-speed (USB 2). This supersedes the pending-test status above.
See [measured results](results.md#asynchronous-usb-test). Driving remains unqualified.
