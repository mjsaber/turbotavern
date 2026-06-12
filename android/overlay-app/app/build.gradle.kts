plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bobassist.phase0"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.bobassist.phase0"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-prototype"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "sku"
    productFlavors {
        create("clean") {
            dimension = "sku"
            applicationId = "com.turbotavern"          // clean Play SKU — no VPN, no GPL core
        }
        create("full") {
            dimension = "sku"
            applicationId = "com.bobassist.phase0"      // full sideload SKU — 拔线 + GPL; keeps the existing id
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true   // required for Robolectric
    }
}

dependencies {
    "fullImplementation"(files("libs/bobcore.aar"))
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}

// GPL-3.0 compliance guard (codex Stage 2 P2): a full RELEASE build must not ship the placeholder
// Corresponding-Source URL in NOTICE.txt. Debug dev builds may proceed (they are never distributed).
tasks.matching { it.name == "assembleFullRelease" || it.name == "bundleFullRelease" }.configureEach {
    doFirst {
        val notice = file("src/full/assets/licenses/NOTICE.txt")
        if (notice.readText().contains("REPLACE-ME")) {
            throw GradleException(
                "NOTICE.txt still contains the placeholder Corresponding-Source URL. Set the real GPL " +
                    "source repository URL before building a full RELEASE APK (GPL-3.0 compliance)."
            )
        }
    }
}
