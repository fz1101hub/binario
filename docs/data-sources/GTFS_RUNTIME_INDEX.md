# Indice runtime GTFS embedded

L’app legge i pack GTFS embedded con un indice a colonne e limiti espliciti. Il parser non materializza più `stop_times.txt` come `List<Map<String, String>>`: consuma una riga per volta, conserva soltanto sei colonne intere per gli stop time appartenenti a trip dichiarati e limita la cache a due pack.

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

Il calcolo offline dei viaggi con cambi (`data/journey/EmbeddedGtfsJourneyProvider.kt`) usa questo stesso indice tramite un'istanza dell'adapter con un solo indice in cache. Dall'indice ricava, per pack e giorno di servizio, un array compatto di connessioni (fermate consecutive con orario di ogni corsa attiva in quel giorno secondo `calendar.txt` e `calendar_dates.txt`): circa 25 byte per connessione, 4,8 MB per un giorno del pack svizzero. Del giorno precedente conserva solo le connessioni oltre la mezzanotte (24:00+) e del successivo solo quelle entro la finestra di ricerca; la cache dei giorni è limitata a 16 voci e 16 MB. Per riconoscere i punti di cambio legge una volta `stops.txt` di ogni pack con lo stesso scanner limitato.
