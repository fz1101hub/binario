# Contratto per fonti ferroviarie autorizzate

## Obiettivo e principio fail-closed

Binario non deve costruire, dedurre o presentare come live **orari, ritardi, binari, cancellazioni, posizioni, avvisi o itinerari** quando una fonte non è disponibile o non è autorizzata. Ogni futura integrazione passa da un adapter isolato e da un contratto dati comune; in caso di errore, licenza assente, credenziali mancanti o dato scaduto, l’interfaccia mantiene lo stato `non disponibile` oppure `orario programmato`.

## Stati obbligatori del dato

| Campo | Valori ammessi | Regola di visualizzazione |
|---|---|---|
| Natura | `realtime`, `scheduled`, `estimated`, `unavailable` | Mai chiamare realtime un dato programmato o stimato |
| Freschezza | timestamp UTC e soglia per provider | Rendere visibile l’ultimo aggiornamento; degradare il dato oltre soglia |
| Provenienza | identificativo provider, licenza e URL informativa | Esporre provider e nota licenza nel dettaglio |
| Fiducia | `verified`, `partial`, `conflict`, `unavailable` | Evidenziare conflitti e impedire messaggi di certezza non giustificati |
| Correlazione | identificativi tratta/stazione/treno e versione feed | Non unire record se la corrispondenza non è deterministica |

## Percorso di attivazione

1. **Verifica legale e contrattuale.** Registrare titolare, licenza, termini di riuso, limiti di caching, attribuzione richiesta, finalità, territori e procedure di revoca.
2. **Adapter server-side.** Conservare chiavi e chiamate lato server; applicare timeout, validazione schema, rate limit concordati, cache conforme alla licenza e osservabilità senza registrare dati vocali o di viaggio non necessari.
3. **Normalizzazione.** Convertire il feed in un modello interno con natura del dato, timestamp, provider e confidence.
4. **Verifica operativa.** Confrontare casi puntuali con il canale ufficiale, monitorare divergenze, misurare freschezza ed esporre indisponibilità invece di fallback inventati.
5. **Attivazione UI.** Abilitare singolarmente tabellone, itinerari, avvisi o monitoraggi solo dopo i punti precedenti.

## Perimetro non autorizzato

Non riutilizzare endpoint osservati in APK di terzi, chiavi estratte, API non documentate o flussi di scraping che violino termini d’uso. Il benchmark storico è una fonte di requisiti UX, non di codice, asset, credenziali o backend da replicare.

