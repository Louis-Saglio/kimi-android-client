package fr.louissaglio.kimi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Static brand scheme — dynamic color is deliberately not used: this app is a
// companion to one specific service, so Material You wallpaper tinting would
// defeat brand parity with the web client.
private val kimiLightColorScheme = lightColorScheme(
    primary = KimiBlueLightPrimary,
    primaryContainer = KimiBlueLightContainer,
    onPrimaryContainer = KimiBlueLightOnContainer,
)

private val kimiDarkColorScheme = darkColorScheme(
    primary = KimiBlueDarkPrimary,
    onPrimary = KimiBlueDarkOnPrimary,
    primaryContainer = KimiBlueDarkContainer,
    onPrimaryContainer = KimiBlueDarkOnContainer,
)

// M3 has no success role; expose one as a theme extension for ✓-style glyphs.
val LocalSuccessColor = staticCompositionLocalOf { KimiSuccessLight }

val MaterialTheme.successColor: Color
    @Composable
    get() = LocalSuccessColor.current

@Composable
fun KimiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSuccessColor provides if (darkTheme) KimiSuccessDark else KimiSuccessLight,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) kimiDarkColorScheme else kimiLightColorScheme,
            content = content,
        )
    }
}
