package fr.louissaglio.kimi.android

import android.app.Application
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.compose.runtime.Composer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KimiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Group keys let the uploaded R8 mapping deobfuscate composition stack
        // traces from the minified release build in Crashlytics (Compose 1.10+).
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.GroupKeys)
    }
}
