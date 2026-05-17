# Hearthstone Android Disconnect App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-05-16-hearthstone-disconnect-design.md`

**Goal:** Build a native Kotlin Android app that displays a floating button when Hearthstone is in the foreground; tapping the button drops only Hearthstone's network traffic for a configurable duration (3/5/8/10s) via a per-app `VpnService` black hole, then auto-restores so the player rejoins the live match.

**Architecture:**
- One foreground `OverlayService` (specialUse) owns: a `ForegroundDetector` polling `UsageStatsManager.queryEvents()`, an `OverlayWindow` rendering the circular floating button via `WindowManager`, and a `DisconnectController` state machine.
- The controller launches a separate `DropVpnService` (shortService + BIND_VPN_SERVICE) that builds a TUN interface with `addAllowedApplication("com.blizzard.wtcg.hearthstone")` and discards all packets for N seconds.
- A Compose `MainActivity` handles onboarding (4 permissions), settings (default duration), and the master Start/Stop toggle.

**Tech Stack:**
- Kotlin 2.0+, Android Gradle Plugin 8.7+, minSdk 26, targetSdk 35
- Jetpack Compose Material3 (settings UI only)
- Coroutines + StateFlow (intra-service communication)
- JUnit 4 + MockK (unit tests for pure-Kotlin units)
- No DI framework (manual construction; the dependency graph is ~8 objects)

**Package name:** `com.hsdisconnect.app`

---

## File Structure

```
hs-disconnect/                                     ← repo root (current bob-assist/)
├── settings.gradle.kts                            (T1) project + module declarations
├── build.gradle.kts                               (T1) root buildscript
├── gradle.properties                              (T1) JVM args, AndroidX flag
├── gradle/
│   ├── libs.versions.toml                         (T2) version catalog
│   └── wrapper/gradle-wrapper.properties          (T1) Gradle 8.10
├── .gitignore                                     (T1) replace RN ignores with Kotlin/Gradle
└── app/
    ├── build.gradle.kts                           (T2) module config + deps
    ├── proguard-rules.pro                         (T2) empty stub
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml                (T3) permissions, services, queries
        │   ├── res/
        │   │   ├── values/strings.xml             (T3) app name, notification text
        │   │   ├── values/themes.xml              (T3) Compose theme parent
        │   │   ├── mipmap-*/ic_launcher.xml       (T3) adaptive icon stub
        │   │   └── drawable/ic_notification.xml   (T3) monochrome notif icon
        │   └── java/com/hsdisconnect/app/
        │       ├── MainActivity.kt                (T10) Compose entry
        │       ├── core/
        │       │   ├── Constants.kt               (T5) HEARTHSTONE_PACKAGE, defaults
        │       │   ├── Prefs.kt                   (T6) SharedPreferences wrapper
        │       │   └── PermissionGate.kt          (T9) permission status checks
        │       ├── overlay/
        │       │   ├── OverlayService.kt          (T16) foreground service, wires components
        │       │   ├── OverlayWindow.kt           (T17,T18,T20) WindowManager + Canvas button
        │       │   ├── ForegroundDetector.kt      (T8) queryEvents poller + debounce
        │       │   └── DisconnectController.kt    (T7) state machine + counter
        │       ├── vpn/
        │       │   └── DropVpnService.kt          (T14,T15) VpnService TUN black hole
        │       └── ui/
        │           ├── SettingsScreen.kt          (T10,T11,T12,T13) Compose UI
        │           └── theme/Theme.kt             (T10) Material3 theme
        ├── test/java/com/hsdisconnect/app/
        │   ├── core/PrefsTest.kt                  (T6)
        │   ├── overlay/DisconnectControllerTest.kt (T7)
        │   └── overlay/ForegroundDetectorTest.kt  (T8)
        └── androidTest/                            (skipped for MVP per spec §10)
```

**Total: 25 tasks. Estimated 6-10 hours of work for a Kotlin developer.**

---

## Stage 0: Project Scaffolding

### Task 1: Initialize Gradle wrapper and root build files

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar` (binary; download from gradle 8.10 distribution)
- Create: `gradlew`, `gradlew.bat` (scripts)
- Modify: `.gitignore`

- [ ] **Step 1: Generate Gradle wrapper using system Gradle**

If you have Gradle 8.10+ installed locally:
```bash
cd /Users/jun/code/bob-assist
gradle wrapper --gradle-version 8.10 --distribution-type bin
```
If not, install via Homebrew first: `brew install gradle`, then run the above.

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`.

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HsDisconnect"
include(":app")
```

- [ ] **Step 3: Write `build.gradle.kts` (root)**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Replace `.gitignore`**

```
# Gradle
.gradle/
build/
local.properties

# IntelliJ / Android Studio
.idea/
*.iml
captures/

# macOS
.DS_Store

# Keystores
*.jks
*.keystore
```

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ .gitignore
git commit -m "Add Gradle wrapper and root build files"
```

---

### Task 2: Configure app module and version catalog

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`

- [ ] **Step 1: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.21"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
coroutines = "1.9.0"
junit = "4.13.2"
mockk = "1.13.13"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hsdisconnect.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hsdisconnect.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Write empty `app/proguard-rules.pro`**

```
# Project-specific ProGuard rules.
```

- [ ] **Step 4: Run sync**

```bash
./gradlew :app:tasks --no-daemon
```
Expected: BUILD SUCCESSFUL, lists Android tasks.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/proguard-rules.pro
git commit -m "Configure app module with Compose + version catalog"
```

---

### Task 3: Write AndroidManifest, strings, and theme

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values-night/themes.xml`
- Create: `app/src/main/res/drawable/ic_notification.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`
- Create: `app/src/main/res/values/ic_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`

- [ ] **Step 1: Write `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <queries>
        <package android:name="com.blizzard.wtcg.hearthstone"/>
    </queries>

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:theme="@style/Theme.HsDisconnect"
        tools:targetApi="34">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.HsDisconnect">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service
            android:name=".overlay.OverlayService"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="hearthstone_disconnect_button_overlay"/>
        </service>

        <service
            android:name=".vpn.DropVpnService"
            android:foregroundServiceType="shortService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:exported="false">
            <intent-filter>
                <action android:name="android.net.VpnService"/>
            </intent-filter>
        </service>
    </application>
</manifest>
```

- [ ] **Step 2: Write `res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">炉石拔线</string>
    <string name="notif_channel_overlay">悬浮按钮服务</string>
    <string name="notif_overlay_title">炉石拔线运行中</string>
    <string name="notif_overlay_text">炉石前台时显示悬浮按钮</string>
    <string name="notif_channel_vpn">拔线执行</string>
    <string name="notif_vpn_text">拔线中…</string>
</resources>
```

- [ ] **Step 3: Write `res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.HsDisconnect" parent="android:Theme.Material.Light.NoActionBar"/>
</resources>
```

- [ ] **Step 4: Write `res/values-night/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.HsDisconnect" parent="android:Theme.Material.NoActionBar"/>
</resources>
```

- [ ] **Step 5: Write `res/drawable/ic_notification.xml` (white silhouette)**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,2L2,12l10,10 10,-10z M12,5l7,7 -7,7 -7,-7z"/>
</vector>
```

- [ ] **Step 6: Write `res/drawable/ic_launcher_foreground.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF"
        android:pathData="M54,30 L78,54 54,78 30,54z"/>
</vector>
```

- [ ] **Step 7: Write `res/values/ic_launcher_background.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1A237E</color>
</resources>
```

- [ ] **Step 8: Write `res/mipmap-anydpi-v26/ic_launcher.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

Copy the same content to `res/mipmap-anydpi-v26/ic_launcher_round.xml`.

- [ ] **Step 9: Verify manifest parses**

```bash
./gradlew :app:processDebugManifest --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/
git commit -m "Add manifest, strings, theme, and launcher icon"
```

---

### Task 4: Empty MainActivity that compiles

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/MainActivity.kt`

- [ ] **Step 1: Write minimal MainActivity**

```kotlin
package com.hsdisconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Hello()
                }
            }
        }
    }
}

@Composable
private fun Hello() {
    Text("炉石拔线 — 脚手架就绪")
}
```

- [ ] **Step 2: Build debug APK**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/MainActivity.kt
git commit -m "Add minimal MainActivity scaffold"
```

---

## Stage 1: Pure Kotlin Core (TDD)

### Task 5: Constants

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/core/Constants.kt`

- [ ] **Step 1: Write Constants.kt**

```kotlin
package com.hsdisconnect.app.core

object Constants {
    const val HEARTHSTONE_PACKAGE = "com.blizzard.wtcg.hearthstone"

    val ALLOWED_DURATIONS_MS = listOf(3_000L, 5_000L, 8_000L, 10_000L)
    const val DEFAULT_DURATION_MS = 5_000L

    const val FOREGROUND_POLL_INTERVAL_MS = 1_500L
    const val FOREGROUND_DEBOUNCE_SAMPLES = 2

    const val NOTIF_CHANNEL_OVERLAY = "overlay_service"
    const val NOTIF_CHANNEL_VPN = "vpn_service"
    const val NOTIF_ID_OVERLAY = 1001
    const val NOTIF_ID_VPN = 1002

    const val VPN_TUN_ADDRESS = "10.42.42.1"
    const val VPN_TUN_PREFIX = 32
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/core/Constants.kt
git commit -m "Add Constants for package name, durations, and notification IDs"
```

---

### Task 6: Prefs (SharedPreferences wrapper) — TDD

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/core/Prefs.kt`
- Create: `app/src/test/java/com/hsdisconnect/app/core/PrefsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hsdisconnect.app.core

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PrefsTest {
    private lateinit var sp: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        sp = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { sp.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        prefs = Prefs(sp)
    }

    @Test
    fun `default duration falls back to Constants when unset`() {
        every { sp.getLong("duration_ms", Constants.DEFAULT_DURATION_MS) } returns Constants.DEFAULT_DURATION_MS
        assertEquals(Constants.DEFAULT_DURATION_MS, prefs.durationMs)
    }

    @Test
    fun `setting duration writes to prefs`() {
        prefs.durationMs = 8_000L
        verify { editor.putLong("duration_ms", 8_000L) }
        verify { editor.apply() }
    }

    @Test
    fun `button position defaults to -1, -1 when unset`() {
        every { sp.getInt("button_x", -1) } returns -1
        every { sp.getInt("button_y", -1) } returns -1
        assertEquals(-1 to -1, prefs.buttonPosition)
    }

    @Test
    fun `setting button position writes both x and y`() {
        prefs.buttonPosition = 120 to 340
        verify { editor.putInt("button_x", 120) }
        verify { editor.putInt("button_y", 340) }
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.core.PrefsTest" --no-daemon
```
Expected: FAIL — `Prefs` class not found.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.hsdisconnect.app.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(private val sp: SharedPreferences) {
    companion object {
        private const val FILE = "hs_disconnect_prefs"
        private const val KEY_DURATION = "duration_ms"
        private const val KEY_X = "button_x"
        private const val KEY_Y = "button_y"

        fun from(context: Context): Prefs =
            Prefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }

    var durationMs: Long
        get() = sp.getLong(KEY_DURATION, Constants.DEFAULT_DURATION_MS)
        set(value) = sp.edit().putLong(KEY_DURATION, value).apply()

    var buttonPosition: Pair<Int, Int>
        get() = sp.getInt(KEY_X, -1) to sp.getInt(KEY_Y, -1)
        set(value) = sp.edit()
            .putInt(KEY_X, value.first)
            .putInt(KEY_Y, value.second)
            .apply()
}
```

- [ ] **Step 4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.core.PrefsTest" --no-daemon
```
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/core/Prefs.kt app/src/test/java/com/hsdisconnect/app/core/PrefsTest.kt
git commit -m "Add Prefs wrapper with duration and button position"
```

---

### Task 7: DisconnectController state machine — TDD

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/overlay/DisconnectController.kt`
- Create: `app/src/test/java/com/hsdisconnect/app/overlay/DisconnectControllerTest.kt`

The controller is the heart of the app. It models the state machine from spec §7 and drives the VPN via a `VpnLauncher` interface (decoupled for testability).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hsdisconnect.app.overlay

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisconnectControllerTest {

    private fun controller(
        launcher: VpnLauncher = mockk(relaxed = true),
        prepareOk: () -> Boolean = { true },
    ): Pair<DisconnectController, StandardTestDispatcher> {
        val dispatcher = StandardTestDispatcher()
        return DisconnectController(
            scope = TestScope(dispatcher),
            launcher = launcher,
            checkVpnPrepared = prepareOk,
        ) to dispatcher
    }

    @Test
    fun `initial state is Idle and counter is 0`() = runTest {
        val (c, _) = controller()
        assertEquals(DisconnectState.Idle, c.state.value)
        assertEquals(0, c.counter.value)
    }

    @Test
    fun `tap when Idle and prepared transitions to Active and increments counter`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        // Simulate launcher reporting active
        c.onVpnActive()
        assertEquals(DisconnectState.Active(5_000L), c.state.value)
        assertEquals(1, c.counter.value)
        verify { launcher.start(5_000L) }
    }

    @Test
    fun `tap when VPN not prepared goes to Failed without counter increment`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher, prepareOk = { false })
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(DisconnectState.Failed(FailureReason.VpnNotAuthorized), c.state.value)
        assertEquals(0, c.counter.value)
        verify(exactly = 0) { launcher.start(any()) }
    }

    @Test
    fun `Active times out to Idle after duration`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(3_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        dispatcher.scheduler.advanceTimeBy(3_100L)
        assertEquals(DisconnectState.Idle, c.state.value)
        verify { launcher.stop() }
    }

    @Test
    fun `onVpnRevoked from Active returns to Idle`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.onVpnRevoked()
        assertEquals(DisconnectState.Idle, c.state.value)
    }

    @Test
    fun `tap while not Idle is ignored`() = runTest {
        val launcher = mockk<VpnLauncher>(relaxed = true)
        val (c, dispatcher) = controller(launcher = launcher)
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.onTap(5_000L)  // second tap during Active
        dispatcher.scheduler.runCurrent()
        assertEquals(1, c.counter.value)
        verify(exactly = 1) { launcher.start(any()) }
    }

    @Test
    fun `resetCounter sets counter to 0`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnActive()
        c.resetCounter()
        assertEquals(0, c.counter.value)
    }

    @Test
    fun `vpn launch failure transitions to Failed`() = runTest {
        val (c, dispatcher) = controller()
        c.onTap(5_000L)
        dispatcher.scheduler.runCurrent()
        c.onVpnFailed("establish returned null")
        assertEquals(
            DisconnectState.Failed(FailureReason.VpnLaunchFailed("establish returned null")),
            c.state.value
        )
        assertEquals(0, c.counter.value)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.overlay.DisconnectControllerTest" --no-daemon
```
Expected: FAIL — `DisconnectController` not found.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.hsdisconnect.app.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DisconnectState {
    data object Idle : DisconnectState()
    data object Preparing : DisconnectState()
    data class Active(val durationMs: Long) : DisconnectState()
    data class Failed(val reason: FailureReason) : DisconnectState()
}

sealed class FailureReason {
    data object VpnNotAuthorized : FailureReason()
    data class VpnLaunchFailed(val message: String) : FailureReason()
}

interface VpnLauncher {
    fun start(durationMs: Long)
    fun stop()
}

class DisconnectController(
    private val scope: CoroutineScope,
    private val launcher: VpnLauncher,
    private val checkVpnPrepared: () -> Boolean,
) {
    private val _state = MutableStateFlow<DisconnectState>(DisconnectState.Idle)
    val state: StateFlow<DisconnectState> = _state.asStateFlow()

    private val _counter = MutableStateFlow(0)
    val counter: StateFlow<Int> = _counter.asStateFlow()

    private var activeTimer: Job? = null
    private var pendingDurationMs: Long = 0L

    fun onTap(durationMs: Long) {
        if (_state.value != DisconnectState.Idle) return
        if (!checkVpnPrepared()) {
            _state.value = DisconnectState.Failed(FailureReason.VpnNotAuthorized)
            return
        }
        _state.value = DisconnectState.Preparing
        pendingDurationMs = durationMs
        launcher.start(durationMs)
    }

    fun onVpnActive() {
        if (_state.value !is DisconnectState.Preparing) return
        _state.value = DisconnectState.Active(pendingDurationMs)
        _counter.value = _counter.value + 1
        activeTimer = scope.launch {
            delay(pendingDurationMs)
            launcher.stop()
            if (_state.value is DisconnectState.Active) {
                _state.value = DisconnectState.Idle
            }
        }
    }

    fun onVpnRevoked() {
        activeTimer?.cancel()
        if (_state.value !is DisconnectState.Idle) {
            _state.value = DisconnectState.Idle
        }
    }

    fun onVpnFailed(message: String) {
        activeTimer?.cancel()
        _state.value = DisconnectState.Failed(FailureReason.VpnLaunchFailed(message))
    }

    fun clearFailure() {
        if (_state.value is DisconnectState.Failed) _state.value = DisconnectState.Idle
    }

    fun resetCounter() {
        _counter.value = 0
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.overlay.DisconnectControllerTest" --no-daemon
```
Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/DisconnectController.kt app/src/test/java/com/hsdisconnect/app/overlay/DisconnectControllerTest.kt
git commit -m "Add DisconnectController state machine"
```

---

## Stage 2: Foreground Detection

### Task 8: ForegroundDetector — TDD

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/overlay/ForegroundDetector.kt`
- Create: `app/src/test/java/com/hsdisconnect/app/overlay/ForegroundDetectorTest.kt`

The detector polls `UsageStatsManager.queryEvents()` and emits the latest `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED`/`ACTIVITY_STOPPED` state for the Hearthstone package. Debounce: only flip the StateFlow after N consecutive same samples.

We test the pure parsing/debounce logic by feeding fake `UsageEvents` results through an injectable provider.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.hsdisconnect.app.overlay

import com.hsdisconnect.app.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDetectorTest {

    private fun fixedDetector(samples: List<Boolean>): ForegroundDetector {
        var i = 0
        return ForegroundDetector(
            sampleNow = {
                samples[i++.coerceAtMost(samples.lastIndex)]
            },
            debounceSamples = Constants.FOREGROUND_DEBOUNCE_SAMPLES,
        )
    }

    @Test
    fun `initial state is false`() {
        val d = fixedDetector(listOf(false))
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `one true sample does not flip yet (debounce of 2)`() {
        val d = fixedDetector(listOf(true, false))
        d.tick()
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `two consecutive true samples flip to true`() {
        val d = fixedDetector(listOf(true, true))
        d.tick(); d.tick()
        assertTrue(d.isForeground.value)
    }

    @Test
    fun `two true then one false stays true`() {
        val d = fixedDetector(listOf(true, true, false))
        d.tick(); d.tick(); d.tick()
        assertTrue(d.isForeground.value)
    }

    @Test
    fun `two true then two false flips back to false`() {
        val d = fixedDetector(listOf(true, true, false, false))
        d.tick(); d.tick(); d.tick(); d.tick()
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `state transitions count is 2 over true,true,false,false`() {
        val d = fixedDetector(listOf(true, true, false, false))
        val seen = mutableListOf<Boolean>()
        seen.add(d.isForeground.value)
        repeat(4) {
            d.tick()
            if (seen.last() != d.isForeground.value) seen.add(d.isForeground.value)
        }
        assertEquals(listOf(false, true, false), seen)
    }
}
```

- [ ] **Step 2: Run tests, verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.overlay.ForegroundDetectorTest" --no-daemon
```
Expected: FAIL — `ForegroundDetector` not found.

- [ ] **Step 3: Write implementation (parse logic + debounce only)**

```kotlin
package com.hsdisconnect.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.hsdisconnect.app.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ForegroundDetector(
    private val sampleNow: () -> Boolean,
    private val debounceSamples: Int = Constants.FOREGROUND_DEBOUNCE_SAMPLES,
) {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var streak = 0
    private var lastSample: Boolean = false

    fun tick() {
        val sample = sampleNow()
        if (sample == lastSample) {
            streak++
        } else {
            streak = 1
            lastSample = sample
        }
        if (streak >= debounceSamples && _isForeground.value != sample) {
            _isForeground.value = sample
        }
    }

    companion object {
        fun fromUsageStats(
            usm: UsageStatsManager,
            targetPackage: String = Constants.HEARTHSTONE_PACKAGE,
        ): ForegroundDetector = ForegroundDetector(
            sampleNow = {
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 10_000L, now)
                val e = UsageEvents.Event()
                var lastType = -1
                while (events.getNextEvent(e)) {
                    if (e.packageName == targetPackage) {
                        when (e.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED -> lastType = e.eventType
                        }
                    }
                }
                lastType == UsageEvents.Event.ACTIVITY_RESUMED
            },
        )
    }

    fun startPolling(scope: CoroutineScope, intervalMs: Long = Constants.FOREGROUND_POLL_INTERVAL_MS): Job {
        return scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hsdisconnect.app.overlay.ForegroundDetectorTest" --no-daemon
```
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/ForegroundDetector.kt app/src/test/java/com/hsdisconnect/app/overlay/ForegroundDetectorTest.kt
git commit -m "Add ForegroundDetector with queryEvents poll and debounce"
```

---

## Stage 3: Settings UI

### Task 9: PermissionGate

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/core/PermissionGate.kt`

This is glue over Android Settings APIs — manual testing only. No unit tests (would require Robolectric for not much value).

- [ ] **Step 1: Write PermissionGate.kt**

```kotlin
package com.hsdisconnect.app.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val overlay: Boolean,
    val usageStats: Boolean,
    val vpnPrepared: Boolean,
    val postNotifications: Boolean,
    val hearthstoneInstalled: Boolean,
) {
    val allGranted: Boolean
        get() = overlay && usageStats && vpnPrepared && postNotifications && hearthstoneInstalled
}

object PermissionGate {
    fun check(context: Context): PermissionStatus = PermissionStatus(
        overlay = Settings.canDrawOverlays(context),
        usageStats = hasUsageStats(context),
        vpnPrepared = VpnService.prepare(context) == null,
        postNotifications = hasNotificationPermission(context),
        hearthstoneInstalled = isHearthstoneInstalled(context),
    )

    private fun hasUsageStats(context: Context): Boolean {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            aom.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isHearthstoneInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(Constants.HEARTHSTONE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/core/PermissionGate.kt
git commit -m "Add PermissionGate covering overlay, usage stats, VPN, notifications, and HS install check"
```

---

### Task 10: SettingsScreen skeleton with permission status cards

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/hsdisconnect/app/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/hsdisconnect/app/MainActivity.kt`

- [ ] **Step 1: Write Theme.kt**

```kotlin
package com.hsdisconnect.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun HsDisconnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
```

- [ ] **Step 2: Write SettingsScreen.kt with permission cards (no actions yet)**

```kotlin
package com.hsdisconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hsdisconnect.app.core.PermissionStatus

data class SettingsUiState(
    val perms: PermissionStatus,
    val durationMs: Long,
    val isRunning: Boolean,
)

interface SettingsActions {
    fun requestOverlay()
    fun requestUsageStats()
    fun requestVpn()
    fun requestNotifications()
    fun setDuration(ms: Long)
    fun toggleService()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("炉石拔线") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.perms.hearthstoneInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "未检测到炉石传说，无法启动。",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Red,
                    )
                }
            }
            PermissionRow("悬浮窗权限", state.perms.overlay, actions::requestOverlay)
            PermissionRow("使用情况访问", state.perms.usageStats, actions::requestUsageStats)
            PermissionRow("VPN 授权", state.perms.vpnPrepared, actions::requestVpn)
            PermissionRow("通知权限", state.perms.postNotifications, actions::requestNotifications)
            DurationRow(state.durationMs, actions::setDuration)
            OutlinedButton(
                onClick = actions::toggleService,
                enabled = state.perms.allGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isRunning) "停止拔线助手" else "启动拔线助手")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (granted) Color(0xFF2E7D32) else Color.Red,
                modifier = Modifier.size(24.dp),
            )
            Text(label, modifier = Modifier.padding(start = 12.dp).weight(1f))
            if (!granted) {
                OutlinedButton(onClick = onAction) { Text("去授权") }
            }
        }
    }
}

@Composable
private fun DurationRow(currentMs: Long, onChange: (Long) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("默认拔线时长", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3_000L, 5_000L, 8_000L, 10_000L).forEach { ms ->
                    OutlinedButton(
                        onClick = { onChange(ms) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${ms / 1000}s")
                    }
                }
            }
            Text(
                "当前: ${currentMs / 1000} 秒",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
```

- [ ] **Step 3: Update MainActivity.kt to host SettingsScreen with stub state**

```kotlin
package com.hsdisconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hsdisconnect.app.core.PermissionGate
import com.hsdisconnect.app.core.Prefs
import com.hsdisconnect.app.ui.SettingsActions
import com.hsdisconnect.app.ui.SettingsScreen
import com.hsdisconnect.app.ui.SettingsUiState
import com.hsdisconnect.app.ui.theme.HsDisconnectTheme

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.from(this)
        setContent {
            HsDisconnectTheme {
                var perms by remember { mutableStateOf(PermissionGate.check(this)) }
                var duration by remember { mutableStateOf(prefs.durationMs) }
                val state = SettingsUiState(perms, duration, isRunning = false)
                val actions = object : SettingsActions {
                    override fun requestOverlay() {}
                    override fun requestUsageStats() {}
                    override fun requestVpn() {}
                    override fun requestNotifications() {}
                    override fun setDuration(ms: Long) {
                        prefs.durationMs = ms
                        duration = ms
                    }
                    override fun toggleService() {}
                }
                SettingsScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // refreshed by recomposition on next setContent navigation; full refresh wired in T11
    }
}
```

- [ ] **Step 4: Build debug APK**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Add material-icons-extended dep (Icons.Default.CheckCircle/Cancel are in core, but to be safe, verify)**

If the build fails on `Icons.Default.Cancel`, add to `app/build.gradle.kts` dependencies:
```kotlin
implementation("androidx.compose.material:material-icons-extended")
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/ui/ app/src/main/java/com/hsdisconnect/app/MainActivity.kt
git commit -m "Add SettingsScreen with permission status cards and duration picker"
```

---

### Task 11: Wire up permission request flows

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/MainActivity.kt`

Each permission has a different request mechanism — `Settings.ACTION_*` intent or a `requestPermissions` for runtime permissions.

- [ ] **Step 1: Replace `actions` in MainActivity with real implementations**

```kotlin
package com.hsdisconnect.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hsdisconnect.app.core.PermissionGate
import com.hsdisconnect.app.core.Prefs
import com.hsdisconnect.app.ui.SettingsActions
import com.hsdisconnect.app.ui.SettingsScreen
import com.hsdisconnect.app.ui.SettingsUiState
import com.hsdisconnect.app.ui.theme.HsDisconnectTheme

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.from(this)
        setContent {
            HsDisconnectTheme {
                var perms by remember { mutableStateOf(PermissionGate.check(this)) }
                var duration by remember { mutableStateOf(prefs.durationMs) }

                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.addObserver(
                        androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                perms = PermissionGate.check(this@MainActivity)
                            }
                        }
                    )
                }

                val vpnLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { perms = PermissionGate.check(this) }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { perms = PermissionGate.check(this) }

                val state = SettingsUiState(perms, duration, isRunning = false)
                val actions = object : SettingsActions {
                    override fun requestOverlay() {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    override fun requestUsageStats() {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    override fun requestVpn() {
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) vpnLauncher.launch(intent)
                        else perms = PermissionGate.check(this@MainActivity)
                    }
                    override fun requestNotifications() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    override fun setDuration(ms: Long) {
                        prefs.durationMs = ms
                        duration = ms
                    }
                    override fun toggleService() { /* T13 */ }
                }
                SettingsScreen(state, actions)
            }
        }
    }
}
```

- [ ] **Step 2: Build debug APK**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual test on device or emulator**

Install:
```bash
./gradlew :app:installDebug --no-daemon
```
Open app, tap each "去授权" button:
- 悬浮窗权限 → opens Android system overlay grant page
- 使用情况访问 → opens "Usage data access" settings list
- VPN 授权 → pops Android system VPN authorization dialog
- 通知权限 → pops runtime permission dialog (Android 13+)

After returning to the app, the corresponding row should flip to green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/MainActivity.kt
git commit -m "Wire up permission request flows to system settings/dialogs"
```

---

### Task 12: Duration persistence verified end-to-end

Already implemented in T10/T11 (`setDuration` writes to Prefs). This task is **manual verification** only — no code change. Skip if T11 manual test confirmed duration persisted across app restart.

- [ ] **Step 1: Manual verify**
  - Open app, tap "8s" duration button.
  - Force-stop the app, reopen.
  - "当前: 8 秒" still shows.

No commit needed.

---

### Task 13: Start/Stop OverlayService toggle

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/MainActivity.kt`

This task wires the master toggle button but `OverlayService` doesn't exist yet (T16 creates it). For now we'll just `startForegroundService` / `stopService` with an Intent — the empty stub will be created in T16.

- [ ] **Step 1: Add isRunning tracking and toggleService impl**

In MainActivity, replace `toggleService()` and add a running check based on whether the service is running:

```kotlin
// At top of MainActivity:
import android.app.ActivityManager
import android.content.Context
import com.hsdisconnect.app.overlay.OverlayService

// Add this helper somewhere in MainActivity:
private fun isOverlayServiceRunning(): Boolean {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    return am.getRunningServices(Integer.MAX_VALUE).any {
        it.service.className == OverlayService::class.java.name
    }
}
```

In the `setContent` block, replace `isRunning = false` with `isOverlayServiceRunning()` and replace `toggleService()`:

```kotlin
var running by remember { mutableStateOf(isOverlayServiceRunning()) }
// (use `running` in SettingsUiState)
val state = SettingsUiState(perms, duration, isRunning = running)
// ...
override fun toggleService() {
    val intent = Intent(this@MainActivity, OverlayService::class.java)
    if (running) {
        stopService(intent)
        running = false
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        running = true
    }
}
```

- [ ] **Step 2: Don't build yet — OverlayService is created in T16. Just verify the code compiles after T16.**

Defer commit to after T16.

---

## Stage 4: VPN Service

### Task 14: DropVpnService scaffold (start/stop, no TUN)

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/vpn/DropVpnService.kt`

This task creates the service class with proper FGS lifecycle but doesn't actually establish a TUN yet. We'll verify it starts and stops cleanly with a notification.

- [ ] **Step 1: Write DropVpnService.kt skeleton**

```kotlin
package com.hsdisconnect.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hsdisconnect.app.R
import com.hsdisconnect.app.core.Constants

class DropVpnService : VpnService() {

    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        const val ACTION_START = "com.hsdisconnect.action.START_DROP"
        const val ACTION_STOP = "com.hsdisconnect.action.STOP_DROP"

        @Volatile var listener: Listener? = null

        fun start(context: Context, durationMs: Long) {
            val intent = Intent(context, DropVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DropVpnService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    interface Listener {
        fun onVpnActive()
        fun onVpnStopped()
        fun onVpnRevoked()
        fun onVpnFailed(message: String)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                // T15 will add: establish() and start drop loop
                listener?.onVpnActive()
            }
            ACTION_STOP -> stopAndRelease()
            else -> stopAndRelease()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        listener?.onVpnRevoked()
        stopAndRelease()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.onVpnStopped()
    }

    private fun stopAndRelease() {
        // T15 will add: close ParcelFileDescriptor
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_VPN)
            .setContentTitle(getString(R.string.notif_vpn_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_ID_VPN, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(Constants.NOTIF_ID_VPN, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_VPN) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.NOTIF_CHANNEL_VPN,
                getString(R.string.notif_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/vpn/DropVpnService.kt
git commit -m "Add DropVpnService scaffold with shortService FGS lifecycle"
```

---

### Task 15: TUN black hole with addAllowedApplication

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/vpn/DropVpnService.kt`

Now actually establish the VPN interface that only catches Hearthstone, and don't forward any packets (just close the fd at the end).

- [ ] **Step 1: Update DropVpnService.kt**

Replace the `onStartCommand` ACTION_START branch and add `establishTunnel()`:

```kotlin
import android.os.ParcelFileDescriptor
import com.hsdisconnect.app.core.Constants
// (existing imports)

class DropVpnService : VpnService() {
    // (existing companion, Listener)

    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var closed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                try {
                    pfd = establishTunnel()
                    if (pfd == null) {
                        listener?.onVpnFailed("establish returned null")
                        stopAndRelease()
                    } else {
                        listener?.onVpnActive()
                    }
                } catch (t: Throwable) {
                    listener?.onVpnFailed(t.message ?: t.javaClass.simpleName)
                    stopAndRelease()
                }
            }
            ACTION_STOP -> stopAndRelease()
            else -> stopAndRelease()
        }
        return START_NOT_STICKY
    }

    private fun establishTunnel(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(Constants.VPN_TUN_ADDRESS, Constants.VPN_TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        try {
            builder.addAllowedApplication(Constants.HEARTHSTONE_PACKAGE)
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            throw IllegalStateException("Hearthstone not installed")
        }
        return builder.establish()
    }

    private fun stopAndRelease() {
        if (closed) return
        closed = true
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // (existing onRevoke, onDestroy, startForegroundWithNotification, ensureChannel)
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test (requires Hearthstone installed)**

```bash
./gradlew :app:installDebug --no-daemon
adb shell am startservice -a com.hsdisconnect.action.START_DROP \
    -n com.hsdisconnect.app/.vpn.DropVpnService --el duration_ms 5000
```
Expected:
- Status bar shows VPN key icon
- Notification "拔线中…" appears
- `adb logcat | grep -i vpn` shows no errors

Stop:
```bash
adb shell am startservice -a com.hsdisconnect.action.STOP_DROP \
    -n com.hsdisconnect.app/.vpn.DropVpnService
```
Expected: VPN icon disappears, notification cleared.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/vpn/DropVpnService.kt
git commit -m "Establish TUN tunnel with addAllowedApplication for Hearthstone"
```

---

## Stage 5: Overlay

### Task 16: OverlayService foreground service shell

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

This is the long-running container that holds the detector + window + controller. T19/T21 will wire it up; for now, just the FGS shell + notification.

- [ ] **Step 1: Write OverlayService.kt**

```kotlin
package com.hsdisconnect.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hsdisconnect.app.MainActivity
import com.hsdisconnect.app.R
import com.hsdisconnect.app.core.Constants
import com.hsdisconnect.app.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var detector: ForegroundDetector
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.from(this)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        detector = ForegroundDetector.fromUsageStats(usm)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        pollingJob = detector.startPolling(scope)
        // T19 will wire detector.isForeground → window show/hide
        // T21 will instantiate DisconnectController
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_OVERLAY)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_ID_OVERLAY, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Constants.NOTIF_ID_OVERLAY, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_OVERLAY) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.NOTIF_CHANNEL_OVERLAY,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
```

- [ ] **Step 2: Build (this also satisfies T13's deferred build)**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual test toggle from MainActivity**

```bash
./gradlew :app:installDebug --no-daemon
```
Open app, grant all 4 permissions, tap "启动拔线助手".

Expected:
- "炉石拔线运行中" notification appears
- Tap "停止拔线助手" → notification disappears

- [ ] **Step 4: Commit (covers T13 + T16)**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt app/src/main/java/com/hsdisconnect/app/MainActivity.kt
git commit -m "Add OverlayService foreground service with start/stop toggle"
```

---

### Task 17: OverlayWindow — basic circular button via WindowManager

**Files:**
- Create: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt`

A plain `View` subclass that draws a circle in `onDraw`. Added/removed via `WindowManager`. No drag yet, no countdown yet.

- [ ] **Step 1: Write OverlayWindow.kt**

```kotlin
package com.hsdisconnect.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.min

class OverlayWindow(context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = (60 * context.resources.displayMetrics.density).toInt()

    private val view = ButtonView(context, sizePx)
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 200
    }

    private var attached = false

    var onClick: () -> Unit = {}

    fun show() {
        if (attached) return
        attached = true
        view.onClick = { onClick() }
        wm.addView(view, params)
    }

    fun hide() {
        if (!attached) return
        attached = false
        wm.removeView(view)
    }

    fun isShown(): Boolean = attached

    private class ButtonView(context: Context, private val sizePx: Int) : View(context) {

        var onClick: () -> Unit = {}

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 220, 60, 60)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f - strokePaint.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            canvas.drawCircle(width / 2f, height / 2f, r, strokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onClick()
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt
git commit -m "Add OverlayWindow with circular button view"
```

---

### Task 18: OverlayWindow drag + position clamping + persistence

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt`

Handle drag in `ButtonView.onTouchEvent`. Distinguish click vs drag by total movement distance. Clamp position to display bounds. Persist position via callback.

- [ ] **Step 1: Update OverlayWindow.kt**

```kotlin
package com.hsdisconnect.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = (60 * context.resources.displayMetrics.density).toInt()

    var onClick: () -> Unit = {}
    var onPositionChanged: (Int, Int) -> Unit = { _, _ -> }

    private val view = ButtonView(context, sizePx)
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 200
    }

    private var attached = false

    init {
        view.onClick = { onClick() }
        view.onDrag = { dx, dy ->
            params.x = clampX(params.x + dx)
            params.y = clampY(params.y + dy)
            if (attached) wm.updateViewLayout(view, params)
        }
        view.onDragEnd = { onPositionChanged(params.x, params.y) }
    }

    fun setPosition(x: Int, y: Int) {
        params.x = clampX(x)
        params.y = clampY(y)
        if (attached) wm.updateViewLayout(view, params)
    }

    fun show() {
        if (attached) return
        attached = true
        wm.addView(view, params)
    }

    fun hide() {
        if (!attached) return
        attached = false
        wm.removeView(view)
    }

    fun isShown(): Boolean = attached

    private fun clampX(v: Int): Int {
        val w = context.resources.displayMetrics.widthPixels
        return v.coerceIn(0, max(0, w - sizePx))
    }

    private fun clampY(v: Int): Int {
        val h = context.resources.displayMetrics.heightPixels
        return v.coerceIn(0, max(0, h - sizePx))
    }

    private class ButtonView(context: Context, private val sizePx: Int) : View(context) {

        var onClick: () -> Unit = {}
        var onDrag: (Int, Int) -> Unit = { _, _ -> }
        var onDragEnd: () -> Unit = {}

        private val touchSlopPx = (8 * context.resources.displayMetrics.density).toInt()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 220, 60, 60)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var isDragging = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f - strokePaint.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            canvas.drawCircle(width / 2f, height / 2f, r, strokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    lastX = downX; lastY = downY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downX
                    val totalDy = event.rawY - downY
                    if (!isDragging && (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastX).toInt()
                        val dy = (event.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) onDrag(dx, dy)
                        lastX = event.rawX; lastY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) onDragEnd() else onClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt
git commit -m "Add drag, position clamping, and persistence callbacks to OverlayWindow"
```

---

### Task 19: Wire ForegroundDetector → OverlayWindow show/hide

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

- [ ] **Step 1: Update OverlayService to instantiate OverlayWindow and observe detector**

Replace the body of OverlayService (additions only — keep existing imports and helpers):

```kotlin
class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var detector: ForegroundDetector
    private lateinit var window: OverlayWindow
    private var pollingJob: Job? = null
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.from(this)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        detector = ForegroundDetector.fromUsageStats(usm)
        window = OverlayWindow(this).apply {
            val (x, y) = prefs.buttonPosition
            if (x >= 0 && y >= 0) setPosition(x, y)
            onPositionChanged = { newX, newY -> prefs.buttonPosition = newX to newY }
            onClick = { /* T22 will wire to controller */ }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        pollingJob = detector.startPolling(scope)
        observerJob = scope.launch {
            detector.isForeground.collect { foreground ->
                if (foreground) window.show() else window.hide()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        observerJob?.cancel()
        if (window.isShown()) window.hide()
        scope.cancel()
    }

    // (existing onBind, startForegroundWithNotification, ensureChannel)
}
```

Add the missing import: `import kotlinx.coroutines.launch`.

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual test**

```bash
./gradlew :app:installDebug --no-daemon
```
With the service started:
- Open Hearthstone (or any test app — adjust HEARTHSTONE_PACKAGE in Constants.kt to a known package like `com.android.chrome` for testing)
- Expected: red circle button appears within ~2 seconds
- Switch to a different app
- Expected: button disappears within ~3 seconds (1.5s poll × 2 debounce)

**Reset the constant to `com.blizzard.wtcg.hearthstone` after testing.**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt
git commit -m "Wire ForegroundDetector to OverlayWindow show/hide"
```

---

### Task 20: Countdown ring and disconnecting visual state

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt`

Add an arc that animates from full circle to empty over the disconnect duration. The window will be controlled by an external `setDisconnectingState(startTime, durationMs)` method.

- [ ] **Step 1: Update ButtonView to support a countdown arc**

Add inside `OverlayWindow` class:

```kotlin
// Add as a public method on OverlayWindow:
fun setDisconnecting(durationMs: Long?) {
    view.setDisconnecting(durationMs)
}
```

Update `ButtonView` to render the arc and animate:

```kotlin
private class ButtonView(context: Context, private val sizePx: Int) : View(context) {

    var onClick: () -> Unit = {}
    var onDrag: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}

    private val touchSlopPx = (8 * context.resources.displayMetrics.density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 220, 60, 60)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = android.graphics.RectF()

    private var disconnectStartTime: Long = 0L
    private var disconnectDurationMs: Long = 0L
    private var disconnecting = false

    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var isDragging = false

    fun setDisconnecting(durationMs: Long?) {
        if (durationMs == null) {
            disconnecting = false
            invalidate()
        } else {
            disconnecting = true
            disconnectStartTime = System.currentTimeMillis()
            disconnectDurationMs = durationMs
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - arcPaint.strokeWidth
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
        canvas.drawCircle(width / 2f, height / 2f, r, strokePaint)
        if (disconnecting) {
            val elapsed = System.currentTimeMillis() - disconnectStartTime
            val fraction = (elapsed.toFloat() / disconnectDurationMs).coerceIn(0f, 1f)
            val remainingFraction = 1f - fraction
            arcRect.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
            canvas.drawArc(arcRect, -90f, 360f * remainingFraction, false, arcPaint)
            if (remainingFraction > 0f) {
                postInvalidateOnAnimation()
            } else {
                disconnecting = false
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disconnecting) return true  // disable clicks while disconnecting
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downX
                val totalDy = event.rawY - downY
                if (!isDragging && (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx)) {
                    isDragging = true
                }
                if (isDragging) {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) onDrag(dx, dy)
                    lastX = event.rawX; lastY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) onDragEnd() else onClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt
git commit -m "Add countdown ring animation and click-disable during disconnect"
```

---

## Stage 6: Integration

### Task 21: Wire DisconnectController and DropVpnService inside OverlayService

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

Build the controller, give it a `VpnLauncher` that delegates to `DropVpnService`, and register the service's `Listener`.

- [ ] **Step 1: Update OverlayService**

Add fields and update `onCreate` / `onStartCommand` / `onDestroy`:

```kotlin
class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var detector: ForegroundDetector
    private lateinit var window: OverlayWindow
    private lateinit var controller: DisconnectController
    private var pollingJob: Job? = null
    private var observerJob: Job? = null
    private var counterObserverJob: Job? = null
    private var stateObserverJob: Job? = null

    private val launcher = object : VpnLauncher {
        override fun start(durationMs: Long) {
            com.hsdisconnect.app.vpn.DropVpnService.start(this@OverlayService, durationMs)
        }
        override fun stop() {
            com.hsdisconnect.app.vpn.DropVpnService.stop(this@OverlayService)
        }
    }

    private val vpnListener = object : com.hsdisconnect.app.vpn.DropVpnService.Listener {
        override fun onVpnActive() { controller.onVpnActive() }
        override fun onVpnStopped() { /* timer-driven stop already handled */ }
        override fun onVpnRevoked() { controller.onVpnRevoked() }
        override fun onVpnFailed(message: String) { controller.onVpnFailed(message) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.from(this)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        detector = ForegroundDetector.fromUsageStats(usm)
        controller = DisconnectController(
            scope = scope,
            launcher = launcher,
            checkVpnPrepared = { android.net.VpnService.prepare(this) == null },
        )
        com.hsdisconnect.app.vpn.DropVpnService.listener = vpnListener
        window = OverlayWindow(this).apply {
            val (x, y) = prefs.buttonPosition
            if (x >= 0 && y >= 0) setPosition(x, y)
            onPositionChanged = { newX, newY -> prefs.buttonPosition = newX to newY }
            onClick = { controller.onTap(prefs.durationMs) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        pollingJob = detector.startPolling(scope)
        observerJob = scope.launch {
            detector.isForeground.collect { foreground ->
                if (foreground) {
                    window.show()
                } else {
                    window.hide()
                    controller.resetCounter()
                }
            }
        }
        stateObserverJob = scope.launch {
            controller.state.collect { state ->
                when (state) {
                    is DisconnectState.Active -> window.setDisconnecting(state.durationMs)
                    is DisconnectState.Failed -> {
                        window.setDisconnecting(null)
                        // T24 will show a notification
                    }
                    else -> window.setDisconnecting(null)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        observerJob?.cancel()
        stateObserverJob?.cancel()
        counterObserverJob?.cancel()
        if (window.isShown()) window.hide()
        com.hsdisconnect.app.vpn.DropVpnService.listener = null
        scope.cancel()
    }

    // (existing onBind, startForegroundWithNotification, ensureChannel)
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual end-to-end test**

```bash
./gradlew :app:installDebug --no-daemon
```
1. Grant all 4 permissions, start service.
2. Open Hearthstone (or temporarily set HEARTHSTONE_PACKAGE to a test app).
3. Wait for button to appear.
4. Tap button.
5. Expected: countdown arc animates over 5 seconds; status bar shows VPN key icon for the duration; in Hearthstone, the game should show "Reconnecting…" then resume.

**Smoke-only test without Hearthstone**:
- Set HEARTHSTONE_PACKAGE to `com.android.chrome`, open Chrome, load a long-running stream
- Tap the disconnect button
- The page should stall for 5 seconds then resume

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt
git commit -m "Wire DisconnectController and DropVpnService into OverlayService"
```

---

### Task 22: Counter display on button (small badge)

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt`
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

Show the session counter in the lower-right of the button as a small number badge.

- [ ] **Step 1: Add counter rendering to OverlayWindow**

Inside `OverlayWindow`, add:

```kotlin
fun setCounter(count: Int) {
    view.setCounter(count)
}
```

Inside `ButtonView`, add:

```kotlin
private var counter = 0
private val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE
    textSize = sizePx * 0.3f
    textAlign = Paint.Align.CENTER
    typeface = android.graphics.Typeface.DEFAULT_BOLD
}

fun setCounter(count: Int) {
    if (counter == count) return
    counter = count
    invalidate()
}
```

In `onDraw`, after the circle drawing, add:

```kotlin
if (counter > 0) {
    val cx = width * 0.75f
    val cy = height * 0.75f
    canvas.drawCircle(cx, cy, sizePx * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 30, 30, 30)
    })
    val baseline = cy - (counterPaint.descent() + counterPaint.ascent()) / 2
    canvas.drawText(counter.toString(), cx, baseline, counterPaint)
}
```

- [ ] **Step 2: Wire counter in OverlayService**

In `onStartCommand`, add:

```kotlin
counterObserverJob = scope.launch {
    controller.counter.collect { count -> window.setCounter(count) }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual test**

Tap button multiple times in quick succession (during foreground session). Counter should increment 1, 2, 3… Switching to another app and back should reset to 0.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayWindow.kt app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt
git commit -m "Add session counter badge on overlay button"
```

---

## Stage 7: Error Paths & Polish

### Task 23: Handle ForegroundServiceStartNotAllowedException

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

The VpnLauncher's `start` call could throw on Android 12+ if started from background. Wrap and forward as `onVpnFailed`.

- [ ] **Step 1: Update VpnLauncher in OverlayService**

```kotlin
private val launcher = object : VpnLauncher {
    override fun start(durationMs: Long) {
        try {
            com.hsdisconnect.app.vpn.DropVpnService.start(this@OverlayService, durationMs)
        } catch (e: Exception) {
            // Android 12+ ForegroundServiceStartNotAllowedException, or others
            controller.onVpnFailed(e.message ?: e.javaClass.simpleName)
        }
    }
    override fun stop() {
        try {
            com.hsdisconnect.app.vpn.DropVpnService.stop(this@OverlayService)
        } catch (_: Exception) { /* idempotent */ }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt
git commit -m "Catch ForegroundServiceStartNotAllowedException when starting VPN"
```

---

### Task 24: Failure notification for VPN auth revoked / setup failed

**Files:**
- Modify: `app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt`

When state transitions to `Failed`, post a notification offering to open SettingsActivity. Auto-clear when state returns to Idle.

- [ ] **Step 1: Add failure notification logic**

Add to OverlayService:

```kotlin
private val notificationManager by lazy {
    getSystemService(NotificationManager::class.java)
}

private fun postFailureNotification(reason: FailureReason) {
    val text = when (reason) {
        FailureReason.VpnNotAuthorized -> "VPN 授权失效，点击修复"
        is FailureReason.VpnLaunchFailed -> "拔线失败：${reason.message}"
    }
    val tapIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_OVERLAY)
        .setContentTitle("炉石拔线")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(tapIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    notificationManager.notify(Constants.NOTIF_ID_OVERLAY + 1, n)
}

private fun clearFailureNotification() {
    notificationManager.cancel(Constants.NOTIF_ID_OVERLAY + 1)
}
```

Update the state observer in `onStartCommand`:

```kotlin
stateObserverJob = scope.launch {
    controller.state.collect { state ->
        when (state) {
            is DisconnectState.Active -> {
                window.setDisconnecting(state.durationMs)
                clearFailureNotification()
            }
            is DisconnectState.Failed -> {
                window.setDisconnecting(null)
                postFailureNotification(state.reason)
                // Auto-clear failure after 3s so user can tap again
                scope.launch {
                    kotlinx.coroutines.delay(3_000L)
                    controller.clearFailure()
                }
            }
            else -> window.setDisconnecting(null)
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual test**
- Open the app, grant all permissions, start service.
- Open Android Settings → Apps → 炉石拔线 → revoke VPN authorization (or open a different VPN app to override).
- Return to test app, trigger a disconnect.
- Expected: notification "VPN 授权失效，点击修复" appears, button briefly shows failure state, tap notification opens MainActivity.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hsdisconnect/app/overlay/OverlayService.kt
git commit -m "Show notification on disconnect failure and auto-clear after 3s"
```

---

### Task 25: Full manual E2E test pass

**Files:** none modified.

- [ ] **Step 1: Run through the spec §10 manual test checklist**

Install fresh:
```bash
./gradlew :app:installDebug --no-daemon
adb shell pm clear com.hsdisconnect.app  # reset state
```

Test items (check off each):

- [ ] 4 permission grants flow cleanly without errors
- [ ] Hearthstone foreground → button appears within 3s
- [ ] Hearthstone background → button disappears within 3s
- [ ] Tap button (5s duration) → game shows "Reconnecting…" → resumes within ~10s total
- [ ] Counter increments on each tap, resets when switching apps
- [ ] Drag button to new position, force-stop service, restart, button restores to dragged position
- [ ] Rotate device while button is shown → button stays within screen bounds
- [ ] Revoke VPN authorization manually → tap fails with notification

If any item fails, file a follow-up task and stop here; do not commit a "passes" marker if it doesn't.

- [ ] **Step 2: Tag the v0.1 release**

```bash
git tag v0.1.0 -m "Hearthstone disconnect MVP"
```

---

## Self-Review

I went back through the spec and the plan. Findings:

**Spec coverage check:**
- §3 tech decisions: ✓ all reflected (Kotlin, VpnService, queryEvents, OverlayService, etc.)
- §4 architecture: ✓ T16, T19, T21 build it up
- §5 component list: ✓ all 8 components mapped to tasks
- §6 manifest: ✓ T3
- §7 state machine: ✓ T7 (DisconnectController) covers Idle/Preparing/Active/Failed/onRevoke. Note: I collapsed "Starting" and "Stopping" sub-states into the controller's effective transitions because the launcher events (`onVpnActive`, `onVpnStopped`) are what actually gate those transitions — the spec's 5-state diagram is conceptually preserved but the controller distinguishes only `Idle`/`Preparing`/`Active`/`Failed`. This is a deliberate simplification.
- §8 data flow: ✓ T21 + T22
- §9 error handling: ✓ T23 (FGS exception) + T24 (notification). Two items deferred: "Overlay permission revoked mid-run" and "UsageStats permission revoked mid-run" — the spec lists these but the impact is "UI silently breaks". Adding live monitoring of these permissions is low value for MVP self-use; acceptable to skip.
- §10 testing: ✓ T6/T7/T8 unit; T25 manual checklist
- §11 risks: documented in spec, no implementation needed
- §12 MVP boundary: ✓ respected; no auto-disconnect, no Tile fallback
- §13 implementation order: ✓ matches stages 0-7

**Placeholder scan:** No "TBD" / "implement later" / "fill in details". A few `// T## will wire` forward-references exist but each has the concrete code shown in the cited task.

**Type consistency:**
- `DisconnectState` sealed class: same shape in T7 and T21.
- `VpnLauncher` interface: 2-method (`start(Long)`, `stop()`) consistent across T7 / T21 / T23.
- `DropVpnService.Listener`: 4-method consistent across T14 / T21.
- `Constants.HEARTHSTONE_PACKAGE`: consistent.
- `Prefs.buttonPosition: Pair<Int, Int>`: T6 / T18 / T19 consistent.
- `OverlayWindow.setDisconnecting(Long?)`: T20 defines, T21 calls. ✓

No issues to fix.
