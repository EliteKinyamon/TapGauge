// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build files.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 moved the Compose compiler into its own Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // KSP is used for Room's annotation processing (faster than kapt).
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
