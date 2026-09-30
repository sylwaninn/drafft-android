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
    api(platform(libs.koin.bom))
    api(libs.koin.compose)
    debugImplementation(libs.compose.ui.tooling)
}
