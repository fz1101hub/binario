package it.binario.app

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Opens a document chosen in the system picker so that what is written replaces its whole content (the backup, the
 * agenda's .ics). See [openReplacing].
 */
internal fun ContentResolver.openReplacingOutputStream(uri: Uri): OutputStream? =
    openReplacing { mode -> openOutputStream(uri, mode) }

/**
 * Mode "w" may leave the end of a longer file in place (Android documents that it may or may not truncate, and on
 * Android 10 and later file-backed providers generally do not), so "wt" is asked first. A provider that refuses that
 * mode is asked "w", which is right for the new, empty document the picker creates in most cases; any other failure
 * is the caller's.
 */
internal fun openReplacing(open: (mode: String) -> OutputStream?): OutputStream? =
    try {
        open("wt")
    } catch (refused: FileNotFoundException) {
        open("w")
    } catch (refused: IllegalArgumentException) {
        open("w")
    } catch (refused: UnsupportedOperationException) {
        open("w")
    }
