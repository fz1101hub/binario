package it.binario.app.data.live

import org.json.JSONObject

/**
 * The text of [key], or null when it is missing, JSON `null` or blank.
 *
 * Android's org.json returns the string "null" from `optString` for an explicit JSON null, while the org.json
 * artifact the unit tests run on returns "" (or the fallback): decisions written on `optString` would then differ
 * between the device and the tests. This accessor treats both the same way.
 */
internal fun JSONObject.textOrNull(key: String): String? {
    val value = opt(key) ?: return null
    if (value == JSONObject.NULL) return null
    return value.toString().takeIf { it.isNotBlank() }
}

/** [textOrNull], or [fallback] (empty by default) when the value is missing, JSON `null` or blank. */
internal fun JSONObject.textOr(key: String, fallback: String = ""): String = textOrNull(key) ?: fallback
