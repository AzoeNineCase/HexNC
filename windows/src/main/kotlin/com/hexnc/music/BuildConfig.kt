package com.hexnc.music

/**
 * Stand-in for the AGP-generated `BuildConfig` that the Android build gets for free.
 *
 * `ui/screens/SettingsScreen.kt` shows the version in its About card; keeping the same name means
 * that screen is unchanged between the two builds. Bump this together with `version` in
 * `build.gradle.kts` and the Android `app/build.gradle.kts`.
 */
object BuildConfig {
    const val VERSION_NAME = "1.1.0"
    const val VERSION_CODE = 2
    const val DEBUG = false
}
