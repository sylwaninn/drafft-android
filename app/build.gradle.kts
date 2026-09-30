import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Push (FCM) needs the Firebase project's google-services.json, per environment
// (app/src/<flavor>/google-services.json). Without it the app builds and runs, push stays off.
val hasFirebaseConfig = listOf("production", "staging", "local").any { file("src/$it/google-services.json").exists() } ||
    file("google-services.json").exists()
if (hasFirebaseConfig) apply(plugin = libs.plugins.google.services.get().pluginId)

// Machine-specific values (never committed): the local Supabase URL and key written by
// drafft-backend's scripts/local-backend.sh, and RevenueCat's Google Play public SDK keys.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun local(key: String, fallback: String = ""): String =
    (localProperties.getProperty(key) ?: providers.gradleProperty(key).orNull ?: fallback)

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "so.drafft.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Same identifier as the iPhone app. All environments share it, like the iOS schemes:
        // installing one replaces the other, the launcher name tells them apart.
        applicationId = "so.drafft.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // Cloudflare Turnstile public site key (support form sent signed out); allowed for getdrafft.com.
        buildConfigField("String", "TURNSTILE_SITE_KEY", "0x4AAAAAAFF4RVStAyff4tQU".quoted())
        // Photos sized by the media domain (Cloudflare Image Resizing, /cdn-cgi/image/) once it's enabled there.
        buildConfigField("boolean", "MEDIA_IMAGE_RESIZING", "false")
        buildConfigField("boolean", "HAS_PUSH", hasFirebaseConfig.toString())
    }

    flavorDimensions += "env"
    productFlavors {
        // Production backend. Publishable keys only: they identify the project, row-level security does the rest.
        create("production") {
            dimension = "env"
            resValue("string", "app_name", "drafft")
            buildConfigField("String", "SUPABASE_URL", "https://wrcpgnqwjmnirjfxpcux.supabase.co".quoted())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "sb_publishable_VBj4h4XxRDB-Ky5mK0d_ag_dj86XO22".quoted())
            buildConfigField("String", "REVENUECAT_API_KEY", local("drafft.revenuecat.production").quoted())
            // How long an SMS code works, in seconds: Auth > Providers > Phone > SMS OTP Expiry.
            buildConfigField("int", "SMS_CODE_LIFETIME", "600")
            buildConfigField("String", "ENVIRONMENT", "".quoted())
        }
        // Staging backend: the persistent Supabase branch `staging` of drafft-backend.
        create("staging") {
            dimension = "env"
            resValue("string", "app_name", "drafft β")
            buildConfigField("String", "SUPABASE_URL", "https://rjlghcuspdtrmbimyioe.supabase.co".quoted())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "sb_publishable_W_B8sz0iJ29s3NvbBlRDLQ_VMsaunzy".quoted())
            buildConfigField("String", "REVENUECAT_API_KEY", local("drafft.revenuecat.staging").quoted())
            buildConfigField("int", "SMS_CODE_LIFETIME", "600")
            buildConfigField("String", "ENVIRONMENT", "staging".quoted())
        }
        // Local Supabase (drafft-backend `supabase start`) with the staging services. The URL and key
        // depend on the machine: set drafft.local.supabaseUrl and drafft.local.supabaseKey in
        // local.properties (an emulator reaches the Mac at 10.0.2.2). Without them the app stops at
        // launch and says what's missing.
        create("local") {
            dimension = "env"
            resValue("string", "app_name", "drafft local")
            buildConfigField("String", "SUPABASE_URL", local("drafft.local.supabaseUrl").quoted())
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", local("drafft.local.supabaseKey").quoted())
            buildConfigField("String", "REVENUECAT_API_KEY", local("drafft.revenuecat.staging").quoted())
            // The local Auth's SMS OTP expiry (GOTRUE_SMS_OTP_EXP).
            buildConfigField("int", "SMS_CODE_LIFETIME", "6000")
            buildConfigField("String", "ENVIRONMENT", "local".quoted())
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Android-only code (SDK calls, Stream, RevenueCat, platform services) lives in src/android/kotlin;
    // src/main/kotlin stays platform-neutral so tools/jvmcheck can compile it anywhere.
    sourceSets["main"].java.srcDir("src/android/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The app's own languages (English source); strings come from the shared catalog (core:model).
        localeFilters += listOf("en", "fr", "es", "de", "it", "pt", "nl")
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.stream.chat.client)
    implementation(libs.stream.chat.offline)
    implementation(libs.revenuecat)
    implementation(libs.libphonenumber)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.ktor.client.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
