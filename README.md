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
3. Make sure the OpenCV Android SDK is linked (the app/opencv/ module is included)
4. Run on a physical device or emulator with API 29+

If OpenCV is not linked, delete the folder and follow the below instructions:
### OpenCV Setup

This project will use OpenCV for object selection. It must be added manually.
The steps to do so can be found below.

#### 1. Download OpenCV

Download the Android SDK from: https://opencv.org/releases/
Choose the latest OpenCV Android SDK.

#### a. Import OpenCV Module into Android Studio

1. Extract the downloaded zip file.
2. Open this project in Android Studio.
3. Go to:

```
File → New → Import Module
```

#### b. Select the folder:

```
opencv-android-sdk/sdk/java
```

#### c. Name the module:

```
opencv
```

#### d. Finish the import.

### 3. Verify Dependency

Ensure the following line exists in `app/build.gradle.kts`:

```kotlin
implementation(project(":opencv"))
```

---

### 4. Sync Project

Click **Sync Gradle** and rebuild the project.

If setup was successful, the project should compile without errors.
