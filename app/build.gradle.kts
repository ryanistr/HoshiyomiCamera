import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// --- Rust core build integration -------------------------------------------
// Builds ../rust/hoshiyomi-core for the active ABI set and stages the .so files into
// jniLibs so AGP packs them like any other native dependency. No prebuilt blobs.

val rustDir = layout.projectDirectory.dir("../rust/hoshiyomi-core")
val rustTargetDir = layout.buildDirectory.dir("rust-target")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val rustAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

val cargoEnv = mapOf(
    "RUSTFLAGS" to "-C strip=symbols",
)

val buildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compiles hoshiyomi-core Rust crate for all target ABIs"
    workingDir = rustDir.asFile
    val extraArgs = (project.findProperty("cargoArgs") as String?)
        ?.split(" ")
        .orEmpty()
    commandLine = listOf("cargo", "build", "--release", "--lib") +
        rustAbis.values.flatMap { listOf("--target", it) } +
        listOf("--config", "profile.release.strip=true") + extraArgs
    environment(cargoEnv)
    // Rebuild whenever Rust sources, the manifest, or the cargo config move.
    // Without declared inputs Gradle keeps calling this up-to-date and Rust
    // edits silently never reach the APK.
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file(".cargo/config.toml"))
    // Touch an output marker so Gradle sees up-to-date checks.
    outputs.file(rustTargetDir.get().file(".cargo-build-marker"))
    doLast {
        rustTargetDir.get().asFile.mkdirs()
        rustTargetDir.get().file(".cargo-build-marker").asFile.writeText("ok")
    }
}

val stageRust by tasks.registering(Sync::class) {
    group = "rust"
    description = "Stages compiled Rust libraries into jniLibs"
    dependsOn(buildRust)
    rustAbis.forEach { (abi, target) ->
        from(rustDir.dir("target/$target/release/libhoshiyomi_core.so")) {
            into(abi)
        }
    }
    into(jniLibsDir)
}

android {
    namespace = "com.hoshiyomi.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hoshiyomi.camera"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The Rust .so files land here via the stageRust sync task.
            useLegacyPackaging = false
        }
    }
    androidResources {
        // Locale config comes from res/values/locales_config.xml if shipped.
        generateLocaleConfig = false
    }
}

// Wire the Rust build into the variant pipeline: every jniLibs merge must run
// after the staged .so files exist.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(stageRust) }

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // Camera2 plumbing helpers (lifecycle-aware composition) — the capture stack
    // itself speaks android.hardware.camera2 directly, matching TCAM's vendor-key design.
    implementation(libs.camera.lifecycle)
    implementation(libs.exifinterface)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
