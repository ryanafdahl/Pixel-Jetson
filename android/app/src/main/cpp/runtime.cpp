#include <jni.h>
#include <cstdlib>
#include <cstring>
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
Java_net_afdahl_jetlink_pixel_NativeRuntime_create(JNIEnv* jni, jobject, jstring path, jstring libs) {
  try {
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
    // This selects the same burst setting as benchmark_model; it is not a
    // thermal qualification and never changes model precision.
    std::string toml = "performance_mode = " + std::to_string(kLiteRtGoogleTensorOptionsPerformanceModeBurst) + "\n";
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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_net_afdahl_jetlink_pixel_NativeRuntime_run(JNIEnv* jni, jobject, jlong handle) {
  try {
    auto* r = reinterpret_cast<Runtime*>(handle);
    if (!r) throw std::runtime_error("Closed runtime");
    CHECK(LiteRtRunCompiledModel(r->compiled, 0, 6, r->inputs, 1, &r->output));
    void* memory;
    CHECK(LiteRtLockTensorBuffer(r->output, &memory, kLiteRtTensorBufferLockModeRead));
    auto result = jni->NewFloatArray(18452);
    if (result) jni->SetFloatArrayRegion(result, 0, 18452, static_cast<const float*>(memory));
    CHECK(LiteRtUnlockTensorBuffer(r->output));
    return result;
  } catch (const std::exception& e) { fail(jni, e); return nullptr; }
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
