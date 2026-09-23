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

Una prima passata separata sull'HEAD integrato ha individuato sette lacune residue verificabili. Una seconda revisione indipendente delle correzioni ha poi stressato race, persistenza, cache e possibilità di aggirare i gate. I finding confermati di entrambe le passate sono stati chiusi nella stessa tranche, senza modificare la gerarchia di prodotto.

| Finding confermato | Chiusura e regressione |
|---|---|
| URI e race di revoca Data Layer | Wildcard documentata `wear://*/binario/wear/snapshot`; generation token anche per enable; `revocation_pending` persistito prima delle coroutine; Task Data Layer terminali prima di rilasciare il gate; ricontrollo request/route dopo ogni sospensione. |
| Race e failure storage/native dei reminder Expo | Scheduler nativo autorevole, coda globale, identifier deterministico, migrazione legacy senza dipendere dal marker, deadline e ora civile/DST validate, cancellazione degli ID legacy e handler foreground; test concorrenza/failure injection. |
| Authorization-code o token ancora spendibili dopo unlink/relink | `revokedAt` e `credentialVersion` nella migrazione Drizzle 0003; access token generazionali; code/link/refresh revocati atomicamente; vecchi refresh invalidati al relink; letture personali autorizzate nello stesso statement; test MariaDB concorrenti. |
| Body e cache RFI non bounded | Soglia prima dell'allocazione tramite `Content-Length` e streaming; cache TTL/cap single-flight con worker supervisor indipendente dal primo consumer; cancellazioni chiamante non propagano ai waiter; redirect automatici disabilitati. |
| Parser RFI supplementare sul dispatcher chiamante | Fetch su `Dispatchers.IO`, parsing e filtro su `Dispatchers.Default`, entrambi iniettabili; `CancellationException` viene propagata in entrambi gli adapter. |
| Redirect/policy/memoria/pubblicazione GTFS permissivi | Handler per ogni hop, URL finale HTTPS e `OPEN_DIRECT`; quote central directory, espansione, member e righe; record fisico bounded prima di `csv.DictReader`, campi extra vietati, massimo 32 colonne e 16 KiB per campo; budget aggregati di righe, celle e 128 MiB UTF-8 retained; scrittura ZIP streaming; lock sull'intera transazione build/publish e base merge-aware anche con input/output separati. |
| CI basata su riferimenti mobili o gate testuale | Actions a SHA, MariaDB a digest OCI, runtime patch-pinned inclusi pnpm, runner allowlistati, Xcode/XcodeGen fissati; tutti gli step shell Apple hanno hash nel lock v2, mentre env/defaults/shell aggiuntivi sono vietati; Apple Watch si attiva su ogni modifica della trust chain XcodeGen. |
| Grafi Gradle risolti senza checksum | Dependency verification strict sul root Android/Wear (1.041 artifact, 599 componenti) e sul Companion generato (2.043 artifact, 1.127 componenti), con un SHA-256 per artifact e metadata e nessuna trust exception. Il plugin Expo copia byte per byte il lock sorgente in ogni prebuild.[14] |

## Evidenze di verifica

| Gate | Esito |
|---|---|
| Asset e freshness GTFS | **Verde:** 10 pack, nessun pack scaduto o in scadenza nel periodo minimo; manifest, checksum e registro runtime coerenti. |
| Pipeline GTFS e policy CI | **Verde:** 39 test Python, inclusi download/redirect, quote ZIP/CSV, budget memoria pre-parser, build parziali concorrenti con directory separate, installer XcodeGen, lock CI semantico e i due profili dependency verification Gradle. |
| Regressione memoria GTFS | **Verde:** feed sintetico da 1,35 milioni di righe eseguito con heap JVM massimo di 256 MiB. |
| Android e Wear | **Verde:** 296 test app e 9 test Wear senza failure; compilazione dei test UI; lint debug a **zero issue** per entrambi i moduli, assemble debug, assemble release minificata e `lintVitalRelease`; 204 task nel gate release finale. La release è unsigned perché non sono state fornite credenziali di firma. |
| Alexa Companion | **Verde:** TypeScript, 69 test ordinari e 3 test su MariaDB reale (migrazione fresh, upgrade, revoca generazionale e race OAuth); audit high/critical, build di produzione e smoke test fail-closed/`healthz`. |
| Companion Expo TypeScript | **Verde:** typecheck, 86 test in 15 file, inclusi 11 casi fail-closed per la policy audit, 13 casi reminder, presentazione foreground e lock Gradle generato; branding, managed config e release inputs. Due `expo prebuild --clean` consecutivi producono output identico, inclusi test Kotlin e metadata checksum. |
| Companion Expo nativo | **Verde:** 3 test app e 3 test Wear; doppio prebuild pulito con hash identico `4c5818bb7edc816b6aa9da8dfeec947bc12b5030e07c2345ec85cc55f36f6ecb`, lint e assemble debug di app e Wear generati in modalità Gradle strict; 452 task nell'ultimo gate nativo. Gli avvisi residui provengono dalle dipendenze Expo/React Native deprecate, non da errori di compilazione. |
| Privacy e supply chain | **Verde con eccezione documentata:** verificatore privacy, integrità asset e policy lockfile; gli alert Dependabot sono attivi sul repository. Alexa non contiene advisory high/critical correggibili dopo l’aggiornamento delle dipendenze e la rimozione di 303 pacchetti template. Companion Expo non contiene advisory high/critical correggibili; il gate consente soltanto due advisory `image-size` senza versione corretta disponibile, transitivi di Expo/Metro e limitati agli asset PNG versionati o generati dal progetto. GitHub Actions sono fissate a commit SHA, MariaDB a digest OCI, XcodeGen 2.46.0 a checksum; i grafi Gradle root e Companion sono protetti complessivamente da 3.084 SHA-256.[12] [14] |
| Apple Watch | Il rail macOS isolato ha compilato e testato il contratto; manca intenzionalmente un produttore iOS reale e quindi non esiste una prova end-to-end iPhone–Watch. |

## Decisioni lasciate in sospeso

| Decisione | Stato tecnico | Azione proprietaria necessaria |
|---|---|---|
| Licenza | `LICENSE` non viene inventata. Alexa dichiara MIT, ma ciò non autorizza ad applicarla all'intero repository. | Scegliere MIT o un'altra licenza. |
| Firma release Android/Wear | R8, shrinking e build release sono verificati; il materiale di firma non deve entrare nel repository. | Configurare i quattro segreti `BINARIO_RELEASE_*` in un ambiente di release. |
| Distribuzione Wear | Il prodotto è coerente come companion non standalone e usa lo stesso application ID del telefono. | Scegliere track Wear in Google Play o distribuzione di collaudo e svolgere la prova telefono–watch. |
| OAuth dashboard Alexa | Il codice è operabile, ma il portale OAuth dipende dalla scelta di hosting. | Confermare hosting Manus oppure configurare un provider OAuth sostitutivo. |
| Apple Watch | Il prototipo è watch-only, bounded e testabile, ma non dispone di app iOS produttrice. | Congelarlo fuori dalla release oppure autorizzare lo sviluppo dell'app iOS. |
| Promozione Git | Il branch integrato è pronto per revisione e merge. Protezione di `main`, tag e policy di merge modificano l'accesso al repository. | Approvare separatamente le impostazioni di protezione e i tag. |

## Limiti della consegna corrente

GitHub Actions ha restituito fallimenti di startup sia per l'evento `push` sia per `pull_request`, sempre con **zero step eseguiti**. La diagnosi API mostra `runner_id=0`, `runner_name` vuoto, Actions abilitate e policy repository `allowed_actions=all`: nessun runner viene assegnato e non esiste un log di job. Questi risultati non rappresentano test rossi del codice; per questo il ramo combinato è stato validato localmente. I workflow coprono ora anche i push su `integration/**`, ma la prova remota resta subordinata allo sblocco dei runner. Il branch finale è pubblicato e la PR 26 è aperta e mergeable. Il bundle Git portabile resta disponibile come copia verificata della consegna.

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
