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
            implementation(project(":kit"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
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
                // JetBrains Runtime API: a custom title bar when the JVM is a JBR (already on the classpath through Compose).
                implementation(libs.jbr.api)
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

compose.resources {
    packageOfResClass = "app.loki.text.res"
}

// Interface translations live in storytold/craft-i18n (the commit in ../../craft-i18n.rev), as the desktop front end's
// do: its locales/<code>/common.tsv and wordcraft.tsv are copied in at build time from CRAFT_I18N_DIR, with an index of
// the languages. Without CRAFT_I18N_DIR the app is English only. The copies are build output, not part of this repository.
val i18nOut = layout.projectDirectory.dir("src/commonMain/composeResources/files/i18n")
val copyI18n by tasks.registering {
    val src = providers.environmentVariable("CRAFT_I18N_DIR").orNull?.let { file("$it/locales") }
    inputs.property("source", src?.path ?: "")
    outputs.dir(i18nOut)
    doLast {
        val out = i18nOut.asFile
        out.mkdirs()
        val codes = mutableListOf<String>()
        src?.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val files = listOf("common.tsv", "wordcraft.tsv").map { File(dir, it) }.filter { it.isFile }
            if (files.isEmpty()) return@forEach
            val to = File(out, dir.name).apply { mkdirs() }
            files.forEach { it.copyTo(File(to, it.name), overwrite = true) }
            codes += dir.name
        }
        File(out, "locales.txt").writeText(codes.joinToString("\n"))
    }
}
// Every task that reads the shared resources (Compose's copies and its resource accessors) waits for the catalogs.
tasks.matching { it.name != "copyI18n" && (it.name.contains("ComposeResources") || it.name.contains("ValueResources") || it.name.contains("ResourceAccessors")) }
    .configureEach { dependsOn(copyI18n) }

compose.desktop {
    application {
        mainClass = "app.loki.text.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Loki Text"
            packageVersion = "1.0.0"
            // The installer and launcher icon (the Loki Text icon, from icons/).
            linux { iconFile.set(project.file("icons/loki-text.png")) }
            windows { iconFile.set(project.file("icons/loki-text.ico")) }
            macOS { iconFile.set(project.file("icons/loki-text.icns")) }
        }
        // The desktop run finds libloki_ffi next to the working dir or via -Dloki.lib.
        jvmArgs += "-Dloki.lib=${rootDir}/target/release"
        // On a Mac the Dock and the application menu show this name instead of the main class (MainKt).
        // `-Xdock` is a Mac-only JVM option: other systems refuse to start with it.
        if (System.getProperty("os.name").orEmpty().startsWith("Mac")) jvmArgs += "-Xdock:name=Loki Text"
    }
}

// Tests that drive the real engine find the library `scripts/build-desktop.sh` builds.
tasks.withType<Test>().configureEach { systemProperty("loki.lib", "${rootDir}/target/release") }
