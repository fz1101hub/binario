# Verifica — Binario v0.9.0

| Area | Esito |
|---|---|
| Ricerca | Pianificatore con modalità Viaggio, Treno e Stazione. |
| Memoria personale | Otto ricerche recenti al massimo, solo in locale, deduplicate per direzione. |
| Monitoraggi | Aggiunta/rimozione di numeri treno dal dettaglio; gestione in Altro. |
| Dato live | Nessuna simulazione: alert e tabellone live restano esplicitamente subordinati a provider autorizzato. |
| Test | Test unitari superati, incluso contratto chiave ricerca recente. |
| Lint | `No issues found`. |
| APK | Debug v0.9.0 generato. |

La matrice funzionale completa identifica le funzioni successive: alert personali, monitoraggio stazione/direttrice, infomobilità, provider real-time autorizzati e migrazione Room. Nessuna di esse viene dichiarata già funzionante prima della relativa implementazione e della disponibilità della fonte dati.
