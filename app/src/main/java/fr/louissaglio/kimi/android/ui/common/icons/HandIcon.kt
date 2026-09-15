package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Raised-hand glyph (Material Icons "pan_tool", Apache-2.0 path data) for the
 * permission picker sheet's "Always Ask" row (ticket 141). `Icon` tints it via
 * `LocalContentColor`, so the fill is black.
 */
val HandIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Hand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(23f, 5.5f)
            verticalLineTo(20f)
            curveToRelative(0f, 2.2f, -1.8f, 4f, -4f, 4f)
            horizontalLineToRelative(-7.3f)
            curveToRelative(-1.08f, 0f, -2.1f, -0.43f, -2.85f, -1.19f)
            lineTo(1f, 14.83f)
            reflectiveCurveToRelative(1.26f, -1.23f, 1.3f, -1.25f)
            curveToRelative(0.22f, -0.19f, 0.49f, -0.29f, 0.79f, -0.29f)
            curveToRelative(0.22f, 0f, 0.42f, 0.06f, 0.6f, 0.16f)
            curveToRelative(0.04f, 0.01f, 4.31f, 2.46f, 4.31f, 2.46f)
            verticalLineTo(4f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveTo(11f, 3.17f, 11f, 4f)
            verticalLineToRelative(7f)
            horizontalLineToRelative(1f)
            verticalLineTo(1.5f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveTo(15f, 0.67f, 15f, 1.5f)
            verticalLineTo(11f)
            horizontalLineToRelative(1f)
            verticalLineTo(2.5f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            verticalLineTo(11f)
            horizontalLineToRelative(1f)
            verticalLineTo(5.5f)
            curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
            reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
            close()
        }
    }.build()
}
