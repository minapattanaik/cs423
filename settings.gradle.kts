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
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "CS 423 Application"
include(":app")
// Uncomment the two lines below if using the local OpenCV module instead of the Maven dependency.
// See README for full setup instructions.
// include(":opencv")
// project(":opencv").projectDir = file("opencv-4.12.0-android-sdk/OpenCV-android-sdk/sdk")
