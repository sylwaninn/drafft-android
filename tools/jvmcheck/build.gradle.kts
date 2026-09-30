plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.7.3"
}

val root = rootDir.resolve("../..")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xcontext-receivers", "-opt-in=kotlin.RequiresOptIn", "-opt-in=kotlin.time.ExperimentalTime")
    }
}

sourceSets {
    main {
        kotlin.srcDirs(
            root.resolve("core/model/src/main/kotlin"),
            root.resolve("core/data/src/main/kotlin"),
            root.resolve("core/ui/src/main/kotlin"),
            root.resolve("app/src/main/kotlin"),
            "shims",
            layout.buildDirectory.dir("generated/r"),
        )
        resources.srcDirs(root.resolve("core/model/src/main/resources"))
    }
    test {
        kotlin.srcDirs(root.resolve("core/model/src/test/kotlin"), root.resolve("core/data/src/test/kotlin"))
    }
}

// Stand-ins for the Android resource classes (R.drawable, R.font), from the resource folders.
val generateR by tasks.registering {
    val resDirs = listOf("core/ui" to "so.drafft.core.ui", "app" to "so.drafft.app").map { (m, pkg) -> root.resolve("$m/src/main/res") to pkg }
    inputs.files(resDirs.map { it.first })
    val out = layout.buildDirectory.dir("generated/r")
    outputs.dir(out)
    doLast {
        resDirs.forEach { (dir, pkg) ->
            val kinds = sortedMapOf<String, MutableSet<String>>()
            dir.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                val kind = d.name.substringBefore('-')
                if (kind == "values") return@forEach
                d.listFiles()?.forEach { f -> kinds.getOrPut(kind) { sortedSetOf() }.add(f.nameWithoutExtension.substringBefore('.')) }
            }
            var n = 0x7f000000
            val body = kinds.entries.joinToString("\n") { (kind, names) ->
                "    object $kind {\n" + names.joinToString("\n") { "        const val $it = ${n++}" } + "\n    }"
            }
            val file = out.get().asFile.resolve(pkg.replace('.', '/') + "/R.kt")
            file.parentFile.mkdirs()
            file.writeText("package $pkg\n\nobject R {\n$body\n    object string {\n        const val app_name = ${n++}\n    }\n}\n")
        }
    }
}
tasks.named("compileKotlin") { dependsOn(generateR) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.animation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.insert-koin:koin-core:4.0.4")
    implementation("io.insert-koin:koin-compose:4.0.4")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
    // 3.1.4, not the app's 3.2.4: 3.2 is built with Kotlin 2.2 and crashes this build's 2.1 compiler.
    // 3.2 returns kotlin.time.Instant (opted in above), which is where the two can disagree.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.15")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnit() }

// Some Compose Multiplatform runtime dependencies live on Google's Maven only. The unit tests exercise
// plain logic (no UI), so they run without them.
configurations.named("testRuntimeClasspath") {
    exclude(group = "androidx.arch.core")
    exclude(group = "androidx.lifecycle")
    exclude(group = "androidx.annotation")
    exclude(group = "androidx.collection")
}
