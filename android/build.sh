#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Set ANDROID_HOME to the installed Android SDK}"
: "${LITERT_LIBRARY_DIR:?Set LITERT_LIBRARY_DIR to extracted 2.2.0 libLiteRt.so directory}"
ndk_bin="$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$ndk_bin/aarch64-linux-android29-clang++" -std=c++17 -O3 -shared -fPIC -static-libstdc++ \
  -Wl,-z,max-page-size=16384 app/src/main/cpp/runtime.cpp -I build/native/include \
  -L "$LITERT_LIBRARY_DIR" -lLiteRt -o app/src/main/jniLibs/arm64-v8a/libpixel_runtime.so
"${GRADLE_BIN:-gradle}" --no-daemon :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
