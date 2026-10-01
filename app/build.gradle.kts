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

// Each flavor's values live in config/<flavor>.properties, committed, like the iPhone's
// Config/*.xcconfig: public keys only (Supabase publishable key, RevenueCat public SDK key,
// Turnstile site key). The local Supabase depends on the machine: scripts/local-backend.sh writes
// its URL and key to local.private.properties (gitignored), like the iPhone's Local.private.xcconfig.
val flavors = listOf("production", "staging", "local")
val machineKeys = setOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY")
val secretLike = Regex("sb_secret_|service_role|PRIVATE KEY")

fun readProperties(path: String): Map<String, String>? {
    val file = rootProject.file(path)
    if (!file.exists()) return null
    val properties = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
}

fun flavorConfig(flavor: String): Map<String, String> {
    val committed = readProperties("config/$flavor.properties")
        ?: throw GradleException("config/$flavor.properties is missing.")
    val machine = if (flavor == "local") readProperties("local.private.properties").orEmpty().filterKeys { it in machineKeys } else emptyMap()
    val values = committed + machine
    // Refused at configuration, before anything builds: a secret in the app, or a remote backend over http.
    values.forEach { (key, value) ->
        if (secretLike.containsMatchIn(value)) throw GradleException("$key ($flavor) looks like a secret: only public keys go in the app.")
    }
    val url = values["SUPABASE_URL"].orEmpty()
    if (flavor != "local" && url.isNotEmpty() && !url.startsWith("https://")) {
        throw GradleException("SUPABASE_URL ($flavor) must be https.")
    }
    return values
}

val flavorValues = flavors.associateWith(::flavorConfig)
// A release build lacking one of these fails; a debug build stops at launch and says what's missing.
val releaseRequired = listOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "REVENUECAT_API_KEY")

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun com.android.build.api.dsl.VariantDimension.flavorFields(flavor: String) {
    val v = flavorValues.getValue(flavor)
    resValue("string", "app_name", v["APP_DISPLAY_NAME"] ?: "drafft")
    buildConfigField("String", "SUPABASE_URL", v["SUPABASE_URL"].orEmpty().quoted())
    buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", v["SUPABASE_PUBLISHABLE_KEY"].orEmpty().quoted())
    buildConfigField("String", "REVENUECAT_API_KEY", v["REVENUECAT_API_KEY"].orEmpty().quoted())
    buildConfigField("String", "TURNSTILE_SITE_KEY", v["TURNSTILE_SITE_KEY"].orEmpty().quoted())
    buildConfigField("int", "SMS_CODE_LIFETIME", (v["SMS_CODE_LIFETIME"]?.toIntOrNull() ?: 600).toString())
    buildConfigField("String", "ENVIRONMENT", (if (flavor == "production") "" else flavor).quoted())
}

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
        buildConfigField("boolean", "HAS_PUSH", hasFirebaseConfig.toString())
    }

    flavorDimensions += "env"
    productFlavors {
        // The iPhone's schemes: Drafft, Drafft Staging, Drafft Local. Values: config/<flavor>.properties.
        flavors.forEach { flavor ->
            create(flavor) {
                dimension = "env"
                flavorFields(flavor)
            }
        }
    }

    // Release signing: the keystore and its passwords stay out of the repository. Set
    // DRAFFT_KEYSTORE_FILE, DRAFFT_KEYSTORE_PASSWORD, DRAFFT_KEY_ALIAS and DRAFFT_KEY_PASSWORD (CI secrets,
    // or the shell). Without them a release build is left unsigned.
    val releaseKeystore = System.getenv("DRAFFT_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("DRAFFT_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DRAFFT_KEY_ALIAS")
            keyPassword = System.getenv("DRAFFT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

androidComponents {
    // The local flavor talks to a machine on the network over http: debug only, never shipped.
    beforeVariants(selector().withFlavor("env" to "local").withBuildType("release")) { it.enable = false }
    // A release build whose config lacks a required value fails before compiling anything.
    onVariants(selector().withBuildType("release")) { variant ->
        val flavor = variant.flavorName.orEmpty()
        val missing = releaseRequired.filter { flavorValues[flavor]?.get(it).isNullOrBlank() }
        if (missing.isEmpty()) return@onVariants
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val message = "$name: missing from config/$flavor.properties: ${missing.joinToString()}"
        val check = tasks.register("check${name}Config") { doLast { throw GradleException(message) } }
        tasks.matching { it.name == "pre${name}Build" }.configureEach { dependsOn(check) }
    }
}

kotlin {
    // Bytecode for Java 17 from whichever JDK runs Gradle (a toolchain would require a JDK 17 install).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.java.get())) }
}

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
