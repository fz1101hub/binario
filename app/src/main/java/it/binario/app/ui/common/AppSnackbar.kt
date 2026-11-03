package it.binario.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's snackbar: confirmations (an alarm set) and destructive actions with "Annulla" (a saved route, a favourite
 * train, a saved timetable deleted). It is shown above the bottom navigation wherever the user is scrolled, and
 * TalkBack announces it.
 */
internal class AppSnackbar(private val hostState: SnackbarHostState, private val scope: CoroutineScope) {
    /** Shows [message], replacing the one on screen; [onAction] runs when [actionLabel] is tapped. */
    fun show(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** Null outside the app host (isolated screen tests): the screens then act without a snackbar. */
internal val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar?> { null }
