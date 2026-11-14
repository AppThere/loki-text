// Loki Kit: what every Craft front end of this kind shares. Nothing here knows which engine it
// talks to: an app hands it a CommandProfile (its tabs, its named command sets) and a transport.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
}

// The coordinates another Craft app depends on (`app.loki:kit`), by composite build or once published.
group = "app.loki"
version = "0.1.0"

kotlin {
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.lucide)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // The off-screen screenshot runner draws with the desktop's Skia.
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "app.loki.kit"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
