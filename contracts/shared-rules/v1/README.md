# Regole condivise v1: numeri dei treni, statistiche, testi live, Agenda, calendario, codici stazione, mezzi, avvisi e prossimi treni
# Regole condivise v1: numeri dei treni, statistiche, testi live, Agenda, calendario, codici stazione, mezzi, avvisi, aggiornamenti delle corse e tabelloni svizzeri

Casi di prova comuni ad Android, iPhone, Alexa e agli strumenti Python che costruiscono l'indice orario dell'iPhone: il terzo livello della base comune
([ADR-0002](../../../docs/architecture/ADR-0002-BASE-COMUNE.md)). Le implementazioni restano indipendenti, scritte nel
linguaggio di ogni app: condividono soltanto questi file, che i test leggono così come sono. Le tabelle (valori, non
regole) stanno invece in [`contracts/shared-data/`](../../shared-data/README.md) e diventano codice generato.

| File | Contenuto | Lettori |
|---|---|---|
| `bike-sharing-v1.json` | Sistemi che servono una stazione per build, sotto-feed ammessi, documenti GBFS 2 e 3 (quelli del test Android), cosa elenca la sezione entro 300 m | `app/src/test/java/it/binario/app/data/bikesharing/BikeSharingContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/BikeSharingContractTests.swift` |
| `calendar-v1.json` | Le festività nazionali, comprese Pasqua e Pasquetta | Android, Alexa |
| `checklist-presets-v1.json` | Le righe di ogni modello di «Preparazione del viaggio» e come un modello si aggiunge al testo dell'editor | Android, iPhone |
| `delay-compensation-v1.json` | Quando il dettaglio del treno mostra l'indennizzo per ritardo: arrivo effettivo verificato all'ultima fermata, 25 % da 60 minuti, 50 % da 120 | `app/src/test/java/it/binario/app/data/DelayCompensationContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/ExtrasContractTests.swift` |
| `destination-weather-v1.json` | Stazione e giorno della previsione, coordinate e richiesta a Open-Meteo, lettura della risposta (fixture Android `open-meteo-lugano.json`), codici WMO con un testo | `app/src/test/java/it/binario/app/data/weather/DestinationWeatherContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/ExtrasContractTests.swift` |
| `evening-alerts-v1.json` | Promemoria degli scioperi delle 18 e avviso dei lavori di domani delle 19: quando girano, quando possono avvisare, cosa avvisano e cosa ricordano | `app/src/test/java/it/binario/app/notifications/EveningAlertsContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/ExtrasContractTests.swift` |
| `gtfs-realtime-v1.json` | I Trip Updates GTFS-Realtime: lettura del protobuf, feed SNCF, Trenitalia France e STA, raccordo con le corse dell'orario incluso, feed urbani di Roma e ACTV. Gli ingressi sono protobuf in esadecimale o le fixture dei test Android | `app/src/test/java/it/binario/app/data/live/GtfsRealtimeContractTest.kt` (con le binding protobuf e gli adapter Android), `ios/BinarioKit/Tests/BinarioKitTests/GtfsRealtimeContractTests.swift` |
| `gtfs-station-codes-v1.json` | `stop_code`, nome e posizione di una fermata dei pack Trenitalia e Italo → stazione del catalogo attesa (o nessuna), sul catalogo `app/src/main/assets/stations/curated_it_rail_stations.json` | `app/src/test/java/it/binario/app/data/gtfs/GtfsStationCodesContractTest.kt`, `tests/test_ios_gtfs_schedule.py` (regola di `tools/android_station_codes.py`, usata dal builder dell'indice iPhone) |
| `journey-leg-vehicles-v1.json` | `route_type`, pack, operatore e linea di una corsa → modo della tratta, mezzo e chiave dell'etichetta (Android e iPhone; la skill dice l'etichetta tradotta) | `app/src/test/java/it/binario/app/journeys/JourneyLegVehiclesContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `alexa-companion/server/timetable/naming.test.ts` |
| `live-sources-v1.json` | L'operatore di una riga o corsa ViaggiaTreno (`compImpresa`, poi `codiceCliente`), una riga del tabellone arrivi di ViaggiaTreno, gli avvisi per treno validi in un giorno sulle fixture Android (`app/src/test/resources/sources/viaggiatreno-smartcaring-*.json`) | `app/src/test/java/it/binario/app/data/live/LiveSourcesContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/LiveSourcesContractTests.swift` |
| `live-text-v1.json` | La soppressione esplicita in un testo live e l'operatore di una riga del monitor RFI | Android, iPhone |
| `live-train-positions-v1.json` | Treni in movimento: avanzamento sull'orario, posizione stimata, abbinamento dei ritardi, copie unite, i più vicini, etichette, area letta e quarto d'ora | `app/src/test/java/it/binario/app/maps/LiveTrainPositionsContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/LiveTrainPositionsContractTests.swift` |
| `next-trains-v1.json` | Un piccolo indice orario sintetico (schema `userVersion` 2 dell'indice iPhone) e i prossimi treni fra due stazioni da un istante: giorni di servizio, fusi delle aziende, corse oltre le 24:00 e cambio d'ora, copie di un treno tenute una volta, autobus, salita e discesa ammesse, orario scaduto | `ios/BinarioKit/Tests/BinarioKitTests/NextTrainsContractTests.swift` (`SQLiteGTFSJourneyRepository.journeys`), `alexa-companion/server/timetable/nextTrains.test.ts` |
| `notices-v1.json` | I casi degli avvisi ferroviari: giorni degli scioperi del calendario MIT, testo e collegamenti degli avvisi, infomobilità Trenitalia, avvisi Trenord per direttrice e per stazione, lavori e interruzioni sulle tratte. Gli ingressi sono le fixture dei test Android (`app/src/test/resources/sources/`) e il catalogo stazioni curato | `app/src/test/java/it/binario/app/data/notices/NoticesContractTest.kt` (con gli adapter e i matcher Android esistenti), `ios/BinarioKit/Tests/BinarioKitTests/NoticesContractTests.swift` |
| `punctuality-stats-v1.json` | Un file statistiche sintetico nel formato `binario-punctuality-v1` (docs/data-sources/PUNCTUALITY_STATS.md): codici `S000xx` e numeri inventati, nessun dato osservato | Android, iPhone, Alexa |
| `rules-v1.json` | Chiave del numero treno, query del numero, soglie «in orario», campioni minimi, treno misurato all'ultima fermata senza origine | Android, iPhone, Alexa |
| `swiss-station-board-v1.json` | I tabelloni svizzeri di `transport.opendata.ch`: numero UIC della stazione, ricerca per nome vicino al confine, lettura del tabellone, operatori, indirizzi chiesti | `app/src/test/java/it/binario/app/data/live/SwissStationBoardContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SwissStationBoardContractTests.swift` |
| `urban-stops-v1.json` | Nome mostrato, badge e minuti a piedi di una fermata di metro, tram o funicolare | `app/src/test/java/it/binario/app/data/gtfs/UrbanStopsContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `tests/test_ios_gtfs_schedule.py` |
| `weekday-remarks-v1.json` (con `weekday-remarks-stats-v1.json`) | Le osservazioni sui giorni della settimana nel dettaglio di un treno: al più due, la differenza maggiore prima, a parità il giorno prima | `app/src/test/java/it/binario/app/data/reliability/WeekdayRemarksContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift` |

Ogni file nomina i test che lo leggono nel campo `readers` (per `rules-v1.json`:
`app/src/test/java/it/binario/app/data/reliability/SharedRulesContractTest.kt`,
`ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `alexa-companion/server/punctuality/sharedRules.test.ts`).
I workflow Android, Apple, Alexa e GTFS (test Python) partono quando cambia questa directory; Android, Apple, Alexa e GTFS
anche quando cambia `contracts/shared-data/`.

## Tabelle condivise (`contracts/shared-data/v1`)

Le tabelle che questi casi usano sono nel generatore (`tools/generate_shared_code.py`, vedi
[`contracts/shared-data/README.md`](../../shared-data/README.md)): le app le leggono come codice generato, mai come JSON.

- `italo-gtfs-station-codes.json` è la tabella rivista dei codici Italo. Android legge il codice generato
  (`ItaloGtfsStationCodes`); il builder dell'indice iPhone legge il JSON (`tools/android_station_codes.py`, gli strumenti
  Python sono la pipeline).
- `notices-sources-v1.json` contiene le fonti degli avvisi: indirizzi dei feed, accesso (`all-builds` per MIT e RFI,
  `personal` per Trenitalia e Trenord), intervalli, durate di cache e di copia non aggiornata, limiti di risposta,
  reindirizzamenti, e le tabelle di lettura (parole del settore ferroviario e degli scioperi, mesi, radici operative
  Trenord, area Trenord, parole dei lavori, nomi comuni esclusi, domini ufficiali dei collegamenti). Gli adapter e i
  lettori Android e BinarioKit leggono `SharedData.NoticesSources`: l'iPhone non include più il file e non ha più un
  caricatore. Il modo di chiedere a ogni fonte (User-Agent, rifiuti 403 e 429, pausa, interruttore) è
  `source-manners-v1.json`, di tutte le fonti live e non solo degli avvisi.
- Le espressioni regolari della tabella sono quelle che compila Kotlin; l'iPhone compila lo stesso testo con
  NSRegularExpression (ICU), e un test Swift verifica che le accetti tutte. I due motori non leggono allo stesso modo
  `\b`, `\w`, `\W` e `\s` vicino a caratteri non ASCII (lettere accentate, spazio non separabile): come per la
  soppressione esplicita (`live-text-v1.json`), i casi di `notices-v1.json` usano testi su cui coincidono.

## Regole verificate

- **Chiave del numero** (`key`, per le statistiche): le cifre della corsa dopo l'ultimo « - » senza zeri iniziali
  («RE_11 - 2153» → 2153, «S5 - 811A» → 811, «FR 09558» → 9558); con una coda vuota le cifre dell'intero numero
  («S5 - » → 5); senza cifre il numero senza spazi in maiuscolo.
- **Query del numero** (`query`, `queryNumeric`, `queryValid`; Android e iPhone, e Alexa nell'orario programmato): la corsa
  dopo l'ultimo « - » senza spazi né trattini in maiuscolo («S5 - 811A» chiede «811A»); la parte numerica solo dopo una
  categoria nota («VE545» non è il treno 545); valida con 1–32 lettere o cifre e almeno una cifra.
- **In orario**: entro 5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo, entro 15 per le altre categorie.
- **Campioni minimi**: nessuna cifra sotto 5 campioni alla fermata misurata.
- **Giorni in orario**: un giorno cancellato conta come giorno non in orario, (giorni − cancellati) × in orario ÷
  (giorni × campioni); i giorni di fermata soppressa non sono campioni.
- **Stesso numero, origini diverse**: senza origine nessuna cifra quando con quel numero circola più di un treno,
  quali che siano i loro campioni; un treno soppresso in tutti i giorni osservati non circola e non conta.
- **Giorni della settimana** (`weekday-remarks-v1.json`): un giorno con almeno 4 campioni la cui quota in orario, con la soglia del treno, dista almeno 15 punti da quella della fermata; il dettaglio di un treno ne mostra al più due (`maximumWeekdayRemarks` di `punctuality-v1.json`), la differenza maggiore prima e, a parità, il giorno della settimana che viene prima. Android ne mostrava uno, preferendo il giorno della corsa: ora segue l'iPhone.
- **Ultima fermata**: un treno con una sola fermata nel file (di solito l'origine, dove il ritardo è in partenza)
  non ha una corsa da misurare ed è ignorato.

- **Soppressione esplicita** (`live-text-v1.json`): una parola che comincia con «soppress» o «cancellat», in qualsiasi
  maiuscola, segna la corsa soppressa («Soppressione parziale» sì, «insoppressibile» e «Cancellazione» no). L'espressione
  regolare resta nel codice di ogni app: Java e ICU non leggono `\b` e `\w` allo stesso modo sui caratteri non ASCII,
  quindi i casi usano testi su cui le due coincidono.
- **Operatore di una riga RFI** (`live-text-v1.json`): «ITALO» o «NTV» nel vettore, nella categoria o nel numero è Italo,
  poi «TRENORD» è Trenord, poi «FRECC» o «TRENITALIA» è Trenitalia, altrimenti nessun operatore noto. Android distingue
  anche i servizi Trenitalia (Frecce, regionale, lunga percorrenza): il caso confronta l'operatore.
- **Modelli di preparazione** (`checklist-presets-v1.json`): ogni modello aggiunge le tre righe comuni e la propria,
  nell'ordine delle righe. Le righe già presenti (stesso testo, maiuscole ignorate) non si ripetono e la lista si ferma
  al limite dell'Agenda (`planned-journeys-v1.json`, 24 righe). I testi delle righe sono quelli tradotti di ogni app: i
  casi usano etichette proprie.
- **Calendario** (`calendar-v1.json`): le festività della tabella `italian-holidays-v1.json`, Pasqua (computo
  gregoriano) e Pasquetta.

Ogni caso ha un solo atteso, uguale per tutti i lettori del file.

## Fonti live ViaggiaTreno (`live-sources-v1.json`)

- **Operatore**: «trenord» o «trenitalia» in `compImpresa` decidono; un'altra impresa dichiarata non ha operatore noto;
  senza `compImpresa`, il `codiceCliente` della tabella `viaggiatreno-v1.json` (63 Trenord; 1, 2, 4 e 18 Trenitalia);
  altrimenti nessun operatore, mai Trenitalia per ipotesi.
- **Arrivi**: l'ora di arrivo, l'origine al posto della destinazione, il binario di arrivo (quello programmato solo
  accanto a quello effettivo), l'orario previsto e lo stato solo con un rilevamento recente, l'identità della corsa.
- **Avvisi per treno**: solo le note valide nel giorno della corsa, per lo stesso numero (senza zeri iniziali) e, se
  nota, la stessa origine; ogni nota una volta, dalla più recente.

## Codici stazione dei pack nazionali (`gtfs-station-codes-v1.json`)

- **Trenitalia** (`TRENITALIA_LOCATION_ID`): il `stop_code` di nove cifre è il `trenitaliaLocationId` del catalogo,
  accettato entro 10 km dalla fermata; altrimenti, solo per un codice «8300…», la stazione con `railwayCode` «S» più le
  ultime cinque cifre, se non ha un altro location ID, entro 3 km. Quando la fermata o la stazione non ha coordinate,
  conferma il codice una parola di almeno quattro lettere in comune fra i nomi. Un codice estero vale solo se il
  catalogo lo registra (Ginevra); un codice di forma diversa non vale mai.
- **Italo** (`ITALO_STATION_CODE`): la tabella rivista `contracts/shared-data/v1/italo-gtfs-station-codes.json` («LUX»,
  la fermata stagionale di Lucca Comics, resta volutamente senza stazione).
- Una fermata che nessun codice lega resta al riconoscimento per nome di ciascuna piattaforma (fuori da questi casi).

## Mezzi delle tratte (`journey-leg-vehicles-v1.json`)

- **Modo**: `route_type` 2 e 100–199 sono treni; 3 e 700–799 sono il pullman dell'operatore (`BUS`) nei pack di
  `scheduledCoachPacks` (Trenitalia, Italo) e un bus sostitutivo (`REPLACEMENT_BUS`) in ogni altro pack; un altro tipo
  (traghetto, aereo) non è una tratta. Nei pack di `urbanPacks` (`pack-roles-v1.json`) 1, 12 e 400–499 sono metro
  (`METRO`), 0 e 900–999 tram (`TRAM`), 5, 6, 7 e 1300–1499 funicolari (`FUNICULAR`), come le linee urbane di Android.
- **Mezzo ed etichetta** di un `BUS`: FrecciaLink se la linea è «FL» o contiene «FRECCIALINK», ItaloBus se l'operatore
  contiene «italo» o la linea «ITALOBUS», Bus Trenitalia se l'operatore contiene «trenitalia», altrimenti Autobus; un
  treno non ha etichetta di mezzo (mostra categoria e numero). Le chiavi sono `journey_leg_*` su Android e
  `result.vehicle.*` sull'iPhone, con lo stesso testo nelle 17 lingue.
- **Linee urbane** (`urbanLines`, `urbanLabels`): la linea mostrata di una corsa urbana («1» della metro → «M1»,
  «MEA» → «A») e quando l'etichetta del mezzo è già nel nome della linea («Metro M1», non «Metro M1 M1»).

## Fermate urbane (`urban-stops-v1.json`)

- **Nomi**: il nome di una fermata urbana come lo mostra Android (`StopNameCase.display`): un nome tutto maiuscolo o
  tutto minuscolo prende l'iniziale maiuscola in ogni parola (sigle di linea e «FS» in maiuscolo, la barra diventa uno
  spazio); un nome che mescola maiuscole e minuscole resta come pubblicato.
- **Badge**: «M» se una linea della fermata è metro, altrimenti «T» per il tram, «F» per la funicolare.
- **A piedi**: minuti di cammino da una stazione a una fermata entro 600 m (metri / 75 arrotondato per eccesso, più 2),
  con i valori di `contracts/shared-data/v1/urban-stops-v1.json`.

## Prossimi treni (`next-trains-v1.json`)

- **Indice**: ogni lettore crea l'indice vuoto dello schema dell'iPhone (`userVersion` 2, i metadati della fixture dei test iPhone, `source_generated_at` 2024-03-30) e vi inserisce le righe di `index`; l'indice è sintetico e indipendente da quello incluso nelle app, che cambia con i pack.
- **Ricerca**: le fermate legate alla stazione del catalogo, solo quelle con il suo codice ViaggiaTreno quando ce l'ha; salita ammessa all'origine (`pickup_type` 0) e discesa a destinazione (`drop_off_type` 0); i giorni di servizio di ogni corsa nel fuso della sua azienda, dal giorno della richiesta meno sei al giorno dopo; gli orari GTFS contati da mezzogiorno meno dodici ore (nel giorno del cambio d'ora un treno delle 02:30 GTFS parte alle 01:30); le partenze dall'istante chiesto alle 24 ore dopo, al più otto, per orario poi numero.
- **Copie**: un treno che due pack pubblicano (stesso numero, minuto e fermate, oppure stessi minuti di partenza e arrivo con numero uguale, stesso operatore o un pack che ripubblica i treni dei partner) resta una volta, dal pack preferito: nazionale, regionale italiano, estero; estero prima a una stazione che nessun pack nazionale serve (`pack-roles-v1.json`). Due operatori italiani con numeri diversi agli stessi minuti sono due treni.
- **Mezzi e nomi**: numero del viaggio, altrimenti linea; categoria dalla linea («0083_FR» è «FR»), altrimenti dal nome lungo; operatore con il marchio breve; autobus dell'operatore e bus sostitutivi come in `journey-leg-vehicles-v1.json`.
- **Nessun treno**: dopo l'ultimo giorno di tutti i pack, o di tutti quelli che servono le due stazioni, l'orario è scaduto (`scheduleExpired`); altrimenti nessun treno (`noRoute`).

## Avvisi verificati (`notices-v1.json`)

- **Scioperi**: si tengono le voci del settore ferroviario (parole intere, maiuscole e accenti ignorati); le date vengono
  dal titolo, poi dalla descrizione, con le etichette di inizio e fine; «in programma» gli scioperi non ancora finiti,
  poi quelli senza data pubblicati da meno di 14 giorni; un feed non RSS o con DTD è illeggibile.
- **Testo e collegamenti**: markup ridotto a testo, spazi e righe vuote compattati, lunghezza limitata; parola
  «sciopero/scioperi»; le notizie arrivate dal salto in chiaro di ViaggiaTreno tengono toccabili solo i collegamenti
  HTTPS dei domini FS, anche con schemi annidati o entità codificate più volte.
- **Trenitalia e Trenord**: numeri treno delle notizie, stessa notizia su più direttrici come un solo avviso, filtro
  promozionale, gruppi per direttrice con quelli di (quasi) tutta la rete prima, avvisi di una stazione e degli hub.
- **Lavori sulle tratte**: un avviso di lavori (mai di sciopero) vale per una tratta se cita per intero e con la
  maiuscola una sua stazione, non dentro un nome di stazione più lungo né come parola comune, o una linea con entrambi
  gli estremi sulla tratta; date del testo con l'anno più vicino alla pubblicazione.

## Aggiornamenti delle corse (`gtfs-realtime-v1.json`)

La tabella dei feed (indirizzi, limiti, durate, pause) è `contracts/shared-data/v1/realtime-feeds-v1.json`.

- **Lettura del protobuf** (`decoding`): come le binding Java proto2 che usa Android. `header`, `gtfs_realtime_version`,
  l'`id` dell'entità e il `trip` di un `trip_update` sono obbligatori; un messaggio ripetuto si fonde, uno scalare
  ripetuto tiene l'ultimo valore, un valore enum sconosciuto lascia il campo com'era, un campo noto con un altro tipo di
  filo, i gruppi e i campi a larghezza fissa sono saltati. Un varint troncato o di undici byte, una lunghezza oltre la
  fine, il campo zero, il tipo 6 o 7 e una fine gruppo senza inizio rendono illeggibile tutto il feed: nessun dato.
- **Feed** (`protobufUpdates`, `jsonUpdates`): le fermate con orario delle corse SCHEDULED, con giorno di servizio
  (`start_date`), orario previsto e ritardo; corse CANCELED o ADDED, `trip_id` vuoto, `start_date` illeggibile, fermate
  SKIPPED o NO_DATA, senza `stop_id` né sequenza restano fuori. L'osservazione è il `timestamp` della corsa, poi quello
  dell'intestazione, poi l'ora della richiesta. STA pubblica la resa JSON dello stesso messaggio, con i numeri come testo.
- **Raccordo** (`join`): una corsa del pack si aggiorna solo alla fermata elencata (prima chiamata con quello `stop_id`,
  o la sequenza), con un'osservazione al più di cinque minuti prima e un minuto dopo l'ora attuale e non scaduta; vince
  l'osservazione più recente con un ritardo, a parità la prima. Il ritardo è quello pubblicato in minuti interi, altrimenti
  la differenza tra orario previsto e programmato. Senza `start_date` vale solo il giorno della ricerca; un pack le cui
  agenzie non hanno lo scarto di Roma non si raccorda.
- **Reti urbane** (`urban`): le corse del tram 8 di Roma e dei tram di Mestre, solo per le linee del pack; solo nelle
  build personali e con l'orario della rete incluso.

## Tabelloni svizzeri (`swiss-station-board-v1.json`)

- **Numero UIC** (`uic`): lo `stop_id` svizzero (`85` e cinque cifre) di `ch-otd-gtfs` o il `trenitaliaLocationId`
  `8500` e cinque cifre del catalogo (Bellinzona `850005213` è `8505213`); un aggregato di città (`85009…`) non è un
  numero.
- **Ricerca per nome** (`locations`, `lookups`): per un aggregato svizzero o una stazione della zona di confine, solo una
  stazione ferroviaria svizzera entro 1,5 km dalla posizione del catalogo (o, senza coordinate, con lo stesso nome), poi
  il numero trovato resta una settimana; una stazione italiana lontana dal confine non chiede nulla.
- **Tabellone** (`boards`, `operators`, `names`): solo treni con numero, destinazione e orario; la previsione di
  partenza vale solo dal lato e nella finestra giusti, mai quella di arrivo; un binario cambiato tiene quello
  programmato; un tabellone non dell'ora attuale si chiede dalla sua ora svizzera; una pagina di manutenzione, una
  risposta tagliata o il tabellone di un'altra stazione sono un errore della fonte.
