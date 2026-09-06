# Audit codice e funzionalità — Binario (5 settembre 2026)

Ramo analizzato: `audit/official-rail-sources-20260904` al commit `015b912` (app v0.20.3, versionCode 34).
Perimetro: app Android (`app/`), Wear OS (`wear/`), companion Expo (`companion-expo/`), Voice Bridge (`voice-bridge/`), Apple Watch (`apple-watch/`), `surfaces/`, tooling, CI e documentazione.

Metodo: lettura completa del codice per area, verifica diretta di ogni finding grave (grep, diff, hash, esecuzione), esecuzione dei test eseguibili nell'ambiente. Nessun file sorgente è stato modificato.

## 1. Stato verificato

| Verifica | Esito |
|---|---|
| `voice-bridge`: `vitest run` | 10 file, 24 test verdi |
| `voice-bridge`: `tsc --noEmit` | 0 errori |
| `companion-expo`: `vitest run` | 8 file, 35 test verdi |
| `companion-expo`: `tsc --noEmit` | 0 errori |
| `app` / `wear`: `testDebugUnitTest` | **non eseguibile** qui: il Maven di Google (AGP 8.13.0) non è raggiungibile dalla rete dell'ambiente. Nessuna evidenza contraria alla CI. |
| `app/src/main/assets/gtfs/SHA256SUMS` | **FAILED** su `alto-adige-sta-gtfs.zip` (hash dichiarato `15521822…`, reale `af4ed31a…`) |

## 2. Sintesi esecutiva

Il progetto ha una base solida: contratti dati espliciti, politica "fail-closed" coerente (mai inventare ritardi o binari), hardening Android corretto (cleartext off, componenti non esportati, backup escluso, PendingIntent immutabili), nessun segreto nel codice. La qualità cala però su tre fronti:

1. **Feature dichiarate che non funzionano**: refresh dal watch (nessun listener lato telefono), superficie widget selezionabile (mai renderizzata), bridge nativo del companion Expo (nomi di modulo JS/Kotlin diversi), watchOS non buildabile come app standalone.
2. **Difetti di runtime concreti** nel data layer: rischio OOM sui pack GTFS grandi, rete sul main thread nel fallback remoto, falsi "soppresso" per la stazione "Cancello", GTFS-RT svizzero che non decomprime mai il gzip.
3. **Un problema di sicurezza critico nel Voice Bridge**: il runtime di sviluppo Manus viene incluso nella build di produzione e permette a qualunque pagina di impostare il cookie di sessione via `postMessage`.

A questo si aggiunge un repository appesantito da 120 MB di copie di rami in `surfaces/` e da 51 file markdown alla radice.

## 3. Finding per area

Severità: **C** critico, **H** alto, **M** medio, **L** basso.

### 3.1 Android — data layer (`app/src/main/java/it/binario/app/data`)

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| C | `gtfs/EmbeddedGtfsRouteAdapter.kt:194-223` | `stop_times.txt` materializzato come `List<Map<String,String>>`: SNCF ha 421k righe, AMT Genova 1,35M. Stima 0,5–1,5 GB di heap, oltre il limite Android. Una tratta su un pack grande → `OutOfMemoryError` non catturato. | Indice a colonne compatte (array di `Int` per stop, sequence, minuti) o pre-filtro streaming dei trip che toccano origine/destinazione; in alternativa pre-indicizzare offline in formato binario. |
| H | `gtfs/RemoteGtfsFallbackDataSource.kt:56-79` | Download bloccante (`HttpURLConnection`) senza `withContext(Dispatchers.IO)`; chiamato da `viewModelScope` sul Main → `NetworkOnMainThreadException` inghiottita da `runCatching`. Il fallback European Sleeper non funziona mai dalla UI. Adapter e resolver ricreati a ogni chiamata (ZIP ri-parsato). | `withContext(Dispatchers.IO)` attorno a download+parse; cache dell'indice, non solo dei byte. |
| H | `assets/gtfs/SHA256SUMS`, `manifest.json`, `EmbeddedGtfsPacks.kt` | Nessun codice runtime legge manifest o checksum; `SHA256SUMS` è già disallineato (vedi §1). `GtfsArchiveInspector` calcola l'hash solo sulle entry non parsate. | Task Gradle che verifica il manifest a build time; digest sull'intero stream ZIP (`DigestInputStream`); rigenerare `SHA256SUMS`. |
| H | `live/SwissGtfsRealtimeAdapter.kt:70` | `Accept-Encoding: gzip, deflate` impostato a mano disabilita la decompressione trasparente: `FeedMessage.parseFrom` riceve gzip grezzo → sempre `PARSE_ERROR`. | Rimuovere l'header, oppure leggere `Content-Encoding` e avvolgere in `GZIPInputStream`. |
| H | `live/RfiStationBoardAdapter.kt:280-281`, `ViaggiaTrenoStationAdapter.kt:214`, `ItaloPublicStationAdapter.kt:215` | `cancelled = row.text().contains("CANCELL")` sull'intera riga, destinazione inclusa. "Cancello" e "Cancello ed Arnone" sono stazioni reali nel catalogo curato: ogni treno diretto lì diventa `CANCELLED` con `operationalStatusVerified=true`. | Cercare solo nella cella stato (`#RRitardo`, info aggiuntive) con regex a parola intera `\b(cancellato|soppresso)\b`. |
| H | `repository/RealtimeEnrichmentService.kt:61-65` | `staticFeedFor` non mappa `ch-otd-gtfs-rt`: l'enrichment svizzero è un percorso morto. `realtimeEnrichmentService`, `alertProviders`, `tripUpdateProviders`, `vehiclePositionProviders` non hanno chiamanti fuori da `data/`. | Dichiarare `staticFeedId` nel `ProviderDescriptor`; decidere se collegare alla UI o rimuovere. |
| M | `EmbeddedGtfsRouteAdapter.kt:94-101, 272-314`, parser HTML | `index.services()` (scansione trip + sort) e `Jsoup.parse` girano sul dispatcher del chiamante (Main). | `withContext(Dispatchers.Default)` nei data source, che devono possedere il proprio dispatcher. |
| M | `EmbeddedGtfsRouteAdapter.kt:289-290, 378` | Orari GTFS ≥ 24:00 appartengono al giorno di servizio precedente; alle 00:30 vengono valutati solo i `serviceRunsOn(oggi)`: i notturni spariscono. | Valutare anche D-1 con offset +1440 min. |
| M | `planned/RfiProgrammedTimetableAdapter.kt:333-335` (duplicato in `RfiSupplementalHubTimetableAdapter.kt:109-128`) | "FERIALE" mappato a lun–ven; nella convenzione ferroviaria include il sabato. Festività infrasettimanali non gestite. | `FERIALE → 1..6`, calendario festività IT, unificare le due copie. |
| M | `repository/StationBoardRealtimeReconciler.kt:310-311` + `StationBoardInterop.kt:158` | `RailOperator.OTHER` diventa `OperatorId("other")` non null: se RFI non espone il vettore, il board non matcha mai la corsa programmata. | Trattare "other" come sconosciuto (null) nel filtro. |
| M | `gtfs/GtfsArtifactDownloader.kt:60-69` | `contentLengthLong == -1` (chunked) bypassa il limite 256 MB; `readBytes()` illimitato; nessun hash pinnato; i byte remoti saltano i `GtfsSafetyLimits` dell'inspector. | `BoundedInputStream`, hash atteso opzionale, riuso dei limiti. |
| M | `Swiss/Sncf/EuropeanSleeper…Adapter.kt` | `stopSequence.takeIf { it > 0 }`: SNCF numera da 0, l'update della prima fermata viene perso. | Usare `hasStopSequence()`. |
| M | `LocalOnlyRailDataPolicy.kt:41-76`, `ProviderRegistry.kt:6-25` | Adapter ricreati a ogni chiamata: le cache TTL per istanza non colpiscono mai; `RatePolicy`, `RetryPolicy`, `CircuitBreakerPolicy` non sono letti da nessun codice runtime. | Provider singleton nel graph; wrapper `RateLimitedProvider` che applica le policy. |
| M | `gtfs/EmbeddedGtfsStationSearchIndex.kt:43-91` | Ogni digitazione riapre 12 ZIP; in `arst-gtfs.zip` `shapes.txt` (53 MB) precede `stops.txt` e viene decompresso per intero. | Cache in memoria dei `stops.txt` al primo accesso. |
| M | `StaRealtimeAdapter.kt:88-90`, `RfiInfomobilityRssAdapter.kt:73`, cache Swiss/Sncf | `var` non sincronizzati letti da coroutine parallele. | `@Volatile` o `Mutex` come già negli adapter RFI/ViaggiaTreno. |
| L | `TrainDataSource.kt:247-251` | `Clock.systemDefaultZone()` mentre il resto assume `Europe/Rome`. | Iniettare un `Clock` con zona `Europe/Rome`. |
| L | `AuthorizedExternalProviderFactory.kt:13-17` | `ExternalRailCredentials` è `data class`: `toString()` stampa le chiavi. | `override fun toString()` mascherato. |
| L | vari | Codice morto: `RailSourceQualityPolicy` (id inesistenti), `SnapshotReconciler`, `TrenitaliaViaggiaTrenoAdapter`, `AuthorizedLiveDataBoundary`, `GtfsStationCatalogProvider`, `EmbeddedGtfsPackLoader`. User-Agent incoerente (`Binario/0.15`). | Rimuovere o collegare; un solo `USER_AGENT` derivato da `BuildConfig.VERSION_NAME`. |

### 3.2 Android — app, widget, Wear (`app/src/main/java/it/binario/app/*`, `widget/`, `wear/`)

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| H | `MainActivity.kt:157` | Nessun `BackHandler` (0 occorrenze): il tasto Indietro di sistema da Risultati o Dettaglio chiude l'app. | `BackHandler` per schermata o migrazione a Navigation Compose. |
| H | `wear/WearMainActivity.kt:36-43` vs `app` | Il watch invia `/binario/wear/request-refresh`, ma nel telefono esiste solo la costante: nessun `WearableListenerService` né `MessageClient.addListener`. Il "refresh delegato al telefono" del README è un no-op. | `WearableListenerService` che riceve il messaggio, esegue `loadRoute` e `WearSnapshotPublisher.publish`. |
| H | `widget/RouteWidgetProvider.kt:296-347` | `renderPersonalSurface` non è mai invocata; `render()` ignora `PersonalTravelStore.widgetSurface()`. L'impostazione "Superficie widget" salva e non cambia nulla. | Instradare `render()` sulla superficie scelta, oppure rimuovere il pannello. |
| H | `MainActivity.kt:131, 189-201`, manifest | Deep link ri-parsato a ogni ricreazione (rotazione riporta alla schermata del link); nessun `launchMode`: ogni shortcut/widget crea una nuova `MainActivity` e un nuovo `TrainViewModel` (refresh e publish Wear duplicati); `onNewIntent` mai raggiunto. | `android:launchMode="singleTop"`, consumare il deep link solo con `savedInstanceState == null`. |
| M | `TrainViewModel.kt:449` | `toggleRoutePin` reimposta `activeRoute` a "non configurata" se la tratta attiva è una ricerca non salvata. | Aggiornare `activeRoute` solo se `route.id == activeRoute.id`. |
| M | `RouteWidgetProvider.kt:103-118, 529` | Un fallimento dell'app marca tutti i widget tratta come non disponibili; il refresh dall'app sovrascrive la direzione scelta sul widget. | Filtrare con `matchesRoute`; salvare lo snapshot solo se la direzione coincide. |
| M | `RouteWidgetProvider.kt:583` vs `:149-151` | `changePage` usa pagina fissa da 5, `render` usa `visibleRows` (2 in compatto): dopo un resize i contenuti saltano. | Memorizzare l'offset in righe e passare `visibleRows` a `changePage`. |
| M | `RouteWidgetProvider.kt:405-412`, `route_widget_info.xml` | Worker senza `Constraints(NETWORK_CONNECTED)`, `Result.retry()` offline; nessun `PeriodicWorkRequest` e `updatePeriodMillis=0`: il widget si aggiorna solo su tap. | Vincoli di rete, backoff, `enqueueUniquePeriodicWork(KEEP)` ≥ 15 min. |
| M | `StopAlarm.kt:47-48, 80`, `CommuterAlerts.kt:137` | `setAndAllowWhileIdle` è inesatto (in Doze slitta oltre i 5 min promessi); nessun receiver `BOOT_COMPLETED` (gli avvisi ricorrenti muoiono al riavvio); sveglia fermata dopo mezzanotte restituisce `null` perché usa `LocalDate.now()`. | `setExactAndAllowWhileIdle` con `USE_EXACT_ALARM`/`canScheduleExactAlarms()`; receiver di boot; nel planner provare D+1 se `expected <= now`. |
| M | `TrainViewModel.kt:714`, `MainActivity.kt:1751`, `LocalDataBackup.kt:61` | Import backup: alert sostituiti senza ripianificare gli allarmi; file letto senza limite; backup senza tratte rifiutato; `alert.time` non validato. | Cap ~256 KB, `LocalTime.parse`, ripianificazione, accettare backup senza tratte. |
| M | `TrainViewModel.kt:205-221`, `EmbeddedGtfsStationSearchIndex.kt:133-137` | Ricerca su 5.239 stazioni con due regex compilate per chiamata, invocata 5–6 volte per ricomposizione sul main thread. | Pre-normalizzare l'indice; `derivedStateOf`/`debounce` su `Dispatchers.Default`. |
| M | `WidgetConfigureActivity.kt:66` | Istanzia il `TrainViewModel` completo (con refresh tratta attiva e publish Wear) solo per l'autocompletamento. | Estrarre uno `StationSearchIndex` condiviso senza effetti collaterali. |
| M | `MainActivity.kt:157, 320` | `screen` è in `rememberSaveable` ma `selectedService` no: dopo process death `Screen.DETAIL` è una pagina bianca senza bottom bar. | `SavedStateHandle` per l'id corsa o fallback a `detailReturnScreen`. |
| M | `MainActivity.kt` (141 `Text("…")`, 0 `stringResource`) | Stringhe hardcoded in Compose, enum e ViewModel; `strings.xml` ha 20 voci; nessuna `values-en`. | Migrare a risorse per rendere possibile la localizzazione e ridurre la duplicazione app/widget/wear. |
| M | Accessibilità | `IntegrationChoice` e `OperationsSectionCard` codificano lo stato nel testo invece di `semantics { selected; role }`; colonne a larghezza fissa con `maxLines=1` senza `overflow` troncano "Soppresso" a font 1.3+; `StationBoardEntryRow` senza semantica unita; Wear usa `LazyColumn` invece di `ScalingLazyColumn` e `Card(onClick = {})` vuoto. | Semantica esplicita, `overflow = Ellipsis` o layout flessibile, `mergeDescendants`. |
| L | `TrainViewModel.kt:918-932` | `RouteStore.decodeRoute` senza `runCatching`: prefs corrotte → crash all'avvio. | Avvolgere come negli altri store. |
| L | `MainActivity.kt:589-636, 1895, 2405` | `DatePickerDialog` in `LaunchedEffect` non chiuso alla rotazione; impossibile rimuovere l'ultima tratta; `canRemove` e alert "pianificato" mostrato anche se `nextTrigger` è null. | Correzioni puntuali. |
| L | `wear/AndroidManifest.xml:4` | `INTERNET` dichiarato sul watch che non scarica dati. | Rimuovere. |

Architettura: `MainActivity.kt` (2.521 righe) contiene 30+ composable, navigazione manuale, permessi, speech, location, share; `TrainViewModel` (983 righe) istanzia 8 store con `new`, senza interfacce né `SavedStateHandle`, quindi non testabile in JVM. Codice morto: `NextDepartureHero`, `SearchScreen`, `ListHeading`, `reconcileWidgetState`, `route_widget_compact.xml`, stringhe `widget_empty_*`/`widget_column_*`. Logica duplicata: etichette stato/freshness in tre varianti (app, widget, wear), codec Base64-CSV replicato in 6 store.

### 3.3 Voice Bridge (`voice-bridge/`)

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| **C** | `vite.config.ts:153`, `client/src/main.tsx:51-58`, `server/_core/sdk.ts:266-271` | `vitePluginManusRuntime()` non ha guardia dev-only: 367 KB di runtime finiscono in `dist/index.html`. Il runtime registra `addEventListener("message")` **senza controllo di `event.origin`** e gestisce `{type:"setCookie"}` che scrive `document.cookie` e `sessionStorage["manus-cookie"]`; `main.tsx` inoltra quel valore come `Authorization: Bearer` e il server lo accetta. Qualunque pagina che apre la dashboard (nessun `X-Frame-Options`/CSP) può fissare una sessione arbitraria: session fixation / login CSRF. | Rimuovere `vitePluginManusRuntime()` e `jsxLocPlugin()` dalla build (o `apply: "serve"`); eliminare il fallback Bearer da `sessionStorage`; `helmet` con `frame-ancestors 'none'`. |
| H | `server/voice/alexa.ts:138-158`, `oauthContract.ts:15-17` | Authorize OAuth Alexa emette il code e redirige subito se c'è il cookie, senza pagina di consenso; `redirect_uri` vincolato solo a `https:`. Un attaccante può far collegare la propria Alexa all'account Binario della vittima; open redirect del code. | Allowlist dei redirect Amazon via env; consenso con POST + CSRF prima di emettere il code. |
| H | `drizzle/meta/_journal.json` | La migrazione `0002_sweet_rachel_grey.sql` (`binario_profiles`) non è nel journal e manca `0002_snapshot.json`: `drizzle-kit migrate` non la applicherà mai; `db:push` rigenererebbe un duplicato. | Rigenerare da snapshot 0001, eliminare il file orfano, separare `db:generate`/`db:migrate`. |
| M | `server/db.ts:180-182`, `schema.ts:92-93` | `ON DUPLICATE KEY UPDATE` con due UNIQUE (`userId`, `alexaUserHash`): un secondo account può riattivare il link revocato di un altro utente. `recordAlexaVerification` scrive sul DB a ogni intent. | `UPDATE … WHERE userId=?` poi insert; rifiutare `alexaUserHash` già associato ad altro utente. |
| M | `server/_core/storageProxy.ts:5-42` | `GET /manus-storage/*` non autenticato genera presigned URL per qualsiasi chiave con `BUILT_IN_FORGE_API_KEY`; non usato dall'app. | Rimuovere `registerStorageProxy` e `storage.ts`. |
| M | `server/_core/sdk.ts:180-232`, `cookies.ts:45` | JWT di sessione valido 365 giorni, non revocabile al logout, `appId` non verificato, cookie `sameSite: "none"`. | Durata ≤ 7 giorni con rotazione, verifica `appId`, `sameSite: "lax"` in produzione. |
| M | `server/_core/index.ts:41-42`, `alexa.ts:160-164` | Nessun rate limiting su `/api/alexa/*` e `/api/oauth/*`; body JSON 50 MB globale. | `express-rate-limit`; limite JSON 100 KB. |
| M | tRPC formatter | Messaggi mysql2 (con frammenti SQL) arrivano al browser. | `errorFormatter` che maschera `INTERNAL_SERVER_ERROR`. |
| M | `alexa.ts:143` | Utente non loggato durante l'authorize riceve `401` testo: il collegamento fallisce nell'app Alexa. | Redirect al login con ritorno all'URL originale. |
| L | `server/voice/crypto.ts:41` | `verifyVoiceAccessToken` senza `algorithms`: un token HS512 con lo stesso pepper viene accettato; pepper riusato per HMAC e JWT. | `algorithms: ["HS256"]`; derivare due chiavi con HKDF. |
| L | `server/_core/env.ts` | Tutte le variabili con fallback `""`; errore solo a runtime. `findAvailablePort` in produzione cambia porta silenziosamente. | Validazione zod all'avvio. |
| L | Template Manus | `llm.ts`, `imageGeneration.ts`, `map.ts`, `dataApi.ts`, `heartbeat.ts`, `voiceTranscription.ts` non importati; `index.html` carica Umami da un placeholder non risolto; `debug-collector.js` copiato in `dist/__manus__/`; `pages/ComponentShowcase.tsx` (1.437 righe), `AIChatBox`, `Map`, `ManusDialog` irraggiungibili; ~39 componenti `ui/` su 51 non usati; dipendenze inutilizzate (`@aws-sdk/*`, `framer-motion`, `add`, `pnpm`); patch `wouter` serve solo al runtime Manus. | Pulizia completa del template. |

Test: buona copertura di contratti e fail-closed; non coperti `crypto.ts`, token endpoint con credenziali, `consumeAuthorizationCode`, conflitto profilo end-to-end, `sdk.authenticateRequest`. `VERIFICA_RELEASE.md` cita 18 test, sono 24.

### 3.4 Companion Expo (`companion-expo/`)

L'app è locale-only (nessun `fetch`, nessun endpoint o chiave embedded), non un duplicato dell'app nativa.

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| **C** | `lib/native-surface-bridge.ts:4` vs `android/.../BinarioWidgetBridge.kt:25-30` | JS chiama `NativeModules.BinarioCompanionSurfaces.update({subject,dataNature,route})`; il modulo Kotlin si chiama `BinarioWidgetBridge` e richiede `{widget, watch}`. Il bridge è sempre `undefined`: widget Glance e Data Layer Wear non ricevono mai dati. | Allineare nome e shape; costante del nome in un file condiviso con test contrattuale. |
| H | `lib/incoming-shared-text.tsx:5` vs `BinarioSharedTextReceiver.kt:48` | Stesso disallineamento (`BinarioSharedTextStore` vs `BinarioSharedTextReceiver`); nessun listener per l'evento `BinarioSharedTextReceived`. Il target "Condividi" non consegna mai il testo. | Nome corretto + `NativeEventEmitter.addListener`. |
| M | `android/wear/.../BinarioVoiceRequestActivity.kt:42` | Su API 30–32 `registerReceiver` è esportato: qualunque app sul watch può inviare `VOICE_REPLY` con testo arbitrario letto da TTS come risposta Binario. | `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. |
| M | `plugins/with-binario-android-widget.cjs:347-485` | Hardening confermato (backup, cleartext, permessi, no debug signing), ma nessun `exported="false"`: le due activity Wear senza intent-filter restano esportate. | `exported="false"` o permesso signature. |
| M | `.github/workflows/companion-ci.yml:51-56` | "Require idempotent native generation" esegue `expo prebuild` senza `--clean`: prova solo l'idempotenza sul contenuto committato; `git diff --exit-code` ignora i file nuovi. | Job con `--clean` + `git status --porcelain -- android`. |
| M | `app/(tabs)/settings.tsx:175`, `binario_shortcuts.xml:3` | `router.push("/guide")` e shortcut `binario-companion://plan` puntano a route inesistenti. | Creare `app/guide.tsx`; shortcut all'index. |
| M | `app/(tabs)/alerts.tsx:41` | Agenda caricata una sola volta con `useEffect([])`: resta stale dopo un salvataggio in Home. | `useFocusEffect` come in `companion.tsx`. |
| M | `lib/companion-api.ts`, `app.config.ts:24-27` | Sezione "Sincronizzazione" interamente stub; legge `EXPO_PUBLIC_BINARIO_SYNC_URL` mentre il config espone `binarioApiUrl`; `expo-secure-store` mai importato. | Rimuovere UI e dipendenza finché non esiste il client. |
| M | `scripts/build-local-station-catalog.mjs:33`, `lib/offline-station-index.ts:13` | SHA-256 hardcoded e a runtime si verifica solo che sia esadecimale; l'hash reale del JSON è diverso. | Calcolare in build e verificare in un test. |
| M | `tests/native-surface-contract.test.ts` | 10 test su 35 sono `readFileSync` + `toContain("stringa")` sui sorgenti: passano anche con i bug C/H sopra. | Test su funzioni pure e test del plugin su un manifest fixture. |
| L | `tsconfig.json:11`, `package.json` | `tests/` escluso dal type-check; dipendenze inutilizzate (`expo-secure-store`, `expo-network`, `react-native-web`, `@react-navigation/*`); versione duplicata in 3 file; `ACCESS_FINE_LOCATION` non necessario. | Pulizia. |

### 3.5 Apple Watch (`apple-watch/`)

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| H | `project.yml:8-17` | Il target `BinarioWatch` non dichiara `dependencies: [BinarioWatchWidgets]`; l'estensione WidgetKit non ha `NSExtensionPointIdentifier`; l'app non ha `WKApplication`/`WKWatchOnly`. Così com'è non è installabile come app watchOS standalone con widget. | Completare `project.yml` e provare la generazione con XcodeGen su macOS. |
| M | `project.yml:22` | Il target test compila `Shared` e fa `@testable import BinarioWatch`: tipi definiti due volte. | Rimuovere `Shared` dai sources del test. |
| M | `Shared/WatchSnapshotStorage.swift:7-14`, `BinarioWatchWidgets.swift:19-22` | Nessun controllo TTL: `retrievedAtEpochMillis` mai confrontato con `now`; il widget mostra corse vecchie di giorni. | `isFresh(now)` in `load()` e entry "unavailable" alla scadenza. |
| M | `PhoneConnectivity.swift` | Riceve solo da un'app iOS che non esiste nel repo: mostrerà sempre "Nessuna tratta attiva". | Documentare come prerequisito o aggiungere l'app iOS minima. |
| L | `RefreshBinarioIntent.swift:7-10` | `_ = WatchSnapshotStorage.load()` è un no-op. | `WidgetCenter.reloadAllTimelines()` e dialog. |

### 3.6 Repository, build, CI, documentazione

| Sev | Dove | Problema | Fix proposto |
|---|---|---|---|
| H | `surfaces/` (120 MB, 1.412 file) | Tre snapshot di rami copiati come directory: `main` è identico alla radice per `apple-watch` e `voice-bridge`; `feature_data-foundations` e `work_benchmark-…` (58 MB ciascuno) duplicano i 55 MB di GTFS e divergono dalla radice (64 diff in `app`). Solo `web-voice-bridge` contiene lavoro non mergiato (20 componenti, `server/companion`, dettatura). | Convertire in tag/branch git o `git bundle` sotto LFS in `archives/`; triagiare e mergiare `web-voice-bridge`; poi `git rm -r surfaces`. |
| M | `origin/main` | `main` è fermo a v0.16.3 mentre il lavoro (v0.20.3) vive su rami di audit/recupero. | Riportare `main` alla linea corrente e proteggerlo. |
| M | Radice del repo | 51 file `.md` di verifica/versione alla radice, più `todo-xuyr2dxh.md` duplicato in tre posti. | Spostare in `docs/releases/` e `docs/verifiche/`; lasciare `README`, `CHANGELOG`, `todo`. |
| M | `.github/workflows/android-ci.yml` | Gira solo su `pull_request` e `workflow_dispatch`: i push diretti sui rami (come questo) non vengono testati. Java 17 in CI mentre il README dichiara OpenJDK 21. | Aggiungere `push` sui rami di lavoro; allineare la JDK. |
| M | `gradle.properties` | `org.gradle.workers.max=1` rallenta build e test su tutte le macchine. | Rimuovere o limitare a CI via `-P`. |
| L | `app/build.gradle.kts`, `wear/build.gradle.kts` | Versioni disallineate tra moduli (`activity-compose` 1.10.0 vs 1.13.0); nessun version catalog; `jsoup 1.18.3` e `org.json 20240303` datati. | `gradle/libs.versions.toml` condiviso. |
| L | `tools/*.py` | 18 script Python senza `requirements.txt` né test; endpoint hardcoded in 8 file. | `requirements.txt`, costanti in un modulo comune. |
| L | README | Dichiara refresh dal watch, widget con WorkManager periodico, superficie widget configurabile: oggi non corrispondono al codice (vedi §3.2). | Allineare il README dopo le correzioni o marcare come "in lavorazione". |

## 4. Cosa funziona bene

- Politica dati fail-closed coerente su tutte le superfici: mai ritardi, binari o soppressioni inventati; stato programmato/live sempre dichiarato.
- Contratti dati con provenienza per campo (`SourcedValue`, `SourceRef`), `ProviderResult` con messaggi sicuri, fetcher iniettabili e parser `internal` coperti da fixture.
- Hardening Android: cleartext off, trust store di sistema, componenti non esportati, `FLAG_IMMUTABLE`, backup e trasferimento esclusi, permessi minimi richiesti solo su azione esplicita, nessuna PII nei log.
- Nessun segreto o credenziale nel codice o nella storia visibile; `.gitignore` e LFS configurati correttamente per APK e keystore.
- Webhook Alexa con verifica firma, catena certificati, timestamp e Skill ID; code monouso con compare-and-set; `timingSafeEqual` sul client secret; concorrenza ottimistica del profilo remoto idempotente.
- Script di verifica riproducibili (`verify-release-inputs`, `verify-managed-config`) e CI con lockfile bloccato.

## 5. Piano di intervento consigliato

**Subito (sicurezza e crash)**
1. Voice Bridge: togliere il runtime Manus dalla build, eliminare il fallback Bearer da `sessionStorage`, aggiungere `frame-ancestors 'none'`.
2. Data layer: indice GTFS compatto (OOM), `Dispatchers.IO` nel fallback remoto, fix "Cancello", fix gzip svizzero, rigenerare `SHA256SUMS` e verificarlo a build time.
3. Expo: allineare i nomi dei moduli nativi e aggiungere un test contrattuale reale.

**Entro la prossima release**
4. `BackHandler`, `launchMode="singleTop"`, listener Wear lato telefono, superficie widget collegata o rimossa, allarmi esatti con receiver di boot.
5. OAuth Alexa con consenso e allowlist dei redirect; journal Drizzle corretto; rate limiting.
6. Rimuovere `surfaces/` dopo aver salvato i rami come tag e mergiato `web-voice-bridge`.

**Debito strutturale**
7. Spezzare `MainActivity.kt` in package per schermata con Navigation Compose; `TrainViewModel` con interfacce iniettate e `StateFlow<UiState>`; stringhe in risorse.
8. Test: `TrainViewModel` con fake store, Robolectric per widget e allarmi, test Compose per navigazione e semantica; test del data layer con dimensioni realistiche (mezzanotte, `stop_sequence=0`, "FERIALE" di sabato).
9. Version catalog Gradle, CI su `push`, JDK allineata, riordino della documentazione alla radice.
