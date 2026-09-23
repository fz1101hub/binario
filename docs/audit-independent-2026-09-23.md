# Audit indipendente — Binario (23 settembre 2026)

Ramo analizzato: `audit/official-rail-sources-20260904` al commit `0b8f3c98487d24171053f318a86e538618218edb` (HEAD verificato con checkout diretto; app v0.20.3, versionCode 34). Il ramo contiene `origin/main` come antenato più 320 commit; 18 commit separano `015b912` (base dell'audit del 5 settembre) da HEAD.

Perimetro principale: `app/`, `wear/`, `companion-expo/`, `voice-bridge/`, `apple-watch/`, `.github/workflows/`, file Gradle, manifest, configurazioni, test e tooling. `surfaces/`, `archives/` e `artifacts/` sono valutati solo per governance e igiene del repository, non come codice corrente.

Metodo: analisi indipendente completata **prima** di leggere `docs/audit-2026-09-05.md` (quattro passate parallele su app Android/data layer, companion-expo, voice-bridge, wear+watch+contratti, più verifiche dirette di build, hash, CI e governance); solo dopo, confronto puntuale con il rapporto precedente. Ogni claim grave riportato qui è stato ri-verificato sul codice a HEAD. Le verifiche non eseguibili in questo ambiente sono dichiarate come tali e non trasformate in fatti.

## 1. Verifiche eseguite ed esiti

| Verifica | Esito |
|---|---|
| voice-bridge: `tsc --noEmit` / `vitest run` / `vite build` produzione | 0 errori / **24 test verdi** / build ok |
| HTML generato (`dist/public/index.html`, 368 KB) | contiene il runtime Manus inline e i placeholder `%VITE_ANALYTICS_*%` non risolti (vedi V1, IG-6) |
| companion-expo: `tsc` / `vitest` / `verify:managed-config` / `verify:release-inputs` | 0 errori / **35 test verdi** / entrambi gli script passano |
| `expo prebuild --platform android` + `git diff --exit-code -- android` (stesso comando della CI) | **idempotente** (exit 0) |
| Checksum GTFS: 12 pack vs `manifest.json` (sha256 ricalcolati) | **12/12 coerenti**; catalogo stazioni coerente (`58b44188…`) |
| `sha256sum -c app/src/main/assets/gtfs/SHA256SUMS` | **FAILED** su `alto-adige-sta-gtfs.zip` (dichiara `15521822…`, reale `af4ed31a…` = quello del manifest — è `SHA256SUMS` a essere stale dal commit `10955e3`) |
| Dimensioni GTFS | 55 MB in `app/src/main/assets`; dentro `amt-genova-casella-gtfs.zip`: `stop_times.txt` **97,1 MB / 1.355.184 righe**; dentro `arst-gtfs.zip`: `shapes.txt` **53,5 MB** mai letto dai parser |
| 56 binari in `archives/`+`artifacts/` (puntatori LFS) vs `SHA256SUMS.txt` | **56/56 OID coerenti** |
| Confronto `015b912..HEAD` | diff analizzato riga per riga (escludendo il rapporto di audit fino a fine analisi indipendente) |
| Migrazioni DB (`voice-bridge/drizzle`) | analizzate 0000–0002 + journal (vedi D-5) |
| Gradle Android/Wear in locale | **Non eseguibile**: nessun SDK Android nell'ambiente e `dl.google.com` bloccato dal proxy (403). **Compensato con evidenza verificata**: run CI reali Android CI #193 (id `34070605193`) e Companion CI #25 (id `34070605138`), entrambi `conclusion: success` **esattamente su HEAD 0b8f3c9** (test unitari, lint e assemble di app+wear su entrambe le pipeline) |
| Build Xcode Apple Watch, test su hardware (telefono/watch), fetch degli oggetti LFS, ispezione interna dell'AAR `play-services-wearable` | **Non eseguibili** in questo ambiente; i giudizi relativi sono dichiarati statici |

## 2. Sintesi esecutiva

La base del progetto è genuinamente solida: politica dati fail-closed coerente su tutte le superfici (mai ritardi, binari o soppressioni inventati), hardening Android corretto, nessun segreto nel codice o nella storia, CI verde sull'esatto commit auditato. I problemi si concentrano su quattro fronti:

1. **Sicurezza**: il runtime di sviluppo Manus è incluso nella build di produzione del Voice Bridge e accetta `postMessage` senza controllo di origin per impostare il cookie di sessione (V1); l'authorize OAuth Alexa emette il code senza consenso né CSRF (V2).
2. **Crash e affidabilità**: rischio OOM fortemente dimostrato sul pack GTFS AMT (C1); rete sul main thread nel fallback remoto (C2); migrazione DB orfana dal journal (D-5).
3. **Feature dichiarate ma non funzionanti**: l'intera catena Wear (root e companion) è inerte per mismatch di `applicationId` più handler mancanti (F1–F3); il bridge JS↔nativo del companion punta a un modulo inesistente (F4); il target "Condividi" non consegna mai il testo (F5).
4. **Governance**: `main` fermo a v0.16.3 con 320 commit non mergiati, zero tag, 117 MB di snapshot duplicati in `surfaces/`, e un modulo `voice-bridge/` in-repo più arretrato dello snapshot `surfaces/web-voice-bridge` (3 vs 12 migrazioni).

## 3. Vulnerabilità sfruttabili

### V1 — ALTA · Runtime Manus in produzione: `postMessage setCookie` senza controllo di origin → session fixation / login CSRF

1. **Severità**: Alta. (Il rapporto del 5/9 la marcava Critical; ricalibrata: il meccanismo è dimostrato nel bundle, ma il danno richiede che la vittima usi poi la sessione avvelenata — non è una compromissione sistemica diretta.)
2. **File**: `voice-bridge/vite.config.ts:153` (`vitePluginManusRuntime()` e `jsxLocPlugin()` senza guardia dev-only). Verificato nel `dist/public/index.html` generato in questo ambiente: `CookieManager.init()` registra `listenMessage({type:"setCookie"})` che scrive `document.cookie` e `sessionStorage["manus-cookie"]`; il listener `window.addEventListener("message", …)` filtra solo `data.type === "SpacePreviewerChannel"` e l'endpoint di destinazione — **nessun confronto con `event.origin`**. `client/src/main.tsx:51-58` inoltra `manus-cookie` come `Authorization: Bearer`; nessun `helmet`/`frame-ancestors`/`X-Frame-Options` nel server (`server/_core/index.ts`).
3. **Percorso runtime**: una pagina ostile apre o inquadra la dashboard e invia `postMessage({type:"SpacePreviewerChannel", payload:{type:"setCookie", …}})` → cookie di sessione arbitrario fissato nel browser della vittima.
4. **Prova**: build di produzione rieseguita in questo ambiente e bundle ispezionato: gestore `setCookie` e assenza del check di origin sono presenti nell'HTML servito.
5. **Condizioni**: vittima con la dashboard apribile/inquadrabile da una pagina attaccante (nessuna difesa frame); l'attaccante fornisce un proprio token valido.
6. **Impatto reale**: la vittima opera dentro la sessione dell'attaccante; le tratte personali che salva (nomi di stazione) diventano leggibili dall'attaccante.
7. **Fix minimo**: rimuovere `vitePluginManusRuntime()` e `jsxLocPlugin()` dalla build di produzione (o `apply: "serve"`); eliminare il fallback Bearer da `sessionStorage`; aggiungere CSP `frame-ancestors 'none'`.
8. **Test di regressione**: test sulla build che asserisce l'assenza di `manus-runtime`/`setCookie` in `dist/index.html`; test HTTP sull'header CSP.

### V2 — ALTA · OAuth Alexa: authorize senza consenso né CSRF → collegamento account forzato

1. **Severità**: Alta (divulgazione cross-utente di dati personali, percorso dimostrato nel codice).
2. **File**: `voice-bridge/server/voice/alexa.ts:138-158`; `voice-bridge/server/_core/cookies.ts:45` (`sameSite: "none"`).
3. **Percorso runtime**: `GET /api/alexa/oauth/authorize` con cookie di sessione valido emette *immediatamente* il code e fa 302 al `redirect_uri` — nessuna pagina di consenso, nessun token anti-CSRF; con `SameSite=None` il cookie parte anche su navigazione cross-site.
4. **Prova**: handler verificato riga per riga. Scenario: l'attaccante avvia il linking dalla *propria* app Alexa, cattura l'URL di authorize (redirect Amazon reale + `state` dell'attaccante) e lo fa aprire alla vittima loggata: il code emesso appartiene alla **vittima** e il linking si completa sull'Alexa dell'attaccante (`state` protegge Amazon come client, non questo authorization server).
5. **Condizioni**: `ALEXA_*` e `VOICE_TOKEN_PEPPER` configurati (configurazione di produzione); vittima con sessione dashboard attiva che apre un link.
6. **Impatto reale**: l'attaccante chiede ad Alexa "le mie tratte" e ottiene le tratte salvate della vittima (`alexa.ts:76-87`); il collegamento persiste finché la vittima non lo nota.
7. **Fix minimo**: pagina di consenso autenticata; emissione del code solo da POST con token CSRF di sessione.
8. **Test di regressione**: GET authorize con sessione e parametri validi NON deve rispondere 302 con un code; emissione solo dal POST protetto.

### V3 — MEDIA · `redirect_uri` non vincolato a un'allowlist

- **File**: `voice-bridge/server/voice/oauthContract.ts:15-20` (unico vincolo: protocollo `https:`); `alexa.ts:154-157` vi redirige col code.
- **Percorso**: `/api/alexa/oauth/authorize?…&redirect_uri=https://attacker.example&state=x` aperto da una vittima loggata → il code atterra sull'origin dell'attaccante. Anche open redirect per utenti autenticati.
- **Condizioni/limiti**: lo scambio a `/token` richiede comunque `client_secret` (confronto `timingSafeEqual`, `alexa.ts:164`) e il code è vincolato a `client_id`+`redirect_uri` al consumo (`db.ts:204-218`): la compromissione diretta è bloccata, per questo Media e non Alta. Resta una violazione RFC 6749 e la perdita di difesa in profondità se il client secret trapelasse.
- **Fix**: allowlist esatta dei redirect URI Amazon via variabile d'ambiente. **Test**: authorize con `redirect_uri=https://evil.example` → 400, nessuna riga code creata.

### V4 — RITIRATO (falso positivo, nessuna azione richiesta sui servizi companion)

- Una prima stesura classificava come vulnerabilità Media l'assenza di un permesso sui `WearableListenerService` esportati del companion, poi la declassava a "hardening". Entrambe le formulazioni erano **errate** e il finding è ritirato: in `play-services-wearable` 20.0.1 il binder di `WearableListenerService` verifica internamente `Binder.getCallingUid()` e accetta soltanto Google Play Services/Wear autorizzato (verifica su AAR decompilato fornita dal committente; coerente con la protezione introdotta in Play Services 8.2+ e con la documentazione ufficiale, che mostra il servizio `exported="true"` **senza** `android:permission`). Un'app terza co-installata non può quindi invocare i callback.
- Anche la raccomandazione era sbagliata nel merito: `com.google.android.gms.wearable.BIND_LISTENER` è storicamente una **action** di bind (deprecata nell'aprile 2016 a favore dei filtri fini `DATA_CHANGED`/`MESSAGE_RECEIVED` con URI), non una permission documentata. **Non** va aggiunta come `android:permission` ai servizi companion: non aggiunge protezione rispetto alla verifica UID interna e può introdurre problemi di compatibilità o di consegna.
- **Osservazione residua (da riesaminare, non un finding attivo)**: è semmai il modulo **root** a usare quel nome come permission (`wear/src/main/AndroidManifest.xml:10`, `android:permission="com.google.android.gms.wearable.BIND_LISTENER"` sul listener), e nessun `<permission>` con quel nome è definito nel repo (verificato). Richiedere una permission non definita non protegge nulla e, a seconda della versione di Android, può ostacolare proprio il bind legittimo di Play Services: da verificare e con ogni probabilità da rimuovere quando si rimetterà in piedi la superficie Wear (F1–F3). La protezione effettiva dei listener esportati è la validazione UID interna di Play Services più i requisiti di package e firma del Data Layer.

### V5 — MEDIA · `voice.unlink` non revoca l'accesso attivo; ogni chiamata Alexa "de-revoca" il link

- **File**: `voice-bridge/server/voice/alexa.ts:19-27` (`getLinkedUser` valida solo il JWT, mai `revokedAt`); `crypto.ts:28-37` (access token stateless 1h, irrevocabile); `db.ts:180-182` (`recordAlexaVerification` fa upsert con `revokedAt: null`).
- **Percorso**: l'utente esegue `voice.unlink` (`routers.ts:56-59`) → un access token in circolazione (≤1h) continua a rispondere agli intent personali, e il suo **primo uso azzera `revokedAt`**, per cui `voice.status` torna a mostrare "collegato" (i refresh token invece sono correttamente revocati).
- **Condizioni**: token emesso prima dell'unlink, entro la scadenza.
- **Impatto**: fino a 60 minuti di accesso ai dati personali dopo la revoca esplicita + stato dashboard fuorviante.
- **Fix minimo**: in `getLinkedUser` caricare la riga del link e rifiutare quando `revokedAt` è valorizzato; non azzerare `revokedAt` nell'upsert (solo nel re-link esplicito via OAuth).
- **Test**: unlink → richiesta webhook con access token ancora valido → risposta "collegamento richiesto" e `revokedAt` invariato.

### V6 — MEDIA · Collisione upsert su `alexaUserHash` tra utenti diversi

- **File**: `voice-bridge/server/db.ts:176-183` con doppio UNIQUE (`userId`, `alexaUserHash`) in `schema.ts:91-94`.
- **Percorso**: lo stesso account Alexa ricollegato a un **diverso** utente Binario → l'insert collide sull'unique di `alexaUserHash` e MySQL aggiorna la riga del *vecchio* utente (`userId` non è nel set di update): il link revocato del precedente utente viene resuscitato e il nuovo non ottiene mai la riga di stato (`voice.status` mostra "non collegato" mentre l'accesso vocale funziona, perché l'autorizzazione passa dal JWT).
- **Impatto**: integrità dei metadati e stato fuorviante; nessun accesso diretto ai dati altrui (i token restano legati all'utente corretto via `sub`).
- **Fix**: gestire il re-link esplicitamente (claim/delete della riga per `alexaUserHash` prima dell'upsert). **Test**: link utente A, revoca, link utente B con lo stesso account Alexa; A resta revocato e B ottiene la propria riga.

### V7 — MEDIA (teorica) · Download GTFS remoto non limitato né verificato

- **File**: `app/src/main/java/it/binario/app/data/gtfs/GtfsArtifactDownloader.kt:60-63` (`contentLengthLong == -1` bypassa il limite di 256 MB; l'header può mentire); `RemoteGtfsFallbackDataSource.kt:171` (`readBytes()` senza tetto sui byte effettivi).
- **Percorso**: fallback European Sleeper attivo → download dal mirror comunitario `jbb.ghsq.de` (`InternationalSourceCatalog.kt:119`), host non controllato dall'operatore; gli hash del `manifest.json` non sono mai verificati a runtime.
- **Condizioni**: server remoto malevolo o compromesso → **rischio teorico**, dichiarato come tale. Impatto: OOM-DoS o orari errati (i dati restano etichettati best-effort/DERIVED, nessuna escalation di fiducia).
- **Fix**: stream con limite sui byte effettivamente letti + fingerprint sha256 atteso (o almeno loggato). Nota positiva: le policy del downloader (HTTPS-only, allowlist `accessMode`, gate best-effort opt-in) sono corrette e coperte da test.

### V8 — BASSE · Hardening residuo del voice-bridge

- `/manus-storage/*` genera presigned URL **senza autenticazione** per chiavi arbitrarie (`storageProxy.ts:5-47`); oggi le uniche chiavi scritte hanno suffisso random, quindi l'esposizione è guess-resistant — rimuovere o proteggere.
- JWT di sessione valido **1 anno**, non revocabile: il logout cancella solo il cookie (`sdk.ts:184-197`, `routers.ts:22-28`), e il fallback `Authorization: Bearer` resta accettato.
- `verifyVoiceAccessToken` senza `algorithms: ["HS256"]` (`crypto.ts:41-44`, verificato ancora aperto a HEAD): con chiave simmetrica condivisa il rischio pratico è minimo, ma va pinnato; il pepper è riusato per HMAC e JWT (derivare due chiavi con HKDF).
- `ENV` con default `""` e nessuna validazione all'avvio (`env.ts:1-14`). Dimostrato eseguendo jose 6.1.0: con `JWT_SECRET=""` firma/verifica lanciano "Zero-length key" → il caso segreto-vuoto è un bug di **disponibilità** (fail-closed), non di forgiabilità; ma un segreto da 1 carattere è accettato e `verifySession` non confronta `appId` con `ENV.appId` né imposta issuer/audience (`sdk.ts:209-227`): un token emesso da un'altra app con lo stesso `JWT_SECRET` è accettato.
- `isSecureRequest` si fida di `x-forwarded-proto` spoofabile (`cookies.ts:11-22`); `express.json` con limite 50 MB (`index.ts:41`); nessun rate limiting su `/api/alexa/*` e `/api/oauth/*`.

## 4. Crash e perdita dati

### C1 — ALTA · Rischio OOM fortemente dimostrato parsando il pack GTFS AMT

1. **Severità**: Alta. **Natura della prova**: rischio dimostrato per via aritmetica su dimensioni misurate, **non** crash riprodotto sperimentalmente — l'esecuzione reale con heap limitato non è stata effettuata in questo ambiente e resta il passo di conferma consigliato.
2. **File**: `app/src/main/java/it/binario/app/data/gtfs/EmbeddedGtfsRouteAdapter.kt:194-232` (`parse`/`parseCsv` materializzano ogni riga di stop_times/trips/… come `List<Map<String,String>>`, senza cap sul numero di righe).
3. **Percorso runtime**: ricerca tratta i cui estremi (anche testo libero, `CustomStationStore.kt:72`) coincidono con due fermate AMT → il tier RFI fallisce (copre solo i 19 hub mappati) → `parse()` sul pack AMT. Stesso percorso nel `WidgetRefreshWorker` (`widget/RouteWidgetProvider.kt:388-397`).
4. **Prova (misurata + aritmetica)**: `stop_times.txt` = 97.113.594 byte / 1.355.184 righe (misurato con `unzip -l` e conteggio righe). 1,35 M righe × (LinkedHashMap da 7 entry + 7 String) ≈ 0,8–1,2 GB di heap Java stimati; il manifest non dichiara `largeHeap` (heap tipico Android 192–512 MB). Un eventuale `OutOfMemoryError` è un `Error` non catturato nella catena (`TrainViewModel.refresh` non ha try/catch; `viewModelScope` → crash del processo). `ch-otd` (268 k righe) è borderline su dispositivi low-RAM e la LRU tiene fino a 5 pack contemporanei (`:48-50`), aggravando.
5. **Condizioni**: qualsiasi ricerca che risolve su fermate AMT (es. "Casella Paese" → "Trensasco"); `stops.txt` di AMT elenca l'intera rete bus di Genova (2.755 fermate), quindi il matching è facile.
6. **Impatto reale**: crash molto probabile dell'app o del worker widget su input legittimo, su gran parte del parco dispositivi.
7. **Fix minimo**: ri-filtrare offline l'asset AMT alla sola ferrovia Genova–Casella (il manifest dichiara che STA/CH/ÖBB sono già stati filtrati così); fix strutturale: pre-filtro streaming dei trip che toccano origine/destinazione o indice colonnare compatto.
8. **Test di regressione**: unit JVM che esegue `loadRoute` contro uno zip sintetico da ~1,3 M righe con `-Xmx256m` (test JVM args) e deve completare.

### C2 — ALTA · Rete sul main thread nel fallback European Sleeper → feature morta + violazione di piattaforma

1. **Severità**: Alta (violazione deterministica di piattaforma che azzera una feature; percorso dimostrato).
2. **File**: `app/src/main/java/it/binario/app/data/gtfs/RemoteGtfsFallbackDataSource.kt:168-174` (verificato a HEAD, **non corretto** rispetto al 5/9): `feedBytes()` usa il downloader bloccante (`HttpURLConnection`, `GtfsArtifactDownloader.kt:46-70`) e `readBytes()` **senza** `withContext(Dispatchers.IO)`, a differenza di tutti gli adapter live.
3. **Percorso runtime**: `TrainViewModel.refresh` (viewModelScope = Main) → facade → `QualityTieredTrainDataSource.coverageFallbacks` (`LocalOnlyRailDataPolicy.kt:34-38`) → `feedBytes()` sul main thread → Android lancia `NetworkOnMainThreadException`, inghiottita dal `runCatching` in `:58-60` come "fonte non disponibile".
4. **Prova**: catena di chiamate e dispatcher verificati file per file; i test JVM (downloader fake) non possono accorgersene, ed è per questo che sono verdi.
5. **Condizioni**: qualunque richiesta dalla UI che arriva al fallback ES (dal worker widget, che gira su IO, invece funziona).
6. **Impatto reale**: il fallback European Sleeper non funziona **mai** dall'app; l'utente vede "fonte non disponibile" senza motivo apparente.
7. **Fix minimo**: `withContext(Dispatchers.IO)` dentro `feedBytes()` (una riga); idealmente cache dell'indice, non solo dei byte.
8. **Test di regressione**: `GtfsArtifactSource` fake che registra il thread/dispatcher di chiamata e lo asserisce IO.

### C3/C4 — BASSE (latenti) · Due crash teorici sul watch (nuovi)

- **C3**: `wear/src/main/java/it/binario/wear/sync/WearDataLayerListenerService.kt:14-15` filtra solo `uri.path`, mai `event.type`: un `TYPE_DELETED` (data null) fa lanciare `DataMapItem.fromDataItem`. Oggi nessun percorso di delete esiste (vedi anche IG-7), quindi teorico. Fix: guard su `TYPE_CHANGED` (il companion lo fa correttamente, `BinarioWearData.kt:36`).
- **C4**: `wear/src/main/java/it/binario/wear/WearMainActivity.kt:59` usa `key = { it.number }` nella LazyColumn: due corse con lo stesso numero → `IllegalArgumentException` (chiave duplicata) e crash dell'app watch; il decoder non deduplica (`WearSnapshot.kt:64-76`). Teorico (serve un payload con due numeri uguali). Fix: key per indice o numero+orario; test del codec con numeri duplicati.

### C5 — MEDIA · Lavoro pesante sul main thread a ogni refresh (rischio jank/ANR, non ancora profilato)

- **Natura della prova**: percorso e dispatcher verificati staticamente; **nessuna misura di profiling è stata eseguita** — l'entità del jank/ANR su dispositivo reale resta da quantificare.
- **File**: `EmbeddedGtfsRouteAdapter.kt:92-101, 272-314` — solo `parse()` è su `Dispatchers.IO`; `index.services(...)` (iterazione di tutti i trip, sort degli stop_times per trip, `Normalizer.normalize` per etichetta) gira sul dispatcher del chiamante, cioè Main (per ch-otd: 24 k trip / 268 k righe). Stesso pattern in `RfiProgrammedTimetableAdapter.kt:123-145` (Jsoup di un'intera pagina giornaliera su Main) e nel reconciler.
- **Impatto atteso**: UI bloccata per la durata dell'indicizzazione sui pack grandi; possibile ANR. **Fix minimo**: `withContext(Dispatchers.Default)` attorno a `dataSource.loadRoute` nel facade. **Test**: fake che asserisce il dispatcher; conferma con profiling su dispositivo.

### C6 — MEDIA · Eliminare una tratta non cancella il promemoria programmato (companion-expo, nuovo)

- **File**: `companion-expo/app/(tabs)/alerts.tsx:50-53` (`confirmDelete` chiama solo `removeLocalPlannedRoute(route.id)`); la mappa promemoria e la notifica OS restano intatte (`lib/planned-reminder-service.ts`).
- **Percorso**: programma un promemoria, poi "Elimina" la tratta e conferma → nessuna chiamata a `cancelPlannedRouteReminder`.
- **Impatto**: l'utente riceve sul lock screen "Binario · tratta pianificata — origine → destinazione alle HH:MM" per una tratta eliminata: fuorviante e avverso alla privacy. Le altre due vie di rimozione (toggle completato e clear di massa) cancellano correttamente: è una svista del solo percorso di delete.
- **Fix**: `await cancelPlannedRouteReminder(route.id)` in `confirmDelete`. **Test**: estrarre la logica in un helper `deleteLocalPlannedRoute` testabile che asserisce la cancellazione del promemoria.

### C7 — MEDIA · Gli itinerari sopravvivono a "Cancella archivio locale" (companion-expo, nuovo)

- **File**: `companion-expo/app/(tabs)/settings.tsx:104` chiama `clearLocalJourneyArchive` **senza** passare `clearItineraries`; `lib/local-itineraries.ts:59` (`clearLocalJourneyItineraries`) esiste e non è chiamato da alcun codice dell'app (solo dai test); anche `reconcileLocalJourneyItineraries` (`:50`) non ha chiamanti app.
- **Percorso**: l'utente conferma la cancellazione dell'archivio → `binario-local-itineraries-v1` resta su AsyncStorage con etichette che incorporano nomi di stazione ("Roma Termini → Milano Centrale"), invisibili in UI ma persistenti su disco a tempo indefinito.
- **Evidenza aggravante**: `tests/local-journey-privacy.test.ts` modella esplicitamente la cancellazione degli itinerari, ma la dipendenza è opzionale nel tipo, quindi il test passa mentre l'app non lo fa.
- **Impatto**: promessa "Dati e privacy" parzialmente non mantenuta. **Fix**: passare `clearItineraries: clearLocalJourneyItineraries` e rendere la dipendenza obbligatoria nel tipo (così `tsc` la impone); chiamare `reconcile` dopo le delete di tratta. **Test**: contrattuale sulla required dependency.

## 5. Feature dichiarate ma non funzionanti

> Calibrazione: da rubrica, una feature rotta è Media salvo impatti di sicurezza/perdita dati. Tutte le voci qui sotto sono fail-closed: mostrano "non disponibile", non inventano dati.

### F1 — MEDIA · Wear OS (root e companion) è morto end-to-end: mismatch di `applicationId`/package (nuovo — assente dal rapporto del 5/9)

- **Evidenza**: telefono `it.binario.app` (`app/build.gradle.kts:12`) vs watch `it.binario.app.wear` (`wear/build.gradle.kts:11`); companion: `im.binario.companion` (`companion-expo/android/app/build.gradle:92`) vs `im.binario.companion.wear` (`companion-expo/android/wear/build.gradle:8`).
- **Contratto di piattaforma**: il Data Layer di Wear OS (DataClient/MessageClient) consegna solo tra nodi le cui app hanno **lo stesso `applicationId`/package name E la stessa firma**. Con package diversi, `WearSnapshotPublisher.publish` (`app/.../wear/WearSnapshotPublisher.kt:46`) non raggiunge mai il watch e il refresh dal watch (`wear/.../WearMainActivity.kt:36-43`) non raggiunge mai il telefono. Nel companion: payload watch, round-trip vocale (timeout fisso di 12 s → "Dati non disponibili in tempo reale", `BinarioVoiceRequestActivity.kt:79`) e open-checklist tutti non recapitabili.
- **Incoerenza aggiuntiva**: `com.google.android.wearable.standalone=false` dichiara un'app phone companion che, per package, non esiste.
- **Nota metodologica**: verifica su hardware non eseguibile in questo ambiente; il giudizio deriva dal contratto documentato della piattaforma ed è dichiarato come tale.
- **Impatto**: activity, Tile e complication restano per sempre su "Apri Binario sul telefono"; l'intera feature Wear descritta nel README è inerte.
- **Fix minimo**: allineare gli `applicationId` (stesso id del telefono, con firma identica; su Play si distribuisce nello **stesso listing** aggiungendo Wear OS come form factor, con artefatti e `versionCode` gestiti separatamente) o dichiarare `standalone=true` e documentare. **Test**: unit che confronta gli id (e, quando esisterà CI hardware, smoke test phone→watch).

### F2 — MEDIA · Refresh dal watch senza handler lato telefono (confermato da entrambi gli audit, ancora aperto)

Il watch invia `/binario/wear/request-refresh` (`WearMainActivity.kt:40`; costante in `WearSnapshotPublisher.kt:19`), ma in `app/` esiste **solo la costante**: nessun `WearableListenerService`, `onMessageReceived` o `MessageClient.addListener` (grep ri-verificato a HEAD, manifest incluso). Anche col fix F1, i due bottoni di refresh del watch resterebbero no-op. **Fix**: `WearableListenerService` lato telefono (con permesso, filtro `MESSAGE_RECEIVED` e `<data>`) che esegue il refresh e ripubblica lo snapshot. **Test**: bind del servizio + asserzione della pubblicazione.

### F3 — MEDIA · Listener wear root senza `<data>` nell'intent-filter (nuovo)

`wear/src/main/AndroidManifest.xml:10-12` dichiara `DATA_CHANGED` senza `<data android:scheme="wear" android:host="*" android:pathPrefix=…/>`. Gli intent di consegna del Data Layer portano un URI `wear://` e il matching Android rifiuta un intent con data su un filtro senza data (`NO_MATCH_DATA`); la documentazione di `WearableListenerService` richiede scheme/host(+path). Il manifest del companion lo fa correttamente (righe 14-15), a conferma. **Impatto**: anche a package allineati, gli snapshot pubblicati a processo watch spento non sveglierebbero il servizio → Tile/complication mai aggiornate in background. **Fix**: aggiungere `<data android:scheme="wear" android:host="*" android:pathPrefix="/binario/wear/snapshot"/>`.

### F4 — MEDIA · Bridge JS↔nativo del companion mai collegato (confermato; ricalibrato da C a Media)

- **Evidenza (doppio disallineamento, ri-verificato a HEAD)**: `companion-expo/lib/native-surface-bridge.ts:4` chiama `NativeModules.BinarioCompanionSurfaces` con payload `{subject, dataNature, expiresAtUtcMs, route:{…}}`; l'unico modulo nativo si chiama `BinarioWidgetBridge` (`getName()`, `BinarioWidgetBridge.kt:25`) e pretende `payload.getMap("widget")` + `payload.getMap("watch")` (early return altrimenti); il plugin iOS è un no-op esplicito, quindi `BinarioCompanionSurfaces` non esiste da nessuna parte.
- **Percorso**: effect della Home a ogni focus (`app/(tabs)/index.tsx:42`); `bridge` è `undefined`; optional chaining + try/catch lo rendono silenzioso.
- **Impatto**: `BinarioWidgetStore` mai scritto → widget home permanentemente su "Dati non disponibili in tempo reale"; `syncWearPayload` mai eseguito → store Wear vuoto → Tile sempre non disponibile e ogni risposta vocale dal watch è quella canonica di indisponibilità. Tutte le descrizioni della tab Companion descrivono superfici che non possono attivarsi.
- **Aggravante**: i 10 test "contrattuali" (`tests/native-surface-contract.test.ts`) sono grep di stringhe sui sorgenti e non hanno visto nulla.
- **Fix minimo**: puntare a `NativeModules.BinarioWidgetBridge` e costruire la shape `{widget:{…}, watch:{…}}` che il Kotlin parsa; costante del nome in un file condiviso. **Test di regressione**: asserire che il literal `NativeModules.X` nel TS coincida col literal `getName()` nel template del plugin, e che ogni campo letto via `getMap/getString/getDouble` nel Kotlin esista nel builder TS.

### F5 — MEDIA · "Condividi testo verso Binario" non consegna mai il testo (confermato, ricalibrato da H a Media)

- **Evidenza**: `companion-expo/lib/incoming-shared-text.tsx:5` chiama `NativeModules.BinarioSharedTextStore` — è il nome dell'*object* Kotlin, non del modulo RN (`getName() = "BinarioSharedTextReceiver"`, `BinarioSharedTextReceiver.kt:48`) → `consume()` non gira mai. In più, il percorso warm-start è rotto anche col nome giusto: `capture()` emette l'evento `BinarioSharedTextReceived` e cancella subito la pref, ma **nessun** codice JS si iscrive all'evento (grep: zero listener) → testo condiviso ad app aperta perso.
- **Percorso**: manifest espone `ACTION_SEND text/plain` su MainActivity (`main/AndroidManifest.xml:37-41`); l'utente condivide → l'app si apre → nella card di import dell'Agenda non appare nulla.
- **Impatto**: feature pubblicizzata nella UI completamente non funzionante; testo perso in silenzio. Nessun impatto di sicurezza (cap e normalizzazione nativi corretti).
- **Fix**: nome modulo corretto + `DeviceEventEmitter.addListener` nel provider + non rimuovere la pref sull'emit (o rimuoverla su ack JS); allineare i limiti (JS 4096 vs nativo 4000). **Test**: contrattuale sul nome + unit del provider.

### F6 — MEDIA · Superficie widget selezionabile mai renderizzata (confermato ancora aperto)

`app/src/main/java/it/binario/app/widget/RouteWidgetProvider.kt:296`: `renderPersonalSurface` esiste solo come definizione — zero chiamanti (ri-verificato); `render()` ignora `PersonalTravelStore.widgetSurface()`. L'impostazione "Superficie widget" salva e non cambia nulla. **Fix**: instradare `render()` sulla superficie scelta, oppure rimuovere il pannello.

### F7 — MEDIA · Il README dichiara realtime che la build distribuibile non ha (confermato)

- Gli unici adapter di tabellone live (RFI, ViaggiaTreno, Italo) sono compilati **fuori** dal build salvo `-PBINARIO_PERSONAL_REALTIME_SOURCES=true` (`app/build.gradle.kts:19-23`, default `"false"`; `LocalOnlyRailDataPolicy.kt:41-50`); il flag non è documentato nel README.
- `alertProviders`/`tripUpdateProviders`/`vehiclePositionProviders`/`realtimeEnrichmentService`/`stationSearchIndex`/`authorizedExternalProviders` di `BinarioDataGraph` hanno **zero chiamanti** fuori dal data layer (grep su tutto `app/src/main`, widget e wear inclusi) → RSS RFI, STA realtime, SNCF-RT, `RealtimeEnrichmentService`, `EmbeddedGtfsRealtimeScheduleResolver` (via graph), `SnapshotReconciler`, `AuthorizedLiveDataBoundary` sono codice morto.
- Il modello "doppio ritardo" (`detectionDelayMinutes`, `lastOperationalDetection`, `delayConsistency`, warning `DELAY_DIVERGENCE`) non è mai valorizzato da alcun percorso produttivo cablato: la visualizzazione doppio-ritardo è irraggiungibile.
- **Nota onesta**: Svizzera e DB Timetables sono correttamente documentati come inattivi-senza-credenziali e il codice corrisponde (`AuthorizedExternalProviderFactory` senza chiamanti): quello **non** è un claim falso.
- **Fix**: cablare le superfici o rimuovere gli adapter morti, e correggere il README (indicando anche il flag opt-in).

### F8 — MEDIA · Apple Watch senza produttore e progetto non generabile (confermato; giudizio statico, senza Xcode)

- Nessuna app iOS nel repo; `PhoneConnectivity.swift:4-5` lo ammette in commento; l'unico consumatore di `binario_snapshot_json` non ha alcun encoder in tutto il repo → l'app watch renderà per sempre `BinarioWatchSnapshot.unavailable` (`WatchModels.swift:71-83`), pur mostrando sezioni tratta/corse che non potranno mai popolarsi.
- `project.yml`: il target app non dichiara `INFOPLIST_FILE`/`GENERATE_INFOPLIST_FILE` né le chiavi watchOS richieste (WKApplication); al target widget manca `NSExtensionPointIdentifier`; il target test compila `Shared` **e** fa `@testable import BinarioWatch` (tipi doppi: i test non esercitano il modulo spedito). Nessuna CI macOS.
- `RefreshBinarioIntent.swift:7-10` è un no-op (carica lo storage e scarta il risultato; nessun `WidgetCenter.reloadAllTimelines`).
- **Fix**: completare `project.yml`, rimuovere `Shared` dai sources del test, gate del target dietro la futura app iOS o dichiarare il limite in-app; job CI macOS (`xcodegen` + `xcodebuild test`).

### F9 — MEDIA · Falso "soppresso" per le stazioni "Cancello" (confermato ancora aperto; ricalibrato H→M)

- **Evidenza (ri-verificata a HEAD)**: `RfiStationBoardAdapter.kt:280-281` usa `row.text()` (riga intera, **destinazione inclusa**) per `contains("SOPPRESS")/("CANCELL")`; identico pattern su tutto il contenuto in `ViaggiaTrenoStationAdapter.kt:214` e `ItaloPublicStationAdapter.kt:215`. Il catalogo curato contiene "Cancello", "Cancello ed Arnone", "Cancello-Arnone", "San Felice a Cancello" (verificato): ogni treno diretto lì risulterebbe `CANCELLED` con `operationalStatusVerified=true`.
- **Condizioni**: raggiungibile solo nella build opt-in (`BINARIO_PERSONAL_REALTIME_SOURCES=true`) — da qui la ricalibrazione a Media: nella build distribuita il percorso è compilato fuori.
- **Impatto**: dato operativo falso e "verificato" su corse reali, in una build personale. **Fix**: cercare `\b(soppresso|cancellato)\b` solo nella cella di stato (`#RRitardo`, info aggiuntive). **Test**: fixture con destinazione "Cancello ed Arnone" e stato regolare → non CANCELLED.

### Minori (Basse)

- **Gzip svizzero** ancora rotto (`SwissGtfsRealtimeAdapter.kt:70`): `Accept-Encoding: gzip, deflate` impostato a mano disabilita la decompressione trasparente e nulla avvolge la risposta in `GZIPInputStream` → `parseFrom` riceverebbe gzip grezzo → sempre `PARSE_ERROR`. Declassato da H a Bassa perché l'adapter è irraggiungibile senza credenziali (F7).
- **SNCF** `stopSequence.takeIf { it > 0 }` (`SncfGtfsRealtimeAdapter.kt:135`): SNCF numera da 0, l'update della prima fermata è perso; usare `hasStopSequence()`. Percorso in pratica morto.
- **Shortcut e route inesistenti** (companion): shortcut launcher "Pianifica" → `binario-companion://plan` (`binario_shortcuts.xml:4`, generato dal plugin ~riga 311) e bottone "Come leggere i dati" → `router.push("/guide" as Href)` (`settings.tsx:175`, il cast vanifica typedRoutes): entrambi finiscono su "Unmatched Route". Fix: puntare alla home / creare la schermata; rimuovere il cast.
- **Sezione Sincronizzazione** companion interamente stub: `lib/companion-api.ts` ritorna sempre false/null/[]; legge `EXPO_PUBLIC_BINARIO_SYNC_URL` mentre `app.config.ts` espone `extra.binarioApiUrl` che **nessuno** legge; "Collega dispositivo" risponde sempre "Codice non valido o scaduto" senza il guard "non configurato" che `checkSyncStatus` ha; `scripts/parse-binario-api-url.cjs:8` accetta `http:` in contraddizione con l'hardening cleartext.
- **Agenda companion stale**: `alerts.tsx:41` carica una sola volta con `useEffect([])` (ri-verificato) → non si aggiorna dopo un salvataggio in Home; usare `useFocusEffect` come `companion.tsx`.
- **Overflow timer** (nuovo): `index.tsx:46-49` + `lib/network-status.ts:3-5` — un delay > 2^31−1 ms (tratte pianificate oltre ~24,8 giorni) overflowa i timer del browser e scatta subito: sul target web la tratta è immediatamente dichiarata "scaduta" (con annuncio vocale) — fattualmente errato. Clampare il delay.
- **"Checksum" del catalogo companion è integrità apparente** (confermato): `scripts/build-local-station-catalog.mjs:32` scrive un `checksumSha256` **hardcoded** qualunque siano i dati trasformati; a runtime si verifica solo che sia esadecimale (`offline-station-index.ts:13`). Calcolarlo davvero nello script e verificarlo in un test.
- **UX bloccanti app nativa** (confermate ancora aperte, Medie): zero `BackHandler` in `MainActivity.kt` (ri-verificato: il tasto Indietro di sistema chiude l'app da qualunque schermata interna); nessun `launchMode` nel manifest (ri-verificato: ogni shortcut/widget crea nuova Activity + nuovo `TrainViewModel` con refresh e publish duplicati; il deep link viene ri-consumato a ogni rotazione).

## 6. Prestazioni (misurabili, non ancora profilate)

- `EmbeddedGtfsStationSearchIndex.kt:43-91`: ogni digitazione riapre 12 ZIP; in `arst-gtfs.zip` i **53,5 MB misurati** di `shapes.txt` precedono `stops.txt` nell'archivio e vengono decompressi-e-scartati integralmente a ogni passata. Media. Fix: cache in memoria degli `stops.txt` al primo accesso.
- 55 MB di asset GTFS nell'APK, di cui ~25–30 MB eliminabili (shapes ARST mai letti dai parser + AMT non filtrato al ferro, lo stesso filtro già applicato a STA/CH/ÖBB). Bassa, ma è anche la mitigazione principale di C1.
- `gradle.properties`: `-Xmx2048m` (migliorato rispetto ai 640 MB) ma `org.gradle.workers.max=1` serializza ancora build e test ovunque. Bassa.

## 7. Rischi solo teorici

- `com.google.transit:gtfs-realtime-bindings:0.0.4` (`app/build.gradle.kts:63`, ~2015) trascina un lineage protobuf con CVE note di DoS (es. CVE-2021-22569, CVE-2022-3509/3510) e parsa feed remoti; gli adapter interessati sono però in gran parte morti (F7). Aggiornare a 0.0.8+.
- Ambiguità di direzione in `openService` se lo stesso id corsa comparisse in entrambe le gambe (trip circolari): il parametro esplicito `serviceDirection` aggiunto nei fix recenti esiste ma **nessun** call site lo passa (`MainActivity.kt:301, 318`).
- Cache `var` non-volatile in `StaRealtimeAdapter.kt:88-90` e `RfiInfomobilityRssAdapter.kt:73` (ri-verificate: il rilievo del 5/9 era corretto; il mio primo passaggio su questo punto era errato). Su percorsi oggi morti; worst case staleness/doppio fetch, non corruzione (swap atomico di reference sul JVM). Bassa.
- Regex del plugin wear-manifest che non gestirebbe `value="@bool/…"` (`with-binario-wear-manifest.cjs:60-66`): coperto in pratica dal gate di idempotenza CI.
- `android:permission="com.google.android.gms.wearable.BIND_LISTENER"` sul listener wear root: permission non definita da alcun pacchetto del repo (è storicamente una action deprecata) — possibile ostacolo al bind legittimo di Play Services, oggi senza effetto osservabile solo perché la superficie è già morta per F1 (vedi V4, osservazione residua).
- C3/C4: crash latenti che richiedono precondizioni oggi assenti.

## 8. Debito architetturale

- `MainActivity.kt` ~2.500 righe (30+ composable, navigazione manuale, permessi, speech, location, share) e `TrainViewModel` non testabile in JVM: **nessuno dei 3 fix recenti ha test a livello ViewModel** — solo l'helper puro `findRefreshedService` è coperto (3 casi, tutti corretti). I fix stessi hanno introdotto codice morto: `refreshSelectedService()` e `currentFreshness()` hanno zero chiamanti.
- **Tre schemi snapshot incompatibili senza fonte di verità condivisa**: Kotlin telefono→wear (DataMap flat: `version`, `serviceN*`…), Swift (JSON annidato: `schemaVersion`, `routeId`, `services[]`, `confidenceLabel` umano — campi, nesting e semantica tutti diversi; `serviceNDelay`/`serviceNOperationalVerified` pubblicati da Android non sono consumati da nessuno), companion (terzo schema `/v1`, l'unico coerente produttore↔consumatore, verificato campo per campo). Gli enum Swift (`BinarioFreshness`) non hanno caso `unknown`: un valore nuovo del produttore rigetta l'intero snapshot. Serve un contratto documentato + fixture golden condivise su entrambi i lati.
- Test "contrattuali" grep-based (`tests/native-surface-contract.test.ts`: 10 test `readFileSync`+`toContain`) che hanno mancato F4 e F5.
- **D-5 (Media, affidabilità)** — `voice-bridge/drizzle/meta/_journal.json` elenca solo `0000` e `0001`: la migrazione `0002_sweet_rachel_grey.sql` (CREATE TABLE `binario_profiles` + FK) è **orfana** (nessuna entry, nessun `0002_snapshot.json`). Su un database vergine `drizzle-kit migrate` non crea mai la tabella e `profile.get/upsert` (`profile/router.ts:31-41` → `db.ts:141-167`) va in 500; `db:push` (`generate && migrate`) genererebbe un duplicato dal diff dello snapshot 0001 lasciando l'orfano per sempre. Per il resto le migrazioni 0000–0002 sono non-distruttive (solo CREATE/ALTER ADD, FK con CASCADE, unique presenti) e coerenti con `schema.ts`. Fix: rigenerare la 0002 con journal+snapshot e riconciliare l'orfano. Test: applicare la catena a un MySQL vuoto e asserire l'esistenza di `binario_profiles`.
- Altro codice morto confermato: `SnapshotReconciler`/`ServiceConflict` (solo test), `AuthorizedLiveDataBoundary`, `LocalOnlyRailDataPolicy.coverageTripUpdateProviders()` (vuoto e inutilizzato), `components/local-station-board-card.tsx` (65 righe importate da nessuno), dipendenze dichiarate mai usate (`expo-secure-store`, `expo-network`).

## 9. Igiene del repository e governance

- **IG-1 (Media)** · `surfaces/` = **117 MB** di snapshot di rami copiati come file (1.412 file, oggetti git normali: la policy LFS in `.gitattributes` copre solo zip/apk di `archives/` e `artifacts/`), con i 55 MB di GTFS duplicati due volte; pack git a 83 MB. Inoltre `surfaces/web-voice-bridge` è **più avanti del modulo canonico** `voice-bridge/` (12 migrazioni vs 3, `server/companion`, dettatura, decine di test): la sorgente canonica del web vive fuori repo (progetto WebDev, come documentato in `docs/REGISTRO_PROVENIENZA_SUPERFICI_2026-09-03.md`) e il modulo in-repo è una copia arretrata. Da triagiare/mergiare, poi convertire gli snapshot in tag o bundle LFS e rimuovere `surfaces/`.
- **IG-2 (Media)** · `origin/main` fermo a v0.16.3 con **320 commit non mergiati** su questo ramo; la PR #2 è draft e ha come base un altro ramo di recovery, non `main`; **zero tag** a fronte di 34 versionCode. Il default branch non rappresenta il prodotto.
- **IG-3 (Bassa)** · `SHA256SUMS` GTFS stale (vedi §1) e **nessun** codice, test o CI che verifichi manifest o checksum contro gli zip: `EmbeddedGtfsPacks.kt:23-36` è una lista hardcoded che oggi coincide col manifest, ma il drift è invisibile (il campo `purpose` del manifest cita un "source test" che non esiste). Fix: test JVM che carica manifest+SHA256SUMS, confronta con `EmbeddedGtfsPacks.all` e ricalcola gli hash; rigenerare la riga STA.
- **IG-4 (Bassa)** · CI: trigger solo `pull_request`/`workflow_dispatch` (nessuna validazione post-merge sul default branch); niente `gradle/actions/wrapper-validation` per il jar committato; il gate di idempotenza Expo non usa `--clean` né controlla i file untracked (`git diff` li ignora; i file marker-guarded possono driftare); actions pinnate a tag, non a SHA.
- **IG-5 (Bassa)** · 51 file `.md` di verifica/versione alla radice + `todo-xuyr2dxh.md` di sessione duplicato in tre punti; `app.config.ts` senza newline finale; `xmlns:tools` inutilizzato nel manifest main del companion; `org.json:20240303` come `implementation` invece di `testImplementation` (`app/build.gradle.kts:61`: a runtime lo shadowa la piattaforma, i test possono divergere).
- **IG-6 (Bassa)** · HTML di produzione del voice-bridge con placeholder letterali `%VITE_ANALYTICS_ENDPOINT%`/`%VITE_ANALYTICS_WEBSITE_ID%` (`client/index.html:22-23`; variabili definite in nessun posto) → richiesta rotta a ogni page load; residui template (`__manus__/debug-collector.js` copiato in dist, ~39 componenti UI inutilizzati, `ComponentShowcase.tsx` da 1.437 righe irraggiungibile).
- **IG-7 (Bassa)** · Disattivare lo sharing Wear non cancella il DataItem già pubblicato (`TrainViewModel.kt:760-766` ferma solo i publish futuri; nessun `deleteDataItems` nel repo): l'etichetta tratta resta nel Data Layer e nelle pref del watch; lo sharing è anche default-ON (`IntegrationSettings.kt:10,19`). Il modulo `wear/` dichiara inoltre `INTERNET` senza mai usare la rete (`wear/src/main/AndroidManifest.xml:4`).

## 10. Cosa è risultato solido (contro-verifiche esplicite)

- **Fail-closed coerente ovunque**: mai ritardi/binari/soppressioni inventati; il fallback di copertura è quality-capped a LIMITED/64 e non può superare il tier ufficiale (`QualityTieredTrainDataSource` + test); DEMONSTRATION a 39.
- **Voice-bridge**: nessun IDOR (ogni procedura tRPC deriva l'utente da `ctx.user.id` e ogni query filtra per esso — verificato procedura per procedura); nessun SQL raw (solo query builder drizzle); zod su ogni mutation; webhook Alexa con verifica firma+timestamp (verificato fin dentro `ask-sdk-express-adapter`: `ExpressAdapter(skill, true, true)`) e Skill ID enforced; ordine dei parser corretto (raw-body prima del json globale); fail-closed 503 senza config; code OAuth monouso con compare-and-set su `consumedAt` (replay-safe anche sotto race), scadenza 5 min, binding a client_id+redirect_uri; code e refresh token salvati solo come HMAC; refresh ruotati e revocati a ogni uso; `state` del login dashboard vincolato al cookie `__Host-oauth_state`; JWT di sessione con `algorithms:["HS256"]` pinnato al verify; deep link con destinazioni fisse e valori cappati; mutation protette da preflight CORS (nessun middleware CORS presente).
- **GTFS/zip**: nessuna estrazione su disco (streaming puro) → zip-slip impossibile (nomi con `..` comunque rifiutati); zip-bomb limitata (64 entry, 128 MB/entry, 512 MB totali contati sui byte decompressi reali, 250 k stazioni, righe da 32 KB, quote sbilanciate rifiutate); input malformati → `GtfsValidationException` convertita in Failure da ogni chiamante produttivo.
- **Manifest Android app**: cleartext off + `network_security_config` con soli trust anchor di sistema; un solo componente esportato necessario (MainActivity: launcher + `binario://` con whitelist e sanificazione del parametro); receiver/activity secondarie non esportate; backup e trasferimento esclusi; `FLAG_IMMUTABLE`; nessun segreto in BuildConfig/risorse né nella storia git.
- **Ingest wear root**: path esatto, gate `version==1`, cap per campo, normalizzazione whitespace, rifiuto di blank/timestamp negativi, max 2 corse, payload malformati non clobberano lo snapshot persistito; test reali del codec in CI.
- **Listener Wear esportati**: protetti dalla validazione UID interna di Play Services (solo Google Play Services/Wear autorizzato può invocare i callback) e dai requisiti di package e firma del Data Layer; l'`exported="true"` senza `android:permission` dei servizi companion è la configurazione documentata, non una lacuna.
- **Hardening degli ultimi 18 commit**: corretto e fail-closed — firma debug standard AGP su phone+wear companion (il keystore di repo non è mai stato committato in nessuna storia, verificato), release mai firmate con chiavi debug, `standalone=false` inserito correttamente, `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` (miglioramento reale anche su API < 33), ordine dei plugin Expo verificato contro il sorgente di `@expo/config-plugins@54.0.5` (l'hardening gira dopo la rigenerazione del modulo; un'inversione verrebbe colta dal gate CI), plugin idempotente e fail-closed sugli anchor.
- **I 3 fix di provenienza in `TrainViewModel`** (`015b912..HEAD`) sono funzionalmente corretti: riaggancio per id poi per identità pubblica stabile (e nessun rebind su run ambigua), pinning dello snapshot del dettaglio, routing del refresh sulla gamba giusta senza side effect su cache/widget/Wear.
- **Privacy companion in gran parte onorata**: export manuale senza campi sensibili, dettatura senza microfono (solo tastiera di sistema), posizione mai persistita, payload watch senza testi delle checklist, import manuale/ICS ri-validato e cappato — con le due eccezioni C6/C7.
- **Storage companion robusto**: ogni load AsyncStorage/JSON.parse è try/caught con output validato/normalizzato/cappato; nessun percorso di crash trovato.
- **CI**: run verdi verificati sull'esatto HEAD (vedi §1); frozen install, typecheck, test, verifiche config e security-inputs, lint+assemble di entrambi i moduli su entrambe le pipeline; `permissions: contents: read`, concurrency e timeout corretti.

## 11. Confronto con `docs/audit-2026-09-05.md`

### Confermati indipendentemente (ancora aperti a HEAD)

L'analisi indipendente ha ritrovato da sola, prima di leggere il rapporto: OOM GTFS (C1), main-thread nel fallback ES (C2), SHA256SUMS stale + integrità mai verificata (IG-3), adapter realtime morti/gate opt-in e doppio-ritardo irraggiungibile (F7), authorize Alexa senza consenso (V2), redirect_uri senza allowlist (V3), journal drizzle senza 0002 (D-5), collisione upsert (V6), storageProxy e JWT 1 anno/appId (V8), bridge JS↔Kotlin rotto (F4), shared-text rotto (F5), refresh watch senza handler (F2), superficie widget morta (F6), Apple Watch senza produttore e `project.yml` difettoso (F8), `surfaces/` (IG-1), `main` stale (IG-2), checksum companion hardcoded, test grep-based, sync stub, shortcut/route morte.

Ho inoltre ri-verificato puntualmente a HEAD, uno per uno: runtime Manus (**aperto**), "Cancello" (**aperto**, F9), gzip svizzero (**aperto**), `BackHandler`=0 (**aperto**), `launchMode` assente (**aperto**), `renderPersonalSurface` senza chiamanti (**aperto**), `alerts.tsx` con `useEffect([])` (**aperto**), `verifyVoiceAccessToken` senza `algorithms` (**aperto**), cache `var` non sincronizzate (**aperto** — su questo punto la mia prima passata si sbagliava e il rapporto del 5/9 aveva ragione).

### Corretti tra `015b912` e HEAD

Tutti e soli gli esiti dei 18 commit: dipendenza dal `debug.keystore` di repo eliminata su phone+wear companion (firma debug standard AGP, con enforcement in `verify-release-inputs.mjs`); receiver vocale Wear ora `RECEIVER_NOT_EXPORTED` su tutte le API (era il finding M di §3.4 del vecchio rapporto: **chiuso**); `com.google.android.wearable.standalone=false` dichiarato; deriva di provenienza del dettaglio corsa su andata/ritorno corretta (con test sull'helper); idempotenza del prebuild mantenuta (ri-eseguita qui: verde). **Tutto il resto del rapporto del 5/9 risulta non corretto.**

### Nuovi problemi trovati da questo audit (assenti dal rapporto del 5/9)

Mismatch di `applicationId` telefono↔watch in **entrambe** le linee (F1 — il vecchio rapporto attribuiva la morte del Wear al solo listener mancante: la causa è più profonda e il fix lì proposto non basta da solo); `<data>` mancante nell'intent-filter del listener wear root (F3); finestra post-unlink + auto-de-revoca Alexa (V5, estende il loro finding M sull'upsert); promemoria superstite alla cancellazione della tratta (C6); itinerari superstiti a "Cancella archivio locale" (C7); crash latenti `TYPE_DELETED` e chiave LazyColumn duplicata (C3/C4); overflow dei timer oltre ~24,8 giorni; DataItem Wear mai cancellato disattivando lo sharing (IG-7); enum Swift senza fallback `unknown`; codice morto introdotto dai fix stessi (`refreshSelectedService`, `currentFreshness`); divergenza `voice-bridge/` vs `surfaces/web-voice-bridge` (3 vs 12 migrazioni) come rischio di governance esplicito; uso di `BIND_LISTENER` (action deprecata, non permission) come `android:permission` nel manifest wear root (osservazione residua in V4).

### Falsi positivi

- **Nel rapporto del 5/9** (emersi al riesame puntuale; nessuno tra i C/H):
  - **`OperatorId("other")` nel reconciler** — la conseguenza descritta ("se RFI non espone il vettore, il board non matcha mai la corsa programmata") è **errata come formulata**. Il mapping del reconciler tratta già `RailOperator.OTHER` come sconosciuto (`StationBoardRealtimeReconciler.kt:396` → `null`) e il filtro consente il match quando uno dei due lati è null (`:311`); la board della build di default (`RfiProgrammedStationBoardAdapter.kt:148`) costruisce le entry **senza** `operatorId` (default null, `RailCore.kt:292`) → match consentito. Residuo reale ma stretto: `StationBoardInterop.kt:160` mappa `OTHER → OperatorId("other")` non-null, quindi solo nella build opt-in, con operatore programmato noto e vettore assente sulla board scrappata, il mismatch esiste ancora — il fix proposto dal vecchio audit resta valido per quel caso.
  - **Lista del codice morto parzialmente errata** — `ListHeading` ha un chiamante (`MainActivity.kt:1466` definizione, `:1646` uso) e `reconcileWidgetState` pure (`TrainViewModel.kt:387` definizione, `MainActivity.kt:371` uso in `LaunchedEffect`): non sono codice morto. Confermati invece irraggiungibili al riesame: `NextDepartureHero` e `SearchScreen` (una sola occorrenza ciascuno: la definizione), `route_widget_compact.xml` (file presente, zero riferimenti) e le stringhe `widget_empty_*`/`widget_column_*` (zero riferimenti in codice e layout).
  - **Non** è invece un falso positivo il "backup senza tratte rifiutato", che una segnalazione esterna proponeva di ritrattare: a HEAD `LocalDataBackup.kt:61` esegue `require(routes.isNotEmpty())` dentro `decode()`, quindi un backup senza tratte valide **è rifiutato**; il ramo di gestione in `TrainViewModel.kt:751-755` ("Backup importato senza una tratta salvata…") esiste ma è irraggiungibile via import proprio per quel `require` — è dead code, non gestione effettiva. Il rilievo del 5/9 su questo punto è confermato. 
- **In questa stessa analisi** (corretti prima della versione definitiva): la prima stesura classificava V4 come vulnerabilità Media di spoofing locale, poi lo aveva conservato come "hardening Bassa" raccomandando di aggiungere `BIND_LISTENER` come permission ai servizi companion. Entrambe le cose erano sbagliate e il finding è stato **ritirato del tutto**: la validazione UID interna di `play-services-wearable` 20.0.1 (confermata su AAR decompilato dal committente) rende il vettore impraticabile, e `BIND_LISTENER` è una action deprecata nel 2016, non una permission — la raccomandazione avrebbe potuto danneggiare la consegna anziché migliorarla (resta solo l'osservazione residua sul manifest root, in V4). Inoltre una passata aveva giudicato "sincronizzate" le cache degli adapter STA/RSS: è stato lo stesso audit a smentirla ri-verificando i file (§7).

### Non ri-verificati puntualmente in questa passata (plausibili, nessuna smentita trovata)

Orari GTFS ≥ 24:00 a cavallo di mezzanotte; mappatura "FERIALE" senza sabato; allarmi in Doze e assenza di `BOOT_COMPLETED`; dettagli dell'import backup (cap dimensione file, validazione `alert.time`, ripianificazione allarmi — mentre il rifiuto dei backup senza tratte è stato ri-verificato: esiste, vedi Falsi positivi); salti di pagina del widget al resize e sovrascrittura direzione; i singoli item di accessibilità; `Clock.systemDefaultZone()`; `toString()` delle credenziali; `RatePolicy`/`RetryPolicy`/`CircuitBreakerPolicy` mai letti; adapter ricreati a ogni chiamata.

### Ricalibrazioni di severità (rubrica: Critical solo per compromissioni dimostrate; feature rotte ≠ C/H)

| Finding | 5/9 | Questo audit | Motivo |
|---|---|---|---|
| Runtime Manus (V1) | C | **H** | meccanismo dimostrato, danno mediato dal comportamento della vittima |
| OOM GTFS (C1) | C | **H** | crash (rischio fortemente dimostrato), non compromissione |
| Bridge companion (F4) | C | **M** | feature rotta, fail-closed |
| Shared-text (F5) | H | **M** | feature rotta, fail-closed |
| "Cancello" (F9) | H | **M** | raggiungibile solo nella build opt-in |
| Gzip svizzero | H | **L** | percorso irraggiungibile senza credenziali |
| `surfaces/` (IG-1) | H | **M** | solo governance, come da perimetro |
| SHA256SUMS/manifest (IG-3) | H | **L** | nessun consumo runtime; resta il contratto d'integrità rotto |
| Journal drizzle (D-5) | H | **M** | impatta solo i deploy su DB vergine |
| BackHandler / launchMode | H | **M** | blocco UX riproducibile, non crash |
| Authorize Alexa (V2) | H | **H** | invariato |

## 12. Conclusioni e piano d'intervento

Riepilogo severità (questo audit): **0 Critical · 4 High (V1, V2, C1, C2) · ~18 Medium · resto Low**.

### P0 — Sicurezza e crash (subito)

1. **V1**: togliere il runtime Manus e `jsxLocPlugin` dalla build di produzione; eliminare il fallback Bearer; CSP `frame-ancestors 'none'`.
2. **V2/V3**: consenso autenticato con POST+CSRF sull'authorize Alexa; allowlist esatta dei `redirect_uri`.
3. **C2**: `withContext(Dispatchers.IO)` in `feedBytes()` (una riga).
4. **C1**: ri-filtrare l'asset AMT al solo ferro (mitigazione immediata) e pianificare l'indice streaming/colonnare; test JVM con heap limitato come conferma sperimentale.

### P1 — Verità del prodotto e affidabilità (prossima release)

5. **F1–F3**: decidere il destino del Wear — allineare `applicationId` e firma, aggiungere `<data>` al filtro e l'handler di refresh lato telefono, oppure dichiarare esplicitamente la superficie non funzionante (README e UI).
6. **F4/F5**: allineare nome e shape dei bridge nativi companion + test contrattuali reali (non grep).
7. **D-5**: rigenerare la migrazione 0002 con journal+snapshot.
8. **V5/V6**: revoca effettiva post-unlink e gestione esplicita del re-link.
9. **C6/C7**: cancellazione promemoria alla delete e `clearItineraries` obbligatoria.
10. **F6/F7**: collegare o rimuovere superficie widget e adapter morti; correggere il README (incluso il flag opt-in realtime).
11. **F9** + gzip svizzero + `stopSequence`: correggere i parser prima di qualsiasi attivazione realtime.

### P2 — Governance, igiene, debito

12. **IG-1/IG-2**: triage di `surfaces/web-voice-bridge` verso il modulo canonico, conversione degli snapshot in tag/bundle e rimozione di `surfaces/`; riportare `main` alla linea corrente e proteggerlo; taggare le release.
13. **IG-3/IG-4**: rigenerare `SHA256SUMS` + test di coerenza manifest/pack in CI; trigger `push`, wrapper-validation, gate prebuild con `--clean` e check dei file untracked.
14. Contratto snapshot unico con fixture golden per le tre superfici; enum Swift con caso `unknown`.
15. Spezzare `MainActivity.kt`/`TrainViewModel` (testabilità JVM), `BackHandler`, `launchMode="singleTop"`; pulizia del codice morto e dei residui template; riordino dei 51 md di radice in `docs/`.

---

*Verifiche dichiarate non eseguibili in questo ambiente: esecuzione Gradle Android/Wear locale (compensata dai run CI verificati su HEAD), build/test Xcode, prove su hardware phone/watch, fetch dei binari LFS, ispezione interna dell'AAR `play-services-wearable`, profiling su dispositivo (C5), riproduzione sperimentale dell'OOM (C1). Nessuna di queste assenze è stata trasformata in un fatto.*
