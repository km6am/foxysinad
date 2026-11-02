plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "com.km6am.foxysinad"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.km6am.foxysinad"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}

chaquopy {
    defaultConfig {
        // 3.10 is the newest Python with SciPy in Chaquopy's package index
        // (SciPy 1.8.1, which pins numpy 1.23.3). The build machine's Python
        // must be the same minor version.
        version = "3.10"
        buildPython(System.getenv("FOXYSINAD_BUILD_PYTHON")
            ?: "${System.getProperty("user.home")}/miniconda3/envs/chaq310/bin/python")
        pip {
            install("numpy==1.23.3")
            install("scipy==1.8.1")
        }
    }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
