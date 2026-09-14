package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Undo glyph (Material Symbols "undo", Apache-2.0 path data) for the archived
 * screen's per-row Restore action (ticket 132). `Icon` tints it via
 * `LocalContentColor`, so the fill is black.
 */
val RestoreIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Restore",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.5f, 8f)
            curveTo(9.85f, 8f, 7.45f, 8.99f, 5.6f, 10.6f)
            lineTo(2f, 7f)
            verticalLineTo(16f)
            horizontalLineTo(11f)
            lineTo(7.38f, 12.38f)
            curveTo(8.77f, 11.22f, 10.54f, 10.5f, 12.5f, 10.5f)
            curveTo(16.04f, 10.5f, 19.05f, 12.81f, 20.1f, 16f)
            lineTo(22.47f, 15.22f)
            curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
            close()
        }
    }.build()
}
