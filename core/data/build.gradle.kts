// The app's state (AppModel) and everything it talks to: Supabase (accounts, profiles, discovery,
// likes, matches, sessions, safety), Stream (chat), RevenueCat (purchases), the on-device cache.
// Platform services (location, audio, calendar, notifications, camera) are interfaces here,
// implemented in :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "so.drafft.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    // Android-only code (SDK calls, Stream, RevenueCat, platform services) lives in src/android/kotlin;
    // src/main/kotlin stays platform-neutral so tools/jvmcheck can compile it anywhere.
    sourceSets["main"].java.srcDir("src/android/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
    api(project(":core:model"))
    // Snapshot state: AppModel is observed by the screens the way SwiftUI observes @Observable.
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(platform(libs.koin.bom))
    api(libs.koin.core)
    api(libs.koin.android)

    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    api(libs.supabase.realtime)
    api(libs.supabase.storage)
    api(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.stream.chat.client)
    implementation(libs.stream.chat.offline)
    implementation(libs.stream.chat.state)
    implementation(libs.revenuecat)
    implementation(libs.libphonenumber)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.media3.transformer)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
