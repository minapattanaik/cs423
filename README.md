# Touchscreen Image Editing App

An Android app that lets users pick a photo and edit it using hand-drawn gestures, eliminating the need for buttons in the actual editing. The app uses the $P Point-Cloud Recognizer to classify what users  draw on top of the image and applies the appropriate OpenCV operation.

## Features
- Pick any image from your gallery. EXIF orientation is automatically corrected on load
- Gesture-based editing. Draw directly on the image to trigger operations:
  - Rectangle → opens the crop tool pre-seeded with your drawn region
  - X (two diagonal strokes) → erases the selected region using GrabCut + TELEA inpainting
  - Left arrow → applies Gaussian blur (arrow length controls kernel size)
  - Right arrow → applies a sharpening convolution (arrow length controls strength)
- Undo. Single-level undo stack for all destructive edits.
- Save. Exports the result as a JPEG to `Pictures/CS423/` via MediaStore.
## Tech Stack
- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Image processing: OpenCV
- Gesture recognition: $P Point-Cloud Recognizer
- Crop UI: Android Image Cropper
- Image loading: Coil

## How Gesture Recognition Works
Each finger drag is captured as a sequence of screen coordinates. When the drag ends, the points are passed to PDollarRecognizer, which:

- Resamples the stroke to 64 evenly-spaced points
- Scales the bounding box uniformly so the largest dimension = 1
- Translates the centroid to the origin
- Greedy-matches against stored templates (rectangle, X, left arrow, right arrow) using weighted nearest-neighbor distance
- X gestures require two separate strokes. The first stroke is held in memory while the app waits for the second.

## Project Structure
```
app/src/main/java/com/example/cs423application/
├── MainActivity.kt        # Compose UI, gesture capture, crop launcher
├── ImageViewModel.kt      # State, gesture dispatch, OpenCV calls
├── ImagePipeline.kt       # EXIF correction, MediaStore save
└── PDollarRecognizer.kt   # $P algorithm + gesture templates
```

## Setup

1. Clone the repo
2. Open in Android Studio (Hedgehog or later)
3. Follow the instructions below to set up OpenCV.

### OpenCV Setup

OpenCV is not included in the repo and must be added manually. Follow the steps below exactly.

#### 1. Download OpenCV

Download the Android SDK from: https://opencv.org/releases/
Choose the **OpenCV Android SDK** (not the iOS or source package).

#### 2. Extract into the project root

Extract the downloaded zip **into the root of this project** (next to `app/`, `gradle/`, etc.).

After extraction, your project root should contain a folder like:
```
opencv-4.x.x-android-sdk/
└── OpenCV-android-sdk/
    └── sdk/
```

#### 3. Import the OpenCV module into Android Studio

1. In Android Studio, go to `File → New → Import Module`
2. Select the following folder (adjust version number to match your download):
```
opencv-4.x.x-android-sdk/OpenCV-android-sdk/sdk
```
3. When prompted for a module name, enter:
```
opencv
```
4. Click **Finish**.

#### 4. Configure `settings.gradle.kts`

Ensure both of these lines exist in `settings.gradle.kts`:

```kotlin
include(":opencv")
project(":opencv").projectDir = file("opencv-4.x.x-android-sdk/OpenCV-android-sdk/sdk")
```

Replace `4.x.x` with your actual version number.

#### 5. Configure `app/build.gradle.kts`

In `app/build.gradle.kts`, **uncomment** the local module dependency and **comment out** the Maven one:

```kotlin
implementation(project(":opencv"))   // uncomment this
// implementation("org.opencv:opencv:4.10.0")  // comment this out
```

#### 6. Sync and build

Click **Sync Now** in the Gradle bar (or go to `File → Sync Project with Gradle Files`), then rebuild the project.

If setup was successful, the project should compile without errors. **Minimum supported API level is 28.**
