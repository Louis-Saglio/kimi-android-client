package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Folder glyph (Material Symbols "folder", Apache-2.0 path data) for the
 * by-workspace grouping affordances: the flat → grouped toggle shows it as the
 * "group by workspace" target mode, and each group header carries it next to the
 * workspace name. `Icon` tints it via `LocalContentColor`, so the fill is black.
 */
val WorkspaceIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Workspace",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            horizontalLineTo(12f)
            lineTo(10f, 4f)
            close()
        }
    }.build()
}
