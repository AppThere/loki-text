// Loki Kit: what every Craft front end of this kind shares. Nothing here knows which engine it
// talks to: an app hands it a CommandProfile (its tabs, its named command sets) and a transport.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidLibrary)
}

// The coordinates another Craft app depends on (`com.appthere.loki:kit`), by composite build or once published.
group = "com.appthere.loki"
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
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // The off-screen screenshot runner draws with the desktop's Skia.
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            // Ink drawn straight to the display's front buffer while a pen moves (InkLayer.android.kt).
            implementation(libs.graphics.core)
        }
    }
}

android {
    namespace = "com.appthere.loki.kit"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

// The interface font (composeResources/font) every Craft front end shares.
compose.resources {
    packageOfResClass = "com.appthere.loki.kit.res"
    generateResClass = always
}
