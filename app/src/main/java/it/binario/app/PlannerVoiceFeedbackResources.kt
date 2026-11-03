package it.binario.app

import android.content.res.Resources

/** Renders dictation feedback in the display language, which is also the recognizer language. */
internal fun PlannerVoiceFeedback.resolve(resources: Resources): String = when (this) {
    PlannerVoiceFeedback.Confirmed -> resources.getString(R.string.voice_feedback_confirmed)
    PlannerVoiceFeedback.Incomplete -> resources.getString(R.string.voice_feedback_incomplete)
    is PlannerVoiceFeedback.Ambiguous -> {
        val separator = resources.getString(R.string.voice_feedback_alternative_separator)
        val groups = alternatives.joinToString("; ") { (role, names) ->
            val label = when (role) {
                DictatedStationRole.ORIGIN -> resources.getString(R.string.voice_feedback_role_origin)
                DictatedStationRole.DESTINATION -> resources.getString(R.string.voice_feedback_role_destination)
                DictatedStationRole.STATION -> resources.getString(R.string.voice_feedback_role_station)
            }
            "$label: ${names.joinToString(separator)}"
        }
        resources.getString(R.string.voice_feedback_ambiguous, groups)
    }
    is PlannerVoiceFeedback.Unresolved -> resources.getString(
        when (target) {
            DictationTarget.ROUTE -> R.string.voice_feedback_unresolved_route
            DictationTarget.TRAIN -> R.string.voice_feedback_unresolved_train
            DictationTarget.STATION -> R.string.voice_feedback_unresolved_station
        }
    )
}
