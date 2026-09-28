#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <arm_neon.h>
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/options/litert_google_tensor_options_type.h"

static void check(LiteRtStatus result, const char* call) {
  if (result != kLiteRtStatusOk) throw std::runtime_error(std::string(call) + ": " + std::to_string(result));
}
#define CHECK(call) check((call), #call)
static void fail(JNIEnv* env, const std::exception& e) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
struct Utf {
  JNIEnv* env; jstring value; const char* data;
  Utf(JNIEnv* e, jstring v) : env(e), value(v), data(e->GetStringUTFChars(v, nullptr)) {}
  ~Utf() { if (data) env->ReleaseStringUTFChars(value, data); }
};
struct Runtime {
  LiteRtEnvironment env = nullptr;
  LiteRtModel model = nullptr;
  LiteRtCompiledModel compiled = nullptr;
  LiteRtOptions options = nullptr;
  LiteRtTensorBuffer inputs[6] = {};
  LiteRtTensorBuffer output = nullptr;
  const size_t logical[6] = {393216, 393216, 1056, 8, 8, 2097152};
  ~Runtime() {
    for (auto buffer : inputs) if (buffer) LiteRtDestroyTensorBuffer(buffer);
    if (output) LiteRtDestroyTensorBuffer(output);
    if (compiled) LiteRtDestroyCompiledModel(compiled);
    if (model) LiteRtDestroyModel(model);
    if (options) LiteRtDestroyOptions(options);
    if (env) LiteRtDestroyEnvironment(env);
  }
};

extern "C" JNIEXPORT jlong JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_create(JNIEnv* jni, jobject, jstring path, jstring libs, jint performance_mode) {
  try {
    if (performance_mode < 3 || performance_mode > 5) throw std::runtime_error("Unsupported performance mode");
    Utf file(jni, path), library(jni, libs);
    auto r = std::make_unique<Runtime>();
    LiteRtEnvOption option{};
    option.tag = kLiteRtEnvOptionTagDispatchLibraryDir;
    option.value.type = kLiteRtAnyTypeString;
    option.value.str_value = library.data;
    CHECK(LiteRtCreateEnvironment(1, &option, &r->env));
    CHECK(LiteRtCreateModelFromFile(r->env, file.data, &r->model));
    CHECK(LiteRtCreateOptions(&r->options));
    CHECK(LiteRtSetOptionsHardwareAccelerators(r->options, kLiteRtHwAcceleratorNpu));
    // Exact TOML encoding used by LiteRT 2.2.0's public GoogleTensorOptions.
    // Default is burst; isolated desk tests can also compare high/sustained.
    // This never changes model precision or bypasses thermal management.
    std::string toml = "performance_mode = " + std::to_string(performance_mode) + "\n";
    char* payload = strdup(toml.c_str());
    LiteRtOpaqueOptions opaque = nullptr;
    auto status = LiteRtCreateOpaqueOptions("google_tensor", payload, free, &opaque);
    if (status != kLiteRtStatusOk) { free(payload); check(status, "opaque options"); }
    status = LiteRtAddOpaqueOptions(r->options, opaque);
    if (status != kLiteRtStatusOk) { LiteRtDestroyOpaqueOptions(opaque); check(status, "add options"); }
    CHECK(LiteRtCreateCompiledModel(r->env, r->model, r->options, &r->compiled));
    LiteRtSignature signature;
    CHECK(LiteRtGetModelSignature(r->model, 0, &signature));
    const char* names[] = {"img", "big_img", "desire_pulse", "traffic_convention", "action_t", "features_buffer"};
    for (int i = 0; i < 7; ++i) {
      LiteRtTensor tensor;
      LiteRtTensorBufferRequirements requirements;
      LiteRtRankedTensorType type;
      if (i < 6) {
        const char* name;
        CHECK(LiteRtGetSignatureInputName(signature, i, &name));
        if (strcmp(name, names[i])) throw std::runtime_error("Unexpected input ordering");
        CHECK(LiteRtGetSignatureInputTensorByIndex(signature, i, &tensor));
        CHECK(LiteRtGetCompiledModelInputBufferRequirements(r->compiled, 0, i, &requirements));
      } else {
        CHECK(LiteRtGetSignatureOutputTensorByIndex(signature, 0, &tensor));
        CHECK(LiteRtGetCompiledModelOutputBufferRequirements(r->compiled, 0, 0, &requirements));
      }
      CHECK(LiteRtGetRankedTensorType(tensor, &type));
      auto* target = i < 6 ? &r->inputs[i] : &r->output;
      CHECK(LiteRtCreateManagedTensorBufferFromRequirements(r->env, &type, requirements, target));
      size_t size;
      CHECK(LiteRtGetTensorBufferSize(*target, &size));
      if (size < (i < 6 ? r->logical[i] : 73808)) throw std::runtime_error("Tensor allocation is too small");
      void* data;
      CHECK(LiteRtLockTensorBuffer(*target, &data, kLiteRtTensorBufferLockModeWrite));
      memset(data, 0, size);
      CHECK(LiteRtUnlockTensorBuffer(*target));
    }
    return reinterpret_cast<jlong>(r.release());
  } catch (const std::exception& e) { fail(jni, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_write(JNIEnv* jni, jobject, jlong handle, jint index, jarray array, jboolean floats) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    if (!r || index < 0 || index >= 6) throw std::runtime_error("Invalid input");
    size_t size = jni->GetArrayLength(array) * (floats ? sizeof(float) : 1);
    if (size != r->logical[index]) throw std::runtime_error("Incorrect logical input size");
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->inputs[index], &memory, kLiteRtTensorBufferLockModeWrite));
    if (floats) jni->GetFloatArrayRegion(static_cast<jfloatArray>(array), 0, size / 4, static_cast<jfloat*>(memory));
    else jni->GetByteArrayRegion(static_cast<jbyteArray>(array), 0, size, static_cast<jbyte*>(memory));
    CHECK(LiteRtUnlockTensorBuffer(r->inputs[index]));
  } catch (const std::exception& e) { fail(jni, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_run(JNIEnv* jni, jobject, jlong handle, jfloatArray result, jlongArray timings) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    if (!r) throw std::runtime_error("Closed runtime");
    if (!result || jni->GetArrayLength(result) != 18452) throw std::runtime_error("Incorrect output size");
    if (!timings || jni->GetArrayLength(timings) != 2) throw std::runtime_error("Incorrect timing output size");
    const auto start = std::chrono::steady_clock::now();
    CHECK(LiteRtRunCompiledModel(r->compiled, 0, 6, r->inputs, 1, &r->output));
    const auto invoked = std::chrono::steady_clock::now();
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->output, &memory, kLiteRtTensorBufferLockModeRead));
    jni->SetFloatArrayRegion(result, 0, 18452, static_cast<const float*>(memory));
    CHECK(LiteRtUnlockTensorBuffer(r->output));
    const auto end = std::chrono::steady_clock::now();
    const jlong ns[] = {std::chrono::duration_cast<std::chrono::nanoseconds>(invoked - start).count(),
                        std::chrono::duration_cast<std::chrono::nanoseconds>(end - invoked).count()};
    jni->SetLongArrayRegion(timings, 0, 2, ns);
  } catch (const std::exception& e) { fail(jni, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_destroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Runtime*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_roundHalf(JNIEnv* jni, jobject, jfloatArray array) {
  const auto count = jni->GetArrayLength(array);
  auto* values = jni->GetFloatArrayElements(array, nullptr);
  if (!values) return;
  int i = 0;
  for (; i + 4 <= count; i += 4) {
    vst1q_f32(values + i, vcvt_f32_f16(vcvt_f16_f32(vld1q_f32(values + i))));
  }
  for (; i < count; ++i) values[i] = static_cast<float>(static_cast<__fp16>(values[i]));
  jni->ReleaseFloatArrayElements(array, values, 0);
}

// Copy sampled recurrent rows once, directly from the direct history buffer.
extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_writeFeatureHistory(JNIEnv* jni, jobject, jlong handle, jobject history, jint head) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    constexpr size_t row = 16384 * sizeof(float);
    if (!r || head < 0 || head >= 128 || !history || jni->GetDirectBufferCapacity(history) != 128 * row)
      throw std::runtime_error("Invalid direct feature history");
    auto* source = static_cast<const char*>(jni->GetDirectBufferAddress(history));
    if (!source) throw std::runtime_error("Feature history must be direct");
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->inputs[5], &memory, kLiteRtTensorBufferLockModeWrite));
    for (int i = 0; i < 32; ++i)
      memcpy(static_cast<char*>(memory) + i * row, source + ((head + 4 * i) % 128) * row, row);
    CHECK(LiteRtUnlockTensorBuffer(r->inputs[5]));
  } catch (const std::exception& e) { fail(jni, e); }
}

// Startup-only check of the actual LiteRT input, including wrap/reset samples.
extern "C" JNIEXPORT jboolean JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_verifyFeatureInput(JNIEnv* jni, jobject, jlong handle, jfloatArray expected) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    if (!r || !expected || jni->GetArrayLength(expected) != 32 * 16384)
      throw std::runtime_error("Invalid feature verification input");
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->inputs[5], &memory, kLiteRtTensorBufferLockModeRead));
    float row[16384];
    bool same = true;
    for (int i = 0; i < 32; ++i) {
      jni->GetFloatArrayRegion(expected, i * 16384, 16384, row);
      if (memcmp(static_cast<const char*>(memory) + i * sizeof(row), row, sizeof(row))) same = false;
    }
    CHECK(LiteRtUnlockTensorBuffer(r->inputs[5]));
    return same;
  } catch (const std::exception& e) { fail(jni, e); return false; }
}

extern "C" JNIEXPORT void JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_writeImageHistory(JNIEnv* jni, jobject, jlong handle, jint index, jobject history, jint head) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    constexpr size_t row = 6 * 128 * 256;
    if (!r || index < 0 || index > 1 || head < 0 || head >= 5 || !history || jni->GetDirectBufferCapacity(history) != 5 * row)
      throw std::runtime_error("Invalid direct image history");
    auto* source = static_cast<const char*>(jni->GetDirectBufferAddress(history));
    if (!source) throw std::runtime_error("Image history must be direct");
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->inputs[index], &memory, kLiteRtTensorBufferLockModeWrite));
    memcpy(memory, source + head * row, row);
    memcpy(static_cast<char*>(memory) + row, source + ((head + 4) % 5) * row, row);
    CHECK(LiteRtUnlockTensorBuffer(r->inputs[index]));
  } catch (const std::exception& e) { fail(jni, e); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_verifyImageInput(JNIEnv* jni, jobject, jlong handle, jint index, jbyteArray expected) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    if (!r || index < 0 || index > 1 || !expected || jni->GetArrayLength(expected) != r->logical[index])
      throw std::runtime_error("Invalid image verification input");
    auto* bytes = jni->GetByteArrayElements(expected, nullptr);
    if (!bytes) return false;
    void* memory;
    auto status = LiteRtLockTensorBuffer(r->inputs[index], &memory, kLiteRtTensorBufferLockModeRead);
    if (status != kLiteRtStatusOk) { jni->ReleaseByteArrayElements(expected, bytes, JNI_ABORT); check(status, "lock verification input"); }
    bool same = !memcmp(memory, bytes, r->logical[index]);
    jni->ReleaseByteArrayElements(expected, bytes, JNI_ABORT);
    CHECK(LiteRtUnlockTensorBuffer(r->inputs[index]));
    return same;
  } catch (const std::exception& e) { fail(jni, e); return false; }
}
