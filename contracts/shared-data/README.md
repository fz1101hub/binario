# Tabelle condivise

Le tabelle che più piattaforme usano, scritte una sola volta. È il secondo livello della base comune
([ADR-0002](../../docs/architecture/ADR-0002-BASE-COMUNE.md)): `tools/generate_shared_code.py` le trasforma in codice,
e ogni app legge le costanti come codice, mai interpretando il JSON a runtime.

| Codice generato | Tabelle |
|---|---|
| `app/src/main/java/it/binario/app/shared/SharedData.kt` (`internal object SharedData`) | tutte |
| `ios/BinarioKit/Sources/BinarioKit/Generated/SharedData.swift` (`public enum SharedData`) | tutte |
| `alexa-companion/shared/generated/sharedData.ts` | solo quelle che la skill legge (`alexa=True`) |

I file generati sono nel repository e non si modificano a mano: si cambia il JSON e si esegue il generatore.

```bash
python3 tools/generate_shared_code.py           # riscrive i file generati
python3 tools/generate_shared_code.py --check   # esce con 1 se uno non corrisponde alle tabelle (CI)
```

## Tabelle v1

| File | Contenuto | Letta da |
|---|---|---|
| `airport-routes-v1.json` | Collegamenti con gli aeroporti (id del catalogo curato) e pack di linee urbane (`cityLine`) | Android, iPhone |
| `italian-holidays-v1.json` | Festività nazionali (alcune da un anno in poi) e giorni contati dalla Pasqua | Android, Alexa; generata anche per l'iPhone |
| `italo-gtfs-station-codes.json` | Codici delle stazioni del pack Italo (`stop_code`) e stazione del catalogo di ciascuno, rivisti; codici lasciati al pack | Android; il builder dell'indice iPhone legge il JSON (`tools/android_station_codes.py`); generata anche per l'iPhone |
| `italo-stations-v1.json` | Codici Italo di riserva con i loro alias; pagine Italo note per nome | Android, iPhone |
| `notices-sources-v1.json` | Fonti degli avvisi (indirizzi, accesso, limiti, intervalli, cache, reindirizzamenti), pagine aperte con un tocco, limiti, parole ed espressioni regolari dei lettori, quanto mostrano le sezioni | Android, iPhone |
| `pack-roles-v1.json` | Ruoli dei pack orari: nazionali, esteri, che ripubblicano i treni dei partner, con autobus dell'operatore, reti urbane (metro, tram, funicolari) | Android, iPhone; il builder e il verificatore dell'indice iPhone leggono il JSON |
| `planned-journeys-v1.json` | Limiti dell'Agenda, del file di trasferimento e dei modelli | Android, iPhone |
| `punctuality-v1.json` | Categorie misurate a 5 minuti, soglie e campioni minimi delle statistiche | Android, iPhone, Alexa |
| `rfi-place-ids-v1.json` | Place ID del monitor RFI per i nomi di stazione noti | Android, iPhone |
| `source-manners-v1.json` | Come si chiede a ogni fonte pubblica: User-Agent, risposte che sono un rifiuto e pausa dopo un rifiuto, interruttore dopo errori ripetuti | Android, iPhone |
| `source-scores-v1.json` | Punteggi delle fonti per categoria, bonus dell'operatore, fonti di sola copertura | Android, iPhone |
| `urban-stops-v1.json` | Fermate urbane: distanza massima a piedi, velocità e margine del cammino, stessa fermata e banchine dello stesso nome | Android, iPhone; il builder e il verificatore dell'indice iPhone leggono il JSON |
| `train-number-prefixes-v1.json` | Categorie ammesse davanti al numero di un treno | Android, iPhone |

Gli strumenti Python sono la pipeline: quando servono una tabella la leggono dal JSON, controllata con lo stesso schema
(`generate_shared_code.table_values`), oppure con un loro lettore (`tools/android_station_codes.py`).

I test che confrontano ogni tabella con il codice che la usa: `app/src/test/java/it/binario/app/shared/SharedDataTest.kt`,
`ios/BinarioKit/Tests/BinarioKitTests/SharedDataTests.swift`, `alexa-companion/server/punctuality/sharedData.test.ts`.
Il generatore ha i suoi test in `tests/test_generate_shared_code.py`.

## Formato

Ogni file è un oggetto JSON con `"schema": "binario-shared-data-v1"`, una `description` in inglese (diventa il commento
del codice) e le chiavi della tabella. Le chiavi sono in camelCase: restano così in Swift e diventano maiuscole con trattini bassi in Kotlin e
TypeScript (`minimumSamples` → `MINIMUM_SAMPLES`). Una voce può avere una `note` (`comment` nei codici Italo), che diventa
un commento nel codice generato. Sono facoltativi per ogni tabella `name` (il nome del file) e `readers` (chi la legge);
una tabella può dichiarare altre chiavi descrittive, controllate ma non generate (`catalogue` dei codici Italo). I
secondi (`Seconds` nello schema) sono interi in Kotlin e TypeScript, `Double` (`TimeInterval`) in Swift.

Prima di scrivere qualsiasi file, il generatore controlla ogni tabella con lo schema registrato in `TABLES`. Controlla i
tipi (un booleano non vale come intero), le chiavi obbligatorie, le chiavi sconosciute o ripetute, le voci doppie, le
stringhe vuote, con spazi ai bordi o con caratteri di controllo, i pattern e i limiti, poi i controlli propri della
tabella: per esempio, ogni pack di una tratta deve essere dichiarato e i bonus devono coprire ogni categoria. Una
tabella non valida non fa scrivere nulla.

## Aggiungere una tabella

1. Creare `contracts/shared-data/v1/<nome>-v1.json` nel formato sopra.
2. Registrarla in `TABLES` di `tools/generate_shared_code.py` con il nome del namespace, lo schema dei campi e
   `alexa=True` se la skill la legge.
3. Eseguire `python3 tools/generate_shared_code.py` e sostituire nel codice delle app la copia a mano con
   `SharedData.<Nome>`.
4. Aggiungere un caso per piattaforma ai test di questa tabella (sopra) e fare il commit del JSON e dei file generati.

Un JSON di questa directory non ancora registrato viene segnalato da `--check` («non è registrato») ma non lo fa
fallire e non genera codice: una tabella che arriva da un altro lavoro passa al generatore con i passi 2–4, come
`italo-gtfs-station-codes.json` e `notices-sources-v1.json` il 23 novembre 2026.

I workflow Android, Apple e Alexa partono quando cambia questa directory o il generatore; il job Python di
`gtfs-assets-ci.yml` esegue `--check`.
