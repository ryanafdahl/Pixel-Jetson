plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "net.afdahl.jetlink.pixel"
  compileSdk = 35
  defaultConfig {
    applicationId = "net.afdahl.jetlink.pixel"
    minSdk = 33
    targetSdk = 35
    versionCode = 1
    versionName = "0.1-parked-test"
    ndk { abiFilters += "arm64-v8a" }
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  packaging { jniLibs { useLegacyPackaging = true } }
  buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
dependencies {
  implementation("com.google.ai.edge.litert:litert:2.2.0")
  testImplementation("junit:junit:4.13.2")
}
