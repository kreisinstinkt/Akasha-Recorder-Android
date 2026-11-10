plugins { id("com.android.application") }
android {
    namespace = "chronik.akasha.recorder"
    compileSdk = 35
    defaultConfig { applicationId = "chronik.akasha.recorder"; minSdk = 26; targetSdk = 28; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
