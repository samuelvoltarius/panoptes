plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "at.greenchili.panoptes.launcher"
    compileSdk = 36

    buildFeatures {
        // MainActivity filters its own package from Panoptes' app list.
        buildConfig = true
    }

    defaultConfig {
        applicationId = "at.greenchili.panoptes.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 23
        versionName = "0.1.43.6"
    }

    buildTypes {
        debug {
            // Wie die Haupt-App (0.1.15): nicht debuggable, kein run-as, keine Debugger-Anbindung. Die Release-
            // Pipeline signiert genau diesen Build und verteilt ihn über die version.json.
            isDebuggable = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.zxing.core)   // 0.1.43.4: QR-Code im Fenster „Datei vom Handy“ (Apache-2.0, wie in :app)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
