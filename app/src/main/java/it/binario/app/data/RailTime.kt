package it.binario.app.data

import java.time.ZoneId

/**
 * Riferimento temporale predefinito dell'app ferroviaria italiana.
 *
 * I componenti ricevono comunque il fuso come dipendenza quando devono essere
 * testati o riusati per una rete diversa: questo valore evita che data di
 * servizio e allarmi cambino con il fuso configurato sul telefono.
 */
object RailTime {
    val defaultZone: ZoneId = ZoneId.of("Europe/Rome")
}
