package it.binario.app

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@StringRes
internal fun UiMessageKey.resourceId(): Int = when (this) {
    UiMessageKey.PLANNED_JOURNEY_EDITOR_INVALID -> R.string.planned_journey_editor_invalid
    UiMessageKey.PLANNED_JOURNEY_SAVE_SUCCESS -> R.string.planned_journey_save_success
    UiMessageKey.PLANNED_JOURNEY_COMPLETE_SUCCESS -> R.string.planned_journey_complete_success
    UiMessageKey.PLANNED_JOURNEY_ARCHIVE_SUCCESS -> R.string.planned_journey_archive_success
    UiMessageKey.PLANNED_JOURNEY_DELETE_SUCCESS -> R.string.planned_journey_delete_success
    UiMessageKey.PLANNED_JOURNEY_CHECKLIST_SUCCESS -> R.string.planned_journey_checklist_success
    UiMessageKey.PLANNED_JOURNEY_IMPORT_SUCCESS -> R.string.planned_journey_import_success
    UiMessageKey.PLANNED_JOURNEY_IMPORT_INVALID -> R.string.planned_journey_import_invalid
    UiMessageKey.PLANNED_JOURNEY_ACTION_REJECTED -> R.string.planned_journey_action_rejected
    UiMessageKey.PLANNED_JOURNEY_STORED_UNREADABLE -> R.string.planned_journey_stored_unreadable
    UiMessageKey.PLANNED_JOURNEY_EXPORT_SUCCESS -> R.string.planned_journey_export_success
    UiMessageKey.PLANNED_JOURNEY_EXPORT_FAILURE -> R.string.planned_journey_export_failure_generic
    UiMessageKey.PLANNED_JOURNEY_IMPORT_FAILURE -> R.string.planned_journey_import_failure_generic
    UiMessageKey.ROUTE_STATIONS_MUST_DIFFER -> R.string.route_stations_must_differ
    UiMessageKey.ROUND_TRIP_RETURN_MUST_FOLLOW_DEPARTURE -> R.string.round_trip_return_must_follow_departure
    UiMessageKey.ROUTE_REQUIRED_TO_START -> R.string.route_required_to_start
    UiMessageKey.ROUTE_ORIGIN_DESTINATION_REQUIRED -> R.string.route_origin_destination_required
    UiMessageKey.RAIL_DATA_UNAVAILABLE -> R.string.rail_data_unavailable
    UiMessageKey.ROUTE_NOT_COVERED -> R.string.route_not_covered
    UiMessageKey.ROUTE_SCHEDULE_EXPIRED -> R.string.route_schedule_expired
    UiMessageKey.ROUTE_NO_SERVICE_AFTER -> R.string.route_no_service_after
    UiMessageKey.ROUTE_COVERAGE_FALLBACK -> R.string.route_coverage_fallback
    UiMessageKey.STATION_BOARD_UNAVAILABLE -> R.string.station_board_unavailable_generic
    UiMessageKey.STATION_BOARD_SOURCE_CONFLICT -> R.string.station_board_warning_source_conflict
    UiMessageKey.STATION_BOARD_REALTIME_UNAVAILABLE -> R.string.station_board_warning_realtime_unavailable
    UiMessageKey.STATION_BOARD_STALE_SOURCE -> R.string.station_board_warning_stale_source
    UiMessageKey.STATION_BOARD_IDENTITY_AMBIGUOUS -> R.string.station_board_warning_identity_ambiguous
    UiMessageKey.STATION_BOARD_REPLACEMENT_SERVICE -> R.string.station_board_warning_replacement_service
    UiMessageKey.STATION_BOARD_INCOMPLETE_COVERAGE -> R.string.station_board_warning_incomplete_coverage
    UiMessageKey.BACKUP_IMPORTED_WITHOUT_ROUTE -> R.string.backup_imported_without_route
    UiMessageKey.BACKUP_IMPORT_SUCCESS -> R.string.backup_import_success_summary
    UiMessageKey.BACKUP_IMPORT_INVALID -> R.string.backup_import_invalid
}

internal fun UiMessage.resolve(resources: Resources): String {
    if (key != UiMessageKey.BACKUP_IMPORT_SUCCESS) {
        return resources.getString(key.resourceId(), *formattedArguments(resources.configuration.locales[0]))
    }
    val routes = integerArgument(0)
    val stations = integerArgument(1)
    val alerts = integerArgument(2)
    return resources.getString(
        R.string.backup_import_success_summary,
        resources.getQuantityString(R.plurals.backup_import_route_count, routes, routes),
        resources.getQuantityString(R.plurals.backup_import_station_count, stations, stations),
        resources.getQuantityString(R.plurals.backup_import_alert_count, alerts, alerts)
    )
}

@Composable
internal fun UiMessage.localized(): String {
    if (key != UiMessageKey.BACKUP_IMPORT_SUCCESS) {
        return stringResource(key.resourceId(), *formattedArguments(LocalConfiguration.current.locales[0]))
    }
    val routes = integerArgument(0)
    val stations = integerArgument(1)
    val alerts = integerArgument(2)
    return stringResource(
        R.string.backup_import_success_summary,
        pluralStringResource(R.plurals.backup_import_route_count, routes, routes),
        pluralStringResource(R.plurals.backup_import_station_count, stations, stations),
        pluralStringResource(R.plurals.backup_import_alert_count, alerts, alerts)
    )
}

/** Dates and times are formatted in the display language, not with their ISO toString(). */
private fun UiMessage.formattedArguments(locale: Locale): Array<Any> = arguments.map { argument ->
    when (argument) {
        is LocalDate -> argument.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        is LocalTime -> argument.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        else -> argument
    }
}.toTypedArray()

private fun UiMessage.integerArgument(index: Int): Int =
    (arguments.getOrNull(index) as? Number)?.toInt()
        ?: error("${key.name} requires integer argument $index")
