// Top-level build file.
buildscript {
    dependencies {
        // AGP 9's built-in Kotlin ships KGP 2.2.10; pin the newer KGP/KSP on the
        // classpath here (the documented AGP 9 mechanism) — ticket 075.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10")
    }
}

plugins {
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin via built-in Kotlin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
