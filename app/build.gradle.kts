plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.cs423application"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.cs423application"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
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
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // imported libraries
    implementation("io.coil-kt:coil:2.7.0") // coil, used for image loading
    implementation("io.coil-kt:coil-compose:2.7.0") // AsyncImage composable
    implementation("androidx.exifinterface:exifinterface:1.4.2") // exifInterface, auto-rotates portraits
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4") // image state safety
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4") // viewModel() in composables
    implementation(libs.androidx.lifecycle.runtime.ktx.v284) // same as above
    implementation(libs.android.image.cropper) // android-image-cropper, for cropping function. replaced uCrop
    implementation(libs.gpuimage) // gpuimage, blur and sharpen
    implementation("androidx.navigation:navigation-compose:2.7.7") // navigation for ui
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") // kotlin coroutine for background processes
    // opencv -- LOOK AT README FOR INSTRUCTIONS
    implementation("org.opencv:opencv:4.10.0")
// commented out for time being; breaks current pipeline
}