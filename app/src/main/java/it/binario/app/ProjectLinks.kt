package it.binario.app

import java.net.URLEncoder

/**
 * Pages of the Binario project that the app opens in the browser only on an explicit tap; the app never requests
 * them itself. The repository defines no e-mail contact (see PRIVACY.md), so suggestions go to the project's public
 * issue form with a prefilled title.
 */
object ProjectLinks {
    const val NEW_ISSUE = "https://github.com/fz1101hub/binario/issues/new"

    /** The issue form with [title] already filled in. */
    fun newIssue(title: String): String = NEW_ISSUE + "?title=" + URLEncoder.encode(title, Charsets.UTF_8.name())
}
