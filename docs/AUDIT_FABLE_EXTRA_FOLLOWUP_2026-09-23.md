# Follow-up all'audit Claude Fable Extra

**Data:** 23 settembre 2026

**Audit di origine:** branch `docs/audit-integration-20260923`, eseguito sul commit `97c24b450e00bc1b10662fa6ce9096394fd512d5`

**Perimetro del follow-up:** branch `integration/stabilization-complete-v0204`, dopo verifica indipendente dei finding e correzioni locali

## Sintesi

L'audit Fable Extra è risultato utile e sostanzialmente fondato: ha riprodotto una perdita silenziosa dei reminder Companion classificata **High**, oltre a difetti **Medium** in Alexa, Companion/Wear, correttezza Android, Apple Watch e governance CI. I finding sono stati verificati sul codice corrente da revisioni indipendenti per area, poi corretti e controrevisionati.

Nessun finding confermato ha modificato la gerarchia del prodotto: **l'app Android per smartphone resta il prodotto principale**; Wear OS, Companion Expo, Apple Watch e Alexa sono superfici companion separate.

## Esito dei finding High e Medium

| Finding audit | Esito sul follow-up | Correzione verificata |
|---|---|---|
| N-C1 — migrazione reminder legacy distruttiva | **Chiuso** | I reminder futuri vengono ricreati con ID canonico prima della cancellazione del precedente. Orfani e scaduti non sono eliminati silenziosamente; la UI mostra un avviso una tantum. I test verificano anche l'ordine schedule-before-cancel. |
| N-A1 — CSRF su logout/unlink Alexa | **Chiuso** | Cookie `SameSite=Lax`; mutation unsafe tRPC limitate a JSON e stessa origine; le riproduzioni cross-site multipart ricevono `415`. |
| N-A2 — discendenza Drizzle divergente | **Chiuso** | La catena WebDev 0000–0011 è canonica; il bridge additivo 0012 adotta anche la breve catena Alexa 0000–0003. Test MariaDB reali coprono database vuoto, entrambe le discendenze e revoca OAuth; la CI esegue `drizzle-kit generate` e fallisce anche su migrazioni o snapshot untracked. |
| N-C2 — applicationId/firma Wear Companion incoerenti | **Chiuso** | Telefono e Wear Companion usano `im.binario.companion`, versionCode distinti e la stessa configurazione di firma release. |
| N-C3 — test Kotlin Companion non eseguiti | **Chiuso** | Il workflow Companion esegue `:app:testDebugUnitTest` e `:wear:testDebugUnitTest`; i test sono rigenerati dal plugin Expo e compilati localmente con dependency verification strict. |
| N-C4 — precisione reminder non dichiarata | **Chiuso per policy approssimativa** | I permessi exact-alarm sono esplicitamente esclusi e la UI dichiara che Android può posticipare i reminder. Non è stato richiesto un privilegio aggiuntivo. |
| N-D1 — cache GTFS con churn 10/5 | **Chiuso** | La cache stop-presence è dimensionata sul numero dei pack embedded e coperta da regressione. |
| N-D2 — notice Wear troncata con spazio finale | **Chiuso** | Normalizzazione e secondo `trim()` dopo il limite; producer e decoder restano fail-closed. |
| N-D3 — Data Item non urgente | **Chiuso** | Le pubblicazioni usano `setUrgent()` e mantengono TTL bounded. |
| N-D4 — FESTIVO nei feriali | **Chiuso** | Calendario ferroviario unico per `FERIALE`/`FESTIVO`, domeniche e festività mobili, con test Pasquetta e giorni ordinari. |
| N-D5 — effetti collaterali del configuratore widget | **Chiuso** | Il ViewModel del configuratore disabilita bootstrap rete e pubblicazione Wear. |
| N-G1 — verifier privacy rotto/assente | **Chiuso tecnicamente** | Il verifier legge le disclosure effettive ed è eseguito da Android, GTFS/release engineering e Companion CI. La pubblicazione resta bloccata finché il proprietario non fornisce titolare, recapito e URL pubblico. |
| N-G2 — hash PyYAML mancante su macOS arm64 | **Chiuso** | `requirements-ci.txt` contiene wheel hashate e binary-only per Linux e macOS arm64; il test dedicato è nel rail Apple. |
| N-G3 — ZIP GTFS non byte-riproducibili | **Chiuso semanticamente** | Lo schema manifest 4 separa hash del container e digest canonico dei member CSV. Un repack byte-diverso ma semanticamente identico è accettato; una modifica ai dati è rifiutata. |
| N-W1 — progetto Apple Watch incompleto | **Chiuso staticamente** | XcodeGen definisce scheme testabile, app watch-only, widget embedded e extension point WidgetKit; quattro test statici sono ora obbligatori nel rail macOS. Il build macOS finale resta da rieseguire dopo il ripristino dei minuti Actions. |
| N-CI — raffica di workflow e minuti Actions esauriti | **Mitigato; blocco esterno aperto** | Le PR path-filtered validano i branch di lavoro; il trigger `push` resta solo su `main`, eliminando il doppio run push+PR. Concurrency cancellation e dispatch manuale restano attivi. I run zero-step non sono test rossi; serve ripristinare quota/minuti e rieseguire i cinque rail sullo SHA finale. |

## Chiusure aggiuntive della controrevisione

La controrevisione successiva ha individuato e chiuso quattro lacune introdotte o rimaste ai bordi delle correzioni. Il contratto Companion/Wear richiede ora `routeId` e `routeLabel` non vuoti in TypeScript, producer Kotlin e consumer Kotlin, con fallback comune `unavailable` / `Nessuna tratta attiva`. Il test statico del progetto Apple Watch è eseguito realmente dal job macOS ed è incluso nei suoi path trigger. Il contratto smartwatch condiviso attiva anche Android CI. Infine, ogni modifica al Companion esegue il verifier delle disclosure privacy.

## Prove eseguite

| Area | Prova | Esito |
|---|---|---|
| Alexa | TypeScript e suite ordinaria | **74 test verdi** |
| Alexa / MariaDB 11.4 | Fresh 0000→0012, adozione 0000→0011, adozione 0000→0003, revoca OAuth | **4 test reali verdi** |
| Alexa supply chain | `pnpm audit --audit-level high`, build Vite/esbuild, verifica bundle | **0 high/critical; build verde** |
| Companion TypeScript | Typecheck, branding, suite, managed config, release inputs | **89 test in 15 file; tutti verdi** |
| Companion nativo | Test JVM app/Wear, lint e assemble con checksum Gradle strict | **Verde** |
| Android/Wear root | 304 test app, 10 test Wear, lint debug, release minificate e `lintVitalRelease` | **Verde; 204 task nel gate completo** |
| GTFS/release engineering | Suite Python, check 30 giorni, digest contenuto, privacy, CI pinning e confronto AAB unsigned | **59 test verdi; 10/10 pack validi; due full rebuild telefono/Wear byte-identiche** |
| Apple Watch | Test progetto, requisiti macOS e verifier CI | **4 test progetto e 23 test mirati verdi** |
| Repository | `git diff --check`, prebuild generato e lock Gradle | **Verde** |

## Limiti e decisioni ancora aperte

Il codice è arrivato al limite delle attività tecniche autonome verificabili nel repository. Prima di una distribuzione pubblica restano decisioni o prove che richiedono il proprietario o infrastruttura esterna: scelta della licenza; materiale di firma e credenziali store; canale Wear e prova telefono–watch; titolare, recapito e URL privacy; hosting/OAuth/Amazon Alexa; produttore iOS e prova iPhone–Apple Watch; ripristino dei minuti GitHub Actions e rerun dei cinque workflow; approvazione del merge della PR #26, promozione a `main` e tag di release.

> I fallimenti GitHub Actions con `runner_id=0` e zero step non attestano un errore del codice. Il merge non deve tuttavia avvenire finché la quota/minuti non viene ripristinata e i workflow non vengono rieseguiti sullo SHA finale.
