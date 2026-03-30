# HoopLand Helper — Design Spec
**Date:** 2026-03-30
**Status:** Approved

---

## Overview

An Android overlay app that automatically executes a perfect shot in the mobile game **Hoop Land**. The shoot mechanic requires holding a button while a cursor moves across a bar at the top of the screen, then releasing when the cursor hits the green zone. This app automates that: the user taps one floating button, the app holds the shoot input, detects the green zone in real time, and releases at the perfect moment.

**Target platform:** Android 10+ (API 29), rooted and non-rooted support.
**Future:** iOS support planned, not in scope for this phase.

---

## Architecture

Three main components coordinated by a central ShotManager:

```
Overlay UI (floating button)
        │
        ▼
   ShotManager
        │
   ┌────┴────┐
   │         │
Screen     Touch
Watcher   Injector
(MediaPro- (root shell
 jection)   or A11y)
```

**Flow:**
1. User taps the floating AUTO button
2. ShotManager tells TouchInjector to HOLD the Shoot button position on screen
3. ScreenWatcher starts capturing frames via MediaProjection, cropping to the top bar region
4. GreenZoneDetector analyzes each frame — finds cursor position, checks surrounding pixels for green color range
5. When green detected: ShotManager tells TouchInjector to RELEASE
6. Result: perfect shot

**Timeout:** If no green zone is detected within 3 seconds, auto-release to prevent stuck input.

---

## Overlay UI

A minimal draggable floating button rendered via `WindowManager` on top of Hoop Land.

- **Shape:** Circular button labeled "AUTO"
- **Draggable:** User can reposition anywhere on screen
- **Default position:** Top-left corner (away from game controls)
- **State colors:**
  - Grey — idle/ready
  - Yellow — holding, scanning for green zone
  - Green flash — released at perfect moment
  - Red flash — timeout, no green zone found
- **Settings gear icon:** Opens calibration flow

---

## Calibration

One-time setup via `CalibrationActivity` accessed from the settings gear:

1. **Bar region:** User taps the left edge then right edge of the shooting meter bar on screen. Defines the pixel scan region.
2. **Green color sample:** User taps a pixel on the green zone. App stores the HSV color range (hue ±15, saturation >50%, value >30%) to tolerate lighting variation.
3. **Shoot button position:** User taps the Shoot button location so the app knows where to simulate the hold touch.

Calibration data is persisted in SharedPreferences.

---

## Green Zone Detection

**Per-frame logic (runs at ~60fps):**
1. Capture screen frame via MediaProjection
2. Crop bitmap to the calibrated bar region (small area = fast)
3. Scan horizontally for cursor color (distinct from background)
4. At cursor position, sample surrounding pixels (±5px)
5. Convert sampled pixels to HSV
6. If HSV falls within green color range → trigger release

**Performance:** Cropping to a small region (e.g., 800×20px strip) keeps analysis fast enough to not miss the green zone window (~100-200ms).

---

## Touch Injection

**Rooted devices (preferred):**
- Detect root via `su` command availability
- HOLD: `su -c "input swipe <shootX> <shootY> <shootX> <shootY> 3000"` (3s max hold)
- RELEASE: Cancel via a second `input tap` or let ShotManager interrupt the swipe
- Faster and more reliable than Accessibility Service

**Non-rooted devices (fallback):**
- `BIND_ACCESSIBILITY_SERVICE` permission
- HOLD: `AccessibilityService.dispatchGesture()` with a long-duration `GestureDescription`
- RELEASE: Dispatch a short cancellation gesture

`TouchInjector.kt` detects root at runtime and selects the appropriate path.

---

## Project Structure

```
app/src/main/
├── service/
│   ├── OverlayService.kt         # Floating button, WindowManager
│   ├── ScreenCaptureService.kt   # MediaProjection frame capture
│   └── HoopAccessibilityService.kt  # Non-root touch injection
├── core/
│   ├── ShotManager.kt            # Coordinates full shot flow
│   ├── GreenZoneDetector.kt      # Per-frame pixel color analysis
│   └── TouchInjector.kt          # Root vs non-root injection logic
├── ui/
│   ├── MainActivity.kt           # Permissions, onboarding
│   └── CalibrationActivity.kt    # Bar region + color sampling
└── utils/
    └── RootChecker.kt            # Detects root availability
```

---

## Permissions

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw floating button over other apps |
| `FOREGROUND_SERVICE` | Keep service alive while game runs |
| `MediaProjection` (runtime) | Capture screen frames |
| `BIND_ACCESSIBILITY_SERVICE` | Touch injection on non-rooted devices |

---

## Out of Scope (This Phase)

- iOS support
- Multiple game support (Hoop Land only)
- Auto-movement or pass automation
- Network/cloud features
