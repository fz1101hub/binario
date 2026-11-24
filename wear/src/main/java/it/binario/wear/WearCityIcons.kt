package it.binario.wear

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import it.binario.wear.model.WearService
import it.binario.wear.model.WearVehicle

/**
 * The icon drawn before the time of a service on the watch screen, as the phone draws a leg (`JourneyLegKind.icon`):
 * the bus of a bus, the metro, tram or funicular of a city line ([WearService.isUrban]); none for a train or an unknown
 * vehicle. Drawn here like [WearBusIcon], so the watch app needs no icon library; tinted by the caller.
 */
internal val WearService.vehicleIcon: ImageVector?
    get() = when (vehicle) {
        WearVehicle.METRO -> WearMetroIcon
        WearVehicle.TRAM -> WearTramIcon
        WearVehicle.FUNICULAR -> WearFunicularIcon
        else -> if (isBus) WearBusIcon else null
    }

/** A metro (the phone's `Subway`): a train front with a windscreen and two headlights under a tunnel arch. */
internal val WearMetroIcon: ImageVector by lazy {
    ImageVector.Builder(name = "WearMetro", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Tunnel arch: a half ring standing on two pillars.
            moveTo(2f, 22f)
            verticalLineTo(12f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 22f, y1 = 12f)
            verticalLineTo(22f)
            horizontalLineTo(20.5f)
            verticalLineTo(12f)
            arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 3.5f, y1 = 12f)
            verticalLineTo(22f)
            close()
            // Train front with rounded top corners.
            moveTo(9f, 7.5f)
            horizontalLineTo(15f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.5f, y1 = 9f)
            verticalLineTo(19f)
            horizontalLineTo(7.5f)
            verticalLineTo(9f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 9f, y1 = 7.5f)
            close()
            // Windscreen.
            moveTo(9f, 9.5f)
            horizontalLineTo(15f)
            verticalLineTo(13f)
            horizontalLineTo(9f)
            close()
            // Headlights.
            moveTo(8.8f, 16f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10.8f, y1 = 16f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 8.8f, y1 = 16f)
            close()
            moveTo(13.2f, 16f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 15.2f, y1 = 16f)
            arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 13.2f, y1 = 16f)
            close()
        }
        .build()
}

/** A tram (the phone's `Tram`): a car with a windscreen and two headlights under its pantograph, on a rail. */
internal val WearTramIcon: ImageVector by lazy {
    ImageVector.Builder(name = "WearTram", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Pantograph: the contact bar and its mast.
            moveTo(7f, 1f)
            horizontalLineTo(17f)
            verticalLineTo(2.2f)
            horizontalLineTo(12.7f)
            verticalLineTo(5f)
            horizontalLineTo(11.3f)
            verticalLineTo(2.2f)
            horizontalLineTo(7f)
            close()
            // Car with rounded corners.
            moveTo(7f, 5f)
            horizontalLineTo(17f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 7f)
            verticalLineTo(18f)
            horizontalLineTo(5f)
            verticalLineTo(7f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 5f)
            close()
            // Windscreen.
            moveTo(7f, 7.5f)
            horizontalLineTo(17f)
            verticalLineTo(11.5f)
            horizontalLineTo(7f)
            close()
            // Headlights.
            moveTo(7f, 14.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.4f, y1 = 14.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 7f, y1 = 14.5f)
            close()
            moveTo(14.6f, 14.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17f, y1 = 14.5f)
            arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 14.6f, y1 = 14.5f)
            close()
            // Bogies.
            moveTo(7f, 18f)
            horizontalLineTo(9.5f)
            verticalLineTo(20f)
            horizontalLineTo(7f)
            close()
            moveTo(14.5f, 18f)
            horizontalLineTo(17f)
            verticalLineTo(20f)
            horizontalLineTo(14.5f)
            close()
            // Rail.
            moveTo(3f, 20.5f)
            horizontalLineTo(21f)
            verticalLineTo(22f)
            horizontalLineTo(3f)
            close()
        }
        .build()
}

/** A funicular (the phone draws `DirectionsRailway`): a car with two windows on a steep rail. */
internal val WearFunicularIcon: ImageVector by lazy {
    ImageVector.Builder(name = "WearFunicular", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            // Rail climbing to the right.
            moveTo(2f, 19f)
            lineTo(22f, 7f)
            lineTo(22f, 8.8f)
            lineTo(2f, 20.8f)
            close()
            // Car, its floor and roof parallel to the rail.
            moveTo(6f, 15.6f)
            lineTo(6f, 9.6f)
            lineTo(18f, 2.4f)
            lineTo(18f, 8.4f)
            close()
            // Windows.
            moveTo(8f, 12.9f)
            lineTo(8f, 10.4f)
            lineTo(11f, 8.6f)
            lineTo(11f, 11.1f)
            close()
            moveTo(13f, 9.9f)
            lineTo(13f, 7.4f)
            lineTo(16f, 5.6f)
            lineTo(16f, 8.1f)
            close()
        }
        .build()
}
