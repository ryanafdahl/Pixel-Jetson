# First successful direct Pixel USB test

Recorded 2026-09-27: Pixel 11 Pro XL, Tensor G6, Android 17, LiteRT 2.2.0,
TGCv2 source model, unrooted device. The Pixel was directly connected by USB to
the comma. The computer launched the isolated test over SSH and was not in the
model-data path. All 120 synthetic recurrent frames completed with 18,452 finite
output values each. The exact source model handshake passed.

| Measurement, excluding first 5 frames | Mean | p95 | Maximum |
| --- | ---: | ---: | ---: |
| Complete USB request/reply | 55.584 ms | 63.561 ms | 68.173 ms |
| NPU inference | 30.794 ms | 32.807 ms | 34.202 ms |
| History queues | 1.646 ms | 2.445 ms | 2.640 ms |
| Phone server work | 32.645 ms | 35.547 ms | 36.624 ms |

This does **not** meet a 50 ms complete-frame budget. The test also excludes
camera capture, image warping, driving-stack parsing, and control integration.
The earlier physical attempt negotiated USB high-speed (USB 2); speed was not
captured in the successful run's report, so its exact negotiated speed is not
asserted. Check the cable and negotiated speed on the next run.

Battery telemetry throughout this short run: 100%, 30 C, normal Android thermal
status, USB host session true. Both USB-powered and wireless-powered were false;
reported current was negative. The phone was running from its battery. This
neither verifies wireless-mount charging nor proves it cannot work.

The app's USB timeout after frame 120 was after successful test completion, when
the test closed and restored the comma's normal JetLink setting. Repeated manual
connections afterward reached the ordinary driving client, which this app rejects.

## Corrections that preceded success

The first physical connection reached the normal driving client. Its reconnects
caused repeated permission prompts. Permission now requires an explicit button
tap, and the isolated comma test must be started first. Its initial connection
window can be extended to five minutes so the operator can reach the phone.

The first isolated attempt passed the model handshake but the Pixel reported
`USB write failed` sending its first reply. The next build capped individual
reply transfers at 16 KiB, retained a one-second total write deadline, used the
OUT endpoint's actual packet size for padding, and added detailed write failures.
That combined change passed the 120-frame direct test. The experiment does not
isolate which change corrected the original write failure.

Six JVM tests cover framing, packet padding, consecutive gadget bursts, large
replies with partial writes, and failing transfers without retrying ambiguous
data. Desk checks also cover model mismatch rejection, parked-only handshake,
duplicate sequence behavior, malformed requests, and recurrent finite outputs.

## Numerical limits

All 54 history hashes matched the numpy JetLink queues over 150 frames including
wrap and reset. All six fixture file hashes matched. The first and last of 21
identical G6 fixture runs matched exactly. Against original FP16 ONNX, max
absolute error was 0.75 and mean 0.0174511 over 18,452 raw outputs; plan max was
0.6875 and hidden-state max 0.22265625. No driving tolerances or recorded-driving
recurrent replay qualification have been established.

Older command-line numerical comparisons are invalid: `run_model` left traffic
convention at `[0,0]` instead of `[1,0]` because its input helper silently failed
oversized padded scalar writes. Use the Android app's checked logical writes,
binary outputs, and fixture comparison, not those old comparisons.

Standalone G6 NPU-only inference completed 2,018 runs over one minute, mean
29.74 ms, max 47.79 ms, CPU disabled. This excludes transport and does not
establish in-car thermal endurance. Earlier G5 results used a different device
and compiled model and must not be substituted for G6 measurements.

Artifact hashes and sanitized per-frame measurements are in
[`results/usb-g6-120-frames.json`](../results/usb-g6-120-frames.json).

## Subsequent auto-launch/dashboard build

The next build adds the USB attachment intent/filter, reuse of the existing
Activity on attachment, and a budget dashboard driven by client-measured
exchange times. It was installed and its USB handler registration verified.
Nine JVM tests passed and another 30 recurrent desk frames passed (mean phone
server work 33.542 ms). The dashboard was inspected on the phone.
APK SHA256: `c045c123f8f19a5cdebdd95f3591d32aff8a927f2048ee20e244b6dd3970140c`.
This later build is not the APK that produced the first direct timing table above.

## Dashboard USB test

A subsequent physical run with the dashboard APK passed all 120 frames. The
phone log confirms the client measurements reached the UI: `TEST COMPLETE`,
average 56.8 ms, p95 64.2 ms, `OVER 50 ms budget`, 115 measured frames after
warmup. Exact mean exchange was 56.845 ms, p95 64.193 ms, maximum 78.578 ms;
mean NPU inference 31.026 ms and phone server work 33.008 ms.

Android's USB settings, read after returning the phone to the computer, retained
JetLink Pixel Test as the default handler for the comma gadget. This verifies
the saved choice. The app was placed on the home screen before the run but its
process was not killed; cold-process launch, reboot behavior, and a completely
unattended mount-to-model session remain separate checks.

Battery telemetry showed 100%, 28.1–28.2 C, normal thermal state and neither USB
nor wireless power. The run does not qualify wireless-mount charging.

After completion, the normal daemon resumed and repeatedly contacted the
parked-only app, which refused it. Those post-test reconnect messages do not
invalidate the result, but the complete plug-and-drive workflow is not ready:
the normal client still cannot use this unqualified model. The current parked
test must be launched separately. Summary:
[`usb-g6-dashboard-120-frames.json`](../results/usb-g6-dashboard-120-frames.json).
