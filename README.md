# Pixel-Jetson

Run the TGCv2 model on a Pixel's Google Tensor NPU through JetLink, with reusable device setup, an Android test app, and measured results. The Jetson TensorRT deployment remains a separate backend.

**Current stage: parked USB testing. Driving is not yet qualified.**

## Start here

1. **Prepare your phone and computer:** follow the [setup guide](docs/setup.md).
2. **Build and install:** compile the model for your phone's chip, then install the Android app using the setup guide.
3. **Test the connection:** follow the [parked USB test](docs/parked-test.md), connecting the Pixel directly to the comma.
4. **Review the measurements:** see [test results and limitations](docs/results.md).

| Device | Required model target |
| --- | --- |
| Pixel 10 Pro XL | Tensor G5 |
| Pixel 11 Pro XL | Tensor G6 |
| Jetson | Separate TensorRT backend |

G5 and G6 compiled models are not interchangeable. Obtain the Tensor SDK through your own Google authorization.

## What works today

- **Direct USB:** two 120-frame synthetic recurrent tests completed successfully.
- **Automatic app launch:** Android's saved USB default was verified. Cold-process launch and reboot behavior still need testing.
- **Readable dashboard:** average, p95, frame count, and a written 50 ms budget status, with high-contrast colors.
- **Correctness checks:** history queues, duplicate requests, model identity, and malformed requests are checked.

The latest completed dashboard USB test averaged **56.8 ms**, with **64.2 ms p95**. It is over the 50 ms budget, even before camera processing and control integration.

The next candidate uses **asynchronous USB transfers and reusable buffers**. Desk checks passed; direct USB performance with the better cable is still pending. See [optimization details, checks, and rollback](docs/async-usb.md).

## Mount, plug in, and test

On the first connection, select **JetLink Pixel Test** and **Always use** if Android offers it. Later matching connections should open the app with USB access. **Connect comma USB** is the manual fallback.

Start the isolated parked test on the comma before plugging in the phone. The phone connects directly to the comma; the computer only starts the test remotely. Keep the vehicle parked and the app visible.

The dashboard uses amber while waiting, green when both average and p95 fit the budget, and red when either exceeds it. A green result describes this synthetic USB test; it does not qualify the system for driving.

## Before driving integration

The app currently refuses the normal driving client. Remaining work includes model accuracy acceptance, complete-frame timing, thermal endurance, and fallback validation. The app is an Activity prototype; startup after reboot and persistent background operation remain unqualified.

## Repository guide

| Location | Contents |
| --- | --- |
| [docs](docs/) | Setup, parked testing, results, and limitations |
| [android](android/) | Android app, native LiteRT bridge, and test clients |
| [model-tools](model-tools/) | Conversion, fixtures, specialization, and compiler scripts |
| [results](results/) | Sanitized measurements |

Licensed SDK files, model weights, runtime binaries, APKs, credentials, phone identifiers, and private logs are excluded.
