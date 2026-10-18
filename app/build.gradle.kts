plugins {
    id("com.android.application")
}

android {
    namespace = "dev.glasskeyboard.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.glasskeyboard.ime"
        minSdk = 29          // blur: Android 12+ (31), AGSL material: Android 13+ (33), fallbacks below
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs right away.
            // Replace with your own signingConfig before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
