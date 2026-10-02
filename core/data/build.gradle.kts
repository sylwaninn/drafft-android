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

kotlin {
    compilerOptions {
        // Bytecode for Java 17 from whichever JDK runs Gradle (a toolchain would require a JDK 17 install).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.java.get()))
        // supabase-kt 3.2 exposes kotlin.time.Instant (session expiry, user dates), still experimental in Kotlin 2.2.
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":core:model"))
    // Snapshot state: the screens observe AppModel's properties directly.
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
    // Video compression (AndroidVideoCompressor): Transformer, its effects (Presentation) and MediaItem.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    // Location (AndroidLocationProvider): the fused provider, awaited as coroutines.
    implementation(libs.play.services.location)
    implementation(libs.play.integrity)
    implementation(libs.kotlinx.coroutines.play.services)
    // Push (DrafftMessagingService, AndroidLocalNotifications): the FCM token and incoming pushes.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // The face on the first photo (AndroidFaceCheck), on device.
    implementation(libs.mlkit.face.detection)
    // Telemetry (src/android/.../telemetry): crashes, errors, performance and logs on Sentry (Kotlin and
    // native crashes), product analytics on PostHog. The model only sees `Telemetry` (src/main).
    implementation(libs.sentry.android.core)
    implementation(libs.sentry.android.ndk)
    implementation(libs.posthog.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
