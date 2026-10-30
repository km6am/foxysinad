plugins {
    id("com.android.application")
}

android {
    namespace = "com.km6am.foxspike"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.km6am.foxspike"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
