# Audit indipendente dell'intero progetto Binario (30 settembre – 1 ottobre 2026)

**Oggetto**: la cima della pila di sviluppo `feature/ios-main-app-mvp` @ `1d3cdee7b48d342dff5ec3713df6451cb4411e43`, che contiene in sequenza lineare:

| Strato | Commit | PR | Contenuto |
|---|---|---|---|
| base | `a5b6808` | #26 (integrazione) | già auditata il 29/09 |
| PR#33 | `a5b6808..7e59376` (20 commit) | #33 draft | remediation dei finding del 29/09 |
| PR#34 | `7e59376..be58f0f` (5 commit) | #34 draft | localizzazione it/en/de/fr/es su Android, Wear, Companion, Alexa, Apple Watch |
| PR#32 | `be58f0f..1d3cdee` (7 commit) | #32 draft | app iPhone, BinarioKit, indice GTFS offline SQLite, viaggi programmati, producer Watch |

Il ramo di integrazione non è cambiato dal 24/09. Ogni finding indica lo **strato** che lo ha introdotto: *preesistente* significa presente già a `a5b6808` o prima.

**Modello**: sessione su `claude-opus-5-5` (verificato con `get_session`: modello configurato e ultimo servito coincidono), workflow con sforzo massimo su finder, regressione, verificatori di riproduzione e critico.

**Metodo**:
1. **Registro**: un agente ha estratto **245 finding** dai quattro rapporti precedenti (R1 = `audit-independent-2026-09-23.md`, R2 = addendum, R3 = `audit-independent-2026-09-23-integrazione.md`, R4 = `audit-independent-2026-09-29-integrazione.md`), inclusi tutti i Low e Info.
2. **Regressione completa**: sei agenti, uno per area, hanno ricontrollato **tutti i 245** nel codice di `1d3cdee`. Nessun id è rimasto senza verdetto.
3. **Scettico delle correzioni**: per i 30 High/Medium di R3 e R4 dichiarati corretti, dieci agenti hanno cercato attivamente casi in cui la correzione non regge. Ne hanno contestate 5.
4. **Ricerca del nuovo**: 13 finder con angolazioni diverse dal 29/09 (diff della remediation, localizzazione, indice GTFS iOS, flussi end-to-end Android, confini di fiducia, persistenza e aggiornamento, concorrenza e ciclo di vita, semantica oraria ferroviaria, test vacui, invarianti fra implementazioni gemelle, verifier aggirabili, Alexa, sicurezza mobile) più un esecutore di controlli. Ogni finder aveva l'elenco dei 245 già noti con l'istruzione di non riproporli.
5. **Verifica avversariale**: ogni finding nuovo è passato da tre lenti indipendenti (riproduzione con istruzione di confutare, impatto e severità, novità rispetto al registro); sopravvive a maggioranza.
6. **Critico di completezza** con un giro di ricerca aggiuntivo.
7. Controllo manuale dell'orchestratore sui finding High (§3) e lettura dei documenti di Manus solo alla fine (§7).

**Rubrica**: Critical solo compromissione dimostrata o guasto sistemico; High vulnerabilità reale, perdita dati, crash riproducibile o blocco di release; Medium funzione importante rotta o affidabilità degradata; Low manutenzione, documentazione, hardening. Verifiche marcate **E** (eseguita), **S** (solo statica), **N** (non eseguibile).

**Limiti di questo audit, dichiarati prima dei risultati**:
- Il container si è riavviato tre volte durante l'esecuzione; il workflow è stato ripreso dalla cache ogni volta. Al terzo riavvio ho chiuso con i risultati completi: **mancano il giro di ricerca sulle lacune** proposto dal critico (cinque aree, elencate in §8) e **due lotti di verifica** con una sola lente su tre.
- Le tre lenti di verifica hanno confutato solo 1 dei 223 candidati nuovi. È un tasso troppo basso per escludere indulgenza: per questo ho ricontrollato io i High (§3) e ho declassato o unito i duplicati segnalati dal critico. La sezione 4 elenca le riserve.
- Nessun toolchain Swift, Xcode o Android SDK: Kotlin e Swift sono analizzati staticamente, con ricostruzioni fedeli in Python/Java/`kotlinc` dove indicato.

## 1. Verifiche eseguite (cima `1d3cdee`)

| Verifica | Esito |
|---|---|
| `alexa-companion`: `tsc`, `vitest`, build + `verify-production-build`, installazione solo production e avvio fino a `/healthz` | 0 errori; **132 test verdi**, 4 skipped; bundle pulito; il server production-only parte (M-11 del 29/09 risolto) |
| `alexa-companion`: **migrazioni reali su MariaDB** (`mariadbd` locale) | **4/4 verdi** (0000→0013, adozione 0000→0011, catena corta 0000→0003, OAuth/replay/erasure) |
| `alexa-companion`: drift schema, `pnpm audit` | nessun drift; 0 high/critical (3 low, 6 moderate) |
| `companion-expo`: `tsc` + branding, `vitest`, `verify:android-manifest`, `verify:managed-config`, `verify:release-inputs` | 0 errori; **102 test verdi** in 18 file; verifier verdi |
| `companion-expo`: `verify:dependency-audit` (gate CI) | **FAIL**: 6 advisory high `brace-expansion` (pubblicate dopo la remediation di `image-size`) |
| `companion-expo`: `expo prebuild` | `--clean` ×2 pulito; **prebuild semplice non idempotente**: duplica `resourceConfigurations` in `android/app/build.gradle` |
| `companion-expo`: script `prebuild` del pacchetto | fallisce senza Pillow (`generate-binario-brand-assets.py`); con Pillow gli asset sono byte-identici |
| Python `unittest discover` | **101 test OK** |
| Verifier dei workflow (`verify_embedded_assets`, `verify_privacy_policy`, `verify_android_release_config`, `verify_gradle_dependency_metadata` root 599/1041 e companion 1129/2050, `verify_ci_pinning`, `build_gtfs_packs --check`) | tutti PASS |
| iOS: `build_ios_gtfs_schedule` + verify + `git diff` su Linux | rigenerazione identica su Linux (SQLite di sistema); 13/13 input coerenti con i pack Android |
| GTFS Android | 10/10 hash OK; giorni residui da oggi: alto-adige, ch-otd e altri 5 pack terminano il **12–13/12/2026** (73 giorni), il gate dei 30 giorni fallirà dal 13/11 |
| Parità localizzazioni | Android 555 chiavi ×5 lingue, Wear 24×5, Companion TS 498×5, Alexa 94×5, iOS 73×5: nessuna chiave mancante o placeholder incoerente; modelli di interazione Alexa con intent/slot identici in 6 locali |
| `git diff --check` e file grandi | 4 spazi finali in un markdown; `ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib` da 7,4 MB aggiunto fuori da LFS |
| `verify_android_app_bundles`, `verify-deterministic-generation.py`, `xcodebuild` | non eseguibili (nessun AAB, nessun macOS) |
| **GitHub Actions** | run di prova `GTFS Asset Integrity` #76 su `1d3cdee` (30/09 04:41 UTC): fallita in 4 s senza runner. L'annotazione dei check run, letta dal critico, dice: *"The job was not started because recent account payments have failed or your spending limit needs to be increased"*. **La causa del blocco è il billing dell'account**, non il codice. Nessun job è partito dal 23/09 06:44 UTC |

## 2. Riepilogo

| Categoria | Numero |
|---|---|
| Finding precedenti ricontrollati | **245** (tutti) |
| Precedenti **risolti** | **85** |
| Precedenti **ancora aperti** | **101** |
| Precedenti **parzialmente risolti** | **24** (di cui 5 con correzione contestata dallo scettico) |
| Precedenti superati da un altro id / decisione del proprietario / rimossi per scelta | 27 / 3 / 5 |
| Finding **nuovi** confermati, dopo unione dei duplicati | **7 High**, **30 Medium**, **~140 Low**, **8 Info** |
| Critical | 0 |

Il risultato più importante: **l'app Android principale non si avvia**. Il difetto è preesistente (23/09), quindi presente anche nella PR #26 "pronta al merge", e nessuno dei quattro audit precedenti né dei gate locali di Manus l'ha visto (N-H1).

## 3. Finding nuovi mai rilevati prima

### High

- **N-H1 · Android — l'app crasha a ogni avvio: `TrainViewModel` non ha più un costruttore `(Application)`** [preesistente, introdotto da `2399cd5` il 23/09 come correzione di N-D5; verificato dall'orchestratore nel codice e da tre lenti; nessun dispositivo]
  - File: `app/src/main/java/it/binario/app/TrainViewModel.kt:56-60` `class TrainViewModel(application: Application, private val enableStartupEffects: Boolean = true) : AndroidViewModel(application)`; `MainActivity.kt:223` `val viewModel: TrainViewModel = viewModel()` senza factory.
  - Percorso: la factory predefinita di `ComponentActivity` (`SavedStateViewModelFactory`, lifecycle 2.8.7) non trova un costruttore `(Application, SavedStateHandle)` e delega ad `AndroidViewModelFactory`, che chiama `getConstructor(Application::class.java)`. Kotlin non genera un overload a un argomento per i parametri con default senza `@JvmOverloads`, quindi `NoSuchMethodException` → `RuntimeException("Cannot create an instance of class TrainViewModel")` all'apertura di `MainActivity`, l'attività LAUNCHER.
  - Condizioni: ogni avvio, ogni dispositivo, debug e release (R8 non può aggiungere un costruttore). Presente a `a5b6808`, `7e59376`, `be58f0f`, `1d3cdee`.
  - Impatto: l'app principale è inutilizzabile. Per la rubrica sarebbe un guasto sistemico (Critical), ma la prova è statica: lo classifico High con priorità assoluta finché non viene osservato su un dispositivo, cosa che richiede un minuto.
  - Perché nessuno l'ha visto: i 330 test JVM istanziano il ViewModel direttamente; nessun test strumentato apre `MainActivity`; nessuno ha avviato l'app dopo il 23/09.
  - Fix minimo: `class TrainViewModel @JvmOverloads constructor(application: Application, private val enableStartupEffects: Boolean = true)`, oppure una factory esplicita in `MainActivity`. Test: test strumentato o Robolectric che lancia `MainActivity`; in alternativa un test JVM che chiama `ViewModelProvider.AndroidViewModelFactory(app).create(TrainViewModel::class.java)`.

- **N-H2 · Alexa — una richiesta non autenticata con `Accept-Language: constructor` termina l'intero processo Node** [PR#34; E: riprodotto sul bundle di produzione dal finder e dal verificatore; ricontrollato dall'orchestratore con una probe]
  - File: `alexa-companion/shared/i18n.ts:297-300` (`exactLocaleMap[normalized]` e `language in languageDefaultLocale` ereditano da `Object.prototype`); uso in `server/voice/alexa.ts:353`, `:466`, `:487` e in `consent.ts`.
  - Prova: `resolveLocale("constructor")` restituisce la funzione `Object` invece di una stringa (probe eseguita); il passo successivo chiama `.slice` e lancia dentro un handler `async` di Express 4 senza gestione d'errore; Node 22 termina il processo su rejection non gestita.
  - Impatto: denial of service per tutti gli utenti (dashboard, tRPC, account linking, webhook Alexa) con una sola richiesta GET, ripetibile dopo ogni riavvio. `Accept-Language` è un header CORS-safelisted.
  - Fix: mappe con `Object.create(null)` o `Map` e `Object.hasOwn`; più N-H3.

- **N-H3 · Alexa — qualsiasi errore negli handler OAuth `async` termina il processo** [preesistente; E: errore DB iniettato sul bundle di produzione] (unisce AD-1, CL-1)
  - File: `server/voice/alexa.ts:432` e seguenti (`app.get("/api/alexa/oauth/authorize", async …)`, POST consenso, `/token`); nessun `asyncHandler`, nessun middleware d'errore terminale, nessun `process.on("unhandledRejection")` in `_core/index.ts`; Express 4.21.2.
  - Inneschi: un errore transitorio di MariaDB, oppure il deadlock riprodotto (CL-2, ~95 %) quando due utenti scambiano un codice di linking in contemporanea.
  - Fix: try/catch o `asyncHandler` che inoltra a `next(err)`, middleware d'errore RFC 6749 per `/api/alexa/oauth`, retry su `ER_LOCK_DEADLOCK`.

- **N-H4 · Android — le corse notturne GTFS producono due `TrainService` con lo stesso id: chiavi duplicate nelle `LazyColumn` e crash di Risultati/Operazioni dopo mezzanotte** [preesistente, introdotto dalla correzione di R2:A2; E: ricostruzione Python sul pack Trenord reale]
  - File: `EmbeddedGtfsRouteAdapter.kt:406-426` (ciclo sulle date di servizio D, D-1, D-2), `:495` `id = "$sourceId:${trip.id}"` senza data; `MainActivity.kt:685`, `:1270`, `:1295` `items(services, key = { it.id })`.
  - Prova: query 2026-10-01 00:00 → 183 coppie di stazioni Trenord restituiscono lo stesso trip due volte nei primi 24 servizi (78 entro 4 righe, cioè sulla stessa schermata). Compose lancia `IllegalArgumentException: Key … was already used`.
  - Fix: includere la data di servizio nell'id; deduplicare per `(tripId, serviceDate)`.

- **N-H5 · Companion — con 40 viaggi salvati, "salva" riporta successo ma perde in silenzio il viaggio nuovo o uno esistente** [preesistente; E] (unisce RD-1, PU-1, NEW-COMP-3)
  - File: `companion-expo/lib/local-routes.ts:6` `maxLocalRoutes = 40`, `:24` `save()` ordina e taglia con `slice(0, maxLocalRoutes)`; `components/local-plan-card.tsx` mostra "Tratta salvata".
  - Impatto: perdita di dati utente senza avviso; un promemoria già schedulato per il viaggio eliminato scatta comunque.
  - Fix: esito distinto "archivio pieno" oppure potatura dei viaggi passati/completati, mai di quelli futuri.

- **N-H6 · Apple Watch — il widget non compila: `AccessoryWidgetBackground { … }` usa una closure che l'API non accetta** [preesistente, `f118275` del 13/08; S] (unisce MS-2, AI-NF-1)
  - File: `apple-watch/BinarioWatchWidgets/BinarioWatchWidgets.swift:45`. `AccessoryWidgetBackground` ha solo `init()`; serve `ZStack { AccessoryWidgetBackground(); … }`.
  - Impatto: né il progetto Watch né l'app iPhone integrata (PR #32) possono essere compilati, anche dopo la correzione di H-2. Nessuna CI ha mai compilato il target widget.

- **N-H7 [PR#32] · iOS — il bundle id della Watch app incorporata (`it.binario.watch`) non è prefissato da quello dell'app iPhone (`it.binario.ios`)** [PR#32; S/N] (unisce AI-NF-2, IOSGTFS-02)
  - File: `ios/project.yml:73`, `:103` (e `:30` per il parent). Xcode rifiuta un binario incorporato non prefissato; l'app integrata non supera validazione né upload.
  - Fix: `it.binario.ios.watchkitapp` e `it.binario.ios.watchkitapp.widgets` nel progetto integrato.

### Medium

**Regressioni introdotte dalla remediation (PR#33)**
- **N-M1 · Periodicità RFI riscritta male** [PR#33, `179d2b4`; E] (RD-2, FSK-ND4-01) — `ItalianRailServiceCalendar.kt`: le forme negative o a intervallo che citano SABATO/DOMENICA ("non si effettua il sabato", "dal lunedì al sabato") vengono valutate come il contrario; `\bFEST(?:IVO|IVI)?\b` non riconosce più "festive/festiva/festività", che ricadono nel default `true` → **N-D4 riaperto**. Le stringhe RFI reali non sono state scaricate (proxy): l'esistenza di queste forme nel feed vivo resta da confermare.

**Gemelli di H-1 non coperti dalla correzione** (la canonicalizzazione all'avvio è corretta; restano altri percorsi con la stessa classe di errore)
- **N-M2 · Destinazione RFI per sottostringa** [preesistente; E] (RD-3, E2E-4, AND-NEW-2) — `TrainDataSource.kt` `matchesPublishedName` bidirezionale usato in `RfiProgrammedTimetableAdapter`/`RfiSupplementalHubTimetableAdapter`: corse che non arrivano a destinazione elencate come dirette, arrivo troncato alla stazione sbagliata (Latina → Cisterna di Latina, Milano → Piacenza, Bologna → Ferrara/Rovigo).
- **N-M3 · Stazione scelta ri-risolta per nome con "primo vince"** [preesistente; E] (RD-4, E2E-3, TW-4) — `StationAutocompleteEngine.findExact` + `TrainViewModel`/`MainActivity`: 122 stazioni del catalogo non sono selezionabili ("Milan" → Malpensa Aeroporto); l'identità sbagliata finisce in tratte, recenti, widget.
- **N-M4 · Hub RFI con chiavi che il catalogo non usa** [preesistente; E] (TW-5, E2E-10, AND-NEW-3) — Roma/Milano/Bologna/Napoli → Firenze S. M. Novella senza corse programmate RFI ("S.M.N." non riconosciuto); tabellone sempre indisponibile per Firenze SMN e Bari Centrale; Reggio di Calabria Centrale e Ancona mai serviti.
- **N-M5 · Pack GTFS associati alle stazioni solo per nome esatto** [preesistente; E] (TW-2) — `EmbeddedGtfsRouteAdapter.kt`: 51 stazioni servite dai pack inclusi (quasi tutto l'Alto Adige bilingue, rete Malpensa Express/Cadorna) restituiscono errore su Android, mentre l'iPhone trova le corse.

**Android**
- **N-M6 · Fermate senza salita/discesa proposte come soluzioni** [preesistente; E] (TW-6, IOSGTFS-14) — `pickup_type`/`drop_off_type` ignorati (es. EC Venezia Mestre → Venezia S. Lucia).
- **N-M7 · La sveglia di fermata usa la data di oggi anche per corse di altri giorni** [preesistente; E] (E2E-6) — `StopAlarm.kt`: per un viaggio pianificato (o il ritorno, di default domani) scatta il giorno sbagliato o viene rifiutata come passata.
- **N-M8 · Ricerche pianificate pubblicate su widget e Wear come "prossime partenze"** [preesistente; S] (PU-2, RT-1, E2E-7) — `JourneyQueryState.kt`, `TrainViewModel.kt`, `RouteWidgetProvider.kt`.
- **N-M9 · Tratta attiva persa a ogni avvio a freddo** [preesistente; E] (E2E-8, PU-3) — dopo un ripristino o quando le stazioni escono dalla MRU di 96 voci di `CustomStationStore`, l'app si apre su "nessuna tratta".
- **N-M10 · I backup non contengono le stazioni a testo libero** [preesistente; S] (E2E-9) — tratte, preferiti e monitor che le usano spariscono al ripristino; se sono tutte libere, l'intero backup viene rifiutato.
- **N-M11 · Archivio European Sleeper corrotto o troncato → crash in loop all'avvio** [preesistente; E] (TB-2, AND-NEW-4; residuo di R4:M-2) — `RemoteGtfsFallbackDataSource.loadRoute` non intercetta `ZipException`/`EOFException`.
- **N-M12 · Orizzonte di ricerca fermo a mezzanotte** [preesistente; E] (RT-3) — di sera "nessun treno" anche se esistono partenze 00:02/00:05; il worker del widget va in backoff.
- **N-M13 · Treni TILO elencati due volte** [preesistente; E] (VT-01) — la deduplica fra pack confronta numeri che Trenord e OTD scrivono in modo diverso; il test passa perché usa numeri identici in entrambi i pack.
- **N-M14 · Messaggi d'errore specifici sostituiti da messaggi generici** [PR#34; S] (LOC-5, E2E-5) — "pack scaduto, aggiorna l'app" (correzione di R2:A1), "tratta non coperta", "nessun treno dopo le HH:mm" e l'avviso del fallback comunitario non arrivano più all'utente.
- **N-M15 · Dettatura vocale rotta in en/de/fr/es** [PR#34; E] (LOC-2, E2E-2) — il riconoscitore segue la lingua del dispositivo ma `PlannerVoiceParser` capisce solo l'italiano: numero treno mai riconosciuto, data/ora/ritorno persi in silenzio.

**Wear e Companion**
- **N-M16 · La Tile Wear del Companion non si aggiorna mai** [preesistente; S] (RD-5, CL-3, WC-N2, FSK-M7) — nessun `requestUpdate` alla ricezione, nessuna freshness né validità: gemello della M-5 corretta solo sul Wear principale.
- **N-M17 · Localizzazione del Companion inattiva sui dispositivi** [PR#34; E in emulazione] (LOC-1) — `resolveCompanionLocale` usa `Intl.Locale`, assente in Hermes (RN 0.81): tutto resta in italiano. Provato togliendo `Intl.Locale` a Node, non su un runtime Hermes reale.
- **N-M18 · Firma release del Companion rotta** [PR#34; E] (LOC-3, WC-N1, NEW-COMP-1) — PR#34 ha tolto `with-binario-wear-manifest.cjs` da `app.config.ts`: `signingConfigs.release` non viene più generato (build firmata fallisce) e telefono/Wear debug hanno certificati diversi, quindi il Data Layer non li accoppia → **N-C2 riaperto a metà**.
- **N-M19 · Gate `verify:dependency-audit` del Companion di nuovo rosso** [preesistente; E] (F-CHK-01) — 6 advisory high `brace-expansion` nel tooling Expo; la CI Companion si ferma prima di test, prebuild e Gradle (anche se i runner tornassero).

**Alexa**
- **N-M20 · Route archiviate resuscitate dall'adozione del DB storico** [preesistente; E] (AD-2) — `listPersonalRoutes`/`listPersonalRoutesForAlexa` non filtrano `archivedAt`: Alexa legge ad alta voce tratte che l'utente aveva archiviato.
- **N-M21 · Batching tRPC illimitato** [preesistente; E] (AD-15) — una sola richiesta, contata una volta dal rate limiter, ha inserito 1.000 tratte; le letture batched amplificano fino a ~105 MB.
- **N-M22 · Cookie di sessione senza prefisso `__Host-`** [preesistente; E con Chromium reale] (FS-NA1-1) — un sito fratello sullo stesso dominio registrabile (es. `*.manus.space`, non nella PSL) può impiantare `app_session_id`: loop di login permanente o fissazione nell'account dell'attaccante. Severità condizionata all'hosting, oggi non deciso.

**iOS e Apple Watch [PR #32]**
- **N-M23 · `Package.swift` di BinarioKit senza `platforms`** (IOSGTFS-01) — con i deployment target di default di SwiftPM `@MainActor`/concorrenza non sono disponibili: package e app non archiviabili. [S]
- **N-M24 · Watch senza catalogo stringhe nel progetto integrato** (LOC-4, IOSGTFS-04, TW-1, MS-3) — `ios/project.yml` non include `../apple-watch/Resources`: la Watch UI mostra chiavi grezze (`watch.freshness.fresh`). [S]
- **N-M25 · Gate CI iOS byte-per-byte dipendente dalla versione di SQLite** (IOSGTFS-03, VB-2, F-CHK-02) — la rigenerazione è identica su Linux, ma il runner macOS usa un'altra SQLite: il job `ios` fallirebbe al primo passo funzionale. [E su Linux, S su macOS]
- **N-M26 · Copertura stazioni dell'indice iOS** (IOSGTFS-06, TW-3, VT-02) — corrispondenza per nome esatto e scarto delle omonimie: Merano, Ora, Laives, Chiusa, Napoli Porta Nolana, ~118 stazioni Ferrovienord (Varese Nord, Novara Nord, linea Brescia–Iseo–Edolo) mai pianificabili; il test del builder codifica lo scarto. [E]
- **N-M27 · Nessun controllo di validità dei pack su iOS** (IOSGTFS-05) — dal 13/12/2026 circa il 96,5 % delle corse sparisce e l'app dice "nessun servizio" senza spiegare la scadenza. [E]
- **N-M28 · Il Watch mostra treni già partiti o di altri giorni per 12 ore** (IOSGTFS-07, RT-2, PU-4, TW-7) — snapshot con soli HH:mm, TTL 12 h, nessuna rimozione dei partiti; una ricerca per sabato appare come "prossime corse" di oggi. [S]
- **N-M29 · WCSession attivata solo in `onAppear`** (MS-1) — snapshot e **revoche** arrivano solo con l'app Watch aperta: dopo aver disattivato la condivisione, complication e widget mostrano la tratta fino a 12 h. [S]
- **N-M30 · App iPhone senza launch screen né icona** (AI-NF-5) — modalità compatibilità letterbox e upload rifiutato. [S]

### Low e Info
Circa 140 Low e 8 Info nuovi, elencati per area nell'**Appendice A** con id, strato, verifica e file. Tra i più rilevanti:
- prebuild semplice del Companion non idempotente;
- asset SQLite iOS da 7,4 MB fuori da LFS;
- Hermes e `Intl` in altri punti del Companion;
- residui italiani hard-coded su widget e payload Wear;
- doppia costruzione degli indici di canonicalizzazione sul main thread (introdotta da PR#33);
- deadlock sul linking concorrente (CL-2);
- `start_date` del GTFS-RT European Sleeper ignorata (RT-4);
- divergenza Android/iOS sulle corse notturne il 25/10 (IOSGTFS-08). Non è stabilito quale lato abbia ragione: dipende da come i produttori codificano gli orari.

## 4. Riserve sulla verifica

- Il critico ha segnalato duplicati con severità diverse. Li ho uniti nei cluster di §3 tenendo la severità confermata dalla lente di impatto.
- Le forme di periodicità di N-M1 e la festività del 4 ottobre (Low RT-12/TW-17) poggiano su stringhe e leggi non verificabili offline.
- N-M17 è provato in emulazione, non su Hermes.
- N-M22 dipende dal dominio di produzione.
- Le Apple (N-H6, N-H7, N-M23–N-M30) sono statiche.
- Il critico ha verificato che un toolchain Swift Linux e XcodeGen sono ottenibili in questo ambiente: è la prima cosa da fare in un giro successivo, insieme a un avvio reale dell'app Android per N-H1.

## 5. Finding precedenti: stato

Su 245 finding:
- **85 risolti**: Appendice C, con l'evidenza.
- **125 ancora aperti o parziali**: Appendice B, con id originale, rapporto, severità, file attuale e residuo.
- **35 superati, decisioni del proprietario o rimossi per scelta**: Appendice D.

**High e Medium del 29/09 (R4)**:

| ID | Stato a `1d3cdee` | Nota |
|---|---|---|
| H-1 canonicalizzazione | **parziale** | avvio corretto con test sul catalogo reale; restano i gemelli N-M2–N-M5 e il costo all'avvio (Low RD-9) |
| H-2 Swift 6 | **risolto** | ma il grafo Apple non compila comunque per N-H6, N-H7, N-M23 |
| M-1 backup v0.16.3 | risolto | |
| M-2 orari GTFS vuoti | **parziale** | archivio corrotto ancora fatale (N-M11) |
| M-3 exact alarm | **ritirato a Low** | Manus ha ragione: a `a5b6808` le stringhe `stop_alarm_inexact` e `commuter_alert_scheduled_inexact` già avvisano l'utente. Resta solo l'assenza di un collegamento per concedere il permesso |
| M-4 stato Agenda | risolto | preview e bozza persi solo alla morte del processo (Low) |
| M-5 Tile Wear | risolto | gemello Companion aperto (N-M16) |
| M-6 apertura checklist | risolto | funziona solo con permesso notifiche già concesso (Low) |
| M-7 widget Companion | **parziale, contestato** | aggiornamento ogni ≥15 min; il conto alla rovescia localizzato in produzione non è testato (i test colpiscono una classe non usata) |
| M-8 marker permessi | risolto | permessi del manifest fuso/AAB ancora non verificati in CI |
| M-9 archivio corrotto | risolto | modalità sola lettura senza UX: falsi "eliminato/salvato" (Low) |
| M-10 nome vuoto | risolto | |
| M-11 `vite` in produzione | risolto | verificato con installazione production-only |
| M-12 SSML | **parziale** | caratteri di controllo e surrogati spaiati producono ancora SSML non valido |
| M-13 trigger CI | **parziale** | `stabilization/**` ammesso; `main` senza workflow; nessun runner |
| M-14 European Sleeper | **parziale** | `PRIVACY.md` ora nomina l'host; README contraddice ancora il codice; richieste periodiche dal widget non descritte |
| M-15 audit Companion | **parziale** | `image-size` risolto, gate di nuovo rosso per `brace-expansion` (N-M19) |
| M-16 SAB E FEST | risolto | ma la riscrittura ha introdotto N-M1 |

**Correzioni più vecchie contestate dallo scettico**:
- **N-D5** (R3): il sintomo è sparito, ma la correzione ha introdotto **N-H1**, il crash all'avvio.
- **N-D4** (R3): riaperto da `179d2b4` (N-M1).
- **N-C2** (R3): applicationId allineato, firma comune persa con PR#34 (N-M18).
- **N-C3** (R3): i test Kotlin del Companion compilano e passano se eseguiti (lo scettico li ha eseguiti), ma in CI non vengono mai raggiunti perché il gate di audit fallisce prima.
- **M-7** (R4): vedi tabella.

**Riaperto**: R1:F8 (widget senza `NSExtension`). `INFOPLIST_KEY_NSExtension_NSExtensionPointIdentifier` non è un build setting riconosciuto: l'appex generato non ha il dizionario `NSExtension` e non è installabile. Il test `tests/test_verify_apple_watch_project.py:37-44` blinda l'impostazione inefficace.

**Aperti di lunga data** (decisioni o debiti):
- LICENSE assente;
- `main` fermo a v0.16.3 e nessun tag;
- canale Wear/Play;
- provider OAuth;
- 7 pack GTFS in scadenza a dicembre;
- adapter morti;
- ~90 Low di R3/R4.

## 6. Lista unica dei bug da risolvere (backlog)

Ordine: severità, poi impatto sulla funzione principale. "Rif." indica gli id di origine.

| # | Sev. | Strato | Bug | Rif. |
|---|---|---|---|---|
| 1 | High | preesistente | App Android crasha a ogni avvio (costruttore `TrainViewModel`) | N-H1 |
| 2 | High | PR#34 | `Accept-Language: constructor` termina il server Alexa | N-H2 |
| 3 | High | preesistente | Errori negli handler OAuth `async` terminano il server Alexa | N-H3, CL-2 |
| 4 | High | preesistente | Chiavi duplicate per corse notturne: crash dopo mezzanotte | N-H4 |
| 5 | High | preesistente | Companion: perdita silenziosa di viaggi al 41° salvataggio | N-H5 |
| 6 | High | preesistente | Widget Apple Watch non compila | N-H6 |
| 7 | High | PR#32 | Bundle id Watch non prefissato | N-H7 |
| 8 | Medium | preesistente | Gemelli H-1: destinazione per sottostringa, scelta ri-risolta, hub RFI, pack per nome | R4:H-1, N-M2–N-M5 |
| 9 | Medium | PR#33 | Periodicità RFI (negazioni, intervalli, "festiva") | N-M1, R3:N-D4 |
| 10 | Medium | preesistente | Archivio European Sleeper corrotto: crash in loop | N-M11, R4:M-2 |
| 11 | Medium | PR#34 | Firma Companion e accoppiamento telefono/Wear | N-M18, R3:N-C2 |
| 12 | Medium | preesistente | Gate audit Companion rosso (`brace-expansion`) | N-M19, R4:M-15 |
| 13 | Medium | preesistente | Ricerche pianificate pubblicate come attuali su widget/Wear | N-M8 |
| 14 | Medium | preesistente | Sveglia di fermata nel giorno sbagliato | N-M7 |
| 15 | Medium | preesistente | Tratta attiva persa all'avvio; backup senza stazioni libere | N-M9, N-M10 |
| 16 | Medium | preesistente | Orizzonte fermo a mezzanotte; fermate senza salita; TILO doppi | N-M12, N-M6, N-M13 |
| 17 | Medium | PR#34 | Messaggi specifici persi; dettatura solo italiana; Companion non localizzato su Hermes | N-M14, N-M15, N-M17 |
| 18 | Medium | preesistente | Tile Wear del Companion mai aggiornata; widget Companion | N-M16, R4:M-7 |
| 19 | Medium | preesistente | Alexa: route archiviate, batching illimitato, cookie senza `__Host-`, SSML residuo | N-M20–N-M22, R4:M-12 |
| 20 | Medium | PR#32 | Grafo Apple non compilabile/installabile: `platforms`, `NSExtension`, catalogo stringhe Watch, icona e launch screen, gate SQLite | N-M23–N-M25, N-M30, R1:F8 |
| 21 | Medium | PR#32 | iOS: copertura stazioni, validità pack, Watch con treni partiti, WCSession solo in primo piano | N-M26–N-M29 |
| 22 | Medium | — | CI: billing GitHub da sistemare, `main` senza workflow, Dependabot inerte | R3:N-CI, R4:M-13 |
| 23 | Medium | preesistente | Privacy: README in contrasto con il fallback; richieste del widget non descritte | R4:M-14 |
| 24 | Medium | preesistente | Altri Medium ancora aperti dei rapporti del 23/09 | R2:B3, R2:C6, R2:A1, R2:C3, R3:N-W1, R3:N-C3 (Appendice B) |
| 25 | Low | vari | ~140 Low nuovi (Appendice A) e ~96 Low/Info precedenti ancora aperti (Appendice B) | — |
| 26 | — | proprietario | LICENSE, `main`/tag, Wear/Play, OAuth, privacy pubblica, iOS, retention | R1–R4 |

## 7. Confronto con i documenti di Manus

Letti solo dopo l'analisi: `docs/CONTROVERIFICA_AUDIT_CLAUDE_2026-09-29.md` e la versione aggiornata di `docs/STATO_STABILIZZAZIONE_2026-09-23.md`.

**Dove Manus ha ragione**:
- **M-3**: avevo scritto "degrada in silenzio", ma il messaggio all'utente esisteva già. Lo ritiro a Low.
- **Bootstrap di H-1**: avevo scritto che all'avvio la tratta sbagliata veniva persistita subito; muta lo stato in memoria e viene persistita ai salvataggi successivi. La correzione è giusta.
- **Severità companion**: le obiezioni su M-6, M-7, M-8, M-13 e M-14 sono ragionevoli; riletti a freddo, Low o Medium basso sono difendibili. In questo rapporto la severità dei nuovi finding segue la lente di impatto indipendente.
- **Remediation**: per H-1 (avvio), M-1, M-4, M-5, M-9, M-10, M-11 e M-16 la regressione la conferma nel codice.

**Dichiarazioni smentite o da ridimensionare**:
1. "Android principale: 330 test… lint e assemble verdi" → vero, ma l'app **non si avvia** (N-H1). Il difetto viene da una correzione del 23/09 ed è sopravvissuto a tutti i gate locali perché nessuno ha lanciato `MainActivity`.
2. "M-15 chiuso… audit senza high/critical" → al 30/09 il gate è di nuovo rosso per `brace-expansion` (N-M19). Non è un errore di Manus al momento della scrittura, ma il gate va mantenuto.
3. "Localizzazione: la lingua segue le preferenze del dispositivo… cataloghi e placeholder hanno test di parità" → la parità c'è (verificata), ma:
   - il Companion su Hermes resta in italiano (N-M17);
   - la dettatura Android non funziona fuori dall'italiano (N-M15);
   - la Watch UI integrata mostra chiavi grezze (N-M24);
   - un header di lingua termina il server Alexa (N-H2);
   - la localizzazione ha tolto alla firma del Companion il suo plugin (N-M18).
4. "M-16 chiuso difensivamente" → la riscrittura riapre N-D4 e inverte i giorni per le forme negative (N-M1).
5. "M-6/M-7/M-8 chiusi come hardening" → M-7 è parziale. M-8 corregge i marker, ma il manifest fuso e l'AAB restano non verificati.
6. "Apple Watch / iOS verde nei gate eseguibili localmente… parse di tutti i sorgenti" → il parse non è una compilazione: il widget non compila (N-H6), il bundle id è respinto (N-H7), `Package.swift` non ha `platforms` (N-M23), `NSExtension` non viene generato (R1:F8).
7. "Il pattern è coerente con un problema di startup/infrastruttura/quota" (CI) → la causa è nota: **pagamenti dell'account falliti o limite di spesa**. È una decisione del proprietario, non un'ipotesi.

## 8. Lacune residue dell'audit

Il critico ha proposto cinque aree per un giro aggiuntivo, non completato per i riavvii del container:
- **Esecuzione reale Swift e XcodeGen**: il toolchain Linux e XcodeGen sono ottenibili qui; servirebbero a confermare circa 20 finding Apple oggi statici.
- **CI reale e test Kotlin JVM**: la causa billing è già individuata.
- **Parser di rete Android**: GTFS-RT European Sleeper con corpo illimitato e protobuf-java 3.16.1.
- **UI del Companion mai eseguita**: nessun audit di accessibilità.
- **Documenti normativi**: attribuzioni, licenze dei dati, provenienza del catalogo.

A queste si aggiunge il test più semplice e più importante, che non è eseguibile in questo container: **avviare l'app Android su un emulatore o un telefono**.

## Appendice A — Finding nuovi Low e Info (non raggruppati in §3)

Titoli nella formulazione originale dei finder (inglese). Strato: preesistente = presente a `a5b6808` o prima. Verifica: E eseguita, S statica, N non eseguibile.

**Android**

- `RD-9` [Low, PR#33, E] Station canonicalizer indexes are built twice on the Main thread at catalog load, roughly doubling startup main-thread index work — `app/TrainViewModel.kt`
- `RD-10` [Low, pre-existing, S] Saved routes, recents and favourites referencing a custom station that the catalog canonicalizes are dropped and later deleted permanently — `app/TrainViewModel.kt`
- `LOC-11` [Low, PR#34, S] Residual hard-coded Italian in 'localized' Android surfaces: new provider fallback unreachable ('Nessun provider'), update time 'aggiornato alle', home-screen widget labels, Wear payload status/notice — `app/TrainViewModel.kt`
- `LOC-14` [Low, PR#34, S] Localization side effects and drift: quality score/band dropped from the phone UI (dead currentQualityBand/Score), stale docs and dead helpers, translation-quality slips — `app/TrainViewModel.kt`
- `E2E-11` [Low, pre-existing, S] Backup import replaces all routes, favourites, monitors, alerts and preferences immediately, without preview, confirmation or undo — `app/MainActivity.kt`
- `E2E-12` [Low, PR#34, S] Localization residuals after PR#34: widget, watch payload, update-time and provider fallback, voice feedback remain Italian; unconfigured route label became ' -> ' in commuter alerts — `app/widget/RouteWidgetProvider.kt`
- `E2E-13` [Low, pre-existing, S] Pinning/unpinning any saved route while a searched (ad-hoc) or widget route is active switches the app to 'no route selected' while still showing the old departures — `app/TrainViewModel.kt`
- `E2E-14` [Low, pre-existing, S] Widget refresh is gated on network even for routes served entirely by embedded offline GTFS: new widgets and direction toggles stay 'data unavailable' offline — `app/widget/RouteWidgetProvider.kt`
- `E2E-15` [Low, pre-existing, S] Stop-alarm confirmation persists for the whole session and is shown on every other train's detail screen — `app/MainActivity.kt`
- `E2E-16` [Low, pre-existing, S] Creating a route when 32 routes exist silently deletes the last existing route — `app/data/SavedRouteOrdering.kt`
- `TB-3` [Low, pre-existing, E] Android: crafted planned-journey JSON (deep nesting, <=256 KiB) crashes the app via StackOverflowError that escapes decode; unit tests cannot see it — `app/plannedjourney/PlannedJourneyTransfer.kt`
- `TB-4` [Low, PR#33, E] Android: v0.16.3 backups import the preset route 'default-roma-torino' as a success, which RouteStore then silently drops on the next launch — `app/LocalDataBackup.kt`
- `TB-6` [Low, pre-existing, S] Android: exported MainActivity trusts EXTRA_APPWIDGET_ID from any caller and persists the corresponding route as active — `app/MainActivity.kt`
- `TB-9` [Low, pre-existing, E] Android: European Sleeper community-mirror fallback is not scoped to European Sleeper services; mirror content can populate arbitrary domestic routes — `app/data/gtfs/RemoteGtfsFallbackDataSource.kt`
- `PU-6` [Low, PR#34, S] PR#34 localization leaves persisted/transported data strings Italian: widget meta, failure text, direction/target labels and TalkBack text; Wear status/category/notice; unreachable localized provider… — `app/widget/RouteWidgetProvider.kt`
- `PU-7` [Low, pre-existing, S] Android: opening the app from a widget persists a synthetic active-route id ('route:<o>:<d>') that RouteStore can never resolve; next cold start opens another route and pinning resets to no route — `app/widget/WidgetConfigureActivity.kt`
- `PU-8` [Low, pre-existing, S] Android: saved-route (32) and commuter-alert (24) caps silently evict an existing record on create; evicted alert's alarm stays armed but fires into nothing — `app/data/SavedRouteOrdering.kt`
- `RT-5` [Low, pre-existing, E] Stop alarm ignores the service date: set from a future-date service it fires today at the same clock time; after-midnight stops cannot be set before 18:00 — `app/StopAlarm.kt`
- `RT-9` [Low, pre-existing, E] Android voice planner mis-dates and mis-times dictation: 'dopodomani'→tomorrow, stations containing 'oggi' (Foggia, Poggibonsi) force today, 'HH:MM' minutes dropped, 'di sera'/'stasera' ignored — `app/PlannerVoiceParser.kt`
- `RT-12` [Low, pre-existing, E] National holiday of 4 October (restored from 2026) missing from the RFI holiday calendar — `app/data/planned/ItalianRailServiceCalendar.kt`
- `VT-06` [Low, PR#33, S] H-1 (station identity) regression test covers only StationCanonicalizer; reverting the TrainViewModel call-site fix leaves every test green — `app/data/StationCanonicalizerTest.kt`
- `VT-11` [Low, PR#33, S] PR#33 test pins a fabricated '00:00' stop time for a malformed departure even though the stop's arrival is valid — `app/data/gtfs/RemoteGtfsFallbackDataSourceTest.kt`
- `VT-12` [Low, PR#34, S] Localization tests certify only strings.xml; widget metadata/TalkBack text and the Android->Wear payload stay Italian in all locales, and tests pin the Italian literals — `app/widget/RouteWidgetProvider.kt`
- `VT-13` [Low, pre-existing, S] Android instrumented Compose accessibility tests are never compiled or run by CI and assert Italian strings without pinning a locale — `app/MainScreenAccessibilityTest.kt`
- `TW-10` [Low, pre-existing, E] Android uses the raw GTFS trip_id as train number when trip_short_name is absent (SNCF, Trentino, TFT, ARST): 100+ character ids on phone/widget and a meaningless 24-char prefix on the watch; iOS fal… — `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt`
- `TW-11` [Low, pre-existing, E] Android planned search never looks at the next service day: after the last train of the day the app/widget/Wear report 'no trains' while iOS shows the next morning's departures — `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt`
- `TW-12` [Low, PR#32, E] DST 2026-10-25: Android (naive midnight) and iOS (GTFS noon-12h) display 147 early-morning departures one hour apart (RE_2 2247 Milano Centrale: Android 00:05, iOS 01:05) — `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt`
- `TW-16` [Low, pre-existing, E] Android station autocomplete is unranked (first N substring matches in catalog order): query 'Roma' never shows 'Roma'/'Roma Termini' among the 5 visible suggestions, unlike iOS and Companion — `app/data/StationAutocompleteEngine.kt`
- `TW-17` [Low, pre-existing, E] Italian rail holiday calendar lacks 4 October, a national holiday since 2026 (Law 151/2025): from 2027-10-04 (Monday) RFI 'FERIALE' trains are assumed running and 'FESTIVO' ones not — `app/data/planned/ItalianRailServiceCalendar.kt`
- `TW-18` [Low, pre-existing, E] Android -> Alexa profile contract drift: monitored train numbers produced by Android (Trenord 'RE_2 - 2247', SNCF trip ids) violate the server's ^[A-Za-z0-9-]{1,32}$ and one invalid entry rejects the… — `app/AlexaCompanionProfile.kt`
- `MS-6` [Low, pre-existing, S] Native launcher shortcuts use implicit intents on an unverified custom scheme (no targetPackage/targetClass), so other apps can intercept them — `app/src/main/res/xml/shortcuts.xml`
- `MS-7` [Low, pre-existing, S] Exported entry points cause state changes without caller validation: MainActivity trusts a caller-supplied EXTRA_APPWIDGET_ID, and the companion Wear exports an unreachable checklist activity that ma… — `app/MainActivity.kt`
- `F-CHK-07` [Low, pre-existing, E] Manifest lastServiceDate overstates effective coverage for eav-gtfs (and hzpp by one day); the iOS index copies the overstated value — `app/src/main/assets/gtfs/manifest.json`
- `AND-NEW-1` [Low, pre-existing, E] Picking a station by name still resolves to the first catalog match: 122 stations cannot be selected; some map silently to another town and are saved that way — `app/data/StationAutocompleteEngine.kt`
- `AND-NEW-5` [Low, PR#34, S] PR#34 removed data-layer notices and failure reasons from the phone UI: the fallback caveat, conflict warning, quality band/score and the GTFS-expiry 'update the app' message are no longer shown — `app/TrainViewModel.kt`
- `AND-NEW-6` [Low, PR#34, S] Android localization is incomplete after PR#34: the widget, update-time label, voice feedback and provider default still render Italian in en/de/fr/es — `app/widget/RouteWidgetProvider.kt`
- `AND-NEW-7` [Low, PR#33, S] The H-1 fix builds two StationCanonicalizers (about 16k normalizations) on the Main dispatcher at startup, on top of the 19.8k-value autocomplete index — `app/TrainViewModel.kt`
- `N-A1-ANDROID-UI-REGRESSION` [Low, PR#34, S] PR#34 drops the actionable expired-timetable message from the Android app screen (R2:A1 in-app warning regressed) — `app/TrainViewModel.kt`
- `FSK-ND4-02` [Low, pre-existing, S] Italian holiday calendar lacks 4 October (restored national holiday from 2026): FESTIVO/FERIALE wrong on Mon 2027-10-04 — `app/data/planned/ItalianRailServiceCalendar.kt`
- `FS-1` [Low, pre-existing, E] Android loads 1,809 railway codes and 2,193 Trenitalia ids of the curated catalog as the literal string "null" (org.json optString on JSON null); JVM tests use a different org.json and cannot see it — `app/data/catalog/EmbeddedCuratedRailStationCatalog.kt`
- `FS-2` [Low, pre-existing, E] M-2 guard boundary: European Sleeper realtime enrichment can still throw out of loadRoute after the new per-update try/catch (ArithmeticException in enrichService), and the try/catch itself is untest… — `app/data/gtfs/RemoteGtfsFallbackDataSource.kt`
- `FS-3` [Low, pre-existing, E] Backup import decodes against the stations the ViewModel has at that moment: after process death or activity recreation during the document picker, a valid v1/v2 backup is rejected (the original M-1 … — `app/MainActivity.kt`
- `FSKB-1` [Low, PR#34, S] PR#34 removed the 'pending export missing' feedback, so a second Agenda export result leaves a 0-byte file silently — `app/MainActivity.kt`
- `FSKB-2` [Low, PR#33, E] The M-16 fix (179d2b4) changes RFI periodicity semantics beyond SAB+FEST: 'festività/festive/prefestivi' forms and full-word negations regress, and 'Sabati e festivi' still drops Saturday — `app/data/planned/ItalianRailServiceCalendar.kt`

**Wear**

- `RD-11` [Low, pre-existing, S] Root Wear complication has no validity range, so it can show an expired departure after process death (the M-5 fix covered only the tile) — `wear/complication/BinarioComplicationService.kt`
- `RD-13` [Low, PR#34, S] PR#34 made the PR#33 tile regression test exercise a test-only layout instead of the production one — `wear/tile/BinarioTileService.kt`
- `TW-8` [Low, PR#34, E] Android -> Wear OS payload carries hard-coded Italian labels (status, category, operator enum label, notices, fallbacks) that the localized Wear UI renders verbatim: mixed-language watch in en/de/fr/… — `app/wear/AndroidWearSnapshotContract.kt`
- `TW-13` [Low, pre-existing, E] Android Wear encoder emits payloads its own Wear decoder rejects when provenanceObservedAt is more than 60 s after generatedAt (remote GTFS-RT header timestamp ahead of the phone clock) — `app/wear/AndroidWearSnapshotContract.kt`
- `TW-15` [Low, pre-existing, E] UNAVAILABLE coherence rule enforced differently: Wear OS root decoder and Apple Watch accept operationalState=UNAVAILABLE with provenanceDataNature PROGRAMMED/OPERATIONAL/UNKNOWN, while TS, Companion… — `wear/model/WearSnapshot.kt`
- `WC-N6` [Low, PR#34, S] Android→Wear payload carries Italian-only display strings, rendered verbatim by the now-localized Wear app and Tile — `app/wear/AndroidWearSnapshotContract.kt`
- `FSK-WEAR-ACTIVITY-UPTIME` [Low, pre-existing, E] The Wear app screen can show an expired snapshot as fresh after deep sleep. TTL revocation runs in-process on an uptime timer (Handler.postDelayed), and WearMainActivity never re-validates on resume. — `wear/sync/WearSnapshotStore.kt`
- `VT-15` [Info, PR#33, S] Wear tile timeline test compares the test-only fallback layout with itself; the production tile layout is never exercised — `wear/tile/BinarioTileTimelineTest.kt`
- `VT-16` [Info, pre-existing, S] Wear codec 'payload oltre budget' test cannot fail if the 8 KB budget check is removed — `wear/model/WearSnapshotCodecTest.kt`

**Companion**

- `RD-6` [Low, PR#33, E] Corrupt-archive protection: one bad record makes the whole archive silently read-only; UI reports false success and 'delete' cancels the reminder but keeps the trip — `companion-expo/lib/local-routes.ts`
- `LOC-6` [Low, PR#34, E] Companion dictation grammars misparse common localized time/date forms: English am/pm ignored, French '8h30' and German '8.30 Uhr' lose minutes, Spanish 'de la mañana' sets tomorrow — `companion-expo/lib/local-dictation.ts`
- `LOC-8` [Low, PR#34, S] Companion native 'binario_reminder' trailing space is trimmed by aapt2 in en/de/fr/es (only Italian is quoted): widget shows 'Reminder 15 min ·Scheduled time…' — `companion-expo/plugins/with-binario-android-widget.cjs`
- `LOC-9` [Low, PR#34, E] Companion Wear voice: German saved-journey keywords 'meine Fahrt'/'meine Reise' can never match (keywords not lowercased, transcript lowercased) — `companion/wear/BinarioVoiceRequestActivity.kt`
- `LOC-10` [Low, pre-existing, E] Companion Wear voice (Italian): realtime keyword 'binari' matches the product name 'Binario', so any request mentioning Binario is routed to the always-unavailable realtime stub — `companion/wear/BinarioVoiceRequestActivity.kt`
- `TB-5` [Low, pre-existing, E] Companion ICS import ignores the UTC 'Z' designator and RFC 5545 line folding: Binario/Android calendar exports are imported 1-2 hours off — `companion-expo/lib/local-calendar.ts`
- `RT-7` [Low, pre-existing, E] Companion dictation maps every evening time 20:00-23:59 to 02:00 in all five locales; English 'pm' ignored — `companion-expo/lib/local-dictation.ts`
- `RT-8` [Low, pre-existing, E] Companion ICS import ignores 'Z' and TZID: UTC calendar times (including Binario Android's own export) are imported as local wall-clock, shifted 1-2 h — `companion-expo/lib/local-calendar.ts`
- `RT-11` [Low, pre-existing, E] Companion interprets Italian timetable times in the device time zone (reminders, countdown, widget, Wear expiry, ICS export) — `companion-expo/lib/planned-reminder-service.ts`
- `VT-03` [Low, pre-existing, E] Companion dependency-audit gate is fail-open on audit/registry errors; its only test checks a one-line predicate; the real gate is red at HEAD — `companion-expo/scripts/verify-dependency-audit.mjs`
- `VT-04` [Low, pre-existing, E] Companion Vitest suite only passes when the process time zone is UTC and cannot detect local-vs-UTC time bugs (CI runs in UTC) — `companion-expo/tests/companion-wear-contract.test.ts`
- `VT-05` [Low, PR#34, S] After PR#34 the Glance widget countdown tests target a dead Italian duplicate; the localized production countdown is untested and a grep test keeps the dead code alive — `companion/binario/BinarioStatusWidget.kt`
- `TW-14` [Low, pre-existing, E] Wear contract 'normalised text' is defined differently by each implementation; concrete break: a Companion route name ending in U+001C-U+001F passes the TS validator but is rejected by the Companion'… — `companion-expo/lib/companion-wear-contract.ts`
- `MS-5` [Low, pre-existing, E] Companion 'Cancella archivio locale' leaves commute data behind: journey templates, the deleted journey on the home-screen widget and watch, and full export/import copies in the cache — `companion-expo/app/(tabs)/settings.tsx`
- `MS-4` [Low, pre-existing, S] Companion Expo does not disable device-to-device transfer (no dataExtractionRules), although PRIVACY.md says it does and the release gate checks only allowBackup — `companion-expo/android/app/src/main/AndroidManifest.xml`
- `F-CHK-03` [Low, PR#34, E] Plain `expo prebuild` / `pnpm prebuild` is not idempotent: expo-localization appends a duplicate resourceConfigurations line on every run — `companion-expo/app.config.ts`
- `F-CHK-04` [Low, pre-existing, E] `pnpm prebuild` depends on undeclared Python package Pillow — `companion-expo/scripts/generate-binario-brand-assets.py`
- `NEW-COMP-2` [Low, pre-existing, E] Companion dependency-audit gate fails at HEAD on brace-expansion high advisories published 2026-09-29, blocking the whole Companion CI job — `companion-expo/pnpm-lock.yaml`
- `NEW-COMP-4` [Low, PR#33, E] Read-only archive mode introduced by the M-9 fix produces false-success UI (delete, template save/delete) and misleading errors on add — `companion-expo/app/(tabs)/alerts.tsx`
- `NEW-COMP-5` [Low, PR#34, S] Widget countdown tests exercise a dead Italian-only twin; the production localized countdown is untested — `companion/binario/BinarioStatusWidget.kt`
- `NEW-COMP-6` [Low, PR#34, S] Translated widget reminder prefix loses its trailing separator space (unquoted Android string resources) — `companion-expo/android/app/src/main/res/values-en/strings.xml`
- `FS-1` [Low, PR#34, S] Companion phone debug build fails on a fresh clone: PR#34 restored the Expo template's repository-local debug.keystore reference, but *.keystore is git-ignored and Expo never recreates it while andro… — `companion-expo/android/app/build.gradle`
- `FS-2` [Low, pre-existing, E] Companion Kotlin v1 contract tests (the ones N-C3 wired into CI) cannot detect removal of about half the decoder rules on either Kotlin twin — `companion-expo/plugins/templates/CompanionWearPayloadV1Test.kt`
- `FS-3` [Low, PR#33, E] M-6 regression guard is textual: re-introducing the original watch false success (show 'Checklist aperta sul telefono' as soon as the request is sent) keeps all 25 native-surface contract tests green — `companion-expo/tests/native-surface-contract.test.ts`
- `FSK-M7-GLANCE-SESSION` [Low, pre-existing, S] Companion widget: updateAll issued while a Glance session is alive is a no-op for this widget, so the claimed immediate refresh after a JS sync can be lost until the next worker tick — `companion/binario/BinarioStatusWidget.kt`
- `FSK-I18N-REMINDER-SPACE` [Low, PR#34, S] Companion widget reminder line loses its separator space in en/de/fr/es because aapt2 trims the unquoted trailing space — `companion-expo/plugins/with-binario-android-widget.cjs`
- `LOC-7` [Info, PR#34, E] Companion error feedback announced with polite live region in en/de/es/fr: reminderFeedbackPoliteness classifies localized messages by Italian keywords — `companion-expo/lib/reminder-accessibility.ts`
- `MS-8` [Info, pre-existing, S] Companion ships an unused, unconfigured Firebase Cloud Messaging, Installations and Datatransport stack through expo-notifications, which is not mentioned in the privacy or permission inventory — `companion-expo/package.json`

**Alexa**

- `RD-7` [Low, PR#34, E] Alexa Simple cards display XML entities (&apos; &amp;) because PR#34 reuses the SSML-escaped speech string as card text — `alexa-companion/server/voice/alexa.ts`
- `RD-8` [Low, PR#33, E] SSML escaping is incomplete: XML-illegal control characters pass through and ask-sdk's pre-unescape bug turns "&apos;]" into "undefined" — `alexa-companion/server/voice/speech.ts`
- `LOC-12` [Low, PR#34, E] Alexa Simple cards added by PR#34 contain SSML-escaped entities ('Casa &amp; lavoro', 'L&apos;Aquila') shown literally in the Alexa app — `alexa-companion/server/voice/alexa.ts`
- `LOC-13` [Low, PR#34, E] Alexa example phrases in en-US/en-GB/de-DE/fr-FR/es-ES reference utterances absent from the interaction models (6 of 15 non-Italian phrases) — `alexa-companion/skill-package/skill.json`
- `TB-7` [Low, pre-existing, E] Alexa companion Android deep-link endpoints emit URIs that the Android parser rejects (documented contract dead) — `alexa-companion/server/voice/deepLinks.ts`
- `TB-8` [Low, PR#33, E] Alexa: SSML-escaped speech reused as card content, so the Alexa app card shows '&amp;', '&apos;' literally — `alexa-companion/server/voice/alexa.ts`
- `PU-5` [Low, pre-existing, E] Alexa migrations depend on server defaults: nullable TIMESTAMPs lack explicit NULL and tables lack a charset, so on MariaDB <=10.9/MySQL 5.7 linking is always refused and on latin1 servers non-cp1252… — `alexa-companion/drizzle/0001_shallow_shadow_king.sql`
- `PU-9` [Low, pre-existing, E] Alexa migration 0013 is not rollback-safe: familyId NOT NULL without default breaks every token insert of the previous server build — `alexa-companion/drizzle/0013_complete_shiva.sql`
- `CL-5` [Low, pre-existing, E] Drizzle migrations create all tables with no explicit CHARACTER SET; on a latin1-default server 4-byte UTF-8 content is rejected (and crashes via CL-1) — `alexa-companion/drizzle/0000_flimsy_raza.sql`
- `VT-08` [Low, PR#34, E] Alexa cards added by PR#34 show XML-escaped route names and train numbers (&amp;, &lt;); SSML tests inspect only outputSpeech and their negative assertion can never fail — `alexa-companion/server/voice/alexa.ts`
- `VT-09` [Low, pre-existing, E] CSRF regression test sends a form content type together with a cross-site Origin/Sec-Fetch-Site, so either guard can be deleted with all tests green — `alexa-companion/server/_core/app.test.ts`
- `AD-3` [Low, PR#34, E] Simple cards added by PR#34 show SSML-escaped entities (&amp;, &apos;) and newly persist route names in the Alexa card history — `alexa-companion/server/voice/alexa.ts`
- `AD-4` [Low, pre-existing, E] SSML escaping still incomplete: route names accept XML-invalid code points (U+0000-U+0008, U+000E-U+001F, U+FFFE/U+FFFF) that make the whole SSML response ill-formed — `alexa-companion/server/routes/contract.ts`
- `AD-5` [Low, pre-existing, E] Dashboard login logs openId, full name and e-mail in clear on any DB error (DrizzleQueryError.message with params logged twice) — `alexa-companion/server/db.ts`
- `AD-6` [Low, pre-existing, E] Migrations never pin utf8mb4: on a latin1-default server users with non-cp1252 names cannot log in and emoji route names fail (and AD-1 becomes user-triggerable) — `alexa-companion/drizzle/0000_flimsy_raza.sql`
- `AD-7` [Low, PR#34, E] Localized example phrases (PR#34) do not match any sample utterance in en-US, en-GB, de-DE, fr-FR and es-ES — `alexa-companion/skill-package/skill.json`
- `AD-8` [Low, pre-existing, E] tRPC CSRF gate compares Origin with req.protocol + raw Host: behind a proxy that does not forward X-Forwarded-Proto or rewrites Host, every dashboard mutation fails with 415 — `alexa-companion/server/_core/app.ts`
- `AD-9` [Low, pre-existing, E] After logout or account deletion the dashboard auto-navigates to the OAuth portal on the next window focus; with a live IdP session the erased account is silently re-provisioned — `alexa-companion/client/src/main.tsx`
- `AD-10` [Low, pre-existing, E] Companion deep-link bridge emits URIs the Android allowlist parser rejects (/app/next-departures?route=..., non-alphanumeric or >12-char train numbers) — `alexa-companion/server/voice/deepLinks.ts`
- `AD-11` [Low, pre-existing, E] Dashboard login config is baked at build time while the server validates VITE_OAUTH_PORTAL_URL at runtime (never used server-side): a build without VITE_* yields new URL('undefined/app-auth') — `alexa-companion/client/src/const.ts`
- `AD-12` [Low, pre-existing, E] Stale alexaUserHash survives re-link: another Binario user's verification revokes a legitimately re-linked account — `alexa-companion/server/db.ts`
- `AD-13` [Low, pre-existing, S] Session cookie app_session_id is not __Host- prefixed although the code treats sibling-subdomain cookie tossing as a threat — `alexa-companion/shared/const.ts`
- `AD-14` [Low, pre-existing, E] skill.json privacy/terms/icon URLs point to paths the companion never serves (SPA catch-all returns index.html 200) — `alexa-companion/skill-package/skill.json`
- `AD-17` [Low, pre-existing, S] PRIVACY.md says deletion is confirmed 'con la parola ELIMINA' but the UI is a one-click dialog that auto-sends the constant; PR#34 now shows this implementation detail to users in 5 languages — `PRIVACY.md`
- `F-CHK-05` [Low, PR#34, S] Alexa skill example phrases in 5 new locales are not sample utterances (certification validation failure) — `alexa-companion/skill-package/skill.json`
- `F-CHK-09` [Low, pre-existing, E] Template boilerplate comment 'BLOCK TO BE DELETED' ships in the production dashboard HTML — `alexa-companion/client/index.html`
- `F-CHK-10` [Low, PR#34, S] localizeHtmlLang() is dead code; server-served dashboard HTML always declares lang="it" until JS runs — `alexa-companion/shared/i18n.ts`
- `ALEXA-REG-NF1` [Low, PR#34, E] Alexa Simple cards show XML-escaped entities because the card reuses the SSML-escaped speech string — `alexa-companion/server/voice/alexa.ts`
- `ALEXA-REG-NF2` [Low, pre-existing, E] XML-invalid code points in route names still yield non-well-formed SSML (incomplete M-12 escaping) — `alexa-companion/server/voice/speech.ts`
- `FS-M10-1` [Low, pre-existing, E] The session JWT still embeds the display name: an overlong upstream name produces a session cookie over 4096 B that the browser drops, reproducing the M-10 login loop — `alexa-companion/server/_core/sdk.ts`
- `FS-M11-NF1` [Low, pre-existing, E] alexa-companion: the production-only install that PR#33 validates cannot run the mandated DB migration (`pnpm db:migrate`: drizzle-kit not found; the artifact has no drizzle/ folder) — `alexa-companion/package.json`
- `AD-16` [Info, pre-existing, S] Italian launch prompt addresses every user in the feminine ('Benvenuta in Binario') while other locales are neutral — `alexa-companion/shared/i18n.ts`

**iOS**

- `IOSGTFS-09` [Low, PR#32, E] No cross-pack de-duplication or reconciliation: the same train appears twice (SBB and Trenord feeds), sometimes with conflicting times, consuming the 8 result and 2 Watch slots — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`
- `IOSGTFS-10` [Low, PR#32, S] One per-query provider failure disables search for the whole session; candidate LIMIT applied before service-day filtering can silently truncate later departures — `ios/BinarioApp/AppModel.swift`
- `IOSGTFS-11` [Low, PR#32, S] Saved routes are display-only: they cannot be opened, searched or sent to the Watch — `ios/BinarioApp/SavedRoutesView.swift`
- `IOSGTFS-15` [Low, PR#32, S] SavedRouteStore is all-or-nothing: one undecodable element drops the whole list and the next save overwrites UserDefaults (latent data loss) — `ios/BinarioApp/AppModel.swift`
- `PU-10` [Low, PR#32, S] iOS GTFS installer leaks hidden 24.5 MB staged databases after an interrupted first-launch install; cleanup skips hidden files and never runs on the fast path — `ios/BinarioKit/Sources/BinarioKit/BundledGTFSScheduleInstaller.swift`
- `RT-6` [Low, PR#32, E] iOS applies GTFS 'noon minus 12h' to feeds that encode wall-clock times: on 2026-10-25 Trenord's 00:02/00:05/00:32 departures are shown one hour late (Android shows the real time) — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`
- `RT-10` [Low, PR#32, E] iOS never uses the stored last_service_date: after 2026-12-13, 490 of 534 indexed stations return a generic 'no service' instead of an expiry notice — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`
- `VT-07` [Low, PR#32, E] iOS DST tests cannot distinguish GTFS noon-minus-12h from midnight origin; a regression would shift every train on 2026-10-25 by one hour with tests green — `ios/BinarioKit/Tests/BinarioKitTests/SQLiteGTFSJourneyRepositoryTests.swift`
- `VT-14` [Low, PR#32, E] iOS installer tests derive expected hashes from the hand-rolled SHA-256 under test; nothing ties Swift hashing to the Python-generated metadata — `ios/BinarioKit/Sources/BinarioKit/BundledGTFSScheduleInstaller.swift`
- `TW-9` [Low, PR#32, S] iOS programmed snapshots reach the Apple Watch with status = raw enum 'PROGRAMMED' and freshness UNKNOWN: detail shows 'Stato: PROGRAMMED' and the headline shows a red 'Dati non disponibili in tempo … — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`
- `F-CHK-08` [Low, PR#32, E] 7.37 MB derived binary (iOS GTFS index) committed outside LFS — `ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib`
- `AI-NF-6` [Low, PR#32, S] iOS localization plist keys use unsupported INFOPLIST_KEY_CFBundleDevelopmentRegion/CFBundleLocalizations: Xcode ignores them, so the development region is silently 'en' — `ios/project.yml`
- `N-A1-IOS-TWIN` [Low, PR#32, S] iOS offline GTFS twin has no expired-timetable notice and the iOS CI rail has no freshness gate — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`
- `FSK-IOS-UNAVAILABLE-TTL` [Low, PR#32, S] iOS→Apple Watch twin of N-D3: UNAVAILABLE snapshots have a 15-minute TTL but travel over non-urgent WatchConnectivity context. When the Watch applies them late, it rejects them as expired and keeps s… — `ios/BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift`
- `FSK-M9-IOS-SAVEDROUTES` [Low, PR#32, S] iOS app: SavedRouteStore turns any undecodable or invalid saved-route payload into [] and the next save or delete overwrites the whole archive (M-9 twin) — `ios/BinarioApp/AppModel.swift`
- `IOSGTFS-16` [Info, PR#32, E] Efficiency: identical SQL executed 32 times per query plus quick_check per query; full 24.5 MB pure-Swift SHA-256 (two array copies) at every launch — `ios/BinarioKit/Sources/BinarioKit/SQLiteGTFSJourneyRepository.swift`

**Apple Watch**

- `IOSGTFS-13` [Low, pre-existing, S] Watch activates WCSession only in WatchHomeView.onAppear and has no .backgroundTask(.watchConnectivity): complication/widget and revocation update only when the user opens the Watch app — `apple-watch/BinarioWatch/BinarioWatchApp.swift`
- `WC-N4` [Low, pre-existing, S] Apple Watch widget and app enforce expiry only at load (Swift twin of R4:M-5) — `apple-watch/BinarioWatchWidgets/BinarioWatchWidgets.swift`
- `WC-N5` [Low, PR#32, E] Apple Watch service identity (number-expectedDeparture) collides on real iOS GTFS data (Swift twin of R1:C4) — `apple-watch/Shared/WatchModels.swift`
- `FSK-1` [Low, PR#32, E] The H-2 regression guard checks only RefreshBinarioIntent.title/description and runs only on the macOS rail: a stored static var anywhere else in the shared Watch sources brings back the Swift 6 comp… — `tests/test_verify_apple_watch_project.py`

**CI/governance**

- `RD-12` [Low, PR#33, S] DEPENDENCY_AUDIT.md still documents the removed image-size allowlist and omits the new metro patch and forced major overrides — `docs/security/DEPENDENCY_AUDIT.md`
- `IOSGTFS-12` [Low, PR#32, S] iOS GTFS pipeline hard-codes exactly 10 packs and the documented pack-refresh procedure omits the iOS rebuild; the iOS artifact is verified only in the macOS job — `tools/build_ios_gtfs_schedule.py`
- `VT-10` [Low, PR#33, E] PR#33 test_wear_compose_gradle_dependency_suppressions.py is not executed by any workflow — `tests/test_wear_compose_gradle_dependency_suppressions.py`
- `VB-1` [Low, pre-existing, E] verify_ci_pinning.py content-locks only the Apple job run-steps; Linux release-rail run-step shell is unreviewed and freely editable — `tools/verify_ci_pinning.py`
- `F-CHK-06` [Low, pre-existing, E] The two GTFS freshness release gates disagree by one day; all current packs trip them on 2026-11-12/13 — `tools/build_gtfs_packs.py`
- `WC-N7` [Low, pre-existing, S] PRIVACY.md still says disabling Wear sharing does not remove the watch copy, contradicting the implemented revocation — `PRIVACY.md`
- `WC-N8` [Low, pre-existing, E] v1 contract does not define the whitespace class or character unit of 'caratteri normalizzati'; decoders disagree — `contracts/wear-snapshot/v1/README.md`
- `N-G1-PATHS` [Low, PR#33, E] Privacy verifier input docs/data-sources/SOURCE_POLICY.md matches no workflow path filter — `tools/verify_privacy_policy.py`
- `N-CI-ORPHAN-TEST` [Low, PR#33, E] tests/test_wear_compose_gradle_dependency_suppressions.py (PR#33 lint-policy guard) runs in no workflow — `tests/test_wear_compose_gradle_dependency_suppressions.py`
- `FSKB-3` [Low, PR#33, E] The JBB disclosure gate requires docs/data-sources/SOURCE_POLICY.md, but no workflow that runs the privacy verifier triggers on that file — `tools/verify_privacy_policy.py`
- `FS-NG1-LOC` [Low, PR#34, E] Privacy verifier checks only the Italian catalogs; the en/de/fr/es in-app privacy disclosures shipped by PR#34 are never verified — `tools/verify_privacy_policy.py`
- `FS-NG1-GUARD` [Low, pre-existing, E] Android half of the privacy-gate wiring is not regression-guarded: removing the android-ci privacy step and its privacy path filters passes every verifier and all 101 Python tests — `tools/verify_ci_pinning.py`
- `VT-17` [Info, PR#32, S] tests/test_verify_ios_project.py asserts the presence of Swift test names and source substrings instead of behaviour — `tests/test_verify_ios_project.py`
- `F-CHK-11` [Info, PR#33, E] git diff --check reports trailing whitespace in the stack (docs markdown only) — `docs/CONTROVERIFICA_AUDIT_CLAUDE_2026-09-29.md`

## Appendice B — Finding precedenti ancora aperti o parziali (125)

Rapporto: R1 = audit 23/09, R2 = addendum 23/09, R3 = integrazione 23/09, R4 = integrazione 29/09. "⚠fix contestato" = lo scettico ha mostrato che la correzione dichiarata non regge del tutto.

| ID | Rapporto | Sev. | Stato | File attuale | Residuo |
|---|---|---|---|---|---|
| `R4:H-1` | R4 | High | parziale | `app/data/StationCanonicalizer.kt` | Station canonicalization uses substring 'first match wins' over catalog order: 3,321/5,23…. First-match name resolution (122 stations; 15 resolve to a place more than 5 km away) is reported as AND-NEW-1. The substring destination matching is… |
| `R2:A1` | R2 | Medium | parziale | `app/src/main/assets/gtfs/manifest.json` | Three embedded GTFS packs already expired (cotral-ferro 2026-09-06, trentino-extraurban 0…. See new findings N-A1-ANDROID-UI-REGRESSION (PR#34) and N-A1-IOS-TWIN (PR#32); timing per R4:L-G-6. |
| `R2:A5` | R2 | Low/Medium | parziale | `app/data/DataQuality.kt` | Quality band conflates source authority with operational verification (scheduled-only RFI…. The unscored live fallback affects only the opt-in BINARIO_PERSONAL_REALTIME_SOURCES build. After PR#34 the phone UI no longer shows the band at all … |
| `R2:B3` | R2 | Medium | parziale | `app/MainActivity.kt` | Empty detail after process death (selectedService/journeyQuery/filters not saved); pendin…. Low UX issue after process death. |
| `R2:C3` | R2 | Medium | parziale | `README.md` | README untruthful: cites 4 nonexistent docs (BUILD_SPEC.md, SECURITY.md, AI_FEATURE_GATE.…. README drift listed above (PR#32/PR#34 made parts worse). |
| `R2:C6` | R2 | Medium | parziale | `alexa-companion/server/_core/index.ts` | Voice Bridge not operable outside Manus: findAvailablePort silently changes port, dashboa…. The Manus OAuth identity-provider dependency is an owner decision. Missing Dockerfile/engines. Logging of DB errors is tracked in R4:L-X-16. |
| `R3:N-C2` | R3 | Medium | parziale ⚠fix contestato | `companion-expo/android/app/build.gradle` | Companion Wear applicationId mismatch not fixed (im.binario.companion vs .wear): v1 paylo…. The applicationId mismatch is gone, but PR#34 regressed the same-signature half of Data Layer pairing: phone debug is signed with the template debug.… |
| `R3:N-C3` | R3 | Medium | parziale ⚠fix contestato | `.github/workflows/companion-ci.yml` | Companion generated Kotlin JUnit tests never compiled or executed (CI runs only lint/asse…. Gradle/AGP not executable here. BinarioWidgetStateTest now exercises a dead twin (NEW-COMP-5). The Gradle step is currently never reached because the… |
| `R3:N-CI` | R3 | Medium | aperto | `.github/workflows/` | GitHub Actions minutes exhausted by ~110 Android + ~110 companion runs (triggers on every…. No SHA of this stack has been validated remotely; billing/minutes cause is inferred (not visible via API). |
| `R3:N-D4` | R3 | Medium | parziale ⚠fix contestato | `app/data/planned/ItalianRailServiceCalendar.kt` | 'FESTIVO' periodicity returns true on non-holiday weekdays; periodicity logic duplicated …. Negations are still evaluated as positives (see R4:L-A-2). / FIX SKEPTIC: Only the three literal words FEST/FESTIVO/FESTIVI are now limited to Sunday… |
| `R3:N-W1` | R3 | Medium | parziale | `apple-watch/project.yml` | Apple Watch project.yml still not reliably generable/installable (no scheme, no INFOPLIST…. The project generates but cannot be built or installed until AI-NF-1 and AI-NF-4 are fixed. No macOS run has ever validated the current spec, because… |
| `R4:M-12` | R4 | Medium | parziale | `alexa-companion/server/voice/speech.ts` | alexa: route names and slot values interpolated into SSML without escaping: & or < break …. C0 control characters, U+FFFE/U+FFFF and lone surrogates in route names still produce malformed SSML. This breaks MyRoutes and NextTrains for that us… |
| `R4:M-13` | R4 | Medium | parziale | `.github/workflows/` | CI: all workflows limit push to main but origin/main (v0.16.3) has no .github/: no push r…. Dependabot and main push path inert until owner promotes the line to main; RELEASE_BUILD.md:64 trigger table omits push on stabilization/**; no run e… |
| `R4:M-14` | R4 | Medium | parziale | `PRIVACY.md` | Privacy: distributed build contacts undeclared third-party community host jbb.ghsq.de (Eu…. The user-facing disclosure is still missing, and the README contradicts the code. The periodic background requests via the widget are not described. … |
| `R4:M-15` | R4 | Medium | parziale | `companion-expo/pnpm-workspace.yaml` | Companion verify:dependency-audit gate fails at HEAD: image-size allowlist invalidated (p…. The gate is red again at HEAD, so Companion CI stops at 'Audit high and critical vulnerabilities' (companion-ci.yml:71-72) before TypeScript, tests, … |
| `R4:M-2` | R4 | Medium | parziale | `app/data/gtfs/EmbeddedGtfsRealtimeScheduleResolver.kt` | EmbeddedGtfsRealtimeScheduleResolver throws IllegalArgumentException/NumberFormatExceptio…. A malformed third-party ZIP still crashes the app. Reported as AND-NEW-4. |
| `R4:M-3` | R4 | Medium | aperto | `app/src/main/AndroidManifest.xml` | SCHEDULE_EXACT_ALARM declared but never requested: on Android 13+ stop alarms and commute…. On Android 14+ with targetSdk 36 the permission is denied by default, so every alarm is inexact. |
| `R4:M-7` | R4 | Medium | parziale ⚠fix contestato | `companion/binario/BinarioStatusWidget.kt` | Companion Glance widget (countdown, next trip) rendered once and never updated (no update…. Countdown can be stale by the WorkManager period (≥15 min, more under Doze/standby buckets). The production localized countdown (BinarioStatusWidget.… |
| `R1:ARCH-1` | R1 | Low | aperto | `app/MainActivity.kt` | MainActivity.kt ~2,500-line monolith (30+ composables, manual navigation); TrainViewModel…. Maintainability debt that grew in PR#34. |
| `R1:ARCH-2` | R1 | Low | aperto | `app/TrainViewModel.kt` | Dead code introduced by provenance fixes: refreshSelectedService() and currentFreshness()…. Dead code. |
| `R1:ARCH-5` | R1 | Low | parziale | `companion-expo/tests/native-surface-contract.test.ts` | Companion 'contract' tests are grep-based readFileSync+toContain string checks that misse…. String-presence tests did not catch the PR#34 regressions (missing signingConfigs.release definition — NEW-COMP-1; dead-twin countdown under test — N… |
| `R1:ARCH-6` | R1 | Low | aperto | `app/data/DataReconciliation.kt` | Android dead code: SnapshotReconciler/ServiceConflict (tests only), AuthorizedLiveDataBou…. Dead code. |
| `R1:ARCH-7` | R1 | Low | aperto | `companion-expo/components/local-station-board-card.tsx` | Companion dead code: components/local-station-board-card.tsx imported by nothing; declare…. Overlaps R4:L-C-6 (component) and R4:L-C-9 (expo-secure-store); expo-network is tracked only here. |
| `R1:PERF-3` | R1 | Low | aperto | `gradle.properties` | gradle.properties org.gradle.workers.max=1 serializes builds and tests. Root build still fully serialized with no documented rationale; docs/RELEASE_BUILD.md:39 justifies single-worker only for Companion AAB member-order … |
| `R1:S11-1` | R1 | Low | aperto | `app/LocalDataBackup.kt` | Backup without valid routes rejected by require(routes.isNotEmpty()) in LocalDataBackup.d…. The dead branch remains, and PR#34 localized its string backup_imported_without_route in 5 locales. |
| `R1:T-1` | R1 | Low | aperto | `app/build.gradle.kts` | gtfs-realtime-bindings 0.0.4 pulls old protobuf lineage with known DoS CVEs (CVE-2021-225…. verification-metadata also lists protobuf-java 3.24.4 and 3.25.5, which are probably build tooling. Whether the runtime classpath is upgraded could n… |
| `R2:A4` | R2 | Low | parziale | `tools/build_gtfs_packs.py` | GTFS pack pipeline not reproducible: only 3/12 packs filtered offline, no script generate…. Remaining sub-items overlap R4:L-G-9 (legacy tools, generatedAt) and R4:INFO-7 (http://); bs4 unpinned and unhashed. |
| `R2:B-TEST` | R2 | Low | parziale | `app/StopAlarmPlannerTest.kt` | Weak tests: StopAlarmPlannerTest never asserts 5-minute offset and mixes fixed 2024 now w…. The widget and alert codecs have no JVM test; the R4:L-A-21 crash path is untested. |
| `R2:B12` | R2 | Low | parziale | `app/MainActivity.kt` | Backup: unbounded URI read parsed on main thread, alert.time not validated, last saved ro…. The last route cannot be removed from the UI. This contradicts PRIVACY.md:58, which says each route can be removed with its command. |
| `R2:B14` | R2 | Low | aperto | `app/MainActivity.kt` | Fixed-width columns wrap/clip at font scale 2.0. The expected wrap/clip at font scale 2.0 was not verified on a device. |
| `R2:C10` | R2 | Low | parziale | `alexa-companion/VERIFICA_RELEASE.md` | Governance leftovers: PR #1 open since 14/08, Manus template.json and placeholder pnpm-wo…. PR #1 hygiene and stale VERIFICA_RELEASE.md numbers/locale. |
| `R3:LOW-13` | R3 | Low | aperto | `app/build.gradle.kts` | Android: heap-bounded GTFS stress test not run with -Xmx256m in CI; 'noise' rows not inde…. The heap bound is not exercised in CI. |
| `R3:LOW-14` | R3 | Low | aperto | `app/data/gtfs/EmbeddedGtfsRealtimeScheduleResolver.kt` | Android: realtime schedule resolver re-decompresses stop_times per trip.  |
| `R3:LOW-17` | R3 | Low | aperto | `app/PlannerVoiceParser.kt` | Android: voice parser compiles one Regex per value (thousands); numericDatePattern can ne….  |
| `R3:LOW-19` | R3 | Low | aperto | `app/MainActivity.kt` | Android: PlannedJourney import on main thread.  |
| `R3:LOW-22` | R3 | Low | parziale | `app/data/gtfs/EmbeddedGtfsStationSearchIndex.kt` | Android: GTFS normalize compiles two Regex per call. The per-call regex compile remains in the GTFS route adapter. It runs on the Default dispatcher, so the cost is performance only. |
| `R3:LOW-23` | R3 | Low | aperto | `app/MainActivity.kt` | Android: serviceDirection still not passed by openService call sites.  |
| `R3:LOW-24` | R3 | Low | aperto | `docs/benchmarks/BENCHMARK_GAP_BINARIO_2026-09-03.md` | governance: 4 broken relative links in historical documents. 56 md files excluded by the independence rule (docs/verifiche, docs/releases, docs/handoffs, named claim docs, CHANGELOG) were not checked; the other… |
| `R3:LOW-28` | R3 | Low | aperto | `.gitattributes` | governance: clone is 95 MiB due to pre-LFS blobs in history. History bloat persists and now grows ~7 MiB per iOS index regeneration plus GTFS zips. |
| `R4:L-A-1` | R4 | Low | aperto | `app/data/planned/RfiProgrammedStationBoardAdapter.kt` | Station board HTML (Jsoup) parsed on main thread: RFI programmed board always, RFI monito….  |
| `R4:L-A-10` | R4 | Low | aperto | `app/plannedjourney/PlannedJourneyAgendaScreen.kt` | Agenda editor filters 5,239 stations in composition on every recomposition; MainActivity ….  |
| `R4:L-A-11` | R4 | Low | aperto | `app/StopAlarm.kt` | Stop alarms not persisted/restored: reboot or force-stop before trigger loses them silent….  |
| `R4:L-A-12` | R4 | Low | aperto | `app/wear/WearRefreshRequestListenerService.kt` | Watch refresh request republishes same payload (same generatedAt/expiresAt), no fetch (F2).  |
| `R4:L-A-13` | R4 | Low | aperto | `app/widget/RouteWidgetProvider.kt` | Periodic widget worker never cancelled: onDeleted re-enqueues, onDisabled not overridden.  |
| `R4:L-A-14` | R4 | Low | aperto | `app/MainActivity.kt` | Commuter reminder time formatted with default locale ('%02d:%02d'.format): non-Latin digi….  |
| `R4:L-A-15` | R4 | Low | aperto | `app/MainActivity.kt` | In-app 'Scuro' theme does not update system bar icon appearance (enableEdgeToEdge follows….  |
| `R4:L-A-16` | R4 | Low | aperto | `PRIVACY.md` | PRIVACY.md overstates backup and widget contents (no widget surface in exported JSON). Overstatement unchanged. |
| `R4:L-A-17` | R4 | Low | aperto | `app/build.gradle.kts` | -PBINARIO_VERSION_CODE assigns same versionCode to phone and Wear sharing an applicationI….  |
| `R4:L-A-18` | R4 | Low | aperto | `app/TrainViewModel.kt` | Synchronous SharedPreferences.commit on main thread at ViewModel creation and on each suc….  |
| `R4:L-A-19` | R4 | Low | aperto | `app/StationPreferences.kt` | Upgrade v0.16.3 -> v0.20.4 loses/degrades favorites, recent routes and all widget configu….  |
| `R4:L-A-2` | R4 | Low | aperto | `app/data/planned/ItalianRailServiceCalendar.kt` | RFI periodicity grammar: ranges other than LUN-VEN, 'escluso' negations, 'Sab e festivi' ….  |
| `R4:L-A-20` | R4 | Low | aperto | `app/widget/WidgetConfigureActivity.kt` | WidgetConfigureActivity form kept in non-saveable remember.  |
| `R4:L-A-21` | R4 | Low | aperto | `app/widget/RouteWidgetProvider.kt` | WidgetStateStore.read decodes Base64 outside runCatching: a corrupt value crashes the rec….  |
| `R4:L-A-22` | R4 | Low | aperto | `app/plannedjourney/PlannedJourneyRepository.kt` | PlannedJourneyRepository contract (unreadable payload left intact and exportable) not hon….  |
| `R4:L-A-23` | R4 | Low | aperto | `app/data/live/StaRealtimeAdapter.kt` | Hard-coded obsolete User-Agents (Binario/0.15, Binario/0.20) vs versionName 0.20.4.  |
| `R4:L-A-3` | R4 | Low | aperto | `app/data/live/ViaggiaTrenoStationAdapter.kt` | Live fetchers (ViaggiaTreno, Italo, RSS) read without byte limit; only RFI programmed is …. The European Sleeper realtime fetch is live by default; the others are opt-in or unwired. |
| `R4:L-A-4` | R4 | Low | aperto | `app/data/gtfs/RemoteGtfsFallbackDataSource.kt` | European Sleeper fallback readBytes() up to 256 MiB into heap ByteArray on every primary-….  |
| `R4:L-A-5` | R4 | Low | aperto | `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt` | Compact index LRU = 2: saved routes on >2 packs re-parse whole packs each load; no negati….  |
| `R4:L-A-6` | R4 | Low | aperto | `app/data/planned/RfiProgrammedTimetableAdapter.kt` | RFI timetable cached per route id: same hub page downloaded once per route/direction and ….  |
| `R4:L-A-7` | R4 | Low | aperto | `app/widget/RouteWidgetProvider.kt` | Widget rows sorted by expectedDeparture string: after-midnight runs jump to top.  |
| `R4:L-A-8` | R4 | Low | aperto | `app/data/repository/BinarioDataGraph.kt` | GtfsStationSearchIndex and RealtimeEnrichmentService wired in BinarioDataGraph but never ….  |
| `R4:L-A-9` | R4 | Low | aperto | `app/data/planned/RfiProgrammedTimetableAdapter.kt` | A8 still open: RFI timetable resolves only with hub as origin; with hub as destination fa…. The PR#34 generic-message change is recorded as AND-NEW-5. |
| `R4:L-C-1` | R4 | Low | aperto | `companion-expo/app/(tabs)/index.tsx` | Home shows 'Promemoria previsto N minuti prima' even when no native reminder is scheduled. Twin on the Glance widget: BinarioStatusWidget.kt:62 prints "Promemoria N min ·" from the same preference, regardless of any scheduled notification. |
| `R4:L-C-10` | R4 | Low | aperto | `companion-expo/assets/data/stazioni-canoniche-v2.json` | Station catalog asset checksum 58b44188 (Android source SHA-256) differs from script-comp….  |
| `R4:L-C-2` | R4 | Low | aperto | `companion-expo/lib/planned-reminder-service.ts` | Notification permission never rechecked after scheduling: with POST_NOTIFICATIONS revoked….  |
| `R4:L-C-3` | R4 | Low | aperto | `companion-expo/app/(tabs)/alerts.tsx` | Agenda confuses 'no reminder' (null) with 'at departure time' (0).  |
| `R4:L-C-4` | R4 | Low | aperto | `companion-expo/lib/local-routes.ts` | Batch import uses Date.now() as id: two additions in same millisecond lose a route.  |
| `R4:L-C-5` | R4 | Low | aperto | `companion-expo/app/(tabs)/settings.tsx` | Unhandled async paths in Settings/Theme/Template.  |
| `R4:L-C-6` | R4 | Low | aperto | `companion-expo/components/local-station-board-card.tsx` | Dead station-board component and recent-stations store; privacy inventory diverges.  |
| `R4:L-C-7` | R4 | Low | aperto | `companion-expo/lib/offline-station-index.ts` | Autocomplete re-normalizes 5,239 names and 2,813 aliases (NFD + regex) per keystroke on J….  |
| `R4:L-C-8` | R4 | Low | aperto | `companion-expo/lib/local-journey-itineraries.ts` | Guided import keeps bidi control characters (U+202E, U+2066-2069) in names.  |
| `R4:L-C-9` | R4 | Low | aperto | `companion-expo/package.json` | Dead dependencies/inputs: expo-secure-store never imported, parse-binario-api-url.cjs orp….  |
| `R4:L-G-1` | R4 | Low | aperto | `docs/governance/BRANCH_AND_RELEASE_POLICY.md` | main stuck at v0.16.3 and no 0.20.4 tag while BRANCH_AND_RELEASE_POLICY describes main as…. Needs owner promotion/tagging or a policy rewrite; gate table :17-28 also omits the Apple/iOS and Alexa rails. |
| `R4:L-G-3` | R4 | Low | aperto | `.github/workflows/android-ci.yml` | AAB reproducibility gate proves only same-runner determinism (two --rerun-tasks builds in…. Same-runner determinism only; the steps never executed remotely (R3:N-CI). |
| `R4:L-G-4` | R4 | Low | aperto | `tools/verify_ci_pinning.py` | verify_ci_pinning.py checks neither permissions block nor pull_request_target/workflow_ru…. Unknown workflow file names also bypass all trigger-budget checks. |
| `R4:L-G-5` | R4 | Low | aperto | `README.md` | Toolchain doc drift: README says OpenJDK 21 vs CI Temurin 17.0.20; RELEASE_BUILD/DEPENDEN…. Unchanged drift. |
| `R4:L-G-6` | R4 | Low | aperto | `app/src/main/assets/gtfs/manifest.json` | 7 of 10 GTFS packs end 12-13/12/2026: the 30-day validity gate will fail from 13/11/2026. Gate breaks in 44 days, data expires in 73 days; after expiry Android shows a generic message (PR#34 regression) and iOS 'no service'. |
| `R4:L-G-7` | R4 | Low | aperto | `PRIVACY.md` | PRIVACY.md does not cover Apple Watch or iOS; Companion section describes nonexistent cod…. Gap widened by PR#32; verify_privacy_policy.py covers no iOS/watch strings. |
| `R4:L-G-8` | R4 | Low | aperto | `docs/data-sources/SOURCE_POLICY.md` | Data-source docs present ViaggiaTreno/RFI Monitor/Italo as active though excluded from di…. All three sub-items unchanged. |
| `R4:L-G-9` | R4 | Low | aperto | `tools/verify_embedded_gtfs_coverage.py` | verify_embedded_gtfs_coverage.py without CLI writes unconditionally into a tracked doc; b…. Unchanged; tracked doc is also stale vs current packs. |
| `R4:L-I-1` | R4 | Low | aperto | `ios/BinarioKit/Sources/BinarioKit/JourneyModels.swift` | Station NC001521 ('Ercolano - Miglio D'Oro', double space) rejected by JourneyQuery/Saved…. Selecting NC001521 as origin or destination always shows 'select origin and destination', and the route cannot be saved. Fix by normalizing names in … |
| `R4:L-I-2` | R4 | Low | aperto | `ios/BinarioKit/Sources/BinarioKit/WearSnapshotV1.swift` | Snapshot whose publication failed kept as 'pending' and republished on re-enable even aft…. A failed publish, then a successful clear or newer publish, then a WCSession re-activation (watch switch) re-sends a stale route after the user revok… |
| `R4:L-I-3` | R4 | Low | aperto | `ios/BinarioApp/AppModel.swift` | With sharing disabled, Settings status never shows 'Disattivata' (PR #32). The status line is misleading in the off state and in the idle-activated state. Fix: show .disabled whenever !watchSharingEnabled, and a neutral 'rea… |
| `R4:L-I-4` | R4 | Low | aperto | `.github/workflows/apple-watch-ci.yml` | ios CI job never runs BinarioWatchTests: Swift 6 watch code only compiled (PR #32). Add `xcodebuild -scheme BinarioWatch -destination <watchOS sim> test` to the ios job. This would also have surfaced AI-NF-3 if paired with a localiza… |
| `R4:L-I-5` | R4 | Low | aperto | `ios/BinarioApp/StationPickerField.swift` | Station search re-normalizes 5,239 names on main thread at every body evaluation (PR #32). Pre-normalize the catalog once (for example at load, off-main) and compute suggestions once per query change. |
| `R4:L-I-6` | R4 | Low | parziale | `apple-watch/BinarioWatch/WatchHomeView.swift` | Apple Watch UI hard-coded in Italian while iOS app has 5 localizations. The shipped companion Watch UI shows raw localization keys instead of any language (AI-NF-3, Medium). |
| `R4:L-I-7` | R4 | Low | aperto | `tests/test_verify_ios_project.py` | tests/test_verify_ios_project.py hard-codes 5,239 stations and catalog hash; README still…. Any catalog refresh breaks three test suites. The README contradicts the existing iOS app (PR#32). |
| `R4:L-W-2` | R4 | Low | aperto | `companion/wear/BinarioWearSnapshotV1Test.kt` | Companion Kotlin tests (phone and Wear) use hard-coded goldens instead of contracts/wear-…. Fixture drift in the companion Kotlin decoders or producer can go undetected. |
| `R4:L-W-3` | R4 | Low | aperto | `wear/model/WearSnapshot.kt` | provenanceObservedAtEpochMillis bounds differ between decoders and are unspecified in the…. The contract still does not specify the bound, and the decoders still disagree. |
| `R4:L-W-4` | R4 | Low | aperto | `companion-expo/plugins/with-binario-android-widget.cjs` | Companion Wear voice flow is a stub: phone always answers 'Dati non disponibili in tempo …. The voice flow remains a stub. |
| `R4:L-W-5` | R4 | Low | aperto | `companion/wear/BinarioWearData.kt` | Companion Wear checklist unreachable (plannedChecklistRemaining fixed at 0) while PRIVACY…. The checklist is unreachable, and PRIVACY.md describes a stored count that does not exist. |
| `R4:L-W-6` | R4 | Low | aperto | `companion-expo/android/wear/src/main/AndroidManifest.xml` | Companion Wear module has no android:icon nor mipmap resources (to verify on merged APK). The merged APK cannot be built here; libraries do not contribute an application icon. |
| `R4:L-X-1` | R4 | Low | aperto | `alexa-companion/server/db.ts` | Refresh-token reuse detection has no tolerance window and does not mark link revoked: a r…. A single retried or concurrent Amazon refresh breaks the link until the user re-links, while the dashboard shows 'Collegato e verificato'. |
| `R4:L-X-10` | R4 | Low | aperto | `alexa-companion/pnpm-lock.yaml` | Nine open advisories with fixes (6 moderate, 3 low) incl. qs/body-parser in ask-sdk-expre…. Nine advisories with fixes remain. Runtime impact is bounded by the 1mb limit and the 1000-parameter limit. |
| `R4:L-X-11` | R4 | Low | aperto | `alexa-companion/docs/DATABASE_MIGRATIONS.md` | Operational documentation obsolete vs code (docs/DATABASE_MIGRATIONS.md and others). The operational and privacy documentation still disagrees with the code, and PR#34 added more drift. |
| `R4:L-X-12` | R4 | Low | aperto | `alexa-companion/tsconfig.json` | pnpm check does not type-check tests (tsconfig excludes **/*.test.ts). Test type errors are invisible to CI. |
| `R4:L-X-13` | R4 | Low | aperto | `alexa-companion/patches/wouter@3.7.1.patch` | wouter patch exposes route table on window.__WOUTER_ROUTES__ in production. The route table is exposed on window in production (currently '/' and '/404'). |
| `R4:L-X-14` | R4 | Low | aperto | `alexa-companion/server/voice/alexa.ts` | NextTrainsIntent answers 'Non hai ancora salvato alcuna tratta' when spoken name merely d…. The user is told they have no routes when the spoken name merely does not match. |
| `R4:L-X-15` | R4 | Low | aperto | `alexa-companion/server/routes/contract.ts` | Route contract validation errors not distinguishable for the user. Validation errors are indistinguishable from server errors. |
| `R4:L-X-16` | R4 | Low | aperto | `alexa-companion/server/_core/trpc.ts` | On DB error the tRPC formatter logs full Drizzle query with parameters (origin, destinati…. Personal data reaches server logs on any DB error, contradicting ALEXA_SETUP.md:39. |
| `R4:L-X-17` | R4 | Low | aperto | `alexa-companion/client/src/pages/Home.tsx` | 'Account eliminato' confirmation unreachable: after account.delete the page shows login. The 'Account eliminato' confirmation is unreachable. The page may auto-redirect to the OAuth portal on refetch; what the upstream portal then does is… |
| `R4:L-X-18` | R4 | Low | aperto | `alexa-companion/client/src/pages/Home.tsx` | Dashboard shows 'Non attivo. Manca la configurazione...' also on 429/5xx of voice.status. The dashboard shows a misleading 'not configured' state on transport errors. |
| `R4:L-X-19` | R4 | Low | aperto | `alexa-companion/server/profile/contract.ts` | Profile idempotency limited to last clientMutationId per user. Only the last mutation id is idempotent. |
| `R4:L-X-2` | R4 | Low | aperto | `alexa-companion/server/voice/config.ts` | No length/entropy check for VOICE_TOKEN_PEPPER and ALEXA_OAUTH_CLIENT_SECRET. A weak pepper or client secret is accepted at boot. |
| `R4:L-X-20` | R4 | Low | aperto | `alexa-companion/server/db.ts` | No per-user cap nor uniqueness for personal routes (previous Low). There is no per-user cap and no uniqueness. |
| `R4:L-X-21` | R4 | Low | aperto | `alexa-companion/server/voice/alexa.ts` | Skill error handler swallows exceptions without server log. Skill failures are silent server-side. |
| `R4:L-X-22` | R4 | Low | aperto | `alexa-companion/server/_core/sdk.ts` | Manus/WebDev residues: wired RPC paths, dead cron_ path forwarding JWT upstream, ManusDia…. Template residues remain. The skill.json host placeholder waits on the owner choosing a hosting domain. |
| `R4:L-X-3` | R4 | Low | aperto | `alexa-companion/server/voice/alexa.ts` | Token endpoint without Cache-Control: no-store, HTML page on malformed JSON, client auth …. The item is unchanged. |
| `R4:L-X-4` | R4 | Low | aperto | `alexa-companion/server/_core/securityHeaders.ts` | No HSTS and no content CSP (only frame-ancestors). No HSTS and no script/content CSP. |
| `R4:L-X-5` | R4 | Low | aperto | `alexa-companion/server/_core/app.ts` | Webhook rate limit per IP with 429 JSON despite shared Amazon egress IPs (kept by choice). This was kept by choice. Amazon's shared egress IPs can hit the webhook limit (60/min) and the token-refresh limit (30/min). |
| `R4:L-X-6` | R4 | Low | aperto | `alexa-companion/shared/const.ts` | Session JWT bearer valid 30 days without iat/jti: logout/unlink/re-login do not invalidat…. The session stays valid for 30 days after logout, unlink or re-login (only account deletion invalidates it). |
| `R4:L-X-7` | R4 | Low | aperto | `alexa-companion/drizzle/0007_next_bloodstrike.sql` | MariaDB-only DDL (ADD COLUMN IF NOT EXISTS, CREATE INDEX IF NOT EXISTS) in 0007/0008/0012…. A fresh migrate on MySQL 8 fails at 0007 statement 2. |
| `R4:L-X-8` | R4 | Low | aperto | `alexa-companion/drizzle/0013_complete_shiva.sql` | Migration 0013 rewritten at same tag/timestamp (c08ec76 -> 0042f22): DBs migrated with fi…. Databases that ran the first 0013 keep shared legacy families. Whether any such database exists is not verifiable. |
| `R4:L-X-9` | R4 | Low | aperto | `alexa-companion/server/db.ts` | No session time_zone on MySQL pool: default timestamps read back shifted if server not in…. DB-default timestamps are shifted by the DB time-zone offset (for example binario_profiles.updatedAt is returned in the profile DTO). With a DST-obse… |
| `R4:INFO-1` | R4 | Info | aperto | `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt` | Empty departure_time classified INVALID instead of MISSING. Unchanged. Info. |
| `R4:INFO-10` | R4 | Info | aperto | `.github/workflows/apple-watch-ci.yml` | PR #32 adds a second macOS CI job (x10 minutes multiplier). Intentional; cost multiplier grew with the broader path filters. |
| `R4:INFO-2` | R4 | Info | aperto | `app/data/gtfs/EmbeddedGtfsRouteAdapter.kt` | Route adapter uses naive LocalDateTime without the 'noon minus 12h' service-day rule used…. Unchanged. Info. |
| `R4:INFO-3` | R4 | Info | aperto | `app/TrainViewModel.kt` | CustomStationStore persists every searched station, catalog stations included. Unchanged. Info. |
| `R4:INFO-4` | R4 | Info | aperto | `alexa-companion/server/voice/consent.ts` | Consent CSRF token replayable and not bound to the request (same user only). Replay is possible by the same user within the 10-minute TTL. |
| `R4:INFO-5` | R4 | Info | aperto | `alexa-companion/drizzle/0013_complete_shiva.sql` | Migrations not resumable after partial failure (DDL auto-commit). Migrations cannot be resumed after a partial failure. |
| `R4:INFO-6` | R4 | Info | aperto | `gradle/verification-metadata.xml` | Gradle dependency verification is checksum-only (verify-signatures=false). Checksum-only verification by design. |
| `R4:INFO-7` | R4 | Info | aperto | `tools/quality_smoke_test.py` | http:// URLs in tools and legacy catalog entries. Unchanged legacy URLs. |
| `R4:INFO-8` | R4 | Info | aperto | `docs/architecture/GTFS_SOURCE_ADAPTERS.md` | Wording drift in architecture documents. Wording drift persists and widened by PR#32. |
| `R4:INFO-9` | R4 | Info | parziale | `alexa-companion/server/voice/alexaSkill.test.ts` | Alexa skill test suite does not exercise linked users, slots, SSML, LaunchRequest or Fall…. Fallback, routeName matching, plural suffix and card-content cases are untested. |
| `R4:L-I-8` | R4 | Info | aperto | `apple-watch/project.yml` | Same bundle id it.binario.watch defined by two contradictory project.yml (watch-only vs i…. The identity conflict is unresolved. The integrated id also breaks the parent-prefix rule (AI-NF-2), which forces a rename of the integrated Watch id… |

## Appendice C — Finding precedenti risolti (85)

| ID | Rapporto | Sev. | Evidenza |
|---|---|---|---|
| `R1:C1` | R1 | High | OOM risk parsing AMT GTFS pack (stop_times 97 MB / 1.35M rows materialized as L…. The parser now builds column arrays instead of List<Map>: EmbeddedGtfsRouteAdapter.kt:248-270 parse() uses BoundedGtfsZipScanner, and Compa… |
| `R1:C2` | R1 | High | Network on main thread in European Sleeper fallback feedBytes() (no Dispatchers…. RemoteGtfsFallbackDataSource.kt:186-188 `val bytes = withContext(ioDispatcher) { downloader.open(source, allowBestEffortFallback = true).us… |
| `R1:V1` | R1 | High | Manus dev runtime (vitePluginManusRuntime/jsxLocPlugin) shipped in voice-bridge…. vite.config.ts:7 `plugins: [react(), tailwindcss()]`: no vitePluginManusRuntime or jsxLocPlugin. client/src/main.tsx:40-53 uses httpBatchLi… |
| `R1:V2` | R1 | High | Alexa OAuth authorize issues code on GET with session cookie, no consent page a…. alexa.ts:432-472: GET /api/alexa/oauth/authorize only renders the consent page (`renderConsentPage({... token: consentToken(nonce, user.id)… |
| `R3:N-C1` | R3 | High | Companion legacy reminder 'migration' cancels all existing notifications (non-c…. planned-reminder-service.ts:133-139 legacy set = stored ids or Binario-titled non-canonical requests only; :164-167 unknown/expired/orphan … |
| `R4:H-2` | R4 | High | iOS project compiles embedded Watch target in Swift 6 mode but RefreshBinarioIn…. Fixed in PR#32 by 15fc29e. RefreshBinarioIntent.swift:15-21 is now `static var title: LocalizedStringResource { "watch.intent.refresh.title… |
| `R1:C5` | R1 | Medium | Heavy GTFS indexing, RFI Jsoup daily-page parse and reconciler run on caller (M…. EmbeddedGtfsRouteAdapter.kt:96, :117, :145, :184 and :202 wrap indexing/filtering in `withContext(parsingDispatcher)` (Default). RfiProgram… |
| `R1:C6` | R1 | Medium | Companion: deleting a planned route does not cancel its scheduled reminder (loc…. companion-expo/app/(tabs)/alerts.tsx:91 confirmDelete → `deleteLocalPlannedRoute({ routeId: route.id, cancelReminder: cancelPlannedRouteRem… |
| `R1:C7` | R1 | Medium | Companion: 'Cancella archivio locale' leaves itineraries (station-name labels) …. local-journey-privacy.ts:6 `clearItineraries: () => Promise<unknown>;` (mandatory) and :10 `if (typeof dependencies.clearItineraries !== "f… |
| `R1:D-5` | R1 | Medium | voice-bridge drizzle journal lists only 0000/0001: migration 0002 (binario_prof…. _journal.json:4-102 lists 0000-0013, including 0002_sweet_rachel_grey. Executed a journal-driven drizzle `migrate()` on an empty MariaDB 10… |
| `R1:F1` | R1 | Medium | Wear OS dead end-to-end: phone/watch applicationId mismatch in both lines (it.b…. Both pairs now share an applicationId. Root: app/build.gradle.kts:33 and wear/build.gradle.kts:33 both set `applicationId = "it.binario.app… |
| `R1:F3` | R1 | Medium | Wear root listener DATA_CHANGED intent-filter lacks <data scheme=wear host=* pa…. wear/src/main/AndroidManifest.xml:25-30 declares `<action android:name="com.google.android.gms.wearable.DATA_CHANGED" />` with `<data andro… |
| `R1:F4` | R1 | Medium | Companion JS-native bridge targets nonexistent NativeModules.BinarioCompanionSu…. native-module-names.ts:5 `binarioWidgetBridgeModuleName = "BinarioWidgetBridge"`; native-surface-bridge.ts:7 `NativeModules[binarioWidgetBr… |
| `R1:F5` | R1 | Medium | Companion 'Condividi testo verso Binario' never delivers text: wrong module nam…. incoming-shared-text.tsx:7 uses `NativeModules[binarioSharedTextReceiverModuleName]` + `DeviceEventEmitter.addListener`; shared-text-contra… |
| `R1:F9` | R1 | Medium | False CANCELLED for trains to 'Cancello' stations: SOPPRESS/CANCELL substring m…. OperationalStatusTerms.kt:4-10 uses a case-insensitive word-boundary regex (soppress* / cancellat*). The call sites are RfiStationBoardAdap… |
| `R1:IG-1` | R1 | Medium | surfaces/ = 117 MB of branch snapshots as plain git objects (GTFS duplicated); …. `git ls-files surfaces voice-bridge` is empty at 1d3cdee; snapshot preserved on origin/archive/surfaces-20260923 (37a408d); single canonica… |
| `R1:V3` | R1 | Medium | Alexa OAuth redirect_uri not bound to an exact allowlist (only https: check); o…. oauthContract.ts:11-18 requires https and `return allowedRedirectUris.includes(redirectUri)` (exact string match). The token exchange binds… |
| `R1:V5` | R1 | Medium | voice.unlink does not revoke live 1h stateless access tokens; every Alexa call …. routers.ts:63-66 unlink calls db.ts:492-524 revokeAllVoiceCredentialsForUser, which sets revokedAt and `credentialVersion + 1` in a transac… |
| `R1:V6` | R1 | Medium | Upsert collision on unique alexaUserHash across different Binario users resurre…. db.ts:287-307: inside `db.transaction`, it first runs `update ... set alexaUserHash: null, revokedAt: now, credentialVersion + 1 where alex… |
| `R1:V7` | R1 | Medium | Remote GTFS download (European Sleeper fallback via community mirror jbb.ghsq.d…. GtfsArtifactDownloader.kt:33 `maxContentLengthBytes: Long = 256L * 1024L * 1024L`; :60 HTTPS-only per hop, bounded redirects/loop detection… |
| `R2:A2` | R2 | Medium | Overnight trips of previous service day (times >= 24:00) lost: only serviceRuns…. EmbeddedGtfsRouteAdapter.kt:405-412 evaluates serviceDates = query.serviceDate, minusDays(1) and minusDays(2); :419 `serviceDate.atStartOfD… |
| `R2:B1` | R2 | Medium | No BackHandler: system Back from Results/Detail closes app (visible with predic…. MainActivity.kt:373-384: DETAIL returns to detailReturnScreen and RESULTS returns to PLANNER via `BackHandler(enabled = screen == Screen.DE… |
| `R2:B11` | R2 | Medium | Alarms: (a) no BOOT_COMPLETED reschedule, (b) stop alarm impossible after midni…. (a) AndroidManifest.xml:88-94 declares CommuterAlertRestoreReceiver for BOOT_COMPLETED and MY_PACKAGE_REPLACED, handled at CommuterAlerts.k… |
| `R2:B4` | R2 | Medium | Static shortcut 'Ricerca treno' is a no-op: binario://train/search without numb…. shortcuts.xml uses the train-search URI binario://train/search. BinarioDeepLink.kt:58 `if (rawQuery == null) return BinarioDeepLink.TrainSe… |
| `R2:B7` | R2 | Medium | markMatchingRoutesUnavailable marks all route widgets unavailable (no matchesRo…. RouteWidgetProvider.kt:127-133 markMatchingRouteDirectionUnavailable applies the error only when `configuration.matchesRouteDirection(route… |
| `R2:B8` | R2 | Medium | App refresh overwrites widget-chosen direction (saveSnapshotForMatchingRoutes, …. RouteWidgetProvider.kt:118-124 saveSnapshotForMatchingRoutes checks matchesRouteDirection against WidgetStateStore.displayedDirection. save… |
| `R2:B9` | R2 | Medium | Widget: no periodic refresh (updatePeriodMillis=0, OneTimeWork without constrai…. RouteWidgetProvider.kt:349-355 schedules periodic work every 15 min with a NetworkType.CONNECTED constraint (:337-340) and exponential back… |
| `R2:C1` | R2 | Medium | No release buildTypes in app/wear: no R8/shrink/proguard, no release signing, l…. app/build.gradle.kts:73-85 and wear/build.gradle.kts:52-64 `isMinifyEnabled = true`, `isShrinkResources = true`, proguardFiles(...), signin… |
| `R2:C4` | R2 | Medium | Companion ships Expo template placeholder icon/splash with same 'Binario' label…. app.config.ts:6 `name: "Binario Companion"`, :12-17 brand icon/splash under assets/brand, :48-51 adaptiveIcon; strings.xml:2 `<string name=… |
| `R2:C5` | R2 | Medium | Alexa skill not certifiable: no Stop/Cancel/Help/Fallback intents or SessionEnd…. alexa.ts:56-65 HANDLED_INTENTS includes Help, Stop, Cancel, NavigateHome and Fallback. In alexa.ts:261-312, Stop/Cancel/NavigateHome end th… |
| `R3:N-A1` | R3 | Medium | alexa-companion CSRF on input-less tRPC mutations (voice.unlink, auth.logout) v…. cookies.ts:17 `sameSite: "lax"`. app.ts:49-76 trpcMutationCsrfGate requires POSTs to be `application/json` and same-origin and rejects `sec… |
| `R3:N-A2` | R3 | Medium | alexa-companion migration lineage (0000-0003) diverges from historical WebDev D…. The journal keeps the historical 0000-0011 tags and timestamps, adds the additive bridge 0012 (0012_...sql:1-86, IF NOT EXISTS) and then 00… |
| `R3:N-D1` | R3 | Medium | Stop-presence LRU cache capped at 5 entries for 10 packs: 0% hit rate, all zips…. EmbeddedGtfsRouteAdapter.kt:57-61 `stopPresenceCacheCapacity = packs.size.coerceAtLeast(1)` with removeEldest when size > capacity, so all … |
| `R3:N-D2` | R3 | Medium | Android->Wear snapshot rejected fail-closed when notice truncation leaves trail…. AndroidWearSnapshotContract.kt:71 `private fun optional(value: String, limit: Int): String = value.replace(Regex("\\s+"), " ").trim().take(… |
| `R3:N-D3` | R3 | Medium | PutDataMapRequest without setUrgent(): delivery deferrable ~30 min vs payload T…. WearSnapshotPublisher.kt:135-146: `PutDataMapRequest.create(SNAPSHOT_PATH)...asPutDataRequest().setUrgent()`, used for both operational and… |
| `R3:N-D5` | R3 | Medium | B10 persists: WidgetConfigureActivity instantiates TrainViewModel (network refr…. WidgetConfigureActivity.kt:59 `TrainViewModel(application, enableStartupEffects = false)`. TrainViewModel.kt:165-179 gates the Wear effects… |
| `R3:N-G1` | R3 | Medium | tools/verify_privacy_policy.py fails at HEAD (fragments moved to strings.xml / …. `python3 tools/verify_privacy_policy.py` → 'Privacy-policy static verification passed.' (executed); wired in android-ci.yml:99-100 and comp… |
| `R3:N-G2` | R3 | Medium | requirements-ci.txt has only manylinux x86_64 PyYAML hash: --require-hashes mus…. requirements-ci.txt:1-4 `--only-binary=PyYAML`, hashes ba1cc08a… and fc09d0aa…; pip download confirms fc09d0aa… = pyyaml-6.0.3-cp312-cp312-… |
| `R4:M-1` | R4 | Medium | v0.16.3 backup (schemaVersion 1) not importable: legacy station slugs not mappe…. b21f6d2 (PR#33): LegacyBackupStationIds.kt:5-31 maps all 26 v0.16.3 slugs, applied only when schemaVersion == 1 (:33-34). LocalDataBackup.k… |
| `R4:M-10` | R4 | Medium | alexa: dashboard session unverifiable when upstream profile name is empty (sign…. Fixed by 069ba85 (PR#33). sdk.ts:214-218 replaces `!isNonEmptyString(name)` with `(name !== undefined && typeof name !== "string")`, and sd… |
| `R4:M-11` | R4 | Medium | alexa: production server bundle statically imports vite (devDependency): pnpm s…. Fixed by 96e72fd (PR#33). Code changes: - app.ts:8 imports `./static`. - app.ts:120-122 `const { setupVite } = await import("./vite")` runs… |
| `R4:M-16` | R4 | Medium | RFI periodicity: FEST evaluated before SAB/DOM so combined values ('SAB E FEST'…. Fixed by 179d2b4 (PR#33): day tokens (SAB/SABATO/DOM/DOMENICA) and the holiday token are combined as `matchesDay // matchesHoliday` (Italia… |
| `R4:M-4` | R4 | Medium | Agenda export/import/editor state in non-retained remember: rotation/process de…. Fixed by 5e4b9a7 (PR#33): MainActivity.kt:277-279 creates the ViewModel with `viewModel(factory = PlannedJourneyViewModel.factory(...))` an… |
| `R4:M-5` | R4 | Medium | Wear Tile keeps showing expired snapshot up to ~30 min after watch process deat…. Fixed in commit 910a322 (PR#33), with localization later in 0dde316. BinarioTileService.kt:26 `.setTileTimeline(binarioTileTimeline(snapsho… |
| `R4:M-6` | R4 | Medium | Companion WearableListenerService calls startActivity for 'open checklist': blo…. BinarioWidgetBridge.kt:185-190 the WearableListenerService now calls `BinarioChecklistHandoff.postNotification(this, event.sourceNodeId, ev… |
| `R4:M-8` | R4 | Medium | Companion widget plugin strips blockedPermissions tools:node=remove entries: AC…. with-binario-android-widget.cjs:1050-1053 filter keeps `permission.$?.["tools:node"] === "remove" // !blockedProductionPermissions.has(...)… |
| `R4:M-9` | R4 | Medium | Companion route/itinerary/template loaders all-or-nothing: one mistyped field m…. local-routes.ts:11-20 `load()` returns `{ routes: ..., writable: routes.length === parsed.length }` and `{ routes: [], writable: false }` o… |
| `R1:ARCH-3` | R1 | Low | Three incompatible watch snapshot schemas (Kotlin flat DataMap, Swift nested JS…. All producers emit the same closed flat v1 key set: Android AndroidWearSnapshotContract.kt:20-48; companion TS companion-wear-contract.ts:1… |
| `R1:ARCH-4` | R1 | Low | Swift enums (BinarioFreshness) have no unknown case: a new producer value rejec…. Fixed in the pre-existing layer (3470a71, 2026-09-23); no regression in PR#33, PR#34 or PR#32. WatchModels.swift:17-31 define protocol Unkn… |
| `R1:C3` | R1 | Low | Wear root DataLayer listener filters only uri.path, not event.type: TYPE_DELETE…. wear/src/main/java/it/binario/wear/sync/WearDataLayerListenerService.kt:16-25: `dataEvents.filter { event -> event.dataItem.uri.path == SNA… |
| `R1:C4` | R1 | Low | Wear LazyColumn key = train number: two services with same number crash watch a…. wear/src/main/java/it/binario/wear/WearMainActivity.kt:66-69: `itemsIndexed(items = snapshot.services, key = { index, service -> "$index-${… |
| `R1:IG-3` | R1 | Low | GTFS SHA256SUMS stale (alto-adige-sta) and no code/test/CI verifies manifest/ch…. `sha256sum -c SHA256SUMS` 10/10 OK; tools/verify_embedded_assets.py:143-159 cross-checks manifest/ZIP/SHA256SUMS (android-ci.yml:93-97) and… |
| `R1:IG-4` | R1 | Low | CI: only pull_request/workflow_dispatch triggers, no gradle wrapper-validation,…. android-ci.yml:3-55 pull_request + push (main, stabilization/**) + workflow_dispatch; android-ci.yml:73-76 `gradle/actions/wrapper-validati… |
| `R1:IG-5` | R1 | Low | Repo hygiene: 51 root .md files, session todo duplicated 3x, app.config.ts with…. Root .md files = 3; no tracked todo files; companion-expo/app.config.ts ends with ";\n"; AndroidManifest.xml:1 xmlns:tools is now used by `… |
| `R1:IG-7` | R1 | Low | Disabling Wear sharing does not delete published DataItem (route label persists…. Default is off: IntegrationSettings.kt:10 `val wearSnapshotSharingEnabled: Boolean = false` and :20 `getBoolean(KEY_WEAR_SHARING, false)`. … |
| `R1:MIN-1` | R1 | Low | Swiss GTFS-RT: manual Accept-Encoding gzip disables transparent decompression, …. SwissGtfsRealtimeAdapter.kt:251-257 decodeContentEncoding checks the gzip magic 0x1f 0x8b and inflates, and it is applied before parseFrom … |
| `R1:MIN-2` | R1 | Low | SNCF GTFS-RT stopSequence.takeIf{>0} drops first-stop update (SNCF numbers from…. SncfGtfsRealtimeAdapter.kt:135 `val sequence = stop.stopSequence.takeIf { stop.hasStopSequence() }` keeps stop_sequence 0. |
| `R1:MIN-3` | R1 | Low | Companion launcher shortcut binario-companion://plan and settings '/guide' rout…. binario_shortcuts.xml:4 plan shortcut `android:data="binario-companion://"` (Home hosts the planner), :7 `binario-companion://alerts`; app/… |
| `R1:MIN-4` | R1 | Low | Companion Sync section entirely stub: companion-api.ts always false/null/[], re…. companion-api.ts is no longer tracked; settings.tsx:95 renders a static card `t("settings.remoteSync")`/`t("settings.remoteUnavailable")`/`… |
| `R1:MIN-5` | R1 | Low | Companion Agenda loads once with useEffect([]) and is stale after saving from H…. alerts.tsx:64 `useFocusEffect(useCallback(() => { void refresh(); }, [locale]));` reloads routes and scheduled ids on every focus (fix comm… |
| `R1:MIN-6` | R1 | Low | Companion timer overflow: delay > 2^31-1 ms (routes > ~24.8 days ahead) fires i…. network-status.ts:4 `maxTimerDelayMs = 2_147_483_647`, :11-13 `clampTimerDelay`; index.tsx:50-55 `if (msUntilSnapshotExpiry(snapshot.proven… |
| `R1:PERF-2` | R1 | Low | 55 MB GTFS assets in APK, ~25-30 MB removable (unread ARST shapes.txt, AMT not …. du -sh app/src/main/assets/gtfs = 15M (10 ZIPs = 14,654,203 B vs ~55 MB in R1). manifest.json: "schemaVersion": 4, "filteringVersion": 2, e… |
| `R1:S11-2` | R1 | Low | Confirmed dead UI code: NextDepartureHero and SearchScreen composables, route_w…. NextDepartureHero and SearchScreen are gone (removed in 47fc4e0). res/layout contains only route_widget.xml (route_widget_compact.xml remov… |
| `R1:S11-3` | R1 | Low | Residual of 5/9 OperatorId('other') claim: StationBoardInterop maps OTHER to no…. StationBoardInterop.kt:158 maps `RailOperator.OTHER -> null`, and StationBoardRealtimeReconciler.kt:396 does the same. bestMatch at :311-31… |
| `R1:T-5` | R1 | Low | Root Wear listener declares android:permission=com.google.android.gms.wearable.…. wear/src/main/AndroidManifest.xml:25 reads `<service android:name=".sync.WearDataLayerListenerService" android:exported="true">` with no an… |
| `R1:V8` | R1 | Low | voice-bridge hardening residuals: unauthenticated /manus-storage presigned URLs…. No storage proxy route remains (app.test.ts 'non espone proxy storage' passes). The session is cookie-only (sdk.ts:267-272) with a 30-day T… |
| `R2:A6` | R2 | Low | Device time zone treated as Europe/Rome in planning and alarms (LocalDateTime.n…. RailTime.kt:13 `val defaultZone = ZoneId.of(Europe/Rome)`. JourneyQuery.now (TrainDataSource.kt:249-252) uses RailTime.defaultZone, and so … |
| `R2:A7` | R2 | Low | RatePolicy/RetryPolicy/CircuitBreakerPolicy never applied at runtime; RfiSupple…. RfiSupplementalHubTimetableAdapter.kt:43-47 uses BoundedSingleFlightCache with an 8-entry, 6 h TTL, keyed at :62. The per-refresh re-downlo… |
| `R2:A9` | R2 | Low | ExternalRailCredentials is a data class: toString prints API keys. AuthorizedExternalProviderFactory.kt:13-19: ExternalRailCredentials is a plain class whose `toString()` returns 'ExternalRailCredentials(<r… |
| `R2:B15` | R2 | Low | No string resources: 0 stringResource, 140 literal Text(...), no values-*, hard…. MainActivity.kt has 379 stringResource( calls and no literal Text('...') (grep). values-en/de/fr/es exist with a parity test. The notificat… |
| `R2:B2` | R2 | Low | Deep link re-consumed on every recreation; standard launchMode makes onNewInten…. AndroidManifest.xml:33 singleTop, so the widget's NEW_TASK/CLEAR_TOP intent reaches onNewIntent (MainActivity.kt:235) instead of recreating… |
| `R2:B5` | R2 | Low | Widget tap ignores EXTRA_APPWIDGET_ID: lands on planner, not on that widget's r…. RouteWidgetProvider.kt:278-284 openAppIntent puts EXTRA_APPWIDGET_ID. MainActivity.kt:2395-2401 parseWidgetDeepLink builds WidgetRoute(rout… |
| `R2:C9` | R2 | Low | In-app privacy notice omits default-ON Wear sharing, third-party hosts (RFI/Via…. IntegrationSettings.kt:10 `wearSnapshotSharingEnabled: Boolean = false`, so Wear sharing is now opt-in. The in-app notice (MainActivity.kt:… |
| `R3:LOW-1` | R3 | Low | alexa: axios error logged with jwtToken/code in config.data. oauth.ts:152 `console.error("[OAuth] Callback failed", safeErrorDetails(error))`. safeError.ts:39-56 returns only code, message, status, me… |
| `R3:LOW-11` | R3 | Low | companion: extra.binarioApiUrl dead config. app.config.ts:32-34 `extra: { binarioDataContract: "companion-snapshot-v1" }` — binarioApiUrl removed; no binarioApiUrl reference remains i… |
| `R3:LOW-12` | R3 | Low | contract: empty routeId/routeLabel accepted by TS/companion but rejected by wea…. Every decoder now requires a non-empty route identity: root Kotlin WearSnapshot.kt:130-131 plus :210-211 (`isNotBlank()`); companion Kotlin… |
| `R3:LOW-21` | R3 | Low | Android: grep-based configuration tests lock in standalone=false. Unchanged since a5b6808, which R4 already marked fixed. standalone=false is still asserted (verify_android_release_config.py:59, WearDataLa… |
| `R3:LOW-26` | R3 | Low | governance: no Gradle verification-metadata.xml. gradle/verification-metadata.xml:9 `<verify-metadata>true</verify-metadata>`; companion generated file identical to native source (cmp); CI… |
| `R3:LOW-27` | R3 | Low | governance: default 14 vs 30 days validity window between script and docs. verify_embedded_assets.py:23 `DEFAULT_MIN_VALID_DAYS = 30`; build_gtfs_packs.py:1152 `--min-valid-days … default=30`; GTFS_PACK_REFRESH.md:… |
| `R3:LOW-3` | R3 | Low | alexa: isSecureRequest trusts raw x-forwarded-proto. cookies.ts:3-9 `if (req.protocol === "https") return true; return false;`. Express derives protocol from X-Forwarded-Proto only through the… |
| `R3:LOW-4` | R3 | Low | alexa: recordAlexaVerification not transactional (V6 residual). db.ts:287-307 recordAlexaVerification wraps the detach UPDATE and the upsert in `db.transaction(async tx => ...)`. It was exercised on Mari… |
| `R3:LOW-7` | R3 | Low | companion: shared text re-captured on Activity recreation. MainActivity.kt:24 `if (savedInstanceState == null) BinarioSharedTextStore.capture(this, intent)`; onNewIntent :28-32 captures only genuine… |
| `R3:LOW-8` | R3 | Low | companion: TS normalize() can produce invalid string (trim before slice). companion-wear-contract.ts:58-60 `return value.trim().replace(/\s+/g, " ").slice(0, maxCharacters).trim();` (final trim after slice), mirro… |
| `R3:LOW-9` | R3 | Low | companion: unhandled promise rejections in alerts.tsx toggle/delete. alerts.tsx:66-88 toggle wrapped in try/catch → `setMessage(t("alerts.reminderUpdateError"))`; :91 delete chain ends with `.catch(() => { se… |
| `R2:A3` | R2 | Medium/Low | RFI periodicity: FERIALE -> Mon-Fri (excludes Saturday), FEST -> Sunday only, n…. All three adapters now share ItalianRailServiceCalendar.runsOnDate (RfiProgrammedTimetableAdapter.kt:387-388, RfiSupplementalHubTimetableAd… |

## Appendice D — Superati, decisioni del proprietario, rimossi per scelta (35)

| ID | Stato | Nota |
|---|---|---|
| `R2:C7` | owner-decision | Wear standalone=false in both lines without documented Play/sideload distributi…. The distribution channel for both Wear APKs (Play track or sideload) is still undocumented and needs an owner decision … |
| `R4:INFO-11` | owner-decision | Wear sharing default flipped from on to off without a one-time notice (privacy-…. This is a privacy-by-default product choice. Whether to show upgraded users a one-time notice is still undecided. |
| `R4:L-G-2` | owner-decision | No LICENSE file nor README declaration; alexa-companion/package.json declares M…. Licence choice pending; MIT declaration without licence text remains inconsistent. |
| `R1:F6` | removed-by-design | Selectable widget surface setting never rendered (renderPersonalSurface has zer…. Documentation drift: PRIVACY.md:56 still says the exported JSON contains 'superficie widget'. |
| `R1:T-4` | removed-by-design | Companion wear-manifest plugin regex would not handle value=@bool/... (covered …. with-binario-wear-manifest.cjs is now dead code; de-registering it also dropped its phone debug/release signing hardeni… |
| `R2:B6` | removed-by-design | Widget targets 'Stazione' and 'Treno' never receive data (only ROUTE fetch path…. Documentation drift: README.md:29 still advertises the widget as configurable for a station or train number. Legacy v0.… |
| `R3:N-C4` | removed-by-design | Companion reminders inexact on Android 12+ (no SCHEDULE_EXACT_ALARM/USE_EXACT_A…. planner.reminderDescription (i18n.ts:216) still says "programmato sul telefono" at planning time although nothing is sc… |
| `R3:N-G3` | removed-by-design | GTFS pack pipeline reproducible only in content, not bytes (10/10 zip hashes di…. The same cross-environment byte-identity pitfall reappears in the PR#32 iOS SQLite pipeline (new finding N-CI-IOS-SQLIT… |
| `R1:F2` | superseded | Watch refresh message /binario/wear/request-refresh has no phone-side handler (…. R4:L-A-12 remains open. The watch refresh buttons (WearMainActivity.kt:38-45, :55, :82) cannot obtain fresher data; an … |
| `R1:F7` | superseded | README claims realtime absent from distributable build: live board adapters com…. README.md:79 says 'nessuna fonte realtime è attiva' for the distributed build, but the default build applies European S… |
| `R1:F8` | superseded | Apple Watch has no producer (no iOS app, snapshot never encoded) and project.ym…. Still open under R3:N-W1: the widget appex has no NSExtension (AI-NF-4), the widget does not compile (AI-NF-1), and the… |
| `R1:IG-2` | superseded | origin/main stuck at v0.16.3 with 320 unmerged commits, PR #2 draft on recovery…. Unchanged; see R4:L-G-1. |
| `R1:IG-6` | superseded | voice-bridge production HTML with literal %VITE_ANALYTICS_ENDPOINT%/%VITE_ANALY…. Harmless template residues, tracked as R4:L-X-22. |
| `R1:MIN-7` | superseded | Companion station catalog checksumSha256 hardcoded by build script regardless o…. Hardcoding in the script fixed; the apparent-integrity problem persists (stale asset checksum, no runtime or test verif… |
| `R1:MIN-8` | superseded | Android: zero BackHandler in MainActivity, system Back closes app from any inne…. Back from the top-level tabs (Operations/Agenda/Settings) still exits the app. That is standard bottom-navigation behav… |
| `R1:MIN-9` | superseded | Android: no launchMode, every shortcut/widget creates new Activity + TrainViewM…. Superseded by R2:B2, which is fixed: AndroidManifest.xml:33 `android:launchMode=singleTop`; MainActivity.kt:217 `if (sa… |
| `R1:PERF-1` | superseded | EmbeddedGtfsStationSearchIndex reopens 12 ZIPs per keystroke, inflating 53.5 MB…. The dead index and its graph wiring remain (R4:L-A-8). |
| `R1:T-2` | superseded | openService direction ambiguity for circular trips: explicit serviceDirection p…. See R3:LOW-23. |
| `R1:T-3` | superseded | Non-volatile var caches in StaRealtimeAdapter and RfiInfomobilityRssAdapter (st…. This becomes relevant only if the adapters are wired in. |
| `R2:A8` | superseded | RFI hub mapping only by origin: Hub->non-hub return leg has no Italian programm…. See R4:L-A-9. |
| `R2:B10` | superseded | WidgetConfigureActivity instantiates full TrainViewModel: network refresh, cach…. Superseded by R3:N-D5, which is fixed: WidgetConfigureActivity.kt:52-60 factory creates `TrainViewModel(application, en… |
| `R2:B13` | superseded | Station search executed inside composition on main thread each recomposition; n…. See R4:L-A-10 and R4:L-A-8. |
| `R2:C2` | superseded | No LICENSE file in repository (voice-bridge package.json declares MIT without t…. Unchanged; owner decision under R4:L-G-2. |
| `R2:C8` | superseded | Companion requests ACCESS_FINE_LOCATION (expo-location default) while using Acc…. See R4:M-8 residual (merged manifest not gated). |
| `R3:LOW-10` | superseded | companion: Kotlin tests use hardcoded golden instead of contract fixture file. Tracked as R4:L-W-2 (open). |
| `R3:LOW-15` | superseded | Android: oversize packs skipped silently without negative cache. See R4:L-A-5. |
| `R3:LOW-16` | superseded | Android: station search in composition reintroduced in Agenda. See R4:L-A-10. |
| `R3:LOW-18` | superseded | Android: BINARIO_VERSION_CODE override assigns same versionCode to app and wear. See R4:L-A-17. |
| `R3:LOW-2` | superseded | alexa: per-IP rate limit also applied to Alexa webhook (shared Amazon IPs) and …. Per-IP webhook limit, tracked in R4:L-X-5. |
| `R3:LOW-20` | superseded | Android: periodic widget worker never cancelled after last widget removed. See R4:L-A-13. |
| `R3:LOW-25` | superseded | governance: http:// in legacy probe tools. See R4:INFO-7. |
| `R3:LOW-5` | superseded | alexa: no per-user cap on routes.create. See R4:L-X-20. |
| `R3:LOW-6` | superseded | alexa: template residues (cron_ synthetic user id:-1, OWNER_OPEN_ID auto-admin,…. See R4:L-X-22 and R4:L-X-13. |
| `R4:L-G-10` | superseded | Three moderate qs/body-parser advisories in Alexa runtime below CI audit thresh…. Still present, below CI threshold. |
| `R4:L-W-1` | superseded | Watch-side view of L-A-12: refresh button republishes identical payload (duplic…. Tracked as R4:L-A-12 (open). |
