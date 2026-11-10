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

`generatedAtEpochMillis` identifica l'istante di generazione dello snapshot; `expiresAtEpochMillis` è il limite assoluto di visualizzazione e `ttlMillis` rende verificabile la finestra senza interpretazioni implicite del trasporto. Il produttore Android fissa la finestra di visualizzazione a 15 minuti da `generatedAtEpochMillis`, preso subito prima della scrittura sul Data Layer, e mai oltre 15 minuti dopo la validità dello snapshot alla fonte: la durata della cache della fonte (30–60 secondi per i tabelloni in tempo reale) non accorcia più la finestra, e uno snapshot caricato da tempo non viene ripubblicato come attuale. Per i dati operativi il watch mostra l'ora di `provenanceObservedAtEpochMillis` nella riga di stato («Dati aggiornati alle 08:12»), così i valori in tempo reale non sembrano attuali per tutta la finestra. Il Companion usa la scadenza della pianificazione locale.

Gli orari `service{n}Scheduled` e `service{n}Expected` del produttore Android sono orari locali `HH:mm` di Europe/Rome (`RailTime.defaultZone`): il watch Wear ne ricava la prima occorrenza non anteriore di oltre due ore a `generatedAtEpochMillis` (una corsa del mattino dopo elencata la sera è nel futuro) per il conto alla rovescia e per passare alla corsa successiva e, se l'orario non è interpretabile, mostra il testo così com'è.

Il produttore Android non invia come `service{n}` una corsa di cui la fonte ha confermato la soppressione: Tile e complication mostrano la prima corsa come «prossimo treno» senza il suo stato. Il campo `notice` del produttore Android contiene l'avviso nella lingua del telefono (la prima soppressione confermata fra le corse lasciate fuori, «Soppresso: R 2345 delle 08:15»; altrimenti fonte di copertura non ufficiale, fonti in conflitto, altrimenti il primo avviso di qualità dello snapshot), mai il testo libero della fonte dati; senza avvisi il campo è vuoto.

Il set di chiavi è chiuso: comprende le chiavi obbligatorie e la sola coppia facoltativa descritta in *Campo facoltativo: affidabilità*. Non sono ammessi segreti o materiale sensibile: token, password, credenziali, header/autorizzazioni, identificativi di sessione, posizione/coordinate, tracce GPS, audio, trascrizioni o comandi vocali. In particolare non esistono campi per location, latitude, longitude, audio, transcript o voice. I valori non elencati non possono essere aggiunti in v1: richiedono una nuova `schemaVersion` e fixture. L'unica eccezione è la coppia facoltativa di affidabilità, inviata solo ai decoder che dichiarano di conoscerla (vedi sotto).

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
| `routeId`, `routeLabel`, `direction` | string | Identità e label minimali; `direction` è `OUTBOUND`, `RETURN` oppure `UNKNOWN`. Il produttore Android invia sempre `routeId: "active"` (`"unavailable"` nello stato non disponibile): l'orologio non usa l'identità della tratta e l'identificativo persistente della tratta salvata resta sul telefono; la chiave resta perché i decoder v1 la richiedono non vuota. |
| `notice` | string | Avviso testuale limitato; può essere vuoto. |
| `serviceCount` | integer | Da 0 a 2. |
| `service{n}Number`, `Operator`, `Category`, `Destination`, `Scheduled`, `Expected`, `Status`, `Platform`, `DelayMinutes` | string/string/string/string/string/string/string/string/integer | Tutti obbligatori per ogni `n < serviceCount`, eccetto `Platform` che può essere stringa vuota. `DelayMinutes` è tra -720 e 1.440. |
| `service{n}ReliabilityOnTime`, `service{n}ReliabilitySamples` | integer/integer | **Facoltativi**, sempre in coppia (entrambi o nessuno) per un `n < serviceCount`; vedi sotto. |

`UNAVAILABLE` richiede `serviceCount: 0` e provenienza `UNAVAILABLE`. `PROGRAMMED` dichiara esplicitamente che i dati non sono realtime. Le fixture sono esempi stabili: i test usano un clock fissato, non l'orologio reale.

## Campo facoltativo: affidabilità (aggiunto il 4 novembre 2026)

Una corsa può portare la propria affidabilità nelle statistiche di rete non ufficiali che l'app Android include (`assets/punctuality/stats_v1.json.gz`, aggregati di osservazioni ViaggiaTreno elaborati da Binario): il watch la mostra come «In orario 22 volte su 27» sotto la corsa nella schermata dell'app, non nella complication né nel Tile.

| Chiave | Tipo | Vincoli |
|---|---|---|
| `service{n}ReliabilitySamples` | integer | Giorni con una rilevazione alla fermata di arrivo della tratta, tra 5 e 10.000. Sotto 5 rilevazioni il produttore non invia la coppia. |
| `service{n}ReliabilityOnTime` | integer | Giorni in orario fra quelli, tra 0 e `service{n}ReliabilitySamples`, entro il limite della categoria (5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo, 15 per le altre). |

- **Compatibilità**: la versione resta `schemaVersion: 1`. Un payload senza la coppia è valido come prima, quindi i produttori che non la conoscono (Companion, iPhone) non cambiano e i decoder aggiornati accettano ogni payload precedente. Una coppia incompleta, fuori intervallo, di tipo errato o per un indice `n >= serviceCount` fa rifiutare il payload intero, come ogni altro campo non valido.
- **Decoder precedenti**: un decoder che non conosce la coppia la tratta come chiave non prevista e rifiuta il payload. Per questo il produttore Android la aggiunge solo se tutti i nodi Wear connessi pubblicano la capability Data Layer `binario_wear_reliability_v1` (dichiarata dall'app Wear in `wear/src/main/res/values/wear.xml`) e la toglie dal payload conservato prima di reinviarlo se un orologio connesso nel frattempo non la pubblica; senza orologi connessi o con la verifica non riuscita non la invia.
- **Origine del dato**: il telefono la ricava sul dispositivo, senza richieste di rete, per un treno diretto della tratta (partenza dalla stazione di origine della tratta, misura alla destinazione), mai per bus sostitutivi, viaggi con cambi o dati dimostrativi; le build con `-Pbinario.punctualityStats=false` non la inviano mai.
- **Decoder Wear OS**: `WearService.reliability` (`WearReliability(onTime, samples)`); i decoder Apple Watch non la ricevono, perché il produttore iOS non la invia.

## Fixture golden

- `android-to-wear-v1.json`: app Android nativa → Wear OS, stato operativo con due corse.
- `android-to-wear-v1-reliability.json`: come la precedente, con la coppia facoltativa di affidabilità sulla prima corsa soltanto.
- `companion-to-wear-v1.json`: Companion → Wear OS, pianificazione locale senza informazione ferroviaria realtime.
- `ios-to-apple-watch-v1.json`: schema previsto iOS → Apple Watch tramite il contesto WCSession esistente.

Ogni modulo ha test di compatibilità propri contro queste fixture. Una modifica intenzionale deve aggiornare prima questa specifica, poi la fixture e tutti i decoder interessati; non deve essere nascosta dietro campi extra tollerati.
