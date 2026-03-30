# HoopLand Helper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android overlay app that automatically executes perfect shots in Hoop Land by holding the shoot button, monitoring the shooting meter bar via screen capture, and releasing touch input exactly when the cursor hits the green zone.

**Architecture:** `OverlayService` renders a draggable floating button over Hoop Land. When tapped, `ShotManager` tells `TouchInjector` to hold the Shoot button (via root shell on rooted devices, or chained `AccessibilityService` gestures on non-rooted), then polls `ScreenCaptureService` frames through `GreenZoneDetector` until the cursor hits the green zone — then releases. A 3-second timeout auto-releases if green is never found.

**Tech Stack:** Kotlin, Android 10 (API 29+), Kotlin Coroutines, MediaProjection API, AccessibilityService API, WindowManager overlay, JUnit 4, Mockito-Kotlin.

---

## File Map

| File | Responsibility |
|------|----------------|
| `app/build.gradle.kts` | Dependencies, SDK config |
| `app/src/main/AndroidManifest.xml` | Permissions, service declarations |
| `app/src/main/res/xml/accessibility_service_config.xml` | Accessibility service metadata |
| `utils/RootChecker.kt` | Detect root at runtime via injectable `su` runner |
| `data/CalibrationData.kt` | Data classes: `BarRegion`, `HsvRange`, `ShootPosition` |
| `data/CalibrationRepository.kt` | SharedPreferences read/write for calibration data |
| `core/IHoopService.kt` | Interface for touch injection abstraction (testability) |
| `core/GreenZoneDetector.kt` | Per-frame pixel analysis: cursor finding + green zone check |
| `core/TouchInjector.kt` | Root shell or `IHoopService` delegation for hold/release |
| `core/ShotManager.kt` | Coroutine-based hold → detect loop → release with timeout |
| `service/HoopAccessibilityService.kt` | Implements `IHoopService` via chained `GestureDescription` |
| `service/ScreenCaptureService.kt` | Plain class: MediaProjection setup + cropped bar frame delivery |
| `service/OverlayService.kt` | Foreground service: floating button, drag, state colors, wiring |
| `ui/MainActivity.kt` | Permission flow, MediaProjection request, service launch |
| `ui/CalibrationActivity.kt` | 4-step tap calibration: bar edges, green color, shoot position |

---

### Task 1: Android Project Scaffold

**Files:**
- Create: Android Studio project at `/home/denusklo/workspace/HoopLandHelper/`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Create the Android project**

Open Android Studio → New Project → Empty Activity with these settings:
- **Name:** `HoopLandHelper`
- **Package name:** `com.denusklo.hooplandhelper`
- **Save location:** `/home/denusklo/workspace/HoopLandHelper`
- **Language:** Kotlin
- **Minimum SDK:** API 29 (Android 10)

- [ ] **Step 2: Replace app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.denusklo.hooplandhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.denusklo.hooplandhelper"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

- [ ] **Step 3: Create package directories**

Under `app/src/main/kotlin/com/denusklo/hooplandhelper/`, create:
- `core/`
- `data/`
- `service/`
- `ui/`
- `utils/`

- [ ] **Step 4: Sync gradle and verify build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/
git commit -m "feat: scaffold Android project with gradle config"
```

---

### Task 2: AndroidManifest and Accessibility Service Config

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`

- [ ] **Step 1: Create accessibility service config**

Create `app/src/main/res/xml/accessibility_service_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFlags="flagDefault"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
```

- [ ] **Step 2: Add string resource**

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:
```xml
<string name="accessibility_service_description">HoopLand Helper uses this to simulate touch on non-rooted devices</string>
```

- [ ] **Step 3: Replace AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.HoopLandHelper">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.CalibrationActivity"
            android:exported="false" />

        <service
            android:name=".service.OverlayService"
            android:foregroundServiceType="mediaProjection"
            android:exported="false" />

        <service
            android:name=".service.HoopAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>

</manifest>
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/res/xml/accessibility_service_config.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat: add manifest permissions and accessibility service config"
```

---

### Task 3: RootChecker

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/utils/RootChecker.kt`
- Create: `app/src/test/kotlin/com/denusklo/hooplandhelper/utils/RootCheckerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/denusklo/hooplandhelper/utils/RootCheckerTest.kt`:
```kotlin
package com.denusklo.hooplandhelper.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCheckerTest {

    @Test
    fun `isRooted returns true when su output contains uid=0`() {
        val checker = RootChecker(runSuId = { "uid=0(root) gid=0(root)" })
        assertTrue(checker.isRooted())
    }

    @Test
    fun `isRooted returns false when su output does not contain uid=0`() {
        val checker = RootChecker(runSuId = { "uid=10123(u0_a123)" })
        assertFalse(checker.isRooted())
    }

    @Test
    fun `isRooted returns false when su throws exception`() {
        val checker = RootChecker(runSuId = { throw RuntimeException("su not found") })
        assertFalse(checker.isRooted())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.RootCheckerTest"`
Expected: FAIL with `RootChecker` not found

- [ ] **Step 3: Implement RootChecker**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/utils/RootChecker.kt`:
```kotlin
package com.denusklo.hooplandhelper.utils

class RootChecker(private val runSuId: () -> String = ::defaultRunSuId) {

    fun isRooted(): Boolean = try {
        runSuId().contains("uid=0")
    } catch (e: Exception) {
        false
    }
}

private fun defaultRunSuId(): String {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
    val result = process.inputStream.bufferedReader().readLine() ?: ""
    process.destroy()
    return result
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.RootCheckerTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/utils/RootChecker.kt \
        app/src/test/kotlin/com/denusklo/hooplandhelper/utils/RootCheckerTest.kt
git commit -m "feat: add RootChecker with injectable su runner"
```

---

### Task 4: CalibrationData and CalibrationRepository

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/data/CalibrationData.kt`
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/data/CalibrationRepository.kt`
- Create: `app/src/test/kotlin/com/denusklo/hooplandhelper/data/CalibrationRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/denusklo/hooplandhelper/data/CalibrationRepositoryTest.kt`:
```kotlin
package com.denusklo.hooplandhelper.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CalibrationRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repo: CalibrationRepository

    @Before
    fun setUp() {
        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        whenever(editor.putFloat(any(), any())).thenReturn(editor)
        repo = CalibrationRepository(prefs)
    }

    @Test
    fun `isCalibrated returns false when bar region key missing`() {
        whenever(prefs.contains("bar_left")).thenReturn(false)
        assertFalse(repo.isCalibrated())
    }

    @Test
    fun `isCalibrated returns true when all keys present`() {
        whenever(prefs.contains("bar_left")).thenReturn(true)
        whenever(prefs.contains("green_hue")).thenReturn(true)
        whenever(prefs.contains("shoot_x")).thenReturn(true)
        assertTrue(repo.isCalibrated())
    }

    @Test
    fun `loadBarRegion returns null when not saved`() {
        whenever(prefs.contains("bar_left")).thenReturn(false)
        assertNull(repo.loadBarRegion())
    }

    @Test
    fun `loadBarRegion returns saved values`() {
        whenever(prefs.contains("bar_left")).thenReturn(true)
        whenever(prefs.getInt(eq("bar_left"), any())).thenReturn(10)
        whenever(prefs.getInt(eq("bar_top"), any())).thenReturn(20)
        whenever(prefs.getInt(eq("bar_right"), any())).thenReturn(800)
        whenever(prefs.getInt(eq("bar_bottom"), any())).thenReturn(40)
        val region = repo.loadBarRegion()
        assertNotNull(region)
        assertEquals(10, region!!.left)
        assertEquals(20, region.top)
        assertEquals(800, region.right)
        assertEquals(40, region.bottom)
    }

    @Test
    fun `loadShootPosition returns null when not saved`() {
        whenever(prefs.contains("shoot_x")).thenReturn(false)
        assertNull(repo.loadShootPosition())
    }

    @Test
    fun `loadShootPosition returns saved values`() {
        whenever(prefs.contains("shoot_x")).thenReturn(true)
        whenever(prefs.getInt(eq("shoot_x"), any())).thenReturn(500)
        whenever(prefs.getInt(eq("shoot_y"), any())).thenReturn(800)
        val pos = repo.loadShootPosition()
        assertNotNull(pos)
        assertEquals(500, pos!!.x)
        assertEquals(800, pos.y)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*.CalibrationRepositoryTest"`
Expected: FAIL — `CalibrationRepository` not found

- [ ] **Step 3: Implement CalibrationData**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/data/CalibrationData.kt`:
```kotlin
package com.denusklo.hooplandhelper.data

data class BarRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class HsvRange(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val hueTolerance: Float = 15f,
    val satMin: Float = 0.5f,
    val valMin: Float = 0.3f
)

data class ShootPosition(val x: Int, val y: Int)
```

- [ ] **Step 4: Implement CalibrationRepository**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/data/CalibrationRepository.kt`:
```kotlin
package com.denusklo.hooplandhelper.data

import android.content.SharedPreferences

class CalibrationRepository(private val prefs: SharedPreferences) {

    fun isCalibrated(): Boolean =
        prefs.contains("bar_left") && prefs.contains("green_hue") && prefs.contains("shoot_x")

    fun saveBarRegion(region: BarRegion) {
        prefs.edit()
            .putInt("bar_left", region.left)
            .putInt("bar_top", region.top)
            .putInt("bar_right", region.right)
            .putInt("bar_bottom", region.bottom)
            .apply()
    }

    fun loadBarRegion(): BarRegion? {
        if (!prefs.contains("bar_left")) return null
        return BarRegion(
            left = prefs.getInt("bar_left", 0),
            top = prefs.getInt("bar_top", 0),
            right = prefs.getInt("bar_right", 0),
            bottom = prefs.getInt("bar_bottom", 0)
        )
    }

    fun saveGreenHsv(hsv: HsvRange) {
        prefs.edit()
            .putFloat("green_hue", hsv.hue)
            .putFloat("green_sat", hsv.saturation)
            .putFloat("green_val", hsv.value)
            .apply()
    }

    fun loadGreenHsv(): HsvRange? {
        if (!prefs.contains("green_hue")) return null
        return HsvRange(
            hue = prefs.getFloat("green_hue", 120f),
            saturation = prefs.getFloat("green_sat", 0.7f),
            value = prefs.getFloat("green_val", 0.8f)
        )
    }

    fun saveShootPosition(pos: ShootPosition) {
        prefs.edit()
            .putInt("shoot_x", pos.x)
            .putInt("shoot_y", pos.y)
            .apply()
    }

    fun loadShootPosition(): ShootPosition? {
        if (!prefs.contains("shoot_x")) return null
        return ShootPosition(
            x = prefs.getInt("shoot_x", 0),
            y = prefs.getInt("shoot_y", 0)
        )
    }

    fun clearAll() = prefs.edit().clear().apply()
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "*.CalibrationRepositoryTest"`
Expected: 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/data/ \
        app/src/test/kotlin/com/denusklo/hooplandhelper/data/
git commit -m "feat: add CalibrationData models and CalibrationRepository"
```

---

### Task 5: IHoopService Interface

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/core/IHoopService.kt`

This interface decouples `TouchInjector` from `HoopAccessibilityService` (an Android class), making `TouchInjector` testable on the JVM.

- [ ] **Step 1: Create IHoopService**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/core/IHoopService.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

interface IHoopService {
    fun dispatchHoldGesture(x: Int, y: Int, durationMs: Long)
    fun cancelHoldGesture()
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/core/IHoopService.kt
git commit -m "feat: add IHoopService interface for testable touch injection"
```

---

### Task 6: GreenZoneDetector

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetector.kt`
- Create: `app/src/test/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetectorTest.kt`

The detector uses an injectable `isGreenPixel` function so tests run on the JVM without Android's `Color` class.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetectorTest.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.HsvRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreenZoneDetectorTest {

    // Raw ARGB int literals — no android.graphics.Color needed
    private val WHITE  = 0xFFFFFFFF.toInt()  // cursor (brightest)
    private val GREEN  = 0xFF00C800.toInt()  // perfect zone
    private val BROWN  = 0xFF8B5A2B.toInt()  // background

    private val greenHsv = HsvRange(hue = 120f, saturation = 0.8f, value = 0.8f)

    // Inject a simple pixel classifier: GREEN pixels are "green", others are not
    private val detector = GreenZoneDetector(
        greenHsv = greenHsv,
        isGreenPixel = { pixel -> pixel == GREEN }
    )

    // Strip: cursor (WHITE) at cursorX, GREEN zone from greenStart to greenEnd, BROWN elsewhere
    private fun makeStrip(width: Int, cursorX: Int, greenStart: Int, greenEnd: Int): (Int, Int) -> Int {
        return { x, _ ->
            when {
                x == cursorX -> WHITE
                x in greenStart..greenEnd -> GREEN
                else -> BROWN
            }
        }
    }

    @Test
    fun `returns true when cursor is on the green zone`() {
        // Cursor at x=10, green from x=8 to x=12 → cursor is inside
        val getPixel = makeStrip(width = 20, cursorX = 10, greenStart = 8, greenEnd = 12)
        assertTrue(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }

    @Test
    fun `returns false when cursor is not on the green zone`() {
        // Cursor at x=2, green from x=15 to x=18 → cursor is outside
        val getPixel = makeStrip(width = 20, cursorX = 2, greenStart = 15, greenEnd = 18)
        assertFalse(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }

    @Test
    fun `returns false when no cursor found (no bright pixel)`() {
        val getPixel: (Int, Int) -> Int = { _, _ -> BROWN }
        assertFalse(detector.isGreenZoneAtCursor(width = 20, height = 10, getPixel = getPixel))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.GreenZoneDetectorTest"`
Expected: FAIL — `GreenZoneDetector` not found

- [ ] **Step 3: Implement GreenZoneDetector**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetector.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import android.graphics.Color
import com.denusklo.hooplandhelper.data.HsvRange

class GreenZoneDetector(
    private val greenHsv: HsvRange,
    private val isGreenPixel: (Int) -> Boolean = { pixel -> defaultIsGreen(pixel, greenHsv) }
) {

    /**
     * @param width  width of the bar strip in pixels
     * @param height height of the bar strip in pixels
     * @param getPixel returns ARGB int for (x, y)
     * @return true if the cursor is currently over the green zone
     */
    fun isGreenZoneAtCursor(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): Boolean {
        val cursorX = findCursorX(width, height, getPixel)
        if (cursorX < 0) return false
        return isSampleGreen(height, cursorX, getPixel)
    }

    // Locate cursor as the brightest pixel on the middle row (bit-ops only — no Android Color)
    private fun findCursorX(width: Int, height: Int, getPixel: (Int, Int) -> Int): Int {
        val midY = height / 2
        var maxBrightness = 0
        var cursorX = -1
        for (x in 0 until width) {
            val p = getPixel(x, midY)
            val brightness = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (brightness > maxBrightness) {
                maxBrightness = brightness
                cursorX = x
            }
        }
        // Threshold: cursor must be clearly brighter than a mid-grey (brightness > 600 out of 765)
        return if (maxBrightness > 600) cursorX else -1
    }

    // Sample ±5px around cursorX on the middle row
    private fun isSampleGreen(height: Int, cursorX: Int, getPixel: (Int, Int) -> Int): Boolean {
        val midY = height / 2
        for (dx in -5..5) {
            val pixel = getPixel((cursorX + dx).coerceAtLeast(0), midY)
            if (isGreenPixel(pixel)) return true
        }
        return false
    }
}

// Default green check using Android's Color.colorToHSV — only called at runtime, not in tests
private fun defaultIsGreen(pixel: Int, greenHsv: HsvRange): Boolean {
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    val hueDiff = Math.abs(hsv[0] - greenHsv.hue).let { if (it > 180f) 360f - it else it }
    return hueDiff <= greenHsv.hueTolerance && hsv[1] >= greenHsv.satMin && hsv[2] >= greenHsv.valMin
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.GreenZoneDetectorTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetector.kt \
        app/src/test/kotlin/com/denusklo/hooplandhelper/core/GreenZoneDetectorTest.kt
git commit -m "feat: add GreenZoneDetector with injectable pixel classifier for testability"
```

---

### Task 7: TouchInjector

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/core/TouchInjector.kt`
- Create: `app/src/test/kotlin/com/denusklo/hooplandhelper/core/TouchInjectorTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/denusklo/hooplandhelper/core/TouchInjectorTest.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.utils.RootChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

class TouchInjectorTest {

    @Test
    fun `hold on rooted device runs root swipe shell command`() {
        val commands = mutableListOf<String>()
        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { "uid=0(root)" }),
            serviceProvider = { null },
            runShellCommand = { cmd -> commands.add(cmd) }
        )

        injector.hold(500, 800)

        assertEquals(1, commands.size)
        assertTrue(commands[0].contains("input swipe 500 800 500 800"))
    }

    @Test
    fun `release on rooted device kills hold and sends tap`() {
        val commands = mutableListOf<String>()
        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { "uid=0(root)" }),
            serviceProvider = { null },
            runShellCommand = { cmd -> commands.add(cmd) }
        )

        injector.hold(500, 800)
        injector.release()

        assertTrue(commands.any { it.contains("input tap 500 800") })
    }

    @Test
    fun `hold on non-rooted device delegates to IHoopService`() {
        var gestureDispatched = false
        val mockService = mock<IHoopService>()
        doAnswer { gestureDispatched = true }.whenever(mockService)
            .dispatchHoldGesture(any(), any(), any())

        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { throw RuntimeException("no su") }),
            serviceProvider = { mockService },
            runShellCommand = {}
        )

        injector.hold(500, 800)

        assertTrue(gestureDispatched)
    }

    @Test
    fun `release on non-rooted device calls cancelHoldGesture`() {
        var cancelled = false
        val mockService = mock<IHoopService>()
        doAnswer { cancelled = true }.whenever(mockService).cancelHoldGesture()

        val injector = TouchInjector(
            rootChecker = RootChecker(runSuId = { throw RuntimeException("no su") }),
            serviceProvider = { mockService },
            runShellCommand = {}
        )

        injector.hold(500, 800)
        injector.release()

        assertTrue(cancelled)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*.TouchInjectorTest"`
Expected: FAIL — `TouchInjector` not found

- [ ] **Step 3: Implement TouchInjector**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/core/TouchInjector.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.utils.RootChecker

class TouchInjector(
    private val rootChecker: RootChecker,
    private val serviceProvider: () -> IHoopService?,
    private val runShellCommand: (String) -> Unit = ::defaultRunShell
) {
    private var holdProcess: Process? = null
    private var holdX = 0
    private var holdY = 0

    fun hold(x: Int, y: Int, durationMs: Long = 3000L) {
        holdX = x
        holdY = y
        if (rootChecker.isRooted()) {
            holdViaRoot(x, y, durationMs)
        } else {
            serviceProvider()?.dispatchHoldGesture(x, y, durationMs)
        }
    }

    fun release() {
        if (rootChecker.isRooted()) {
            releaseViaRoot()
        } else {
            serviceProvider()?.cancelHoldGesture()
        }
    }

    private fun holdViaRoot(x: Int, y: Int, durationMs: Long) {
        Thread {
            holdProcess = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "input swipe $x $y $x $y $durationMs"))
            holdProcess?.waitFor()
        }.start()
    }

    private fun releaseViaRoot() {
        holdProcess?.destroy()
        holdProcess = null
        runShellCommand("su -c \"input tap $holdX $holdY\"")
    }
}

private fun defaultRunShell(cmd: String) {
    Runtime.getRuntime().exec(cmd).waitFor()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.TouchInjectorTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/core/TouchInjector.kt \
        app/src/test/kotlin/com/denusklo/hooplandhelper/core/TouchInjectorTest.kt
git commit -m "feat: add TouchInjector with root shell and IHoopService delegation"
```

---

### Task 8: ShotManager

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/core/ShotManager.kt`
- Create: `app/src/test/kotlin/com/denusklo/hooplandhelper/core/ShotManagerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/denusklo/hooplandhelper/core/ShotManagerTest.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.data.HsvRange
import com.denusklo.hooplandhelper.data.ShootPosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

class ShotManagerTest {

    private val shootPos = ShootPosition(500, 800)
    private val greenHsv = HsvRange(120f, 0.8f, 0.8f)

    private fun makeRepo(calibrated: Boolean): CalibrationRepository {
        val repo = mock<CalibrationRepository>()
        whenever(repo.isCalibrated()).thenReturn(calibrated)
        whenever(repo.loadShootPosition()).thenReturn(if (calibrated) shootPos else null)
        whenever(repo.loadGreenHsv()).thenReturn(if (calibrated) greenHsv else null)
        return repo
    }

    @Test
    fun `shoot calls onResult(false) immediately when not calibrated`() = runTest {
        val manager = ShotManager(
            touchInjector = mock(),
            calibration = makeRepo(calibrated = false),
            frameProvider = { null }
        )
        var result: Boolean? = null
        manager.shoot { result = it }
        assertFalse(result!!)
    }

    @Test
    fun `shoot holds then releases and returns true when green detected`() = runTest {
        var held = false
        var released = false
        val injector = mock<TouchInjector>()
        doAnswer { held = true }.whenever(injector).hold(any(), any(), any())
        doAnswer { released = true }.whenever(injector).release()

        // GREEN_PIXEL = 0xFF00C800 — injected isGreenPixel in ShotManager's detector will recognise it
        val greenPixel = 0xFF00C800.toInt()
        val manager = ShotManager(
            touchInjector = injector,
            calibration = makeRepo(calibrated = true),
            frameProvider = { Triple(10, 5, { _: Int, _: Int -> greenPixel }) },
            isGreenPixelOverride = { it == greenPixel }
        )

        var result: Boolean? = null
        manager.shoot { result = it }

        assertTrue(held)
        assertTrue(released)
        assertTrue(result!!)
    }

    @Test
    fun `shoot returns false on timeout when no green detected`() = runTest {
        val manager = ShotManager(
            touchInjector = mock(),
            calibration = makeRepo(calibrated = true),
            frameProvider = { Triple(10, 5, { _: Int, _: Int -> 0xFF000000.toInt() }) }, // all black
            timeoutMs = 50L
        )
        var result: Boolean? = null
        manager.shoot { result = it }
        assertFalse(result!!)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*.ShotManagerTest"`
Expected: FAIL — `ShotManager` not found

- [ ] **Step 3: Implement ShotManager**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/core/ShotManager.kt`:
```kotlin
package com.denusklo.hooplandhelper.core

import com.denusklo.hooplandhelper.data.CalibrationRepository
import kotlinx.coroutines.*

typealias FrameProvider = () -> Triple<Int, Int, (Int, Int) -> Int>?

class ShotManager(
    private val touchInjector: TouchInjector,
    private val calibration: CalibrationRepository,
    private val frameProvider: FrameProvider,
    private val timeoutMs: Long = 3000L,
    private val isGreenPixelOverride: ((Int) -> Boolean)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var isRunning = false

    fun shoot(onResult: (Boolean) -> Unit) {
        if (!calibration.isCalibrated()) { onResult(false); return }
        if (isRunning) return
        isRunning = true

        val shootPos = calibration.loadShootPosition()!!
        val greenHsv = calibration.loadGreenHsv()!!
        val detector = if (isGreenPixelOverride != null) {
            GreenZoneDetector(greenHsv, isGreenPixelOverride)
        } else {
            GreenZoneDetector(greenHsv)
        }

        scope.launch {
            touchInjector.hold(shootPos.x, shootPos.y, timeoutMs)
            val deadline = System.currentTimeMillis() + timeoutMs
            var detected = false

            while (System.currentTimeMillis() < deadline) {
                val frame = frameProvider()
                if (frame != null) {
                    val (w, h, getPixel) = frame
                    if (detector.isGreenZoneAtCursor(w, h, getPixel)) {
                        detected = true
                        break
                    }
                }
                delay(16L) // ~60 fps
            }

            touchInjector.release()
            isRunning = false
            withContext(Dispatchers.Main) { onResult(detected) }
        }
    }

    fun cancel() {
        if (isRunning) {
            touchInjector.release()
            isRunning = false
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.ShotManagerTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Run all tests so far**

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/core/ShotManager.kt \
        app/src/test/kotlin/com/denusklo/hooplandhelper/core/ShotManagerTest.kt
git commit -m "feat: add ShotManager with coroutine detection loop and timeout"
```

---

### Task 9: HoopAccessibilityService

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/service/HoopAccessibilityService.kt`

Implements `IHoopService` using 50ms chained `GestureDescription` strokes. Calling `cancelHoldGesture()` stops the chain, ending the touch input. No unit test — requires Android instrumentation; verify manually on device.

- [ ] **Step 1: Implement HoopAccessibilityService**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/service/HoopAccessibilityService.kt`:
```kotlin
package com.denusklo.hooplandhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.denusklo.hooplandhelper.core.IHoopService

class HoopAccessibilityService : AccessibilityService(), IHoopService {

    companion object {
        var instance: HoopAccessibilityService? = null
            private set
    }

    private val CHUNK_MS = 50L
    private var isHolding = false
    private var holdX = 0f
    private var holdY = 0f
    private var prevStroke: GestureDescription.StrokeDescription? = null

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun dispatchHoldGesture(x: Int, y: Int, durationMs: Long) {
        holdX = x.toFloat()
        holdY = y.toFloat()
        isHolding = true
        dispatchChunk(isFirst = true)
    }

    override fun cancelHoldGesture() {
        isHolding = false
    }

    private fun dispatchChunk(isFirst: Boolean) {
        if (!isHolding) return
        val path = Path().apply { moveTo(holdX, holdY) }
        val stroke = if (isFirst || prevStroke == null) {
            GestureDescription.StrokeDescription(path, 0, CHUNK_MS, true)
        } else {
            prevStroke!!.continueStroke(path, 0, CHUNK_MS, true)
        }
        prevStroke = stroke
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isHolding) dispatchChunk(isFirst = false)
            }
        }, null)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/service/HoopAccessibilityService.kt
git commit -m "feat: add HoopAccessibilityService with chained gesture hold/release"
```

---

### Task 10: ScreenCaptureService

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/service/ScreenCaptureService.kt`

Plain class (not an Android Service) — `OverlayService` owns and manages its lifecycle.

- [ ] **Step 1: Implement ScreenCaptureService**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/service/ScreenCaptureService.kt`:
```kotlin
package com.denusklo.hooplandhelper.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.denusklo.hooplandhelper.data.BarRegion

class ScreenCaptureService(private val context: Context) {

    companion object {
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var barRegion: BarRegion? = null

    fun start(resultCode: Int, data: android.content.Intent, region: BarRegion) {
        barRegion = region
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HoopCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        instance = this
    }

    fun stop() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
        instance = null
    }

    /**
     * Returns the latest frame cropped to barRegion as a pixel-provider triple,
     * or null if no frame is available.
     */
    fun acquireBarFrame(): Triple<Int, Int, (Int, Int) -> Int>? {
        val region = barRegion ?: return null
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val buffer = plane.buffer

            val width = region.right - region.left
            val height = region.bottom - region.top
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val offset = (region.top + y) * rowStride + (region.left + x) * pixelStride
                    buffer.position(offset)
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    bitmap.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                }
            }
            Triple(width, height, bitmap::getPixel)
        } finally {
            image.close()
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/service/ScreenCaptureService.kt
git commit -m "feat: add ScreenCaptureService for MediaProjection bar frame capture"
```

---

### Task 11: OverlayService

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/service/OverlayService.kt`
- Create: `app/src/main/res/layout/overlay_button.xml`
- Create: `app/src/main/res/drawable/circle_button.xml`

- [ ] **Step 1: Create circle drawable**

Create `app/src/main/res/drawable/circle_button.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#FF888888" />
</shape>
```

- [ ] **Step 2: Create overlay layout**

Create `app/src/main/res/layout/overlay_button.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="72dp"
    android:layout_height="72dp">

    <TextView
        android:id="@+id/btn_auto"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:layout_gravity="center"
        android:background="@drawable/circle_button"
        android:gravity="center"
        android:text="AUTO"
        android:textColor="#FFFFFF"
        android:textSize="11sp"
        android:textStyle="bold" />

    <ImageView
        android:id="@+id/btn_settings"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:layout_gravity="top|end"
        android:src="@android:drawable/ic_menu_manage"
        android:tint="#CCCCCC" />

</FrameLayout>
```

- [ ] **Step 3: Implement OverlayService**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/service/OverlayService.kt`:
```kotlin
package com.denusklo.hooplandhelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.core.ShotManager
import com.denusklo.hooplandhelper.core.TouchInjector
import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.ui.CalibrationActivity
import com.denusklo.hooplandhelper.utils.RootChecker

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "hoopland_overlay"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var btnAuto: TextView
    private lateinit var shotManager: ShotManager
    private lateinit var screenCapture: ScreenCaptureService

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupShotManager()
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) {
            val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
            val region = CalibrationRepository(prefs).loadBarRegion()
            if (region != null) {
                screenCapture.start(resultCode, data, region)
            }
        }
        return START_NOT_STICKY
    }

    private fun setupShotManager() {
        screenCapture = ScreenCaptureService(this)
        val prefs = getSharedPreferences("calibration", Context.MODE_PRIVATE)
        val calibration = CalibrationRepository(prefs)
        val injector = TouchInjector(
            rootChecker = RootChecker(),
            serviceProvider = { HoopAccessibilityService.instance }
        )
        shotManager = ShotManager(
            touchInjector = injector,
            calibration = calibration,
            frameProvider = { screenCapture.acquireBarFrame() }
        )
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        btnAuto = overlayView.findViewById(R.id.btn_auto)

        overlayView.findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 100 }

        overlayView.setOnTouchListener { _, event -> handleTouch(event, params) }
        windowManager.addView(overlayView, params)
    }

    private fun handleTouch(event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x; initialY = params.y
                initialTouchX = event.rawX; initialTouchY = event.rawY
                isDragging = false; true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                if (isDragging) {
                    params.x = initialX + dx; params.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, params)
                }
                true
            }
            MotionEvent.ACTION_UP -> { if (!isDragging) onAutoTapped(); true }
            else -> false
        }
    }

    private fun onAutoTapped() {
        setButtonColor(Color.YELLOW)
        shotManager.shoot { success ->
            setButtonColor(if (success) Color.GREEN else Color.RED)
            overlayView.postDelayed({ setButtonColor(Color.GRAY) }, 500)
        }
    }

    private fun setButtonColor(color: Int) {
        btnAuto.background?.mutate()?.setTint(color)
    }

    override fun onDestroy() {
        screenCapture.stop()
        if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "HoopLand Helper", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HoopLand Helper")
            .setContentText("Auto-shoot active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/service/OverlayService.kt \
        app/src/main/res/layout/overlay_button.xml \
        app/src/main/res/drawable/circle_button.xml
git commit -m "feat: add OverlayService with draggable floating button and shot state colors"
```

---

### Task 12: MainActivity

**Files:**
- Modify: `app/src/main/kotlin/com/denusklo/hooplandhelper/ui/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

- [ ] **Step 1: Replace activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/tv_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Checking permissions..."
        android:textSize="16sp"
        android:textAlignment="center"
        android:layout_marginBottom="24dp" />

    <Button
        android:id="@+id/btn_grant_overlay"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Grant Overlay Permission"
        android:visibility="gone" />

    <Button
        android:id="@+id/btn_grant_accessibility"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Enable Accessibility Service (non-root only)"
        android:layout_marginTop="12dp"
        android:visibility="visible" />

    <Button
        android:id="@+id/btn_start"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Start HoopLand Helper"
        android:layout_marginTop="24dp"
        android:visibility="gone" />

    <Button
        android:id="@+id/btn_calibrate"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Calibrate"
        android:layout_marginTop="12dp" />

</LinearLayout>
```

- [ ] **Step 2: Replace MainActivity.kt**

```kotlin
package com.denusklo.hooplandhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.data.CalibrationRepository
import com.denusklo.hooplandhelper.service.OverlayService

class MainActivity : AppCompatActivity() {

    private val MEDIA_PROJECTION_REQUEST = 1001
    private lateinit var tvStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        btnStart = findViewById(R.id.btn_start)

        btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.btn_grant_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener { requestMediaProjection() }

        findViewById<Button>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val hasOverlay = Settings.canDrawOverlays(this)
        val isCalibrated = CalibrationRepository(
            getSharedPreferences("calibration", Context.MODE_PRIVATE)
        ).isCalibrated()

        btnGrantOverlay.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        btnStart.visibility = if (hasOverlay) View.VISIBLE else View.GONE
        tvStatus.text = when {
            !hasOverlay -> "Grant overlay permission first."
            !isCalibrated -> "Overlay granted. Please calibrate before starting."
            else -> "Ready. Open Hoop Land, then tap Start."
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MEDIA_PROJECTION_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            })
            finish()
        }
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/ui/MainActivity.kt \
        app/src/main/res/layout/activity_main.xml
git commit -m "feat: add MainActivity with permission flow and media projection launch"
```

---

### Task 13: CalibrationActivity

**Files:**
- Create: `app/src/main/kotlin/com/denusklo/hooplandhelper/ui/CalibrationActivity.kt`
- Create: `app/src/main/res/layout/activity_calibration.xml`

- [ ] **Step 1: Create calibration layout**

Create `app/src/main/res/layout/activity_calibration.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#CC000000">

    <TextView
        android:id="@+id/tv_instruction"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal"
        android:gravity="center"
        android:padding="24dp"
        android:text="Tap the LEFT edge of the shooting meter bar"
        android:textColor="#FFFFFF"
        android:textSize="18sp" />

    <View
        android:id="@+id/crosshair"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:background="#FFFF0000"
        android:visibility="gone" />

    <Button
        android:id="@+id/btn_reset"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="32dp"
        android:backgroundTint="#FF444444"
        android:text="Reset Calibration"
        android:textColor="#FFFFFF" />

</FrameLayout>
```

- [ ] **Step 2: Implement CalibrationActivity**

Create `app/src/main/kotlin/com/denusklo/hooplandhelper/ui/CalibrationActivity.kt`:
```kotlin
package com.denusklo.hooplandhelper.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.data.*

class CalibrationActivity : AppCompatActivity() {

    private enum class Step { BAR_LEFT, BAR_RIGHT, GREEN_COLOR, SHOOT_BUTTON, DONE }

    private lateinit var tvInstruction: TextView
    private lateinit var crosshair: View
    private lateinit var repo: CalibrationRepository

    private var step = Step.BAR_LEFT
    private var barLeft = 0; private var barTop = 0
    private var barRight = 0; private var barBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        repo = CalibrationRepository(getSharedPreferences("calibration", Context.MODE_PRIVATE))
        tvInstruction = findViewById(R.id.tv_instruction)
        crosshair = findViewById(R.id.crosshair)

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            repo.clearAll()
            step = Step.BAR_LEFT
            updateInstruction()
            Toast.makeText(this, "Calibration cleared", Toast.LENGTH_SHORT).show()
        }

        window.decorView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) handleTap(event.rawX.toInt(), event.rawY.toInt())
            true
        }
    }

    private fun handleTap(x: Int, y: Int) {
        showCrosshair(x, y)
        when (step) {
            Step.BAR_LEFT  -> { barLeft = x; barTop = y; step = Step.BAR_RIGHT }
            Step.BAR_RIGHT -> {
                barRight = x; barBottom = y + 20
                repo.saveBarRegion(BarRegion(barLeft, barTop, barRight, barBottom))
                step = Step.GREEN_COLOR
            }
            Step.GREEN_COLOR -> {
                // Sample the pixel from the live screen if capture is running; default to pure green
                val sampledColor = com.denusklo.hooplandhelper.service.ScreenCaptureService.instance
                    ?.acquireBarFrame()
                    ?.let { (_, _, getPixel) -> getPixel(x - barLeft, y - barTop) }
                    ?: Color.rgb(0, 200, 0)
                val hsv = FloatArray(3)
                Color.colorToHSV(sampledColor, hsv)
                repo.saveGreenHsv(HsvRange(hue = hsv[0], saturation = hsv[1], value = hsv[2]))
                step = Step.SHOOT_BUTTON
            }
            Step.SHOOT_BUTTON -> {
                repo.saveShootPosition(ShootPosition(x, y))
                step = Step.DONE
            }
            Step.DONE -> {}
        }
        updateInstruction()
        if (step == Step.DONE) {
            Toast.makeText(this, "Calibration complete!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showCrosshair(x: Int, y: Int) {
        crosshair.visibility = View.VISIBLE
        crosshair.x = x.toFloat() - 10
        crosshair.y = y.toFloat() - 10
    }

    private fun updateInstruction() {
        tvInstruction.text = when (step) {
            Step.BAR_LEFT    -> "Tap the LEFT edge of the shooting meter bar"
            Step.BAR_RIGHT   -> "Tap the RIGHT edge of the shooting meter bar"
            Step.GREEN_COLOR -> "Tap a GREEN pixel in the meter (the perfect zone color)"
            Step.SHOOT_BUTTON-> "Tap the SHOOT button in Hoop Land"
            Step.DONE        -> "Done!"
        }
    }
}
```

- [ ] **Step 3: Final build and all tests**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/denusklo/hooplandhelper/ui/CalibrationActivity.kt \
        app/src/main/res/layout/activity_calibration.xml
git commit -m "feat: add CalibrationActivity with 4-step tap calibration flow"
```

---

### Task 14: Push to GitHub

- [ ] **Step 1: Verify remote is set**

Run: `git remote -v`
Expected: `origin  https://github.com/denusklo/HoopLandHelper.git`

- [ ] **Step 2: Push**

Run: `git push -u origin master`
Expected: All commits pushed to `https://github.com/denusklo/HoopLandHelper`

---

## Manual Verification Checklist (On Device)

After installing the APK on your Android 10 phone:

1. Grant **overlay permission** (prompted in MainActivity)
2. Enable **HoopLand Helper** in Accessibility Settings (for non-root path — skip if rooted)
3. Tap **Calibrate** and complete the 4 steps with Hoop Land open
4. Tap **Start** and grant the screen capture permission
5. Open Hoop Land — the grey AUTO button should appear
6. Move your player into shooting position, then tap AUTO
7. Button turns **yellow** (scanning) → should turn **green** (perfect shot released)
