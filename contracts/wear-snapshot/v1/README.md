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

`generatedAtEpochMillis` identifica l'istante di generazione dello snapshot; `expiresAtEpochMillis` è il limite assoluto di visualizzazione e `ttlMillis` rende verificabile la finestra senza interpretazioni implicite del trasporto. Il produttore Android fissa la finestra di visualizzazione a 15 minuti da `generatedAtEpochMillis`, preso subito prima della scrittura sul Data Layer, e mai oltre 15 minuti dopo la validità dello snapshot alla fonte: la durata della cache della fonte (30–60 secondi per i tabelloni in tempo reale) non accorcia più la finestra, e uno snapshot caricato da tempo non viene ripubblicato come attuale. A un orologio che dichiara `binario_wear_programmed_fallback_v1` lo snapshot dura invece fino all'orario programmato dell'ultima corsa inviata, e i valori live restano limitati ai 15 minuti (vedi *Ricaduta sull'orario programmato*). Per i dati operativi il watch mostra l'ora di `provenanceObservedAtEpochMillis` nella riga di stato («Dati aggiornati alle 08:12»), così i valori in tempo reale non sembrano attuali per tutta la finestra. Il Companion usa la scadenza della pianificazione locale.

Gli orari `service{n}Scheduled` e `service{n}Expected` del produttore Android sono orari locali `HH:mm` di Europe/Rome (`RailTime.defaultZone`): il watch Wear ne ricava la prima occorrenza non anteriore di oltre due ore a `generatedAtEpochMillis` (una corsa del mattino dopo elencata la sera è nel futuro) per il conto alla rovescia e per passare alla corsa successiva e, se l'orario non è interpretabile, mostra il testo così com'è.

Il produttore Android non invia come `service{n}` una corsa di cui la fonte ha confermato la soppressione: Tile e complication mostrano la prima corsa come «prossimo treno» senza il suo stato. Il campo `notice` del produttore Android contiene l'avviso nella lingua del telefono (la prima soppressione confermata fra le corse lasciate fuori, «Soppresso: R 2345 delle 08:15»; altrimenti fonte di copertura non ufficiale, fonti in conflitto, altrimenti il primo avviso di qualità dello snapshot), mai il testo libero della fonte dati; senza avvisi il campo è vuoto.

Il set di chiavi è chiuso: comprende le chiavi obbligatorie e i soli campi facoltativi descritti in *Campo facoltativo: affidabilità* e *Campo facoltativo: mezzo*. Non sono ammessi segreti o materiale sensibile: token, password, credenziali, header/autorizzazioni, identificativi di sessione, posizione/coordinate, tracce GPS, audio, trascrizioni o comandi vocali. In particolare non esistono campi per location, latitude, longitude, audio, transcript o voice. I valori non elencati non possono essere aggiunti in v1: richiedono una nuova `schemaVersion` e fixture. Le sole eccezioni sono i campi facoltativi di affidabilità e di mezzo, inviati solo ai decoder che dichiarano di conoscerli (vedi *Come un orologio dichiara i campi facoltativi*).

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
| `service{n}Vehicle` | enum | **Facoltativo**, per un `n < serviceCount`: `TRAIN`, `BUS_TRENITALIA`, `FRECCIALINK`, `ITALOBUS`, `BUS`, `REPLACEMENT_BUS`, `UNKNOWN`; vedi sotto. |

`UNAVAILABLE` richiede `serviceCount: 0` e provenienza `UNAVAILABLE`. `PROGRAMMED` dichiara esplicitamente che i dati non sono realtime. Le fixture sono esempi stabili: i test usano un clock fissato, non l'orologio reale.

## Campo facoltativo: affidabilità (aggiunto il 4 novembre 2026)

Una corsa può portare la propria affidabilità nelle statistiche di rete non ufficiali che l'app Android include (`assets/punctuality/stats_v1.json.gz`, aggregati di osservazioni ViaggiaTreno elaborati da Binario): il watch la mostra come «In orario 22 volte su 27» sotto la corsa nella schermata dell'app, non nella complication né nel Tile.

| Chiave | Tipo | Vincoli |
|---|---|---|
| `service{n}ReliabilitySamples` | integer | Giorni con una rilevazione alla fermata di arrivo della tratta, tra 5 e 10.000. Sotto 5 rilevazioni il produttore non invia la coppia. |
| `service{n}ReliabilityOnTime` | integer | Giorni in orario fra quelli, tra 0 e `service{n}ReliabilitySamples`, entro il limite della categoria (5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo, 15 per le altre). |

- **Compatibilità**: la versione resta `schemaVersion: 1`. Un payload senza la coppia è valido come prima, quindi i produttori che non la conoscono (Companion) non cambiano e i decoder aggiornati accettano ogni payload precedente. Una coppia incompleta, fuori intervallo, di tipo errato o per un indice `n >= serviceCount` fa rifiutare il payload intero, come ogni altro campo non valido.
- **Decoder precedenti**: un decoder che non conosce la coppia la tratta come chiave non prevista e rifiuta il payload. Per questo il produttore Android la aggiunge solo se tutti i nodi Wear connessi pubblicano la capability Data Layer `binario_wear_reliability_v1` (dichiarata dall'app Wear in `wear/src/main/res/values/wear.xml`) e la toglie dal payload conservato prima di reinviarlo se un orologio connesso nel frattempo non la pubblica; senza orologi connessi o con la verifica non riuscita non la invia.
- **Origine del dato**: il telefono la ricava sul dispositivo, senza richieste di rete, per un treno diretto della tratta (partenza dalla stazione di origine della tratta, misura alla destinazione), mai per bus sostitutivi, viaggi con cambi o dati dimostrativi; le build con `-Pbinario.punctualityStats=false` non la inviano mai.
- **Produttore iOS** (dal 23 novembre 2026): la stessa coppia, ricavata sull'iPhone dalle stesse statistiche incluse nell'app (`binario_punctuality_stats_v1.json.gz`) con la stessa regola (`NetworkReliability.match` dall'origine alla destinazione della ricerca, mai per un bus), solo se il Watch ha dichiarato `binario_wear_reliability_v1`.
- **Decoder**: Wear OS `WearService.reliability` (`WearReliability(onTime, samples)`); Apple Watch `WatchService.reliability` (`WatchReliability`). Entrambi la mostrano come «In orario 22 volte su 27» sotto la corsa nella schermata dell'app, con «(statistiche non ufficiali)» per l'accessibilità, mai nella complication, nel Tile o nel widget.

## Campo facoltativo: mezzo (aggiunto il 23 novembre 2026)

Una corsa può dire su che cosa viaggia, con i valori di `JourneyLegVehicle` di Android per i modi di un pack ferroviario e di `TimetableVehicle` dell'iPhone (casi comuni in `contracts/shared-rules/v1/journey-leg-vehicles-v1.json`).

| Chiave | Tipo | Vincoli |
|---|---|---|
| `service{n}Vehicle` | string | 1–32 caratteri normalizzati: `TRAIN` (treno), `BUS_TRENITALIA` («Bus Trenitalia»), `FRECCIALINK`, `ITALOBUS`, `BUS` (pullman di un altro operatore), `REPLACEMENT_BUS` (bus sostitutivo). |

- **Compatibilità**: la versione resta `schemaVersion: 1`. Il campo vale per una corsa sola e può mancare su qualunque corsa: un payload senza il campo, di un produttore precedente o di una copia salvata prima, è valido come prima e la corsa è un **treno**. Un valore sconosciuto diventa `UNKNOWN` e la corsa si mostra come un treno, senza icona del bus; un valore vuoto, non normalizzato, oltre 32 caratteri, non testuale o per un indice `n >= serviceCount` fa rifiutare il payload intero.
- **Etichetta**: per un bus `service{n}Category` è l'etichetta del mezzo nella lingua del telefono («Bus Trenitalia», «ItaloBus», «Bus sostitutivo»; sull'iPhone con la sigla della linea quando aggiunge qualcosa, «Bus Trenitalia BUS», come nelle righe dell'app), mai «Treno». Vale anche per i telefoni che non inviano il campo, perché la categoria è un testo libero: un orologio precedente mostra già l'etichetta, senza icona.
- **Produttori**: Android lo ricava da `TrainService.scheduledBus` (pullman dell'operatore dei pack `scheduledCoachPacks`, con la linea `busLine` per FrecciaLink) e `JourneyLegVehicle.of`; un treno è `TRAIN`. L'iPhone lo ricava da `TrainService.vehicle` (`TimetableVehicle`); un servizio senza mezzo è `TRAIN`. Per una linea urbana l'iPhone invia anche `METRO`, `TRAM` o `FUNICULAR`, con il modo come categoria («Metro») e la linea come numero («M1»): gli orologi di oggi li leggono `UNKNOWN`, senza icona del bus. Nessuno dei due lo invia a un orologio che non ha dichiarato `binario_wear_vehicle_v1`.
- **Decoder**: Wear OS `WearService.vehicle` (`WearVehicle`), Apple Watch `WatchService.vehicle` (`WatchVehicle`). Su Wear OS l'app mostra l'icona del bus prima dell'orario e l'etichetta come categoria; il Tile e la complication sono solo testo, per i treni come per i bus, e mostrano l'etichetta prima del numero («08:10 · Bus Trenitalia 3601»). Su Apple Watch l'app, il dettaglio della corsa e i widget (circolare, rettangolare, in linea) mostrano il simbolo `bus` con l'etichetta. L'accessibilità dice l'etichetta e il numero, mai «treno».

## Come un orologio dichiara i campi facoltativi

Un decoder precedente rifiuta una chiave che non conosce, quindi un produttore aggiunge un campo facoltativo solo a un orologio che dichiara di conoscerlo, e lo toglie da un payload conservato prima di reinviarlo a un orologio che non lo dichiara. Allo stesso modo invia la durata oltre la finestra live solo a un orologio che dichiara di mostrare l'orario programmato alla fine dei valori live.

| Campo | Capability |
|---|---|
| Affidabilità (`service{n}ReliabilityOnTime`, `service{n}ReliabilitySamples`) | `binario_wear_reliability_v1` |
| Mezzo (`service{n}Vehicle`) | `binario_wear_vehicle_v1` |
| Nessun campo: durata fino all'ultima corsa (*Ricaduta sull'orario programmato*) | `binario_wear_programmed_fallback_v1` |

- **Wear OS**: capability del Data Layer pubblicate dall'app Wear in `wear/src/main/res/values/wear.xml`. Il telefono Android aggiunge un campo solo se tutti i nodi connessi pubblicano la sua capability; senza orologi connessi o con la verifica non riuscita non lo invia.
- **Apple Watch**: all'attivazione di Watch Connectivity l'app Watch pubblica verso l'iPhone un application context `{"binario_watch_capabilities": ["binario_wear_reliability_v1", "binario_wear_vehicle_v1", "binario_wear_programmed_fallback_v1"]}` (soltanto questi nomi, nessun altro dato). L'iPhone lo legge da `receivedApplicationContext` e da `session(_:didReceiveApplicationContext:)` e, quando cambia, pubblica di nuovo lo snapshot del Watch; senza il contesto (un'app Watch precedente) non aggiunge i campi facoltativi.

## Ricaduta sull'orario programmato (aggiunta il 23 novembre 2026)

I valori live di uno snapshot `OPERATIONAL` valgono 15 minuti da `generatedAtEpochMillis`. In v1 orario previsto, ritardo, binario e stato di una corsa sono la parte live; l'orario programmato resta in `service{n}Scheduled`. Prima, alla fine dei 15 minuti lo snapshot scadeva e l'orologio mostrava i dati scaduti (Wear OS) o non disponibili (Apple Watch) fino alla richiesta successiva; ora ne mostra l'orario programmato.

- **Orologio** (Wear OS `WearSnapshot.shownAt`, Apple Watch `BinarioWatchSnapshot.programmedFallback`): da 15 minuti dopo `generatedAtEpochMillis` fino a `expiresAtEpochMillis` mostra lo snapshot programmato contenuto in quello live, senza nuova richiesta. Ogni corsa all'orario programmato, ricavato come istante previsto meno `DelayMinutes` (così una corsa in ritardo programmata prima della generazione resta nel suo giorno), in ordine di orario programmato; una corsa già partita secondo l'orario programmato alla fine dei 15 minuti non è mostrata (con la regola di ciascun orologio: Wear OS un minuto dopo la partenza, Apple Watch alla partenza). Ritardo 0, nessun binario, stato «Programmato»; lo snapshot è `PROGRAMMED` / `STATIC_ONLY`, senza fonte. L'avviso è quello degli snapshot programmati del suo telefono: nessuno su Wear OS, «Orari programmati statici. Non sono dati in tempo reale.» su Apple Watch. Affidabilità e mezzo restano. App, Tile, complication e widget cambiano a quell'istante (una voce della timeline), poi seguono le partenze programmate. Poiché lo snapshot può durare fino all'ultima corsa, anche l'app Wear OS toglie dall'elenco una corsa partita (`WearSnapshot.listedAt`), come Tile e complication.
- **Android**: se tutti i nodi connessi pubblicano la capability, `expiresAtEpochMillis` è il più tardi fra la finestra di 15 minuti e due minuti dopo l'orario programmato (`scheduledDepartureDateTime`, Europe/Rome) dell'ultima corsa inviata, al più 12 ore dopo `generatedAtEpochMillis`; vale anche per uno snapshot `PROGRAMMED`. Non estende uno snapshot senza corse o con una corsa senza data programmata, né uno `OPERATIONAL` la cui validità alla fonte era già finita alla scrittura (i suoi valori live varrebbero meno di 15 minuti da `generatedAtEpochMillis`). Prima di reinviare il payload conservato a un orologio che non dichiara la capability lo riporta a 15 minuti da `generatedAtEpochMillis`, o non lo invia se sono passati.
- **iPhone**: con la capability nel contesto del Watch uno snapshot `OPERATIONAL` dura fino a due minuti dopo l'orario dell'orario incluso dell'ultima corsa inviata (al più la scadenza dell'orario, 12 ore), come uno snapshot programmato. `generatedAtEpochMillis` di uno snapshot `OPERATIONAL` è l'ora della richiesta del tabellone (con `provenanceObservedAtEpochMillis` non successivo), così i 15 minuti del Watch sono quelli dei valori live.
- Un orologio senza la capability riceve lo snapshot di 15 minuti, come prima: mostrerebbe i valori live come attuali fino alla scadenza.

## Produttore iOS: dati in tempo reale

L'iPhone chiede le fonti live (RFI, Italo, ViaggiaTreno) soltanto in primo piano, per lo schermo mostrato. Quando pubblica lo snapshot del Watch, sovrappone agli orari programmati il tabellone live della stazione di partenza che ha già (del pianificatore o di «Partenze»), se ha meno di cinque minuti e **Dati in tempo reale** è attivo; per il Watch non chiede nulla di nuovo, nemmeno quando Watch Connectivity lo riattiva in background. Ogni corsa è abbinata alla sua riga live come nei risultati del pianificatore (`LiveOverlay.departures(for:in:)`). Le regole sono quelle del produttore Android:

- una corsa con orario previsto dalla fonte è verificata: `service{n}Expected` è l'orario previsto, `DelayMinutes` il ritardo, `Status` «In orario» o «In ritardo» nella lingua del telefono; il binario della fonte sostituisce quello dell'orario; una corsa senza valori live resta `PROGRAMMED`;
- le corse sono in ordine di partenza prevista e partono da adesso; una soppressione confermata non è inviata, e la prima lasciata fuori prima dell'ultima inviata diventa l'avviso «Soppresso: R 2345 delle 08:15»;
- con almeno una corsa verificata lo snapshot è `OPERATIONAL`, `FRESH`, con `confidence` `OPERATOR_PRIMARY` se ViaggiaTreno o Italo hanno dato un valore delle corse inviate, altrimenti `INFRASTRUCTURE_PRIMARY` (RFI), con l'etichetta della fonte della lingua del telefono e `provenanceObservedAtEpochMillis` dell'osservazione più recente; l'avviso è quello della soppressione, altrimenti «Fonti in conflitto…» quando il tabellone le segnala, altrimenti vuoto. `generatedAtEpochMillis` è l'ora della richiesta del tabellone e lo snapshot scade al più 15 minuti dopo, come su Android, o all'ultima corsa per un Watch che dichiara la ricaduta sull'orario programmato;
- senza corse verificate lo snapshot resta `PROGRAMMED` / `STATIC_ONLY`, con l'avviso degli orari programmati (o quello della soppressione) e la scadenza due minuti dopo l'ultima corsa.

## Fixture golden

- `android-to-wear-v1.json`: app Android nativa → Wear OS, stato operativo con due corse.
- `android-to-wear-v1-reliability.json`: come la precedente, con la coppia facoltativa di affidabilità sulla prima corsa soltanto.
- `companion-to-wear-v1.json`: Companion → Wear OS, pianificazione locale senza informazione ferroviaria realtime.
- `android-to-wear-v1-vehicle.json`: app Android → Wear OS, orario programmato con un Bus Trenitalia e un treno, con il campo facoltativo del mezzo.
- `ios-to-apple-watch-v1.json`: iOS → Apple Watch tramite il contesto WCSession esistente, stato operativo senza campi facoltativi (ciò che riceve un'app Watch che non li dichiara).
- `ios-to-apple-watch-v1-optional.json`: iOS → Apple Watch, stato operativo con i campi facoltativi: affidabilità sulla prima corsa, mezzo di entrambe (un treno e un ItaloBus).

Ogni modulo ha test di compatibilità propri contro queste fixture. Una modifica intenzionale deve aggiornare prima questa specifica, poi la fixture e tutti i decoder interessati; non deve essere nascosta dietro campi extra tollerati.

## Wear OS e Apple Watch: cosa resta diverso

Dal 23 novembre 2026 i due orologi ricevono gli stessi dati: stati operativi, orario previsto, ritardo, binario e soppressioni (sull'iPhone solo dai valori live che ha già, vedi sopra), affidabilità e mezzo, e mostrano l'ora della rilevazione nella riga di stato dei dati live («Dati aggiornati alle 08:12»). Restano diverse le superfici, le azioni e il modo in cui il telefono aggiorna lo snapshot, che dipendono dalla piattaforma:

| Aspetto | Wear OS | Apple Watch |
|---|---|---|
| Superfici | App (schede delle due corse), Tile, complication `SHORT_TEXT` (conto alla rovescia) e `LONG_TEXT` | App (righe delle due corse e dettaglio di ciascuna), widget `accessoryCircular`, `accessoryRectangular` (con conto alla rovescia) e `accessoryInline`, usati come complication e nello Smart Stack; nessun Tile |
| Azioni | «Aggiorna sul telefono»; un tocco su Tile o complication apre l'app | «Aggiorna»; all'apertura senza treni da mostrare l'app chiede da sola una volta all'iPhone; l'App Intent «Aggiorna Binario» rilegge la cache e ricarica il widget |
| Aggiornamento dal telefono | Android ricarica la tratta dalle fonti, anche live, in un lavoro in background | L'iPhone ripete la ricerca nell'orario incluso e sovrappone i valori live che ha già: non chiede nulla alle fonti per il Watch, perché le fonti live si chiedono solo in primo piano |
| Durata dello snapshot | Due minuti dopo l'orario programmato dell'ultima corsa inviata (al più 12 ore), con i valori live per i primi 15 minuti dalla scrittura; a un'app Wear precedente, 15 minuti dalla scrittura | Due minuti dopo l'ultima corsa elencata (al più 12 ore), con i valori live per i primi 15 minuti dalla lettura del tabellone; a un'app Watch precedente, con valori live, 15 minuti dalla lettura |
| Dopo i 15 minuti dei valori live | Orario programmato senza avviso, come gli snapshot programmati di Android | Orario programmato con l'avviso «Orari programmati statici…», come gli snapshot programmati dell'iPhone |
| Cosa pubblica il telefono | La tratta attiva della scheda Treni, per l'orario corrente (mai una ricerca di Pianifica) | La ricerca del pianificatore per l'orario corrente, altrimenti la tratta scelta con «Mostra su Watch» |
| Corsa partita | Lasciata un minuto dopo la partenza prevista | Lasciata alla partenza prevista |
| Categoria e numero | Nome della categoria nella lingua del telefono («Freccia») e numero con la sigla («FR 9505») | Sigla della linea dell'orario («FR») e numero («9505»), come nelle righe dell'iPhone |
| `routeId` | Sempre `"active"` | Identificativo derivato dalle due stazioni (`S08409--S09218`), non dalla tratta salvata |
| Stato vuoto | Distingue app del telefono assente, dati scaduti e condivisione da attivare | Un solo stato «Dati non disponibili» |
