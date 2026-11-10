
plugins {
    id("com.android.application")
}

android {
    namespace = "chronik.akasha.recorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "chronik.akasha.recorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
    }

    signingConfigs {
        create("akashaRelease") {
            val keystorePath = System.getenv("AKASHA_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AKASHA_STORE_PASSWORD")
                keyAlias = System.getenv("AKASHA_KEY_ALIAS")
                keyPassword = System.getenv("AKASHA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("akashaRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

