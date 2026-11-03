package it.binario.app

import java.util.Base64

/**
 * Codec per valori locali delimitati. Usa solo API JVM per essere verificabile
 * con test unitari; un valore Base64 corrotto viene ignorato invece di impedire
 * l'avvio dell'app.
 */
internal object PreferenceValueCodec {
    fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }.getOrNull()
}
