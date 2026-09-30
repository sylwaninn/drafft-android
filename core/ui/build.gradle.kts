// The design system (DESIGN.md): tokens, type, surfaces, components, the drafting motif,
// progressive blur edges, navigation primitives and the bundled photos and fonts.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "so.drafft.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    // Android-only code (SDK calls, Stream, RevenueCat, platform services) lives in src/android/kotlin;
    // src/main/kotlin stays platform-neutral so tools/jvmcheck can compile it anywhere.
    sourceSets["main"].java.srcDir("src/android/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
    api(project(":core:model"))
    api(project(":core:data"))
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.coil.compose)
    // VideoPlayer (AndroidPlatformUi): ExoPlayer drawn by Media3's Compose surface.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    // Front camera for the selfie check (AndroidFrontCamera): CameraX preview, analysis and capture.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    api(platform(libs.koin.bom))
    api(libs.koin.compose)
    debugImplementation(libs.compose.ui.tooling)
}
