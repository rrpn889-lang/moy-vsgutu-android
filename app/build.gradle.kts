import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "online.myesstu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.myesstu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.4"
    }


    signingConfigs {
        // The key lives in signing/ (kept out of git); without it the release build is signed with the debug key.
        val props = Properties()
        val file = rootProject.file("signing/keystore.properties")
        if (file.exists()) file.inputStream().use { props.load(it) }
        if (props.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("signing/" + props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        // Native libraries are unpacked at install, the classic way; some phones refuse the "stored in the APK" layout.
        jniLibs { useLegacyPackaging = true }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
