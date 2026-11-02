plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// foxsinad is the single source of the engine, the analysis and the page;
// the app copies them in unchanged at build time and records which commits.
// Override the checkouts with -Pfoxsinad=... / -PfoxsinadFirmware=...
val home = System.getProperty("user.home")
val foxsinad = file(findProperty("foxsinad") ?: "$home/foxsinad")
val firmware = file(findProperty("foxsinadFirmware") ?: "$home/foxsinad-firmware")
val genPython = layout.buildDirectory.dir("generated/foxsinad/python")
val genAssets = layout.buildDirectory.dir("generated/foxsinad/assets")

fun commitOf(repo: File): String {
    fun git(vararg args: String) = providers.exec {
        commandLine("git", "-C", repo.path, *args)
    }.standardOutput.asText.get().trim()
    return git("rev-parse", "--short", "HEAD") + if (git("status", "--porcelain").isEmpty()) "" else "+dirty"
}

val syncFoxsinad by tasks.registering(Sync::class) {
    into(genPython)
    from(foxsinad) {
        include("foxsinad/**", "cal/picofox_r3_attcal.json",
                "third_party/sinad_meter/filters.py", "third_party/sinad_meter/sinad.py",
                "third_party/sinad_meter/LICENSE", "third_party/sinad_meter/vendored/**")
        exclude("**/__pycache__/**", "foxsinad/app/static/**")
    }
    // Not cached: the commits are read at every build.
    outputs.upToDateWhen { false }
    doLast {
        genPython.get().file("foxsinad_build.py").asFile.writeText(
            "FOXSINAD = \"${commitOf(foxsinad)}\"\nFIRMWARE = \"${commitOf(firmware)}\"\n")
    }
}

val syncFoxsinadAssets by tasks.registering(Sync::class) {
    into(genAssets)
    from(foxsinad.resolve("foxsinad/app/static")) { into("static") }
    from(firmware.resolve("tools/foxctl.py")) { into("foxsinad-firmware/tools") }
}

tasks.named("preBuild") { dependsOn(syncFoxsinad, syncFoxsinadAssets) }
// Gradle wants the readers of the copies to say so, not only run after them.
tasks.matching { it.name.endsWith("PythonSources") }.configureEach { dependsOn(syncFoxsinad) }
tasks.matching { it.name.endsWith("Assets") && it != syncFoxsinadAssets.get() }.configureEach { dependsOn(syncFoxsinadAssets) }

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
    sourceSets.getByName("main").assets.srcDir(genAssets.get().asFile)
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
    sourceSets.getByName("main") { srcDir(genPython.get().asFile) }
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("androidx.core:core:1.13.1")       // FileProvider, for sharing runs
}
