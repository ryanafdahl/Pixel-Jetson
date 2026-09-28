# Phone-side optimization and sustained testing

The `direct-history-v3` candidate preserves the compiled model, precision,
recurrent ordering, and parked-only handshake. It is a development candidate;
its direct USB timing has not yet been measured.

## Changes

- Image and feature histories live in direct buffers. Native code gathers the
  selected rows directly into LiteRT inputs, removing about 2.75 MiB of duplicate
  history assembly per frame.
- The USB inference reply is packed into its own direct buffer and queued
  without copying it into a second USB output buffer. Control messages use
  separate storage so they cannot overwrite a cached duplicate reply.
- Reply completion still owns the buffer until success. Timeout or a short
  write invalidates the session; ambiguous writes are never retried.
- Battery/thermal service calls happen during the existing UI refresh. Frame
  replies read a published snapshot without taking a service-call lock.
- Rolling phone statistics use fixed primitive storage. Formatting only occurs
  when the dashboard is updated, instead of on every frame.

## Correctness checks

Startup checks nine wrap/reset samples across 150 frames. The optimized native
image/feature gather is compared byte-for-byte with reference materialization
inside the actual LiteRT input buffers. All 54 external numpy history hashes
must also match. The fixture and recurrent outputs at frames 0 and 599 matched
exactly between the previous build and the new candidates.

19 JVM tests pass, including cached replies across intervening control writes,
short/time-out writes, out-of-order receive completion, framing/padding, and
stage timing warmup exclusion. The phone passes model mismatch, ordinary driving
client rejection, duplicate-request, and malformed-frame checks.

## Initial matched desk runs

These are sequential 600-frame ADB/TCP tests, excluding five warmup frames.
They do not measure the phone's Android USB host transport. Differences smaller
than run-to-run variation must not be treated as established gains.

| Build | History mean | Phone work mean | Exchange mean | Exchange p95 |
| --- | ---: | ---: | ---: | ---: |
| Prior direct-USB-tested app | 2.260 ms | 34.388 ms | 51.701 ms | 55.410 ms |
| Direct feature history | 0.517 ms | 32.722 ms | 49.546 ms | 53.687 ms |
| Direct image + feature history | 0.285 ms | 32.493 ms | 49.061 ms | 52.126 ms |
| Combined candidate + direct replies | 0.290 ms | 32.717 ms | 49.504 ms | 53.137 ms |
| Final installed build | 0.278 ms | 32.573 ms | 49.229 ms | 52.751 ms |

Combined candidate used for the table and mode comparisons, APK SHA256:
`e93081126a02e30684b0df0229467eb1f7ecd3c23f13ce8a9d8a8f29358500d4`.
Previous direct-tested rollback APK SHA256:
`19493fb7c24db082e18718e1fcd48403c263e0a9e3d8cb7cec26efa389eee6d1`.
Keep both locally with the same signing key; APKs are not committed.

[Sanitized initial desk results](../results/phone-optimization-desk.json).

Final installed APK SHA256 (startup-only caching and validation-guard cleanup):
`17dc73316c82110d14f937b58957420bf1c4ca47b094cd69100639118cf9ec97`.
The exact installed build also passed 600 recurrent frames, all 54 history
hashes, and byte-exact first/last fixture and sampled recurrent comparisons.
Fixture files are now read/hashed once for the 21 startup runs. Failed startup
validation never publishes the engine for USB/TCP use.

## Phase timings

`STATE_RESP.phone_profile` contains mean/max stage durations and the number of
accepted frames after five warmup frames. Stages include history, input writes,
NPU invocation, output reads, validation, telemetry, reply packing and reply
sending. The receive stage includes waiting for the client and must not be
interpreted as pure USB transfer time. Profile JSON is built on state requests,
not every inference. The parked client saves the final profile with its result.

## Performance modes and longer desk tests

The supported LiteRT modes compared are 3 (high performance), 4 (sustained),
and 5 (burst). Default remains 5. To select a mode for a development test, first
stop the app, then start it with an explicit integer extra:

```bash
adb shell am force-stop net.afdahl.jetlink.pixel
adb shell am start -n net.afdahl.jetlink.pixel/.MainActivity --ei performance_mode 5
```

The extra is read only when the Activity/model is created. Confirm the actual
mode and `phone_pipeline` in HELLO. No thermal limits or precision settings are
changed. USB attachment from a fresh process uses the default burst mode.

With the phone ready and `JETLINK_REPO` configured as in setup:

```bash
adb forward tcp:8765 tcp:8765
python android/bench_client.py --frames 200000 --duration-seconds 7200 \
  --state-every 1000 --output /your/local/results/phone-soak
adb forward --remove tcp:8765
```

The run stops at its frame/time cap, or at a sampled Android severe thermal
status / 45 C battery temperature. Checkpoints are saved every 1,000 frames;
inspect `stop_reason` and elapsed time rather than assuming the requested two
hours completed. The desktop and phone must remain connected and awake. This
is a plugged-in desk endurance test, not an in-car thermal qualification.

## Next physical test

Use the updated parked client and a fresh output directory. Begin with 120 frames
on the same cable to compare with 51.140 ms mean / 56.111 ms p95. After a clean
short run, `--frames 1200` provides roughly a minute of synthetic load within
the existing three-minute frame-phase timeout. Record the negotiated speed,
profile and power state. Start the five-minute attachment window only when the
operator is ready to connect the phone; do not leave the comma waiting overnight.

### Mode comparison results

Each mode completed 3,000 recurrent desk frames. Outputs at frames 0 and 2999
matched exactly across modes. All sampled thermal states were Normal.

| Mode | NPU + input/output mean | Phone work mean | Exchange mean | Exchange p95 |
| --- | ---: | ---: | ---: | ---: |
| Burst (5) | 32.099 ms | 32.652 ms | 49.475 ms | 52.939 ms |
| Sustained (4) | 43.826 ms | 44.395 ms | 61.368 ms | 65.012 ms |
| High performance (3) | 31.952 ms | 32.517 ms | 49.645 ms | 54.135 ms |

Keep burst as the established default. High performance did not establish an
exchange improvement; sustained was substantially slower. Runs were sequential,
not temperature-matched randomized trials. A two-hour burst soak is a separate
pending test. [Sanitized mode results](../results/phone-performance-modes.json).
