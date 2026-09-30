# Controverifica dell’audit Claude del 29 settembre 2026

**Autore:** Manus AI  
**Audit esaminato:** `docs/audit-independent-2026-09-29-integrazione.md` sul branch `docs/audit-integration-20260929`  
**Commit audit:** `1c25dfb0d3a94679ec764ccee95c6fdfadecb54c`  
**Target integrazione:** `a5b680834f9f52b6c894b29cb1c42330cd0e4435`  
**Target iOS PR #32:** `0e35c15eced7e8651128173e537e52c124cce407`

## Giudizio complessivo

L’audit Claude è **serio, documentato e materialmente utile**. Il finding principale **H-1 sulla canonicalizzazione delle stazioni Android è confermato** ed è un vero release blocker della funzione primaria. Anche **H-2 sulla compilazione Swift 6 della PR #32 è tecnicamente fondato**, pur richiedendo la prova definitiva con `xcodebuild` su macOS.

Il rapporto è però troppo severo nel riepilogo: dopo una controverifica indipendente dei 18 finding High/Medium, la classificazione più equilibrata è:

- **2 High** confermati, dei quali uno riguarda l’integrazione Android e uno solo la Draft PR iOS #32;
- **9 Medium** confermati o confermati nel nucleo;
- **6 Low**: problemi reali o parziali, ma con impatto inferiore a quello attribuito dall’audit;
- **1 finding non confermato/superato**: M-3, perché l’app comunica già esplicitamente il degrado ad allarme inesatto.

Verdetti metodologici sui 18 finding: **12 confermati**, **5 parzialmente confermati**, **1 superato/non confermato**.

Il rapporto quindi **smentisce correttamente** la precedente formula “nessun P0/P1/P2 tecnico noto aperto”. Non dimostra però che tutti i 16 Medium siano davvero Medium.

## Controverifica finding per finding

| ID | Verdetto | Severità rivista | Valutazione sintetica |
|---|---|---:|---|
| H-1 | Confermato | **High** | `canonicalStation` combina ID e fuzzy-match nello stesso `firstOrNull`; probe fedele sul catalogo: **3.321/5.239 identità mutate**. Release blocker Android. |
| H-2 | Confermato | **High, solo PR #32** | Il target Watch integrato eredita Swift 6 e contiene `static var` memorizzate non isolate in `RefreshBinarioIntent`. Fortemente fondato; prova finale con Xcode/macOS ancora necessaria. |
| M-1 | Confermato | **Medium** | I backup schema 1 realmente prodotti dalla v0.16.3 usano 26 ID legacy assenti dal catalogo `curated:*`; route/preferiti/monitor non migrano. |
| M-2 | Parziale | **Medium** | Resolver e chiamante propagano eccezioni su orari vuoti/malformati; fixture avversaria riproduce il crash. Il feed European Sleeper osservato oggi non contiene tali righe. |
| M-3 | Superato | **Nessuno / miglioramento UX Low** | L’app non apre la pagina di concessione exact-alarm, ma **non degrada in silenzio**: restituisce `isExact` e mostra copy esplicito per sveglie/promemoria inesatti. |
| M-4 | Confermato | **Medium** | Export Agenda pending e `PlannedJourneyViewModel` sono in `remember`; ricreazione Activity durante SAF può lasciare file vuoto senza feedback e perdere stato UI transitorio. |
| M-5 | Confermato | **Medium** | La Tile ha entry singola senza intervallo di validità; il TTL è fail-closed solo alla prossima richiesta e il timer proattivo muore col processo. |
| M-6 | Confermato | **Low** | `startActivity` da `WearableListenerService` è inaffidabile in background e il watch annuncia successo senza acknowledgement. Superficie companion accessoria, non Medium. |
| M-7 | Confermato | **Low** | Il widget ricalcola il countdown solo quando la Home sincronizza; manca refresh autonomo. Degrado di superficie accessoria. |
| M-8 | Confermato | **Low** | Il plugin rimuove anche i marker `tools:node="remove"`; il controfattuale li ripristina. Va verificato sul manifest fuso/AAB, ma l’impatto dimostrato è soprattutto policy/Data Safety. |
| M-9 | Confermato | **Medium** | Un elemento con tipo che fa lanciare il validatore azzera il loader; il salvataggio successivo può sovrascrivere tratte/itinerari/template validi. Riprodotto con Vitest. |
| M-10 | Confermato | **Medium** | OAuth firma una sessione con `name=""`, mentre `verifySession` la rifiuta: dashboard e linking possono entrare in loop per profili senza nome. Riprodotto. |
| M-11 | Confermato | **Medium** | Il bundle server Alexa importa staticamente `vite`, che è devDependency. Dopo installazione `--prod`, `node dist/index.js` fallisce con `ERR_MODULE_NOT_FOUND`. |
| M-12 | Parziale | **Medium** | `route.name` entra in SSML senza escaping: `&` rompe XML e tag vengono interpretati. La generalizzazione a tutti gli slot non è dimostrata. |
| M-13 | Parziale | **Low / governance** | `main` non contiene workflow né `dependabot.yml`; i push sui branch di stabilizzazione non attivano rail. È una policy intenzionale ma lascia un gap di assurance; non è impossibilità permanente. |
| M-14 | Parziale | **Low / documentazione** | Il fallback JBB/European Sleeper è attivo solo quando il tier primario fallisce. La privacy disclosure generica esiste, ma non nomina host, natura community e condizione esatta. |
| M-15 | Confermato | **Medium** | Il gate reale `verify:dependency-audit` fallisce: le advisory `image-size` ora hanno fix disponibile. È emerso anche un advisory `undici`, quindi non basta cambiare una riga di allowlist. |
| M-16 | Parziale | **Low finché non osservato nel feed** | La logica perde il sabato per stringhe `SAB E FEST`; il probe lo dimostra. Non è stato però confermato che RFI emetta attualmente quella forma. |

## Perché considero l’audit buono

Il rapporto usa commit esatti, distingue esecuzione/statica/non eseguibile, prova a confutare i finding e ammette esplicitamente un grave difetto sfuggito agli audit precedenti. Le riproduzioni più convincenti sono H-1, M-1, M-9, M-10, M-11 e M-15. Sono finding con causa, percorso e impatto verificabili, non osservazioni generiche.

Anche il confronto con le dichiarazioni precedenti è utile: mostra che diversi gate verdi controllavano il contratto dichiarato, ma non sempre l’artefatto finale o la compatibilità con la release v0.16.3.

## Dove l’audit eccede

Il numero molto alto di agenti e finding aumenta la copertura, ma anche il rumore. La rubrica dichiarata non è applicata sempre in modo uniforme alle superfici companion. M-6, M-7 e M-8 sono problemi reali, ma non compromettono la funzione principale e non meritano Medium. M-13 è soprattutto una decisione di governance; M-14 è prevalentemente un gap di disclosure; M-16 è un parser bug condizionale senza input live osservato.

M-3 è il caso più netto: il rapporto afferma “degrada in silenzio”, mentre il codice e le stringhe mostrano feedback esplicito per il fallback inesatto. Va ritirato come Medium.

Infine, H-1 contiene una piccola imprecisione: il bootstrap non chiama immediatamente `saveRoutes`; tuttavia muta lo stato in memoria e i successivi flussi di ricerca/salvataggio possono persistere l’identità sbagliata. La sostanza e la severità restano confermate.

## Priorità operative consigliate

1. **Bloccare merge/release di PR #26 finché H-1 non è corretto e testato sul catalogo reale.**
2. **Correggere H-2 nella PR #32** e ottenere una compilazione `xcodebuild` reale del progetto integrato.
3. Correggere prima del rilascio **M-1, M-4, M-5, M-9, M-10, M-11, M-12 e M-15**.
4. Correggere M-2 in difesa, anche se il feed corrente non attiva il caso.
5. Gestire M-6/M-7/M-8/M-13/M-14/M-16 come hardening o governance, senza chiamarli blocker equivalenti alla corruzione delle stazioni.
6. Solo dopo le remediation: congelare nuovi SHA, rieseguire gate locali e ottenere Actions con runner e step reali.

## Conclusione

La risposta breve è: **l’audit Claude è complessivamente credibile e ha trovato almeno un problema grave che i precedenti audit non avevano visto**. Non accetterei però il suo conteggio di severità alla lettera. Il nuovo quadro corretto è **0 Critical, 2 High, circa 9 Medium, 6 Low e un Medium ritirato**.

La PR #26 non è più considerabile pronta alla sola attesa dei prerequisiti esterni: **H-1 riapre lo sviluppo correttivo Android**. La PR #32 resta Draft e **H-2 deve essere chiuso prima di considerarla compilabile**.

## Riferimenti

[1]: https://github.com/fz1101hub/binario/blob/docs/audit-integration-20260929/docs/audit-independent-2026-09-29-integrazione.md "Audit indipendente Claude del 29 settembre 2026"
[2]: https://github.com/fz1101hub/binario/commit/1c25dfb0d3a94679ec764ccee95c6fdfadecb54c "Commit del rapporto Claude"
[3]: https://github.com/fz1101hub/binario/pull/26 "PR #26 — Stabilization v0.20.4"
[4]: https://github.com/fz1101hub/binario/pull/32 "PR #32 — iOS main app MVP"

## Aggiornamento remediation — 30 settembre 2026

La controverifica è stata seguita da una remediation separata, basata sul candidato `a5b680834f9f52b6c894b29cb1c42330cd0e4435`. Il branch `integration/claude-audit-remediation-20260930` chiude i finding confermati senza modificare o mergeare la PR #26.

### Stato dei finding

- **H-1 chiuso:** la canonicalizzazione Android preserva un’identità già riconosciuta prima di qualsiasi fuzzy match; la regressione usa il catalogo reale.
- **H-2 corretto nella Draft PR #32:** la metadata statica di `RefreshBinarioIntent` è resa Swift 6 concurrency-safe. I verifier Linux sono verdi; resta necessaria la prova `xcodebuild` reale su macOS prima di promuovere l’app iOS.
- **M-1, M-2, M-4, M-5, M-9, M-10, M-11, M-12 e M-15 chiusi** con regressioni dedicate.
- **M-6, M-7 e M-8 chiusi come hardening companion:** richiesta Wear con acknowledgement, refresh autonomo widget tramite WorkManager e marker `tools:node="remove"` preservati/verificati.
- **M-13 ridotto ma non completamente chiudibile dal codice:** il lock CI ora ammette e verifica i push `stabilization/*`; restano la mancata esecuzione dei runner GitHub e il triage security del default branch.
- **M-14 chiuso documentalmente:** il fallback community European Sleeper/JBB è nominato in `PRIVACY.md` e nel verifier.
- **M-16 chiuso difensivamente:** i token RFI contenenti sabato e festivo vengono combinati invece di perdere il sabato.
- **M-3 resta ritirato:** il degrado ad allarme inesatto era già comunicato esplicitamente.

### Evidence sul branch consolidato

- Android principale: **330 test**; Wear root: **12 test**; lint debug e assemble Android/Wear verdi in dependency verification strict.
- Companion Expo: **94 test TypeScript**, **7 test Android phone** e **4 test Wear**; typecheck, audit senza high/critical, manifest permission verifier, prebuild idempotente, lint nativi e assemble Wear release verdi. Il lock Companion copre **1.128 componenti / 2.048 artifact** SHA-256.
- Alexa: **86 test verdi + 4 test MariaDB opt-in non eseguiti in questo gate integrato**; typecheck, build, bundle production-only e audit senza high/critical verdi. Le quattro prove MariaDB erano già verdi sulla tranche Alexa isolata.
- Python/verifier: **72 test**; pinning CI, privacy e dependency metadata verdi. Il lock root copre **599 componenti / 1.041 artifact** SHA-256.
- Il primo tentativo dei gate integrati è stato interrotto da `ENOSPC`, non da difetti del codice. Dopo la rimozione di soli artifact/cache rigenerabili, tutti i gate sopra elencati sono stati rieseguiti con successo.

### Limiti residui

Questa remediation non trasforma i precedenti workflow GitHub senza step in CI verde. Restano inoltre: `xcodebuild` reale della Draft PR iOS #32, decisioni proprietarie su privacy/licenza/firma/store, prove hardware telefono↔Wear e iPhone↔Watch, configurazione Alexa di produzione e triage degli alert security del default branch.
