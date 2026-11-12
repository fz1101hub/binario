package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SnapshotWarning
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Resource-independent user-facing event emitted by state holders.
 *
 * ViewModels expose semantic keys and typed formatting arguments; Android resources are resolved
 * only at the rendering boundary. This keeps repositories and viewmodels free of Context while
 * preventing source/provider diagnostic text from leaking into the UI.
 */
data class UiMessage(
    val key: UiMessageKey,
    val arguments: List<Any> = emptyList()
) {
    constructor(key: UiMessageKey, vararg arguments: Any) : this(key, arguments.toList())
}

enum class UiMessageKey(val resourceName: String) {
    PLANNED_JOURNEY_EDITOR_INVALID("planned_journey_editor_invalid"),
    PLANNED_JOURNEY_SAVE_SUCCESS("planned_journey_save_success"),
    PLANNED_JOURNEY_COMPLETE_SUCCESS("planned_journey_complete_success"),
    PLANNED_JOURNEY_ARCHIVE_SUCCESS("planned_journey_archive_success"),
    PLANNED_JOURNEY_DELETE_SUCCESS("planned_journey_delete_success"),
    PLANNED_JOURNEY_CHECKLIST_SUCCESS("planned_journey_checklist_success"),
    PLANNED_JOURNEY_IMPORT_SUCCESS("planned_journey_import_success"),
    PLANNED_JOURNEY_IMPORT_INVALID("planned_journey_import_invalid"),
    PLANNED_JOURNEY_ACTION_REJECTED("planned_journey_action_rejected"),
    PLANNED_JOURNEY_STORED_UNREADABLE("planned_journey_stored_unreadable"),
    PLANNED_JOURNEY_READ_ONLY("planned_journey_read_only"),
    PLANNED_JOURNEY_FULL("planned_journey_full"),
    PLANNED_JOURNEY_DUPLICATE("planned_journey_duplicate"),
    PLANNED_JOURNEY_DEPARTURE_PAST("planned_journey_departure_past"),
    PLANNED_JOURNEY_RETURN_PREPARED("planned_journey_return_prepared"),
    PLANNED_JOURNEY_REPLAN_PREPARED("planned_journey_replan_prepared"),
    PLANNED_JOURNEY_LEGS_DISCONNECTED("planned_journey_legs_disconnected"),
    PLANNED_JOURNEY_LEGS_NOT_CHRONOLOGICAL("planned_journey_legs_not_chronological"),
    PLANNED_JOURNEY_TEXT_IMPORT_NONE("planned_journey_text_import_none"),
    PLANNED_JOURNEY_TEXT_IMPORT_CONFIRM_NEEDED("planned_journey_text_import_confirm_needed"),
    PLANNED_JOURNEY_TEMPLATE_INVALID("planned_journey_template_invalid"),
    PLANNED_JOURNEY_TEMPLATE_SAVED("planned_journey_template_saved"),
    PLANNED_JOURNEY_TEMPLATE_APPLIED("planned_journey_template_applied"),
    PLANNED_JOURNEY_TEMPLATE_DELETED("planned_journey_template_deleted"),
    PLANNED_JOURNEY_CLEARED("planned_journey_cleared"),
    PLANNED_JOURNEY_EXPORT_SUCCESS("planned_journey_export_success"),
    PLANNED_JOURNEY_EXPORT_FAILURE("planned_journey_export_failure_generic"),
    PLANNED_JOURNEY_IMPORT_FAILURE("planned_journey_import_failure_generic"),
    ROUTE_STATIONS_MUST_DIFFER("route_stations_must_differ"),
    ROUND_TRIP_RETURN_MUST_FOLLOW_DEPARTURE("round_trip_return_must_follow_departure"),
    ROUTE_REQUIRED_TO_START("route_required_to_start"),
    ROUTE_ORIGIN_DESTINATION_REQUIRED("route_origin_destination_required"),
    RAIL_DATA_UNAVAILABLE("rail_data_unavailable"),
    RAIL_NETWORK_UNAVAILABLE("rail_network_unavailable"),
    ROUTE_NOT_COVERED("route_not_covered"),
    ROUTE_SCHEDULE_EXPIRED("route_schedule_expired"),
    ROUTE_SCHEDULE_OUTSIDE_VALIDITY("route_schedule_outside_validity"),
    ROUTE_NO_SERVICE_AFTER("route_no_service_after"),
    ROUTE_COVERAGE_FALLBACK("route_coverage_fallback"),
    ROUTE_SOURCE_CONFLICT("route_source_conflict"),
    STATION_BOARD_UNAVAILABLE("station_board_unavailable_generic"),
    /** The board shown is the last one saved on the device ("Copia salvata, non in tempo reale: 10:42"). */
    STATION_BOARD_SAVED_COPY("data_status_cached"),
    STATION_BOARD_SOURCE_CONFLICT("station_board_warning_source_conflict"),
    STATION_BOARD_REALTIME_UNAVAILABLE("station_board_warning_realtime_unavailable"),
    /** A board falling back to the programmed timetable: «Sono disponibili solo orari programmati». */
    STATION_BOARD_PROGRAMMED_ONLY("snapshot_warning_static_only"),
    STATION_BOARD_STALE_SOURCE("station_board_warning_stale_source"),
    STATION_BOARD_IDENTITY_AMBIGUOUS("station_board_warning_identity_ambiguous"),
    STATION_BOARD_REPLACEMENT_SERVICE("station_board_warning_replacement_service"),
    STATION_BOARD_INCOMPLETE_COVERAGE("station_board_warning_incomplete_coverage"),
    BACKUP_IMPORTED_WITHOUT_ROUTE("backup_imported_without_route"),
    BACKUP_IMPORT_SUCCESS("backup_import_success_summary"),
    BACKUP_IMPORT_INVALID("backup_import_invalid"),
    UPDATE_TIME_AT("update_time_at"),
    UPDATE_TIME_NEVER("update_time_never"),
    WIDGET_NEAREST_PERMISSION_DENIED("station_widget_nearest_denied"),
    WIDGET_NEAREST_LOCATION_UNAVAILABLE("station_widget_nearest_unavailable"),
    WIDGET_NEAREST_NONE_NEARBY("station_widget_nearest_none")
}

/** The localized explanation of a failed route search; dates and times are formatted on render. */
internal fun RouteFailureReason.uiMessage(): UiMessage = when (this) {
    RouteFailureReason.Unavailable -> UiMessage(UiMessageKey.RAIL_DATA_UNAVAILABLE)
    RouteFailureReason.NetworkUnavailable -> UiMessage(UiMessageKey.RAIL_NETWORK_UNAVAILABLE)
    RouteFailureReason.RouteNotCovered -> UiMessage(UiMessageKey.ROUTE_NOT_COVERED)
    is RouteFailureReason.ScheduleExpired -> UiMessage(UiMessageKey.ROUTE_SCHEDULE_EXPIRED, validUntil)
    is RouteFailureReason.ScheduleOutsideValidity -> UiMessage(UiMessageKey.ROUTE_SCHEDULE_OUTSIDE_VALIDITY, validFrom, validUntil)
    is RouteFailureReason.NoServiceAfter -> UiMessage(UiMessageKey.ROUTE_NO_SERVICE_AFTER, serviceDate, departureTime)
}

/** The advisory a successful route search must carry, if any; an unofficial source outranks a field conflict. */
internal fun RouteSnapshot.advisoryMessage(): UiMessage? = when {
    coverageFallback -> UiMessage(UiMessageKey.ROUTE_COVERAGE_FALLBACK)
    SnapshotWarning.SOURCE_CONFLICT in qualityWarnings -> UiMessage(UiMessageKey.ROUTE_SOURCE_CONFLICT)
    else -> null
}

/** When the shown data was last refreshed, as a railway-local clock time; the wording follows the display language. */
internal fun updateTimeMessage(epochMillis: Long): UiMessage {
    if (epochMillis <= 0L) return UiMessage(UiMessageKey.UPDATE_TIME_NEVER)
    val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone)
    return UiMessage(UiMessageKey.UPDATE_TIME_AT, formatter.format(Instant.ofEpochMilli(epochMillis)))
}
