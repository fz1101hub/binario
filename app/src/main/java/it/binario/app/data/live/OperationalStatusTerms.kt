package it.binario.app.data.live

/** Lessico operativo: i nomi delle località non sono stati di circolazione. */
internal fun String.hasExplicitCancellationStatus(): Boolean =
    EXPLICIT_CANCELLATION.containsMatchIn(this)

private val EXPLICIT_CANCELLATION = Regex(
    "\\b(?:soppress\\w*|cancellat\\w*)\\b",
    RegexOption.IGNORE_CASE
)
