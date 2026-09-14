package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Splitting-arrows glyph (Material Symbols "call_split", Apache-2.0 path data) for
 * the long-press session dialog's Fork action (ticket 131 gave the row its icon —
 * ticket 128 shipped it bare). `Icon` tints it via `LocalContentColor`, so the fill
 * is black.
 */
val ForkIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Fork",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14f, 4f)
            lineTo(16.29f, 6.29f)
            lineTo(13.41f, 9.17f)
            lineTo(14.83f, 10.59f)
            lineTo(17.71f, 7.71f)
            lineTo(20f, 10f)
            verticalLineTo(4f)
            horizontalLineTo(14f)
            close()
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            verticalLineTo(10f)
            lineTo(6.29f, 7.71f)
            lineTo(11f, 12.41f)
            verticalLineTo(20f)
            horizontalLineTo(13f)
            verticalLineTo(11.59f)
            lineTo(7.71f, 6.29f)
            lineTo(10f, 4f)
            close()
        }
    }.build()
}
