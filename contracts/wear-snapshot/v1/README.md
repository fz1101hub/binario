# Contratto snapshot Wear v1

Questo documento definisce il **contratto logico v1** per lo snapshot minimo inviato alle superfici smartwatch. È una rappresentazione derivata e a durata limitata: non modifica i canali di trasporto esistenti (`/binario/wear/snapshot`, `/binario/companion/watch-payload/v1` e `WCSession.applicationContext["binario_snapshot_json"]`). I decoder sono indipendenti per piattaforma; condividono soltanto questa specifica e le fixture golden in questa directory, non classi o librerie.

## Regole di compatibilità e sicurezza

Un decoder accetta esclusivamente `schemaVersion: 1`. Una versione superiore (o inferiore), un campo obbligatorio assente o del tipo errato, un campo non previsto, un payload oltre il budget, un TTL incoerente o scaduto causa **rifiuto fail-closed**: il dato non sostituisce l'ultima cache valida e la superficie mostra indisponibilità. I decoder possono convertire il valore testuale sconosciuto di un enum nel caso `UNKNOWN`, purché il resto del payload sia valido; ciò evita di attribuire arbitrariamente stato operativo a un valore futuro.

| Vincolo | Valore v1 |
|---|---:|
| Dimensione serializzata massima | 8.192 byte UTF-8 |
| Servizi massimi | 2 |
| TTL | `0 < ttlMillis <= 31.536.000.000` (365 giorni) |
| Coerenza temporale | `expiresAtEpochMillis - generatedAtEpochMillis == ttlMillis`; `expiresAtEpochMillis > now` |
| Skew futuro ammesso | `generatedAtEpochMillis <= now + 60.000` |
| route/provider/source label | 96 caratteri normalizzati |
| id tratta / direzione | 64 / 16 caratteri normalizzati |
| numero treno / orario / binario | 24 / 16 / 16 caratteri normalizzati |
| operatore, categoria, destinazione, stato servizio | 64 / 64 / 96 / 64 caratteri normalizzati |
| avviso | 180 caratteri normalizzati |

`generatedAtEpochMillis` identifica l'istante di generazione dello snapshot; `expiresAtEpochMillis` è il limite assoluto di visualizzazione e `ttlMillis` rende verificabile la finestra senza interpretazioni implicite del trasporto. Il produttore Android limita ulteriormente i dati operativi alla scadenza ricevuta e a 15 minuti; il Companion usa la scadenza della pianificazione locale.

Il set di chiavi è chiuso. Non sono ammessi segreti o materiale sensibile: token, password, credenziali, header/autorizzazioni, identificativi di sessione, posizione/coordinate, tracce GPS, audio, trascrizioni o comandi vocali. In particolare non esistono campi per location, latitude, longitude, audio, transcript o voice. I valori non elencati non possono essere aggiunti in v1: richiedono una nuova `schemaVersion` e fixture.

## Campi e enum

La forma canonica è un oggetto JSON piatto; sulle Data Layer Android le stesse chiavi sono inserite nella `DataMap`, mentre su Apple è codificata come JSON nella chiave WCSession già esistente. Il prefisso `provenance` rende la provenienza esplicita senza aggiungere un envelope di trasporto.

| Campo | Tipo | Note |
|---|---|---|
| `schemaVersion` | integer | Obbligatorio; oggi soltanto `1`. |
| `generatedAtEpochMillis`, `expiresAtEpochMillis`, `ttlMillis` | integer | Obbligatori; rispettano la coerenza temporale sopra. |
| `provenanceProducer` | enum | `ANDROID_APP`, `COMPANION`, `IOS_APP`, `UNKNOWN`. |
| `provenanceDataNature` | enum | `PROGRAMMED`, `OPERATIONAL`, `UNAVAILABLE`, `UNKNOWN`. |
| `provenanceSourceLabel` | string | Etichetta leggibile della fonte o della pianificazione locale. |
| `provenanceObservedAtEpochMillis` | integer | Istante della fonte originale; per dati locali coincide con la generazione. |
| `operationalState` | enum | `PROGRAMMED`, `OPERATIONAL`, `UNAVAILABLE`, `UNKNOWN`. Non dedurre `OPERATIONAL` da un orario. |
| `freshness` | enum | `FRESH`, `AGING`, `STALE`, `UNAVAILABLE`, `UNKNOWN`. |
| `confidence` | enum | `OPERATOR_PRIMARY`, `INFRASTRUCTURE_PRIMARY`, `OPEN_DATA`, `LICENSED_AGGREGATE`, `STATIC_ONLY`, `CACHED`, `LOCAL_PLANNED`, `UNKNOWN`. |
| `routeId`, `routeLabel`, `direction` | string | Identità e label minimali; `direction` è `OUTBOUND`, `RETURN` oppure `UNKNOWN`. |
| `notice` | string | Avviso testuale limitato; può essere vuoto. |
| `serviceCount` | integer | Da 0 a 2. |
| `service{n}Number`, `Operator`, `Category`, `Destination`, `Scheduled`, `Expected`, `Status`, `Platform`, `DelayMinutes` | string/string/string/string/string/string/string/string/integer | Tutti obbligatori per ogni `n < serviceCount`, eccetto `Platform` che può essere stringa vuota. `DelayMinutes` è tra -720 e 1.440. |

`UNAVAILABLE` richiede `serviceCount: 0` e provenienza `UNAVAILABLE`. `PROGRAMMED` dichiara esplicitamente che i dati non sono realtime. Le fixture sono esempi stabili: i test usano un clock fissato, non l'orologio reale.

## Fixture golden

- `android-to-wear-v1.json`: app Android nativa → Wear OS, stato operativo con due corse.
- `companion-to-wear-v1.json`: Companion → Wear OS, pianificazione locale senza informazione ferroviaria realtime.
- `ios-to-apple-watch-v1.json`: schema previsto iOS → Apple Watch tramite il contesto WCSession esistente.

Ogni modulo ha test di compatibilità propri contro queste fixture. Una modifica intenzionale deve aggiornare prima questa specifica, poi la fixture e tutti i decoder interessati; non deve essere nascosta dietro campi extra tollerati.
