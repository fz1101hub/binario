# Regole condivise v1: numeri dei treni e statistiche

Casi di prova comuni ad Android, iPhone e Alexa. Le tre implementazioni restano indipendenti (nessun codice condiviso):
condividono soltanto questi file, che i tre test leggono così come sono.

| File | Contenuto |
|---|---|
| `rules-v1.json` | I casi: chiave del numero treno, query del numero, soglie «in orario», campioni minimi, treno misurato all'ultima fermata senza origine |
| `punctuality-stats-v1.json` | Un file statistiche sintetico nel formato `binario-punctuality-v1` (docs/data-sources/PUNCTUALITY_STATS.md): codici `S000xx` e numeri inventati, nessun dato osservato |

Lettori: `app/src/test/java/it/binario/app/data/reliability/SharedRulesContractTest.kt`,
`ios/BinarioKit/Tests/BinarioKitTests/SharedRulesContractTests.swift`, `alexa-companion/server/punctuality/sharedRules.test.ts`.
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

## Casi divergenti

Un caso con `divergent` porta l'atteso dell'app (`app`, Android e iPhone) e di Alexa (`alexa`): oggi le due regole
differiscono e la scelta spetta al titolare. Quando la regola è decisa, il caso torna ad avere un solo `expected`.
