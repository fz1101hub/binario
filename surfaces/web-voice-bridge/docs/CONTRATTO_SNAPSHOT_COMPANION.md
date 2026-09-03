# Contratto dati Binario per companion e notifiche

## Regola unica

L’app Binario è il nucleo dei monitoraggi e delle preferenze. **Widget, smartwatch, Alexa e notifiche non conservano né calcolano un proprio stato ferroviario.** Ricevono esclusivamente uno snapshot ridotto conforme allo stesso contratto dell’app.

| Campo obbligatorio | Regola |
|---|---|
| Soggetto | Identificativo deterministico di treno, stazione o tratta e relativa etichetta |
| Natura del dato | `realtime`, `scheduled`, `estimated` oppure `unavailable` |
| Provenienza | Provider, label leggibile e, ove previsto, URL licenza |
| Temporalità | Timestamp UTC di osservazione e scadenza/TTL della fonte |
| Affidabilità | `verified`, `partial`, `conflict` oppure `unavailable` |
| Nota | Messaggio umano su stato e limitazioni del dato |

## Regole di consegna

| Caso | Widget / smartwatch / Alexa | Notifica |
|---|---|---|
| Realtime verificato entro TTL | Visualizza o comunica con fonte e ultimo aggiornamento | Può inviare evento coerente |
| Scheduled | Visualizza come programmato, non realtime | Solo promemoria di partenza, con etichetta programmato |
| Estimated o partial | Visualizza con avvertenza | Solo se il caso d’uso lo consente e con avvertenza |
| TTL superato, conflict o unavailable | Mostra fallback “dati non disponibili in tempo reale” | Sopprime l’invio |

Ritardi, cancellazioni, binari e avvisi di servizio sono notificabili solo da uno snapshot `realtime`, `verified` e non scaduto. Alexa usa gli stessi fallback: in assenza di snapshot idoneo non deduce né verbalizza un ritardo.

## Privacy e sincronizzazione

Gli snapshot non includono audio, coordinate, token, storico completo o segreti. La sincronizzazione verso un companion richiede un collegamento esplicito dell’utente e trasporta il minimo indispensabile: monitoraggio scelto, snapshot valido e metadati di provenienza.
