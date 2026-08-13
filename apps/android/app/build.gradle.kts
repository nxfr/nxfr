import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nxfr.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nxfr.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.2-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QR Code
    implementation("com.google.zxing:core:3.5.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
}

tasks.register("verifyNativeSymbols") {
    doLast {
        val jniDirs = listOf("arm64-v8a", "x86_64")
        val jniBase = file("src/main/jniLibs")
        for (abi in jniDirs) {
            val soFile = file("$jniBase/$abi/libnxfr_ffi.so")
            if (soFile.exists()) {
                val output = ByteArrayOutputStream()
                exec {
                    commandLine("nm", "-D", soFile.absolutePath)
                    standardOutput = output
                }
                val symbols = output.toString()
                check(symbols.contains("nxfr_web_start")) {
                    "Native library at ${soFile.path} is missing nxfr_web_start symbol!"
                }
                println("Verified exported native symbols in ${soFile.name} ($abi)")
            }
        }
    }
}

tasks.register("verifyNativeFresh") {
    doLast {
        val jniDirs = listOf("arm64-v8a", "x86_64")
        val jniBase = file("src/main/jniLibs")
        val cratesDir = file("../../crates")
        
        var newestRsTime = 0L
        if (cratesDir.exists()) {
            cratesDir.walkTopDown().filter { it.isFile && it.extension == "rs" }.forEach { file ->
                if (file.lastModified() > newestRsTime) {
                    newestRsTime = file.lastModified()
                }
            }
        }

        for (abi in jniDirs) {
            val soFile = file("$jniBase/$abi/libnxfr_ffi.so")
            if (soFile.exists() && newestRsTime > 0) {
                check(soFile.lastModified() >= newestRsTime) {
                    "STALE NATIVE LIB — run cargo ndk -t arm64-v8a -t x86_64 -o apps/android/app/src/main/jniLibs build --release -p nxfr-ffi"
                }
                println("Verified native freshness for ${soFile.name} ($abi)")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("verifyNativeSymbols", "verifyNativeFresh")
}
