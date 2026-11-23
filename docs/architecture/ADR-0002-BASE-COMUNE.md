# ADR-0002 — Base comune tra i dispositivi

**Stato:** Accettata
**Data:** 23 novembre 2026
**Ambito:** ciò che Android, Wear OS, iPhone, Apple Watch e la skill Alexa condividono, e ciò che ognuno tiene per sé

## Contesto

Il titolare ha chiesto: «Per agevolare il mantenimento e l'aggiornamento futuro vorrei che ci fosse una base comune tra tutti i dispositivi e poi ognuno con le sue specificità.»

Binario ha tre app native: Kotlin per Android e Wear OS (`app/`, `wear/`), Swift per iPhone e Apple Watch (`ios/BinarioApp`, `ios/BinarioKit`, `apple-watch/`), TypeScript per la skill Alexa (`alexa-companion/server`, `alexa-companion/shared`). A queste si aggiunge la pipeline dati in Python (`tools/`, test in `tests/`).

Una parte della base comune esisteva già. I file dati (pack GTFS, statistiche di puntualità, catalogo delle stazioni, pin dei pack esteri) sono prodotti una sola volta dalla pipeline e letti da tutte le piattaforme. Le regole dei numeri dei treni e delle statistiche hanno casi di prova comuni (`contracts/shared-rules/v1/rules-v1.json`). Gli snapshot verso gli orologi e la dettatura hanno contratti propri (`contracts/wear-snapshot/`, `contracts/voice-input/`).

Molte tabelle, invece, erano ricopiate a mano in Kotlin, in Swift e a volte in TypeScript: i codici Italo di riserva, i place ID del monitor RFI, i punteggi delle fonti, le categorie misurate a 5 minuti, i collegamenti con gli aeroporti, il calendario delle festività, i limiti dell'Agenda. Ogni modifica andava fatta due o tre volte, e una copia dimenticata non faceva fallire nessun test.

## Opzioni considerate

| Approccio | Vantaggi | Svantaggi | Decisione |
|---|---|---|---|
| App native con una base comune di dati, tabelle generate, casi di prova e contratti | Nessun runtime condiviso: ogni app resta nel suo linguaggio e nella sua toolchain. Ogni tabella ha una sola fonte, e la CI verifica che il codice generato sia aggiornato | Gli algoritmi restano scritti in ogni linguaggio, tenuti allineati dai casi di prova | **Scelto** |
| Kotlin Multiplatform per il dominio | Un solo codice per le regole di Android e iPhone | Vedi «Perché non Kotlin Multiplatform, per ora» | Non scelto ora |
| Tabelle JSON lette a runtime dalle app | Nessun generatore | Ogni piattaforma dovrebbe leggere i file e gestirne gli errori all'avvio. Nessun controllo dei tipi in compilazione, e una risorsa in più in ogni build | Non scelto |

## Decisione

Le tre app restano native: niente Kotlin Multiplatform, niente runtime condiviso. La base comune ha quattro livelli.

1. **Dati.** Una sola pipeline (`tools/`) produce ogni file dati una volta, e tutte le piattaforme leggono gli stessi file. Vale già per i pack GTFS, le statistiche di puntualità, il catalogo delle stazioni e i pin dei pack esteri.
2. **Tabelle condivise.** Stanno in `contracts/shared-data/v1/*.json` ([README](../../contracts/shared-data/README.md)), e `tools/generate_shared_code.py` le trasforma in codice:
   - un file Kotlin, `app/src/main/java/it/binario/app/shared/SharedData.kt`, con tutte le tabelle;
   - un file Swift, `ios/BinarioKit/Sources/BinarioKit/Generated/SharedData.swift`, con tutte le tabelle: namespace `public enum` con costanti `static let` di tipi valore `Sendable`;
   - un modulo TypeScript, `alexa-companion/shared/generated/sharedData.ts`, con le sole tabelle che la skill legge.

   Ogni file generato comincia con un'intestazione che nomina il generatore e le tabelle e dice di non modificarlo. I file generati sono nel repository. Prima di scrivere qualsiasi cosa, il generatore controlla ogni JSON con lo schema che definisce: tipi, chiavi obbligatorie, nessuna chiave sconosciuta o ripetuta, nessuna voce doppia, più i controlli incrociati della tabella. `--check` fallisce quando un file generato non corrisponde alle tabelle e gira nella CI (`gtfs-assets-ci.yml`). Le app leggono le costanti come codice, mai interpretando JSON a runtime.
3. **Regole condivise.** I casi di prova stanno in `contracts/shared-rules/v1/` ([README](../../contracts/shared-rules/v1/README.md)), e i test di ogni piattaforma li leggono così come sono. L'implementazione della regola resta nel linguaggio di ogni app.
4. **Contratti tra dispositivi.** `contracts/wear-snapshot/` (telefono → Wear OS e Apple Watch) e `contracts/voice-input/` (dettatura).

### Tabelle migrate il 23 novembre 2026

| Tabella | Prima | Ora letta da |
|---|---|---|
| `italo-stations-v1.json`: 50 codici Italo di riserva, 15 gruppi di alias, pagine Italo note per nome | `ItaloPublicStationFallbacks.kt`, `ItaloStationSlugs.byName`; `ItaloLive.swift` | Android, iPhone |
| `rfi-place-ids-v1.json`: 25 place ID del monitor RFI per nome | `RfiStationIds.byName`; `RfiLiveBoard.swift` | Android, iPhone |
| `source-scores-v1.json`: punteggi delle fonti per categoria, bonus dell'operatore, fonti di sola copertura | `RailSourceSelectionPolicy.kt`; `LiveSourcePolicy` in `LiveRailData.swift` | Android, iPhone |
| `punctuality-v1.json`: categorie a 5 minuti (FR FA FB EC ES ESC EN ITALO), soglie e campioni minimi | `NetworkReliabilityStats.kt`, `NetworkReliabilityAggregates.kt`; `NetworkReliability.swift`; `server/punctuality/stats.ts` | Android, iPhone, Alexa |
| `train-number-prefixes-v1.json`: categorie ammesse davanti al numero di un treno | `TrainNumberSearchService.kt`; `ViaggiaTrenoLive.swift` | Android, iPhone |
| `airport-routes-v1.json`: collegamenti con gli aeroporti e pack di linee urbane | `AirportRoutes.kt`; `AirportRoutes.swift` | Android, iPhone (anche `byCityLine`) |
| `italian-holidays-v1.json`: festività nazionali e giorni contati dalla Pasqua | `ItalianRailServiceCalendar.kt`; `server/punctuality/stats.ts` | Android, Alexa (generata anche per l'iPhone) |
| `planned-journeys-v1.json`: limiti dell'Agenda, del file di trasferimento e dei modelli | `PlannedJourneyDomain.kt`, `PlannedJourneyTransfer.kt`, `PlannedJourneyTemplates.kt`; `PlannedJourneys.swift`, `PlannedJourneyTemplates.swift` | Android, iPhone |

Restano fuori, coperti da casi di prova in `contracts/shared-rules/v1/`, la parola che segna una soppressione esplicita e l'operatore di una riga del monitor RFI (`live-text-v1.json`), i modelli di «Preparazione del viaggio» (`checklist-presets-v1.json`) e il calcolo della Pasqua (`calendar-v1.json`). Le espressioni regolari non diventano dati perché Java e ICU non leggono `\b` e `\w` allo stesso modo sui caratteri non ASCII. L'operatore della riga RFI non è una tabella pulita: Android distingue tre servizi Trenitalia, l'iPhone uno. I modelli di preparazione sono enum legati ai testi tradotti di ogni app. Restano fuori anche i limiti dello snapshot per gli orologi: Wear OS e Apple Watch non dipendono da `app/` né da BinarioKit, quindi non possono leggere il codice generato, e quei limiti li governa già il contratto `contracts/wear-snapshot/v1` con le sue fixture.

### Cosa resta specifico di ogni piattaforma

- **Interfaccia**: Jetpack Compose e Wear Compose su Android, SwiftUI su iPhone e Apple Watch, voce e schermate APL su Alexa.
- **Integrazioni di sistema**: widget, notifiche, Tile e complication, Live Activities e WidgetKit, Data Layer di Wear OS, WatchConnectivity, account linking e modello di interazione di Alexa.
- **Client di rete** delle fonti live: richieste HTTP, lettura di pagine e payload, pause dopo un rifiuto, cache.
- **Algoritmi e normalizzazioni del testo** (chiavi dei nomi di stazione, Pasqua, espressioni regolari): restano nel linguaggio di ogni app, ma quando sono comuni hanno i loro casi in `contracts/shared-rules/v1/`.
- **Valori di una sola piattaforma**: per esempio gli identificativi storici delle stazioni di Android (`byLegacyId` di `RfiStationIds` e `ItaloStationSlugs`), che l'iPhone non usa.

### La regola da ora in poi

**Una tabella o una regola presente su più di una piattaforma vive nella base comune**: la tabella in `contracts/shared-data/`, la regola con i suoi casi in `contracts/shared-rules/`. Una copia scritta a mano in un'app è un difetto da correggere. Ciò che una sola piattaforma usa resta nel suo codice, e passa alla base comune il giorno in cui serve a una seconda.

## Come si fa

### Aggiungere una tabella

1. Scrivere `contracts/shared-data/v1/<nome>-v1.json` con `"schema": "binario-shared-data-v1"`, una `description` in inglese (diventa il commento del codice) e i valori. Le voci possono avere una `note`, che diventa un commento.
2. Registrare la tabella in `TABLES` di `tools/generate_shared_code.py`: il nome del namespace, lo schema dei campi (`Integer`, `Text`, `Boolean`, `Nullable`, `ListOf`, `MapOf`, `Record`) con limiti, pattern e unicità, gli eventuali controlli incrociati, e `alexa=True` se la skill la legge.
3. Eseguire `python3 tools/generate_shared_code.py` e sostituire nel codice la copia a mano con `SharedData.<Nome>`.
4. Aggiungere un test per piattaforma che legge il JSON e lo confronta con il codice che usa la tabella (`SharedDataTest.kt`, `SharedDataTests.swift`, `server/punctuality/sharedData.test.ts`). Se lo schema ha controlli nuovi, aggiungere i loro casi in `tests/test_generate_shared_code.py`.
5. Fare il commit del JSON e dei file generati. La CI esegue `--check`, e i workflow Android, Apple e Alexa partono quando cambia `contracts/shared-data/**` o il generatore.

Un JSON della directory non ancora registrato viene segnalato e non genera codice, quindi una tabella può arrivare prima del suo schema. Le tabelle `italo-gtfs-station-codes.json` e `notices-sources-v1.json`, in arrivo con altri lavori, passeranno al generatore con questi stessi passi.

### Aggiungere un caso a una regola

1. Aggiungere il caso al file della regola in `contracts/shared-rules/v1/`: un solo atteso, uguale per tutte le piattaforme che lo leggono.
2. Se la regola è nuova, creare un file con `"schema": "binario-shared-rules-v1"` e il campo `readers` (il test di ogni piattaforma), e scrivere quei test: leggono il file così com'è, dalla checkout del repository.
3. Se un'implementazione dà un risultato diverso, si corregge l'implementazione. Si cambia il caso solo se è sbagliata la regola, e allora per tutte le piattaforme.

### Aggiungere una fonte dati

1. La pipeline in `tools/` produce il file una volta sola. Tutte le piattaforme che lo usano leggono quel file, copiato a build time (per esempio `ios/Scripts/sync-punctuality-stats.py`, `alexa-companion/scripts/rail-data.mjs`), senza conversioni proprie.
2. Il punteggio della fonte per categoria e l'eventuale bonus dell'operatore vanno in `source-scores-v1.json`, non nel codice di un'app.
3. Licenza, host e politica vanno in `docs/data-sources/SOURCE_POLICY.md` e in `PRIVACY.md`. Il client di rete resta specifico di ogni piattaforma.

### Una funzione presente su più piattaforme

1. Prima la base comune: le tabelle in `contracts/shared-data/`, i casi delle regole in `contracts/shared-rules/`, un contratto in `contracts/` se i dispositivi si scambiano dati.
2. Poi l'implementazione in ogni app, con i test che leggono gli stessi file.
3. Infine l'interfaccia e le integrazioni di ogni piattaforma, che restano libere: ciò che è comune è il dato e la regola, non la schermata.

## Perché non Kotlin Multiplatform, per ora

- **BinarioKit andrebbe riscritto.** Sono 42 file Swift, circa 15.000 righe, con i suoi test: tutto da portare in Kotlin, e nel frattempo due implementazioni da mantenere.
- **La build iOS acquisterebbe Kotlin/Native e Gradle.** Oggi l'iPhone si costruisce con Xcode e XcodeGen soltanto (`ios/project.yml`, `apple-watch-ci.yml`). Un framework KMP porterebbe la JVM, Gradle e la toolchain Kotlin/Native nella CI macOS e nella build di ogni sviluppatore iOS.
- **La concorrenza stretta di Swift 6 si integra male.** Il codice Kotlin arriva in Swift attraverso un'interfaccia Objective-C: i tipi non sono `Sendable`, le funzioni sospese diventano callback o richiedono adattatori, e BinarioKit compila in `swiftLanguageModes: [.v6]`.
- **Alexa resterebbe comunque a parte.** La skill è in TypeScript: con KMP le servirebbero comunque i dati, le tabelle e i casi di prova comuni descritti qui.

Si potrà riconsiderare per il nucleo del dominio (regole delle statistiche, numeri dei treni, Agenda), quando l'interoperabilità con Swift 6 sarà matura e il costo della riscrittura sarà giustificato. La base comune di questo ADR prepara quel passo: gli stessi casi di prova verificherebbero il nuovo codice.

## Conseguenze operative

- Le tabelle si modificano solo nel JSON. `python3 tools/generate_shared_code.py` riscrive il codice, e `--check` (CI `gtfs-assets-ci.yml`, job Python) impedisce di dimenticarlo.
- I workflow Android, Apple e Alexa partono quando cambiano `contracts/shared-data/**` o il generatore; `tools/verify_ci_pinning.py` verifica questi filtri e lo step `--check`.
- Il codice generato Swift compila solo sulla CI macOS (Swift 6, iOS 17, watchOS 10). Non richiede modifiche a `ios/project.yml`, perché il pacchetto BinarioKit include tutte le sottocartelle di `Sources/BinarioKit`, compresa `Generated/`.
- Il comportamento delle app non cambia: i valori migrati erano identici su tutte le piattaforme, verificati prima della migrazione. L'unica funzione nuova è `AirportRoutes.byCityLine` sull'iPhone, che oggi non trova linee urbane perché nessun pack urbano arriva sull'iPhone.
