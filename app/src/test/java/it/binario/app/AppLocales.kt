package it.binario.app

/**
 * The languages of the app as docs/l10n/LOCALES.md lists them: the BCP-47 tags of res/xml/locale_config.xml, in
 * order, and the resource folder Android looks up for each one.
 */
object AppLocales {
    val tags: List<String> = listOf(
        "it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh-CN"
    )

    /** Every tag except the Italian default. */
    val translated: List<String> get() = tags.drop(1)

    /**
     * The `values-` qualifier of [tag] ("en" → "en"), null for the Italian default. Indonesian lives in the legacy
     * `values-in` folder because Android rewrites "id" to "in" before resolving resources; Simplified Chinese uses
     * the region qualifier `zh-rCN`.
     */
    fun qualifier(tag: String): String? = when (tag) {
        "it" -> null
        "id" -> "in"
        "zh-CN" -> "zh-rCN"
        else -> tag
    }

    /** "values" for the default, "values-<qualifier>" otherwise. */
    fun folder(tag: String): String = qualifier(tag)?.let { "values-$it" } ?: "values"
}
