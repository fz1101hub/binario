# Regole condivise v1: numeri dei treni, statistiche, testi live, Agenda e calendario

Casi di prova comuni ad Android, iPhone e Alexa: il terzo livello della base comune
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

Ogni file nomina i test che lo leggono nel campo `readers` (per `rules-v1.json`:
`app/src/test/java/it/binario/app/data/reliability/SharedRulesContractTest.kt`,
`ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `alexa-companion/server/punctuality/sharedRules.test.ts`).
I workflow Android, Apple e Alexa partono quando cambia questa directory.

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

Ogni caso ha un solo atteso, uguale per le piattaforme che lo leggono.
