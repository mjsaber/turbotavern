import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing reads from a gitignored keystore.properties (storeFile/storePassword/keyAlias/
// keyPassword). Absent in dev/CI — release stays unsigned there, debug builds are unaffected.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.turbotavern"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.turbotavern"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
            applicationId = "com.turbotavern.full"      // full sideload SKU — 拔线 + GPL (distinct id so it can coexist with the Play clean build)
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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

// GPL-3.0 compliance guard (codex Stage 2 P2; hardened dedup Phase 4): a full RELEASE build must
// bundle a NOTICE with a REAL Corresponding-Source URL. Keyed off a single path constant so renaming
// the asset can't silently disable the guard (it then fails "missing" instead of passing); rejects
// placeholder markers and requires a real https://github.com/... source link. Debug dev builds may
// proceed (they are never distributed).
val noticePath = "src/full/assets/licenses/NOTICE.txt"
tasks.matching { it.name == "assembleFullRelease" || it.name == "bundleFullRelease" }.configureEach {
    doFirst {
        val notice = file(noticePath)
        if (!notice.exists()) throw GradleException(
            "GPL Corresponding-Source NOTICE missing at $noticePath — the full RELEASE build must bundle it (GPL-3.0)."
        )
        val text = notice.readText()
        val hasPlaceholder = listOf("REPLACE-ME", "SET BEFORE DISTRIBUTION", ">>>", "<<<").any { text.contains(it) }
        // The project's own Corresponding-Source URL: a real https://github.com/... link that is
        // neither a placeholder nor the upstream mihomo attribution URL (MetaCubeX/mihomo).
        val hasRealSourceUrl = Regex("""https://github\.com/\S+""").findAll(text).map { it.value }.any {
            !it.contains("REPLACE-ME") && !it.contains("MetaCubeX/mihomo")
        }
        if (hasPlaceholder || !hasRealSourceUrl) throw GradleException(
            "NOTICE.txt ($noticePath) has no valid GPL Corresponding-Source URL — set a real " +
                "https://github.com/... source link (not a placeholder) before a full RELEASE build (GPL-3.0)."
        )
    }
}

// ── Clean-SKU containment guards (dedup Phase 2) ──────────────────────────────
// The clean (Play) SKU must ship neither the mihomo/VPN native core nor the 拔线
// VpnService. This is a Play-policy + APK-size guard (the whole repo is GPL, so it is
// NOT a licensing matter): Google Play rejects 拔线 as game-cheating, and there is no
// reason to ship the ~46MB libgojni.so the clean build never calls.

// (a) Source gate — the GPL mihomo native AAR (com.bobassist.gomobile) must never be
// referenced from any source set the clean variants COMPILE. Scoped to that package ONLY:
// BobVpnService / MihomoCore / Real*Core references would already fail to compile in clean
// (they live in src/full), and android.net.VpnService is a GPL-free AOSP class that
// CoreFacades legitimately uses — so neither belongs in this gate (codex/critic false-positive
// notes). The roots are every source set a clean variant pulls in: shared (main), flavor
// (clean), buildType (debug/release), and flavor-buildType (cleanDebug/cleanRelease) — NOT the
// full* sets, which legitimately carry the GPL core (codex P2: don't miss buildType sets).
val cleanSourceRoots = listOf(
    "src/main", "src/clean", "src/debug", "src/release", "src/cleanDebug", "src/cleanRelease",
)
val verifyCleanSourceGplFree by tasks.registering {
    group = "verification"
    description = "Fail if any clean-compiled source set references the GPL mihomo native core (com.bobassist.gomobile)."
    doLast {
        val hits = cleanSourceRoots.flatMap { root ->
            fileTree(root) { include("**/*.kt") }.files
                .filter { it.readText().contains("com.bobassist.gomobile") }
                .map { it.relativeTo(projectDir).path }
        }
        if (hits.isNotEmpty()) throw GradleException(
            "GPL mihomo core (com.bobassist.gomobile) leaked into a clean-compiled source set — keep it in src/full:\n" +
                hits.joinToString("\n")
        )
    }
}
tasks.matching { it.name in setOf("assembleCleanDebug", "assembleCleanRelease", "bundleCleanRelease") }
    .configureEach { dependsOn(verifyCleanSourceGplFree) }

// (b) Built-artifact gate — the clean RELEASE artifact must not contain the GPL/VPN native lib.
// Check the .so directly: R8 renames/strips dex classes (a dex class-name grep would
// false-NEGATIVE on a minified build), but native libs are never renamed. Covers BOTH the APK
// (assembleCleanRelease) and the Play AAB (bundleCleanRelease — the actual upload artifact;
// codex P2: don't leave the bundle path unguarded). The `**/libgojni*.so` glob matches the APK
// layout (lib/<abi>/) and the AAB layout (base/lib/<abi>/) alike.
tasks.matching { it.name == "assembleCleanRelease" || it.name == "bundleCleanRelease" }.configureEach {
    val isBundle = name == "bundleCleanRelease"
    val dir = if (isBundle) "build/outputs/bundle/cleanRelease" else "build/outputs/apk/clean/release"
    val ext = if (isBundle) ".aab" else ".apk"
    doLast {
        val artifact = file(dir).listFiles()?.firstOrNull { it.name.endsWith(ext) }
            ?: throw GradleException("clean release artifact ($ext) not found in $dir for native-core verification")
        val gpl = zipTree(artifact).matching { include("**/libgojni*.so") }.files
        if (gpl.isNotEmpty()) throw GradleException(
            "clean artifact ${artifact.name} contains the GPL/VPN native core (${gpl.joinToString { it.name }}) — " +
                "it must ship only in the full sideload SKU."
        )
    }
}
