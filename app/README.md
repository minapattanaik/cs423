todo: actual readme

## OpenCV Setup

This project will use OpenCV for object selection. It must be added manually.
The steps to do so can be found below.

### 1. Download OpenCV

Download the Android SDK from: https://opencv.org/releases/
Choose the latest OpenCV Android SDK.

### 2. Import OpenCV Module into Android Studio

1. Extract the downloaded zip file.
2. Open this project in Android Studio.
3. Go to:

```
File → New → Import Module
```

4. Select the folder:

```
opencv-android-sdk/sdk/java
```

5. Name the module:

```
opencv
```

6. Finish the import.

### 3. Verify Dependency

Ensure the following line exists in `app/build.gradle.kts`:

```kotlin
implementation(project(":opencv"))
```

---

### 4. Sync Project

Click **Sync Gradle** and rebuild the project.

If setup was successful, the project should compile without errors.
