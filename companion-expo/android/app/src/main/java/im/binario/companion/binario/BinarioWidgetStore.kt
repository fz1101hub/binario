package im.binario.companion.binario

import android.content.Context

data class BinarioWidgetState(
  val title: String,
  val subtitle: String,
  val status: String,
  val updatedAtUtcMs: Long? = null,
  val expiresAtUtcMs: Long? = null,
  val plannedJourneyTitle: String? = null,
  val plannedJourneyAtUtcMs: Long? = null,
  val plannedReminderMinutes: Int? = null,
)

object BinarioWidgetStore {
  private const val preferenceName = "binario_widget_payload_v1"
  private const val keyTitle = "title"
  private const val keySubtitle = "subtitle"
  private const val keyStatus = "status"
  private const val keyUpdated = "updated_at"
  private const val keyExpires = "expires_at"
  private const val keyPlannedTitle = "planned_title"
  private const val keyPlannedAt = "planned_at"
  private const val keyPlannedReminder = "planned_reminder"

  private fun unavailable(title: String = "Binario") = BinarioWidgetState(
    title = title,
    subtitle = "Dati non disponibili in tempo reale.",
    status = "unavailable",
  )

  fun write(context: Context, state: BinarioWidgetState, now: Long = System.currentTimeMillis()) {
    val usable = state.status == "available" && state.updatedAtUtcMs != null &&
      state.expiresAtUtcMs != null && state.updatedAtUtcMs <= now && state.expiresAtUtcMs > now
    val accepted = if (usable) state else unavailable(state.title)
    context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().apply {
      putString(keyTitle, accepted.title)
      putString(keySubtitle, accepted.subtitle)
      putString(keyStatus, accepted.status)
      if (accepted.updatedAtUtcMs == null) remove(keyUpdated) else putLong(keyUpdated, accepted.updatedAtUtcMs)
      if (accepted.expiresAtUtcMs == null) remove(keyExpires) else putLong(keyExpires, accepted.expiresAtUtcMs)
      if (accepted.plannedJourneyTitle == null || accepted.plannedJourneyAtUtcMs == null || accepted.plannedJourneyAtUtcMs <= now) {
        remove(keyPlannedTitle); remove(keyPlannedAt); remove(keyPlannedReminder)
      } else {
        putString(keyPlannedTitle, accepted.plannedJourneyTitle)
        putLong(keyPlannedAt, accepted.plannedJourneyAtUtcMs)
        if (accepted.plannedReminderMinutes == null) remove(keyPlannedReminder) else putInt(keyPlannedReminder, accepted.plannedReminderMinutes)
      }
    }.apply()
  }

  fun read(context: Context, now: Long = System.currentTimeMillis()): BinarioWidgetState {
    val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val title = prefs.getString(keyTitle, "Binario") ?: "Binario"
    val status = prefs.getString(keyStatus, "unavailable") ?: "unavailable"
    val updatedAt = if (prefs.contains(keyUpdated)) prefs.getLong(keyUpdated, 0L) else null
    val expiresAt = if (prefs.contains(keyExpires)) prefs.getLong(keyExpires, 0L) else null
    val isFresh = status == "available" && updatedAt != null && expiresAt != null && updatedAt <= now && expiresAt > now
    if (!isFresh) return unavailable(title)
    val plannedTitle = prefs.getString(keyPlannedTitle, null)
    val plannedAt = if (prefs.contains(keyPlannedAt)) prefs.getLong(keyPlannedAt, 0L) else null
    val plannedUsable = !plannedTitle.isNullOrBlank() && plannedAt != null && plannedAt > now
    val reminder = if (prefs.contains(keyPlannedReminder)) prefs.getInt(keyPlannedReminder, 0) else null
    return BinarioWidgetState(title, prefs.getString(keySubtitle, "") ?: "", status, updatedAt, expiresAt, if (plannedUsable) plannedTitle else null, if (plannedUsable) plannedAt else null, if (plannedUsable) reminder else null)
  }
}
