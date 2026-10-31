import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidApplication)
}

kotlin {
    jvm()
    androidTarget()

    // The Rust engine is linked as a static library into the iOS framework. `cargo xtask`-style
    // build scripts in ../scripts produce target/<triple>/release/libloki_ffi.a first.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val triple = if (target.name == "iosArm64") "aarch64-apple-ios" else "aarch64-apple-ios-sim"
        target.binaries.framework {
            baseName = "LokiApp"
            isStatic = true
            linkerOpts("-L${rootDir}/target/$triple/release", "-lloki_ffi")
        }
        target.compilations.getByName("main").cinterops.create("loki") {
            definitionFile.set(file("src/nativeInterop/cinterop/loki.def"))
            includeDirs(rootDir.resolve("ffi/include"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.lucide)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // JNA binding shared by the two JVM-based targets.
        val jnaMain by creating { dependsOn(commonMain.get()) }
        jvmMain {
            dependsOn(jnaMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
                implementation(libs.coroutines.swing)
            }
        }
        androidMain {
            dependsOn(jnaMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation("${libs.jna.android.get()}@aar")
            }
        }
    }
}

android {
    namespace = "app.loki.text"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.loki.text"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // libloki_ffi.so per ABI, built by scripts/build-android.sh into src/androidMain/jniLibs.
    sourceSets["main"].jniLibs.srcDir("src/androidMain/jniLibs")

    buildTypes {
        release {
            // Not debuggable, so Compose and ART optimise fully. Signed with the debug key ONLY so a
            // release build installs for local performance tests; real distribution needs its own key.
            // R8 stays off until JNA's reflection has keep rules.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.loki.text.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Loki Text"
            packageVersion = "1.0.0"
        }
        // The desktop run finds libloki_ffi next to the working dir or via -Dloki.lib.
        jvmArgs += "-Dloki.lib=${rootDir}/target/release"
    }
}
