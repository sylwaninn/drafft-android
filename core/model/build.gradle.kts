// Pure Kotlin: the app's data types and the logic that stands alone (catalogs, parsing, ledgers,
// ThumbHash, the string catalog). No Android, so it builds and tests on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(libs.versions.java.get().toInt()) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
