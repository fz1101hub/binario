package it.binario.app

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
    PLANNED_JOURNEY_CHECKLIST_SUCCESS("planned_journey_checklist_success"),
    PLANNED_JOURNEY_IMPORT_SUCCESS("planned_journey_import_success"),
    PLANNED_JOURNEY_IMPORT_INVALID("planned_journey_import_invalid"),
    PLANNED_JOURNEY_ACTION_REJECTED("planned_journey_action_rejected"),
    PLANNED_JOURNEY_STORED_UNREADABLE("planned_journey_stored_unreadable"),
    PLANNED_JOURNEY_EXPORT_SUCCESS("planned_journey_export_success"),
    PLANNED_JOURNEY_EXPORT_FAILURE("planned_journey_export_failure_generic"),
    PLANNED_JOURNEY_IMPORT_FAILURE("planned_journey_import_failure_generic"),
    ROUTE_STATIONS_MUST_DIFFER("route_stations_must_differ"),
    ROUND_TRIP_RETURN_MUST_FOLLOW_DEPARTURE("round_trip_return_must_follow_departure"),
    ROUTE_REQUIRED_TO_START("route_required_to_start"),
    ROUTE_ORIGIN_DESTINATION_REQUIRED("route_origin_destination_required"),
    RAIL_DATA_UNAVAILABLE("rail_data_unavailable"),
    STATION_BOARD_UNAVAILABLE("station_board_unavailable_generic"),
    STATION_BOARD_SOURCE_CONFLICT("station_board_warning_source_conflict"),
    STATION_BOARD_REALTIME_UNAVAILABLE("station_board_warning_realtime_unavailable"),
    STATION_BOARD_STALE_SOURCE("station_board_warning_stale_source"),
    STATION_BOARD_IDENTITY_AMBIGUOUS("station_board_warning_identity_ambiguous"),
    STATION_BOARD_REPLACEMENT_SERVICE("station_board_warning_replacement_service"),
    STATION_BOARD_INCOMPLETE_COVERAGE("station_board_warning_incomplete_coverage"),
    BACKUP_IMPORTED_WITHOUT_ROUTE("backup_imported_without_route"),
    BACKUP_IMPORT_SUCCESS("backup_import_success_summary"),
    BACKUP_IMPORT_INVALID("backup_import_invalid")
}
