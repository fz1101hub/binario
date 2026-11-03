# Verifica Binario v0.17.2 — ricerca stazioni reale

**Data:** 15 agosto 2026  
**Branch canonico:** `feature/data-foundations`  
**Ambito:** rimozione del vincolo alle stazioni seed e collegamento della ricerca ai data pack GTFS autorizzati.

| Controllo | Esito | Evidenza |
|---|---|---|
| Normalizzazione nome | Superato | Test unitario: `Bolzano Bozen` trova `Bolzano/Bozen`. |
| Stazione fuori seed | Superato | Test unitario sul pack Lombardia embedded: `Brescia` è trovata nei `stops.txt`. |
| Ricerca libera | Superato | Input non ancora indicizzato genera una stazione locale selezionabile, senza creare orari. |
| Persistenza selezione | Superato | Le stazioni effettivamente usate fuori seed sono salvate in preferenze locali; nessun cloud o tracking. |
| Fallback dati | Superato | Provider realtime → GTFS programmato → messaggio di indisponibilità; nessun `DemoTrainDataSource` in produzione. |
| Test e APK | Superato | `:app:testDebugUnitTest :app:assembleDebug` completati con successo. |
| Lint | Superato | `:app:lintDebug` completato con successo. |

> La presenza di una stazione nel catalogo non garantisce un ritardo, un binario o una corsa live. Questi attributi restano visibili esclusivamente quando una fonte qualificata li restituisce nello snapshot.
