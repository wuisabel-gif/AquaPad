pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aquapad"

// Pure-Kotlin modules — build and run anywhere with a JDK (no Android SDK needed).
include(":protocol")
include(":sim")

// The Android app. Requires the Android SDK + AGP (sdk.dir in local.properties).
include(":app")
