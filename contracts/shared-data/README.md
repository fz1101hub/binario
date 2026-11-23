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
| `italo-stations-v1.json` | Codici Italo di riserva con i loro alias; pagine Italo note per nome | Android, iPhone |
| `planned-journeys-v1.json` | Limiti dell'Agenda, del file di trasferimento e dei modelli | Android, iPhone |
| `punctuality-v1.json` | Categorie misurate a 5 minuti, soglie e campioni minimi delle statistiche | Android, iPhone, Alexa |
| `rfi-place-ids-v1.json` | Place ID del monitor RFI per i nomi di stazione noti | Android, iPhone |
| `source-scores-v1.json` | Punteggi delle fonti per categoria, bonus dell'operatore, fonti di sola copertura | Android, iPhone |
| `train-number-prefixes-v1.json` | Categorie ammesse davanti al numero di un treno | Android, iPhone |

I test che confrontano ogni tabella con il codice che la usa: `app/src/test/java/it/binario/app/shared/SharedDataTest.kt`,
`ios/BinarioKit/Tests/BinarioKitTests/SharedDataTests.swift`, `alexa-companion/server/punctuality/sharedData.test.ts`.
Il generatore ha i suoi test in `tests/test_generate_shared_code.py`.

## Formato

Ogni file è un oggetto JSON con `"schema": "binario-shared-data-v1"`, una `description` in inglese (diventa il commento
del codice) e le chiavi della tabella. Le chiavi sono in camelCase: restano così in Swift e diventano maiuscole con trattini bassi in Kotlin e
TypeScript (`minimumSamples` → `MINIMUM_SAMPLES`). Una voce può avere una `note`, che diventa un commento nel codice generato.

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
fallire e non genera codice: le tabelle arrivate da altri lavori (`italo-gtfs-station-codes.json`,
`notices-sources-v1.json`) passano al generatore con i passi 2–4.

I workflow Android, Apple e Alexa partono quando cambia questa directory o il generatore; il job Python di
`gtfs-assets-ci.yml` esegue `--check`.
