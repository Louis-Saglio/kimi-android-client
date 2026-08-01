import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9 built-in Kotlin compiles Kotlin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "fr.louissaglio.kimi.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "fr.louissaglio.kimi.android"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // android.util.Log calls (data-layer warnings) no-op in local unit tests.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            // No release keystore yet: sign with the debug config so the APK is installable.
            signingConfig = signingConfigs.getByName("debug")
            // Small APK for slow-network installs; smoke-tested via Firebase robo.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.mikepenz.markdown.renderer.m3)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver3)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
