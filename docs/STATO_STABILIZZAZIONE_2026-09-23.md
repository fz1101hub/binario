# Stato consolidato della stabilizzazione Binario

**Data:** 23 settembre 2026  
**Autore:** Manus AI  
**Base:** `stabilization/v0.20.4`  
**Branch di integrazione:** `integration/stabilization-complete-v0204`

## Conclusione

Il piano di stabilizzazione è stato sviluppato fino al limite delle decisioni proprietarie e delle prove su dispositivi reali. Il branch di integrazione unisce le correzioni Android, Wear OS, Companion Expo e Alexa; include inoltre il contratto smartwatch v1, il dominio `PlannedJourney`, la pipeline GTFS riproducibile e la pulizia del repository. Le correzioni non sono una riscrittura: mantengono l'app Android come prodotto principale e trattano Wear, Companion Expo, Apple Watch e Alexa come superfici subordinate.

I gate locali del codice sono verdi. Restano fuori dalla conclusione tecnica la scelta della licenza, le credenziali di firma, il canale distributivo Wear, il provider OAuth di produzione e la promozione del branch in `main`. Il test telefono-orologio reale e la certificazione Alexa richiedono inoltre hardware o account esterni.

## Perimetro integrato

| Area | Risultato |
|---|---|
| Alexa Companion | Runtime e superfici WebDev generiche rimossi; OAuth con consenso e CSRF; allowlist dei redirect; richiesta `authorize` conservata e rivalidata attraverso il login; revoca opponibile. Unlink, consumo authorization-code, ripristino link, emissione e rotazione refresh sono serializzati in transazioni DB. Access token e link condividono una `credentialVersion`: unlink e relink invalidano le generazioni precedenti, mentre ogni relink revoca anche tutti i refresh token attivi. Le letture personali verificano link e generazione nello stesso statement SQL. Migrazioni fresh e upgrade, race, handler built-in, health check e configurazione fail-closed sono provati anche su MariaDB reale. Il servizio resta separato dall'app e dagli smartwatch.[1] |
| Dati ferroviari | Download bounded e single-flight, dispatcher IO/CPU espliciti, indice GTFS compatto, regressione a heap limitato, fuso ferroviario coerente, semantica del giorno di servizio, festività, gzip, `stop_sequence=0`, operatori sconosciuti e policy provider applicate. Il body RFI è bounded; le cache RFI hanno TTL/cap e worker single-flight indipendente dalla cancellazione del primo consumer. La pipeline GTFS consente solo `OPEN_DIRECT`, mantiene HTTPS su ogni hop, applica quote ZIP/CSV e budget retained, filtra in streaming le tabelle grandi, scrive i CSV direttamente nello ZIP e serializza l'intera transazione build/publish. La CI impone integrità e almeno 30 giorni di validità; AMT e Cotral restano disabilitati finché non esiste un feed affidabile.[2] [3] |
| Wear OS | Package telefonico e Wear coerenti per il Data Layer, sharing disattivato per default, refresh limitato, cancellazione del Data Item e della cache, invalidazione runtime al TTL, chiavi UI univoche, manifest e policy `standalone=false` documentati. La revoca usa la URI wildcard `wear://*/binario/wear/snapshot`, persiste l'intento prima di coroutine cancellabili ed è last-writer-wins rispetto a enable/publish obsoleti. I Task Play Services già avviati raggiungono lo stato terminale sotto il gate prima della delete.[4] [13] Manifest, anteprime Tile, backup policy e copy UI sono verificati dal lint. |
| Contratto smartwatch | Schema v1 chiuso e bounded, TTL e provenienza espliciti, fixture golden Android–Wear, Companion–Wear e iOS–Apple Watch, decoder fail-closed e fallback `unknown`. Il canale Companion→Wear trasporta e valida ora il contratto effettivo, e il prebuild rigenera anche i test Kotlin golden.[5] |
| Android UX | Deep link one-shot, lifecycle e Back corretti, shortcut, widget per istanza, refresh e freshness, allarmi ripristinabili, fascia silenziosa, permessi preservati alla rotazione, backup bounded, autocomplete calcolato fuori dalla composizione e preferenze robuste. Il copy UI della schermata principale, del configuratore widget, delle notifiche e del selettore tema è centralizzato nelle risorse Android.[6] |
| Accessibilità e voce | Stringhe a risorsa e semantiche migliorate; label dinamiche, stati, filtri e annunci delle superfici Android/Wear sono localizzabili; la dettatura distingue esiti confermati, ambigui e irrisolti e applica automaticamente soltanto stazioni confermate.[7] |
| PlannedJourney | Dominio personale offline distinto da `SavedRoute`; persistenza locale versionata e bounded; schermata Agenda raggiungibile; itinerari multi-tratta, reminder, checklist, ricorrenze finite, lifecycle, deduplica, import/export JSON con preview conflitti e calendario ICS bounded.[8] |
| Companion Expo | Bridge nativi, shared text, reminder e archivio locale corretti. Lo scheduler nativo è la fonte autorevole dei reminder: identificatori deterministici, mutazioni serializzate, migrazione legacy riconciliata anche senza marker, cancellazione di ID casuali residui, validazione dell'ora civile/DST e presentazione foreground esplicita. Sincronizzazione remota nascosta, semantiche accessibili, branding e permessi sono verificati; il prebuild Android è riproducibile e comprende i test nativi.[9] |
| Repository | Documenti storici spostati fuori dalla radice; todo di sessione rimossi; snapshot `surfaces/` preservato nel branch archivio e rimosso dalla linea attiva.[10] [11] |

## Riesami indipendenti post-integrazione

Una prima passata separata sull'HEAD integrato ha individuato sette lacune residue verificabili. Una seconda revisione indipendente delle correzioni ha poi stressato race, persistenza, cache e possibilità di aggirare i gate. Il successivo audit **Claude Fable Extra**, eseguito indipendentemente sul commit `97c24b4`, ha riprodotto un High e diversi Medium non ancora coperti; tutti i finding tecnici confermati sono stati riesaminati sul codice corrente, corretti e controrevisionati. La gerarchia di prodotto non cambia: l'app smartphone resta principale, Wear OS, Companion Expo, Apple Watch e Alexa restano companion separati.

| Finding confermato | Chiusura e regressione |
|---|---|
| URI e race di revoca Data Layer | Wildcard documentata `wear://*/binario/wear/snapshot`; generation token anche per enable; `revocation_pending` persistito prima delle coroutine; Task Data Layer terminali prima di rilasciare il gate; ricontrollo request/route dopo ogni sospensione. |
| Race e failure storage/native dei reminder Expo | Scheduler nativo autorevole, coda globale, identifier deterministico, migrazione legacy senza dipendere dal marker, deadline e ora civile/DST validate, cancellazione degli ID legacy e handler foreground; test concorrenza/failure injection. |
| Authorization-code o token ancora spendibili dopo unlink/relink | `revokedAt` e `credentialVersion` nel bridge additivo Drizzle 0012; access token generazionali; code/link/refresh revocati atomicamente; vecchi refresh invalidati al relink; letture personali autorizzate nello stesso statement; test MariaDB concorrenti. |
| Body e cache RFI non bounded | Soglia prima dell'allocazione tramite `Content-Length` e streaming; cache TTL/cap single-flight con worker supervisor indipendente dal primo consumer; cancellazioni chiamante non propagano ai waiter; redirect automatici disabilitati. |
| Parser RFI supplementare sul dispatcher chiamante | Fetch su `Dispatchers.IO`, parsing e filtro su `Dispatchers.Default`, entrambi iniettabili; `CancellationException` viene propagata in entrambi gli adapter. |
| Redirect/policy/memoria/pubblicazione GTFS permissivi | Handler per ogni hop, URL finale HTTPS e `OPEN_DIRECT`; quote central directory, espansione, member e righe; record fisico bounded prima di `csv.DictReader`, campi extra vietati, massimo 32 colonne e 16 KiB per campo; budget aggregati di righe, celle e 128 MiB UTF-8 retained; scrittura ZIP streaming; lock sull'intera transazione build/publish e base merge-aware anche con input/output separati. |
| CI basata su riferimenti mobili o gate testuale | Actions a SHA, MariaDB a digest OCI, runtime patch-pinned inclusi pnpm, runner allowlistati, Xcode/XcodeGen fissati; tutti gli step shell Apple hanno hash nel lock v2, mentre env/defaults/shell aggiuntivi sono vietati; Apple Watch si attiva su ogni modifica della trust chain XcodeGen. |
| Grafi Gradle risolti senza checksum | Dependency verification strict sul root Android/Wear (1.041 artifact, 599 componenti) e sul Companion generato (2.043 artifact, 1.127 componenti), con un SHA-256 per artifact e metadata e nessuna trust exception. Il plugin Expo copia byte per byte il lock sorgente in ogni prebuild.[14] |
| Gate release limitato agli APK | `bundleRelease` produce AAB separati per telefono e Wear. Il verifier fail-closed controlla ZIP, manifest base, DEX, CRC, percorsi, duplicati, limiti e SHA-256 prima di firma o upload.[15] [16] |
| CSRF e logging Alexa | Cookie di sessione `SameSite=Lax`; mutation unsafe tRPC limitate a JSON e stessa origine; logout e unlink cross-site riprodotti nei test come `415`. Il redattore Axios elimina body, query e header sensibili con chiavi case-insensitive. |
| Discendenza database Alexa divergente | Catena WebDev storica 0000–0011 adottata come canonica; bridge additivo/idempotente 0012 compatibile anche con la breve catena Alexa 0000–0003. Quattro test MariaDB reali coprono database vuoto, entrambe le discendenze e revoca OAuth. |
| Migrazione reminder Companion distruttiva | Ogni reminder legacy futuro viene ricreato con ID canonico prima di cancellare il precedente; orfani e scaduti non vengono cancellati silenziosamente e un avviso una tantum rende visibile la migrazione. La suite copre ordine schedule-before-cancel e fallback senza perdita. |
| Package/firma e contratto Companion→Wear | Telefono e Wear Companion condividono `im.binario.companion`, versione e firma configurabile; producer TS/Kotlin e consumer Kotlin richiedono `routeId`/`routeLabel` non vuoti e usano lo stesso fallback `unavailable` / `Nessuna tratta attiva`. I test Kotlin sono generati dal prebuild ed eseguiti in CI. |
| Correttezza Android dati/UX | Cache stop-presence dimensionata su tutti i pack; notice Wear tronca e normalizza senza trailing whitespace; Data Item urgente; calendario ferroviario unico `FERIALE`/`FESTIVO` con Pasquetta; fuso `Europe/Rome`; configuratore widget senza refresh/pubblicazioni di startup. |
| Riproducibilità GTFS semantica | Manifest schema 4 con digest canonico dei member CSV, separato dall'hash del container ZIP; quote e freshness continuano fail-closed e 10/10 asset embedded sono attestati. |
| Apple Watch e trigger CI | Progetto XcodeGen con scheme testabile, app watch-only, widget embedded e refresh timeline; test statico obbligatorio nel comando macOS e nei path trigger. Android include il contratto smartwatch condiviso; Companion esegue anche il verifier privacy. |

## Evidenze di verifica

| Gate | Esito |
|---|---|
| Asset e freshness GTFS | **Verde:** 10 pack, nessun pack scaduto o in scadenza nel periodo minimo; manifest, checksum e registro runtime coerenti. |
| Pipeline GTFS e policy CI | **Verde:** 59 test Python, inclusi download/redirect, quote ZIP/CSV, digest contenuto, freshness, budget memoria pre-parser, build parziali concorrenti con directory separate, progetto/installer XcodeGen, privacy, lock CI semantico, due profili Gradle e struttura/firma/riproducibilità App Bundle bounded. |
| Regressione memoria GTFS | **Verde:** feed sintetico da 1,35 milioni di righe eseguito con heap JVM massimo di 256 MiB. |
| Android e Wear | **Verde:** 304 test app e 10 test Wear senza failure; compilazione dei test UI; lint debug a **zero issue** per entrambi i moduli, assemble debug, assemble release minificata e `lintVitalRelease`; 204 task nel gate release completo. Due full rebuild `bundleRelease --rerun-tasks` hanno prodotto AAB unsigned byte-identici: 18.490.402 byte (telefono) e 3.894.315 byte (Wear). Il verifier blocca firme JAR inattese. La firma resta esclusa perché non sono state fornite credenziali. |
| Alexa Companion | **Verde:** TypeScript, 74 test ordinari e 4 test su MariaDB reale (fresh 0000→0012, adozione WebDev 0000→0011, adozione Alexa breve 0000→0003 e revoca OAuth); audit privo di high/critical, build di produzione e bundle fail-closed verificato. |
| Companion Expo TypeScript | **Verde:** typecheck, 89 test in 15 file, inclusi 11 casi fail-closed per la policy audit, 14 casi reminder, presentazione foreground, contratto Wear e lock Gradle generato; branding, managed config e release inputs. Il prebuild pulito rigenera test Kotlin e metadata checksum. |
| Companion Expo nativo | **Verde:** gate completo precedente da 816 task su lint e assemble debug/release; sul prebuild finale i 4 test app e 4 test Wear verificano anche l'identità route non vuota, con lint e assemble debug in modalità Gradle strict. Due prebuild puliti consecutivi producono lo stesso hash albero `75efe2bf77e64d837b2958db9e19cd0e41cb03c12318fdea2b911a21b05c19f5`. Gli avvisi residui provengono dalle dipendenze Expo/React Native deprecate, non da errori di compilazione. |
| Privacy e supply chain | **Verde con eccezione documentata:** verificatore privacy, integrità asset e policy lockfile; gli alert Dependabot sono attivi sul repository. Alexa non contiene advisory high/critical correggibili dopo l’aggiornamento delle dipendenze e la rimozione di 303 pacchetti template. Companion Expo non contiene advisory high/critical correggibili; il gate consente soltanto due advisory `image-size` senza versione corretta disponibile, transitivi di Expo/Metro e limitati agli asset PNG versionati o generati dal progetto. GitHub Actions sono fissate a commit SHA, MariaDB a digest OCI, XcodeGen 2.46.0 a checksum; i grafi Gradle root e Companion sono protetti complessivamente da 3.084 SHA-256.[12] [14] |
| Apple Watch | **Verde staticamente:** 4 test verificano scheme, app watch-only, embedding WidgetKit, target test e refresh timeline; il test è obbligatorio nel rail macOS. L'ultimo `xcodebuild` sullo SHA finale resta da eseguire dopo il ripristino dei minuti GitHub Actions. Manca intenzionalmente un produttore iOS reale e quindi non esiste una prova end-to-end iPhone–Watch. |

## Decisioni lasciate in sospeso

| Decisione | Stato tecnico | Azione proprietaria necessaria |
|---|---|---|
| Licenza | `LICENSE` non viene inventata. Alexa dichiara MIT, ma ciò non autorizza ad applicarla all'intero repository. | Scegliere MIT o un'altra licenza. |
| Firma release Android/Wear | R8, shrinking, APK e AAB release sono verificati; il materiale di firma non deve entrare nel repository. | Configurare i quattro segreti `BINARIO_RELEASE_*` in un ambiente di release. |
| Distribuzione Wear | Il prodotto è coerente come companion non standalone e usa lo stesso application ID del telefono. | Scegliere track Wear in Google Play o distribuzione di collaudo e svolgere la prova telefono–watch. |
| OAuth dashboard Alexa | Il codice è operabile, ma il portale OAuth dipende dalla scelta di hosting. | Confermare hosting Manus oppure configurare un provider OAuth sostitutivo. |
| Apple Watch | Il prototipo è watch-only, bounded e testabile, ma non dispone di app iOS produttrice. | Congelarlo fuori dalla release oppure autorizzare lo sviluppo dell'app iOS. |
| Promozione Git | Il branch integrato è pronto per revisione e merge. Protezione di `main`, tag e policy di merge modificano l'accesso al repository. | Approvare separatamente le impostazioni di protezione e i tag. |

## Limiti della consegna corrente

Il nuovo audit ha identificato come causa operativa l'**esaurimento dei minuti GitHub Actions** dopo una raffica di run: fino alle 06:44 circa i job venivano assegnati ed eseguivano step, mentre da quel momento `push`, `pull_request` e dispatch falliscono in 3–6 secondi con `runner_id=0`, `runner_name` vuoto e **zero step eseguiti**. Actions risulta abilitato e non esiste un log di job; questi esiti non sono test rossi del codice. I branch di lavoro sono ora verificati soltanto dalle pull request path-filtered; il trigger `push` resta su `main`, eliminando i run duplicati sul medesimo commit. Concurrency cancellation e dispatch manuale restano attivi. La prova remota dello SHA finale richiede comunque il ripristino della quota/minuti e il rerun esplicito dei cinque rail. Il branch finale è pubblicato e la PR 26 resta aperta e mergeable; il bundle Git portabile rimane la copia verificata della consegna.

Le scelte proprietarie elencate sopra non bloccano ulteriore sviluppo interno, ma impediscono di dichiarare una release pubblica completa. In particolare, una build unsigned non sostituisce una release firmata e una fixture smartwatch non sostituisce una prova su hardware reale.

## Riferimenti

[1]: https://github.com/fz1101hub/binario/pull/22 "PR 22 — Alexa companion cleanup"
[2]: https://github.com/fz1101hub/binario/pull/19 "PR 19 — Rail data correctness"
[3]: https://github.com/fz1101hub/binario/pull/12 "PR 12 — Reproducible GTFS pipeline"
[4]: https://github.com/fz1101hub/binario/pull/17 "PR 17 — Wear Data Layer"
[5]: https://github.com/fz1101hub/binario/pull/21 "PR 21 — Shared smartwatch snapshot contract"
[6]: https://github.com/fz1101hub/binario/pull/26 "PR 26 — Complete stabilization integration"
[7]: https://github.com/fz1101hub/binario/pull/25 "PR 25 — Android voice review"
[8]: https://github.com/fz1101hub/binario/pull/5 "PR 5 — PlannedJourney domain"
[9]: https://github.com/fz1101hub/binario/pull/9 "PR 9 — Companion sync visibility"
[10]: https://github.com/fz1101hub/binario/pull/24 "PR 24 — Archive and remove historical surfaces"
[11]: https://github.com/fz1101hub/binario/pull/23 "PR 23 — Repository documentation hygiene"
[12]: security/DEPENDENCY_AUDIT.md "Policy di audit delle dipendenze e degli input CI"
[13]: https://developers.google.com/android/reference/com/google/android/gms/wearable/DataClient "Google Play services DataClient — URI wear://*/path"
[14]: https://docs.gradle.org/8.14.3/userguide/dependency_verification.html "Gradle 8.14.3 — Verifying dependencies"
[15]: https://developer.android.com/guide/app-bundle "Android Developers — About Android App Bundles"
[16]: https://developer.android.com/training/wearables/packaging "Android Developers — Package and distribute Wear OS apps"
