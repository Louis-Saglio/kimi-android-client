package fr.louissaglio.kimi.android.ui.common.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Globe glyph (Material Icons "public", Apache-2.0 path data) marking `WebSearch`
 * tool rows (ticket 122). `Icon` tints it via `LocalContentColor`, so the fill is
 * black.
 */
val GlobeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Globe",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
            reflectiveCurveTo(17.52f, 2f, 12f, 2f)
            close()
            moveToRelative(-1f, 17.93f)
            curveToRelative(-3.95f, -0.49f, -7f, -3.85f, -7f, -7.93f)
            curveToRelative(0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f)
            lineTo(9f, 15f)
            verticalLineToRelative(1f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            verticalLineToRelative(1.93f)
            close()
            moveToRelative(6.9f, -2.54f)
            curveToRelative(-0.26f, -0.81f, -1f, -1.39f, -1.9f, -1.39f)
            horizontalLineToRelative(-1f)
            verticalLineToRelative(-3f)
            curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(2f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            verticalLineTo(7f)
            horizontalLineToRelative(2f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineToRelative(-0.41f)
            curveToRelative(2.93f, 1.19f, 5f, 4.06f, 5f, 7.41f)
            curveToRelative(0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f)
            close()
        }
    }.build()
}
