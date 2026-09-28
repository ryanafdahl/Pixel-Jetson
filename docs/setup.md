# Repeatable Pixel preparation

These instructions reproduce an experimental parked test, not a driving install.
The scripts and model dimensions are specific to the TGCv2 source model below.
The conversion is not numerically equivalent to the source ONNX; preserve the
reference and validate separately. Do not substitute another model silently.

## Phone

1. Install system updates. A factory reset, rooting, and bootloader unlock are
   not required. Back up anything you choose to reset yourself.
2. Enable Developer options, then USB debugging. Connect a data-capable USB
   cable to the development computer and authorize that computer once.
3. Install Android platform-tools on the computer. Run `adb devices -l`, record
   the current device serial locally, then check `adb -s SERIAL shell getprop
   ro.soc.model`. Use Tensor_G5 for Tensor G5 and Tensor_G6 for Tensor G6.
4. Keep the screen unlocked during installation. The app keeps its visible
   display awake. Avoid uninstalling between updates: uninstalling deletes its
   private model and requires importing it again.

Charging from a wireless mount while using comma USB must be checked on that
exact mount/cable combination. The app displays USB/wireless power, battery
temperature and Android thermal status. The successful direct test showed
neither power source active. No power-role forcing or modified cable is needed
for this setup procedure.

## Development dependencies

The tested host used Windows PowerShell for ADB/SSH and Ubuntu 24.04 under WSL
for conversion, Tensor compilation and Android builds. Install dependencies
through their official distributions and retain version-matched environments:

- Python 3.12 and `uv` or pip; separate conversion and Tensor SDK environments.
- Conversion package versions: [`requirements-frozen.txt`](../model-tools/requirements-frozen.txt).
- Tensor compiler environment: `ai-edge-litert-nightly==2.3.0.dev20260926`,
  `ai-edge-litert-sdk-google-tensor-nightly==2.3.0.dev20260926`, ONNX 1.19.1,
  LLVM 18 libc++ and libc++abi. These are tested versions, not an instruction to
  mix a current nightly with older runtime libraries.
- Google-authorized `litert_plugin_compiler.tar.gz`. Follow the supplied SDK
  installation instructions. In the tested SDK installer,
  `GOOGLE_TENSOR_SDK_BETA=/absolute/path/litert_plugin_compiler.tar.gz` supplied
  the local authorized archive during package installation. Verify that
  `ai_edge_litert_sdk_google_tensor.path_to_sdk_libs()` resolves the installed
  compiler and that the target enum supports the selected chip. Do not fake
  installation or disable the SDK download/check to make a missing compiler pass.
- JDK 17, Gradle 8.13, Android platform 35, build-tools 35.0.0 and
  NDK 27.0.12077973. Set `ANDROID_HOME` to that Linux SDK.
- Android runtime is pinned to LiteRT **2.2.0**. Obtain its Google Tensor dispatch
  library from the [2.2.0 release](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0).
  Extract `libLiteRtDispatch_GoogleTensor.so` for arm64-v8a. Obtain `libLiteRt.so`
  for arm64-v8a from the same `com.google.ai.edge.litert:litert:2.2.0` AAR used by
  Gradle, and set `LITERT_LIBRARY_DIR` to its directory.

Clone the compatible JetLink Python implementation separately:

```bash
git clone https://github.com/zoompilot/jetlink.git /path/to/jetlink
git -C /path/to/jetlink checkout 1f0767fd3368c2894929f96e4f934b8824fc2500
export JETLINK_REPO=/path/to/jetlink
```

Keep that dependency's MIT license with any redistributed copies. It is not
vendored here. On the comma, use the already-installed compatible client and
its Python environment; do not replace the driving stack with this repository.

## Model pipeline (Linux)

Provide your existing original `big_driving_supercombo.onnx`, not a converted
file. The tested original has 765,950,064 bytes and SHA256
`1791d5940b2c048d0639813426dd2cf1d6f2a6727ed51e17c8bcea8bbe754123`.
Use your conversion environment for the first block. Generated data is ignored
by Git. Allow space for several multi-gigabyte intermediate files.

```bash
cd model-tools
python audit_model.py --onnx /absolute/path/big_driving_supercombo.onnx
python promote_onnx.py
onnx2tf -i portable-fp32.onnx -o converted-fp32 -tb flatbuffer_direct \
  -kat img big_img desire_pulse traffic_convention action_t features_buffer
cp converted-fp32/portable-fp32_float32.tflite model.tflite
python fix_gather.py
python prepare_fixture.py
python check_fp32_reference.py
```

Activate the Tensor SDK environment before the next block:

```bash
cd tensor-sdk
python fold_static_shapes.py
python prepare_padded_inputs.py
python test_compiler.py --stage apply --soc Tensor_G6 --model model-static-shapes.tflite
```

Use Tensor_G5 instead for G5. Shape specialization proves constant dimensions and
compares converted CPU outputs on three synthetic fixtures. A successful compile
must select all 1,560 remaining operations into one partition. G6 produced
829,635,440 bytes; G5 produced 769,321,072 bytes. The artifacts are not interchangeable.

## Build and install

From the repository root, with the Tensor SDK environment and JETLINK_REPO set:

```bash
cd android
python prepare.py --bench ../model-tools/tensor-sdk --soc Tensor_G6 \
  --onnx /absolute/path/big_driving_supercombo.onnx \
  --dispatch-lib /path/to/arm64-v8a/libLiteRtDispatch_GoogleTensor.so
python prepare_native.py
export ANDROID_HOME=/path/to/android-sdk
export LITERT_LIBRARY_DIR=/path/to/extracted/litert-2.2.0/arm64-v8a
export GRADLE_BIN=/path/to/gradle-8.13/bin/gradle
bash build.sh
```

`prepare_native.py` fetches only public headers from the exact LiteRT v2.2.0 tag.
`prepare.py` creates a local model manifest and copies the authorized runtime;
the APK does not contain the 830 MB model. The local release currently uses the
debug signing key for installation testing. Preserve that key between updates.

From Windows PowerShell, adapting paths and serial:

```powershell
.\android\install.ps1 -Serial 'YOUR_SERIAL' -Bench 'C:\path\P10PXL-Jetson\model-tools\tensor-sdk' -Adb 'C:\path\platform-tools\adb.exe'
```

The installer verifies chip, model size and SHA256, installs a debug build to
import private model/fixture files via `run-as`, then installs the release build
and runs ART optimization. The phone performs its own model checks at startup.
Wait for ready. The displayed phone model must match the intended device.

## Desk verification and later updates

Set `JETLINK_REPO` to the dependency's **Windows path** if running the host checks
with Windows Python. Use numpy in that Python environment.

```powershell
adb -s YOUR_SERIAL forward tcp:8765 tcp:8765
python android/bench_client.py --frames 120 --output local-results/desk
adb -s YOUR_SERIAL forward --remove tcp:8765
```

Pull app reports from `/sdcard/Android/data/net.afdahl.jetlink.pixel/files/`.
`check_queues.py` compares `queue-check.json`; `compare_fixture.py --bench
model-tools --runtime local-results/fixture` expects copied files named
`app-fixture-output.bin`, `app-fixture-output-last.bin`, and
`app-fixture-input-hashes.json`. This checks synthetic parity, not driving accuracy.

For code-only updates, `adb install -r android/app/build/outputs/apk/release/app-release.apk`
preserves the imported model when signed with the same key. Rebuild for the
correct chip and never bypass model/chip checks.
