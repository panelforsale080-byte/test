# Humanoid Detector (Android / Kotlin)

A **detection + visual overlay** app. It captures your own screen
(MediaProjection), finds people with an on-device ML model (MediaPipe
ObjectDetector / efficientdet_lite0), and draws a bounding box over each
person in a transparent overlay.

**What it does NOT do:** it never injects touches, never moves a cursor or
crosshair, and sends no input to any app. It only reads pixels and draws
boxes. This makes it safe to use for testing your own apps, accessibility
experiments, or computer-vision learning.

## Architecture

```
MainActivity  →  requests overlay + MediaProjection permissions
      │
      ▼
CaptureService (foreground service, type mediaProjection)
      │  MediaProjection + VirtualDisplay + ImageReader  (~10 fps)
      ▼
Detector (MediaPipe ObjectDetector, person class only)
      │  optional HSV color filter (secondary emphasis)
      ▼
OverlayView (TYPE_APPLICATION_OVERLAY, draws boxes only)
```

- `MainActivity.kt` — permission flow + start/stop buttons
- `CaptureService.kt` — screen capture pipeline (foreground service)
- `Detector.kt` — MediaPipe person detection + optional HSV color emphasis
- `OverlayView.kt` — transparent overlay that draws the boxes

## Build & run

1. Open the project in Android Studio (Ladybug or newer).
2. Let Gradle sync (AGP 8.5.2 / Kotlin 2.0.20 / Gradle 8.9).
3. Run on a device/emulator with API 26+ (Android 8.0+).
4. In the app:
   - Tap **Grant overlay permission** and allow "Display over other apps".
   - Tap **Start detection**, then accept the "record screen" dialog.
5. Open any other app — people in it get a cyan box; if the optional color
   filter is enabled, matching targets get a red box.

## Model

`app/src/main/assets/efficientdet_lite0.tflite` (float32, ~4.6 MB) is bundled.
If it is missing, download it from:
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite

## Optional color filter

Color-only blobs are unreliable for detecting people (skin/clothes vary too
much), so the ML model does the detection and HSV color is only a secondary
emphasis. Toggle `Detector.colorFilterEnabled` and set `hueTarget` /
`hueTolerance` to highlight people whose center pixel falls in a hue range
(e.g. red = 0, green = 120, blue = 240).

## Notes

- Android 14+ requires the `mediaProjection` foreground-service type and the
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission (already in the manifest).
- The overlay is `FLAG_NOT_TOUCHABLE`, so it never intercepts touches.
