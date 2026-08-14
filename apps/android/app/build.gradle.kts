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
        versionCode = 18
        versionName = "0.3.1-alpha"
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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // DocumentFile SAF Helper
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Exec>("rebuildNative") {
    group = "build"
    description = "Compiles native Rust FFI crates for Android via cargo ndk"
    workingDir = file("../../")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "apps/android/app/src/main/jniLibs",
        "build", "--release", "-p", "nxfr-ffi"
    )
}

tasks.register("verifyNativeSymbols") {
    doLast {
        val externalFunRegex = Regex("""external\s+fun\s+([a-zA-Z0-9_]+)\s*\(""")
        val javaDir = file("src/main/java")
        val declaredExternalFunctions = mutableSetOf<String>()
        if (javaDir.exists()) {
            javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                externalFunRegex.findAll(text).forEach { match ->
                    declaredExternalFunctions.add(match.groupValues[1])
                }
            }
        }

        val jniDirs = listOf("arm64-v8a", "x86_64")
        val jniBase = file("src/main/jniLibs")
        for (abi in jniDirs) {
            val soFile = file("$jniBase/$abi/libnxfr_ffi.so")
            check(soFile.exists()) {
                "MISSING NATIVE LIB: ${soFile.path} does not exist — run ./gradlew rebuildNative"
            }

            val output = ByteArrayOutputStream()
            exec {
                commandLine("nm", "-D", soFile.absolutePath)
                standardOutput = output
            }
            val symbols = output.toString()

            for (fnName in declaredExternalFunctions) {
                val mangledJni = "Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_" + fnName.replace("_", "_1")
                val hasMangled = symbols.contains(mangledJni)
                val hasDirect = symbols.contains(" $fnName\n") || symbols.contains(" $fnName\r\n")
                if (!hasMangled && !hasDirect) {
                    throw GradleException("MISSING JNI SYMBOL: $fnName in $abi — run ./gradlew rebuildNative")
                }
            }
            println("Verified ${declaredExternalFunctions.size} exported JNI symbols in ${soFile.name} ($abi)")
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
                    "STALE NATIVE LIB — run ./gradlew rebuildNative"
                }
                println("Verified native freshness for ${soFile.name} ($abi)")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("verifyNativeSymbols", "verifyNativeFresh")
}
