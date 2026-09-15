package fr.louissaglio.kimi.android.ui.theme

import androidx.compose.ui.graphics.Color

// Palette seeded from the Kimi web client (apps/kimi-web/src/style.css in
// MoonshotAI/kimi-code). Brand blue = KMBlue #1783ff; --blue2 is the web's
// darker hover shade and is used as the light primary because #1783ff itself
// fails WCAG AA (3.7:1) against a white onPrimary. Dark theme uses the web
// client's dark palette verbatim.
internal val KimiBlueLightPrimary = Color(0xFF0F6FE0) // web --blue2 (4.8:1 on white)
internal val KimiBlueLightContainer = Color(0xFFE8F3FF) // web --soft (user bubble)
internal val KimiBlueLightOnContainer = Color(0xFF0A4FA3) // 7.0:1 on the container

internal val KimiBlueDarkPrimary = Color(0xFF58A6FF) // web dark --blue
internal val KimiBlueDarkOnPrimary = Color(0xFF00315F) // 5.2:1 pair
internal val KimiBlueDarkContainer = Color(0xFF1C2A3A) // web dark --bluebg (user bubble)
internal val KimiBlueDarkOnContainer = Color(0xFFBDD8FF) // 9.9:1 on the container

internal val KimiSuccessLight = Color(0xFF0E7A38) // web --ok
internal val KimiSuccessDark = Color(0xFF3FB950) // web dark --ok

// Warning ambers (ticket 135 — the swarm's suspended segment and chip; suspended
// means "provider rate limit, requeued for retry", so the treatment must read as
// transient, never as an error). WCAG-AA: the color doubles as the suspended
// chip's label text on surfaceVariant (4.6:1 light, 4.8:1 dark) and as the
// bar's segment on the surfaceContainerLow card (5.4:1 light, 8.8:1 dark).
internal val KimiWarningLight = Color(0xFF8A5A00)
internal val KimiWarningDark = Color(0xFFE3B341)

// Diff-screen row palettes (ticket 119): a tinted container per direction with its
// content color, WCAG-AA-checked (normal text) against it. The contents reuse the
// success green and the M3 default error red so the rows read like the ✓/✗ glyphs.
internal val KimiDiffAddedLightContainer = Color(0xFFDFF2E1)
internal val KimiDiffAddedLightContent = KimiSuccessLight // 4.6:1 on the container
internal val KimiDiffRemovedLightContainer = Color(0xFFFBEBE9)
internal val KimiDiffRemovedLightContent = Color(0xFFBA1A1A) // M3 light error, 5.6:1
internal val KimiDiffAddedDarkContainer = Color(0xFF14281B)
internal val KimiDiffAddedDarkContent = KimiSuccessDark // 6.1:1 on the container
internal val KimiDiffRemovedDarkContainer = Color(0xFF2B1513)
internal val KimiDiffRemovedDarkContent = Color(0xFFF2B8B5) // M3 dark error, 10.1:1
