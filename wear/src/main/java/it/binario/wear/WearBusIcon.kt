package it.binario.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The bus of a service the phone marks as one (`WearService.isBus`), drawn before its time on the watch screen as the
 * phone draws a bus leg. Drawn here, on a 24-unit grid, so the watch app needs no icon library: a body with a
 * windscreen and two headlights cut out, on two wheels. Tinted by the caller.
 */
internal val WearBusIcon: ImageVector by lazy {
    ImageVector.Builder(name = "WearBus", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Body with rounded corners.
            moveTo(6f, 2f)
            horizontalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 4f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(4f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6f, y1 = 2f)
            close()
            // Windscreen.
            moveTo(6f, 5f)
            horizontalLineTo(18f)
            verticalLineTo(10f)
            horizontalLineTo(6f)
            close()
            // Headlights.
            moveTo(6.5f, 13.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.5f, y1 = 13.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 6.5f, y1 = 13.5f)
            close()
            moveTo(14.5f, 13.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17.5f, y1 = 13.5f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 14.5f, y1 = 13.5f)
            close()
            // Wheels.
            moveTo(5f, 17f)
            horizontalLineTo(8.5f)
            verticalLineTo(21f)
            horizontalLineTo(5f)
            close()
            moveTo(15.5f, 17f)
            horizontalLineTo(19f)
            verticalLineTo(21f)
            horizontalLineTo(15.5f)
            close()
        }
        .build()
}
