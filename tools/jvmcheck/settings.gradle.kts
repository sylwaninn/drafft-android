// A compile check that runs anywhere, without the Android SDK: it builds the app's platform-neutral
// sources (core:model, the state and backend of core:data, the design system and the screens) on the
// JVM against Compose Multiplatform, with small stand-ins for the few Android-only APIs they touch
// (shims/). Android-only code lives in each module's src/android/kotlin and is left out.
// Run: gradle -p tools/jvmcheck check
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "drafft-jvmcheck"
