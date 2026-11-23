# Regole condivise v1: numeri dei treni, statistiche, testi live, Agenda, calendario, codici stazione, mezzi e avvisi

Casi di prova comuni ad Android, iPhone, Alexa e agli strumenti Python che costruiscono l'indice orario dell'iPhone: il terzo livello della base comune
([ADR-0002](../../../docs/architecture/ADR-0002-BASE-COMUNE.md)). Le implementazioni restano indipendenti, scritte nel
linguaggio di ogni app: condividono soltanto questi file, che i test leggono così come sono. Le tabelle (valori, non
regole) stanno invece in [`contracts/shared-data/`](../../shared-data/README.md) e diventano codice generato.

| File | Contenuto | Lettori |
|---|---|---|
| `rules-v1.json` | Chiave del numero treno, query del numero, soglie «in orario», campioni minimi, treno misurato all'ultima fermata senza origine | Android, iPhone, Alexa |
| `punctuality-stats-v1.json` | Un file statistiche sintetico nel formato `binario-punctuality-v1` (docs/data-sources/PUNCTUALITY_STATS.md): codici `S000xx` e numeri inventati, nessun dato osservato | Android, iPhone, Alexa |
| `live-text-v1.json` | La soppressione esplicita in un testo live e l'operatore di una riga del monitor RFI | Android, iPhone |
| `checklist-presets-v1.json` | Le righe di ogni modello di «Preparazione del viaggio» e come un modello si aggiunge al testo dell'editor | Android, iPhone |
| `calendar-v1.json` | Le festività nazionali, comprese Pasqua e Pasquetta | Android, Alexa |
| `gtfs-station-codes-v1.json` | `stop_code`, nome e posizione di una fermata dei pack Trenitalia e Italo → stazione del catalogo attesa (o nessuna), sul catalogo `app/src/main/assets/stations/curated_it_rail_stations.json` | `app/src/test/java/it/binario/app/data/gtfs/GtfsStationCodesContractTest.kt`, `tests/test_ios_gtfs_schedule.py` (regola di `tools/android_station_codes.py`, usata dal builder dell'indice iPhone) |
| `journey-leg-vehicles-v1.json` | `route_type`, pack, operatore e linea di una corsa → modo della tratta, mezzo e chiave dell'etichetta (Android e iPhone) | `app/src/test/java/it/binario/app/journeys/JourneyLegVehiclesContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift` |
| `urban-stops-v1.json` | Nome mostrato, badge e minuti a piedi di una fermata di metro, tram o funicolare | `app/src/test/java/it/binario/app/data/gtfs/UrbanStopsContractTest.kt`, `ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `tests/test_ios_gtfs_schedule.py` |
| `notices-v1.json` | I casi degli avvisi ferroviari: giorni degli scioperi del calendario MIT, testo e collegamenti degli avvisi, infomobilità Trenitalia, avvisi Trenord per direttrice e per stazione, lavori e interruzioni sulle tratte. Gli ingressi sono le fixture dei test Android (`app/src/test/resources/sources/`) e il catalogo stazioni curato | `app/src/test/java/it/binario/app/data/notices/NoticesContractTest.kt` (con gli adapter e i matcher Android esistenti), `ios/BinarioKit/Tests/BinarioKitTests/NoticesContractTests.swift` |

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
- **Query del numero** (`query`, `queryNumeric`, `queryValid`; Android e iPhone, Alexa non cerca per numero): la corsa
  dopo l'ultimo « - » senza spazi né trattini in maiuscolo («S5 - 811A» chiede «811A»); la parte numerica solo dopo una
  categoria nota («VE545» non è il treno 545); valida con 1–32 lettere o cifre e almeno una cifra.
- **In orario**: entro 5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo, entro 15 per le altre categorie.
- **Campioni minimi**: nessuna cifra sotto 5 campioni alla fermata misurata.
- **Giorni in orario**: un giorno cancellato conta come giorno non in orario, (giorni − cancellati) × in orario ÷
  (giorni × campioni); i giorni di fermata soppressa non sono campioni.
- **Stesso numero, origini diverse**: senza origine nessuna cifra quando con quel numero circola più di un treno,
  quali che siano i loro campioni; un treno soppresso in tutti i giorni osservati non circola e non conta.
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
