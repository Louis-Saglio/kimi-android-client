package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Archive-box glyph (Material Symbols "archive", Apache-2.0 path data) for the
 * long-press session dialog's Archive action (ticket 131). `Icon` tints it via
 * `LocalContentColor`, so the fill is black.
 */
val ArchiveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Archive",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.54f, 5.23f)
            lineTo(19.15f, 3.55f)
            curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
            horizontalLineTo(6f)
            curveTo(5.53f, 3f, 5.12f, 3.21f, 4.84f, 3.55f)
            lineTo(3.46f, 5.23f)
            curveTo(3.17f, 5.57f, 3f, 6.02f, 3f, 6.5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(6.5f)
            curveTo(21f, 6.02f, 20.83f, 5.57f, 20.54f, 5.23f)
            close()
            moveTo(12f, 17.5f)
            lineTo(6.5f, 12f)
            horizontalLineTo(10f)
            verticalLineTo(10f)
            horizontalLineTo(14f)
            verticalLineTo(12f)
            horizontalLineTo(17.5f)
            lineTo(12f, 17.5f)
            close()
            moveTo(5.12f, 5f)
            lineTo(5.93f, 4f)
            horizontalLineTo(17.93f)
            lineTo(18.87f, 5f)
            horizontalLineTo(5.12f)
            close()
        }
    }.build()
}
