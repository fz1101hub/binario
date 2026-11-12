# Indice runtime GTFS embedded

L’app legge i pack GTFS embedded con un indice a colonne e limiti espliciti. Il parser non materializza più `stop_times.txt` come `List<Map<String, String>>`: consuma una riga per volta, conserva soltanto sei colonne intere per gli stop time appartenenti a trip dichiarati e limita la cache a tre pack (una tratta servita dal pack Trenitalia, da uno regionale e da uno transfrontaliero non rilegge un pack a ogni ricerca).

La costruzione usa due passate sullo ZIP. La prima indicizza tabelle anagrafiche, trip e calendari; la seconda legge `stop_times.txt`. In questo modo l’esito non dipende dall’ordine delle entry nello ZIP e i trip ignoti non vengono trattenuti. Ogni passata applica limiti a numero di entry, byte non compressi, lunghezza di riga, cardinalità delle tabelle e servizi restituiti. Un pack oltre limite viene ignorato senza sostituire dati validi con dati parziali.

La regressione usa 1.355.184 righe, cardinalità misurata sul pack AMT che ha originato il finding C1. Il comando seguente limita la **JVM forkata da JUnit** a 256 MiB:

```sh
./gradlew --no-daemon :app:testDebugUnitTest \
  -PBINARIO_TEST_MAX_HEAP=256m \
  --tests 'it.binario.app.data.gtfs.EmbeddedGtfsCompactIndexStressTest' \
  --rerun-tasks
```

Il test verifica sia l’esito funzionale sia le metriche: tutte le righe vengono consumate, soltanto quelle della corsa dichiarata sono indicizzate e l’allocazione delle colonne `stop_times` resta proporzionale alle righe conservate.

## Viaggi con cambi

Il calcolo offline dei viaggi con cambi (`data/journey/EmbeddedGtfsJourneyProvider.kt`) usa questo stesso indice tramite un'istanza dell'adapter con un solo indice in cache. Dall'indice ricava, per pack e giorno di servizio, un array compatto di connessioni (fermate consecutive con orario di ogni corsa attiva in quel giorno secondo `calendar.txt` e `calendar_dates.txt`): circa 25 byte per connessione, 4,8 MB per un giorno del pack svizzero, 2,5 MB per quello Trenitalia. Del giorno precedente conserva solo le connessioni oltre la mezzanotte (24:00+); il giorno richiesto e il successivo sono conservati interi, così le ricerche seguenti delle stesse date, a qualunque ora, non rileggono il pack. La cache dei giorni è limitata a 16 voci e 24 MB. Le partenze sono cercate nelle 8 ore dopo l'orario richiesto, ma le connessioni fino a 10 ore oltre la finestra restano nella scansione, perché un viaggio lungo (Merano–Roma) parta nella finestra e arrivi dopo. Per riconoscere i punti di cambio legge una volta `stops.txt` di ogni pack (con `stop_code` e coordinate) con lo stesso scanner limitato.

## Pack nazionali

Il pack Trenitalia è il più grande: 245 mila righe di `stop_times` (40 MB non compressi, 60 MB l'intero pack) e 252 mila righe di `calendar_dates`. Il lettore di righe legge a blocchi in un buffer di caratteri e le righe senza virgolette sono divise senza copie intermedie: sul JVM dei test l'indice Trenitalia si costruisce in circa 0,7–1,1 s (3,4 s con la lettura carattere per carattere precedente), quello svizzero in 0,6 s (1,5 s). Gli id di route e i tipi di eccezione del calendario sono condivisi fra le righe: l'indice Trenitalia trattiene circa 31 MB (45 MB prima).

Le fermate dei pack che pubblicano codici di stazione (`EmbeddedGtfsPack.stopCodeScheme`) sono legate al catalogo dal codice prima che dal nome (`GtfsStationCodeIndex`), sia nel calcolo dei viaggi sia nella ricerca delle tratte, che riceve il catalogo solo per questo. Le linee bus dei pack con `busRoutesAreScheduledCoaches` diventano tratte `LegMode.BUS` e servizi `TrainService.scheduledBus`; negli altri pack un bus resta un bus sostitutivo.

Una corsa pubblicata da due pack con lo stesso numero e almeno una connessione identica diventa una sola corsa della scansione: la copia del pack che viene prima nell'ordine dei pack programmati (`PlannedPackOrder`, vedi SOURCE_POLICY.md) resta quella mostrata, le connessioni identiche dell'altra sono scartate e le restanti proseguono la stessa corsa.

## Ferrovie regionali e reti urbane

I pack di `gtfs-transit/` e, nelle build personali, di `gtfs-personal/` usano lo stesso indice. `EmbeddedGtfsPacks.all` elenca i pack ferroviari (i dodici del primo rilascio, nello stesso ordine, poi le ferrovie regionali), `EmbeddedGtfsPacks.urban` le reti urbane (`EmbeddedGtfsPackKind.URBAN`), lette solo dal calcolo dei viaggi con cambi e dalla ricerca delle fermate.

- **Tipi di tratta.** `route_type` 1 e 400–499 sono `LegMode.METRO`, 0 e 900–999 `TRAM`, 5, 6, 7 e 1300–1499 `FUNICULAR` (funicolari, cremagliere, ascensori pubblici); le corse urbane non hanno un numero pubblico, solo la linea («M3», «T2», «14»; Milano pubblica le metro come «1»…«5» e Roma come «MEA», normalizzate in «M1» e «A»).
- **Fermate urbane.** Restano fermate del proprio pack (`gtfs:<pack>:<stop>`), mai risolte a una stazione del catalogo per nome. Il pack porta `binario_stop_modes.txt` (lettere M, T, F per fermata e stazione madre, scritto da `tools/build_gtfs_packs.py`) per i badge della ricerca; `EmbeddedUrbanStopIndex` legge solo `stops.txt` e quel file, al primo uso.
- **Percorsi a piedi.** Fra nodi della ricerca (stazioni del catalogo e fermate) entro `GtfsJourneyPlannerSettings.maxWalkMeters` (400 m) il grafo aggiunge archi a piedi di `walkMinutes(m)` = metri a 4,5 km/h arrotondati per eccesso più 2 minuti di margine, mai meno del tempo di cambio della destinazione; le celle di una griglia di 400 m limitano i confronti ai vicini. Anche origine e destinazione possono essere raggiunte a piedi (accesso e uscita). Le tratte a piedi sono `LegMode.WALK`, senza corsa.
- **Pack consultati.** Un pack urbano è candidato solo se un estremo è una sua fermata o cade in una delle sue aree (`TransitArea`, centro e raggio), al più `maxUrbanPacksPerSearch` (2). Per un viaggio da o verso una sua fermata entra subito accanto ai pack ferroviari; fra due stazioni ferroviarie le selezioni dei pack ferroviari restano quelle di prima e la rete urbana si aggiunge solo se non trovano soluzioni, così una ricerca Domodossola – Milano o Milano – Mendrisio non legge metro e tram di Milano (stessi tempi di prima). Una corsa urbana non viene mai unita a una corsa di un altro pack.
