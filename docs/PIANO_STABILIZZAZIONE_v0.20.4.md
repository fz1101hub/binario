# Piano di stabilizzazione Binario v0.20.4 — versione aggiornata

**Base**: *Piano completo di stabilizzazione di Binario* (Manus AI, 23/09/2026, 263 righe), integrato con l'addendum dell'audit indipendente (`docs/audit-independent-2026-09-23-addendum.md`, commit `0a39754`).
**Baseline del codice**: `0b8f3c98487d24171053f318a86e538618218edb` (tag locale `audit-baseline-2026-09-23`).
**Branch di lavoro**: `stabilization/v0.20.4`, creato da `0a39754` (stesso codice di `0b8f3c9` + rapporti di audit).

Le sezioni marcate **[NUOVO]** o **[MODIFICATO]** differiscono dal piano originale; il resto lo conferma. Le priorità delle quattro High (V1, V2, C1-OOM, C2-main-thread) sono invariate.

## Sintesi esecutiva

Binario non richiede una riscrittura. Quattro aree bloccano una release completa — sicurezza del companion Alexa, affidabilità del data layer GTFS, comunicazione telefono–Wear OS, contratti del Companion Expo — a cui l'audit finale aggiunge tre prerequisiti trasversali che il piano originale non copriva: **validità temporale dei dati embedded** (tre pack GTFS già scaduti), **release engineering nativo** (nessun `buildTypes`, nessuna firma, nessuna licenza) e **operabilità del companion Alexa fuori dall'ambiente di sviluppo**.

Due traguardi:
1. **Release A — stabilizzazione Android**: data layer sicuro, pack validi, build firmata e minificata, funzioni non operative corrette o nascoste e documentate.
2. **Release B — integrazioni**: Alexa Companion, Wear OS e Companion Expo, solo dopo test end-to-end e gate G1–G6.

Nessuna nuova funzione finché i gate P0 non sono verdi.

## 1. Baseline e strategia Git **[MODIFICATO]**

1. Tag annotato `audit-baseline-2026-09-23` su `0b8f3c9` (creato; il push del tag richiede credenziali con permesso sui tag).
2. Branch `stabilization/v0.20.4` da `0a39754` (fatto).
3. Solo correzioni, test, CI e documentazione; nessuna nuova feature.
4. Un commit (o PR) per finding o gruppo coeso; niente commit-monolite.
5. **[DECISO] Gerarchia e sorgenti canoniche**: `app/` è il prodotto principale per smartphone; `alexa-companion/` è la sorgente canonica della skill Alexa e deriva dall'albero hardenizzato `voice-bridge/`; `surfaces/web-voice-bridge` resta uno snapshot storico non distribuibile. Le funzioni non-Alexa dello snapshot vengono triagiate e portate per capacità nel target corretto, mai fuse in blocco. Decisione completa in `docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`.
6. **[NUOVO] `LICENSE`** nel primo commit del branch (scelta della licenza: decisione del proprietario; `alexa-companion/package.json` dichiara MIT).
7. PR iniziale verso `audit/official-rail-sources-20260904` (diff = solo correzioni post-audit); la CI gira su `pull_request` — in alternativa `workflow_dispatch` sul branch.
8. Dopo i gate, integrazione in `main` (`main` è antenato del ramo di audit: merge senza conflitti). PR #2 ritargettata a `main` o sostituita; **[NUOVO] PR #1** (`feature/data-foundations`, aperta dal 14/08) chiusa o ritargettata.
9. Proteggere `main`, richiedere CI verde, taggare le release.

## 2. Gate di rilascio **[MODIFICATO]**

| Gate | Condizione minima | Blocca |
|---|---|---|
| **G1 — Sicurezza Alexa Companion** | V1, V2, V3 chiusi; revoca Alexa effettiva (V5/V6); migrazioni riproducibili su DB vuoto e in upgrade | Qualunque deploy del companion Alexa |
| **G2 — Affidabilità dati** | Nessuna rete sul Main; AMT non satura l'heap; download bounded; checksum e manifest coerenti; **[NUOVO] nessun pack embedded scaduto alla data di release, avviso in-app oltre la validità, check CI sulle scadenze** | Release Android |
| **G3 — Verità delle feature** | Funzioni non operative corrette oppure nascoste e documentate. **[NUOVO] Elenco vincolante**: target widget "Stazione"/"Treno" (mai alimentati), impostazione "Superficie widget", shortcut "Ricerca treno", toggle Wear (default-ON) e Apple Watch nelle Impostazioni, sezione Sincronizzazione del companion, README (quattro file citati inesistenti, claim realtime della build di default), label/icona placeholder del companion | Pubblicazione con promesse Wear, widget, sync o Apple Watch |
| **G4 — Wear end-to-end** | Package e firma coerenti; filtri `<data>` e handler presenti; postura di distribuzione decisa (Play track Wear vs sideload), comportamento non-standalone documentato; prova telefono↔watch superata | Release Wear OS |
| **G5 — Companion e privacy** | Bridge nativi funzionanti; delete e clear cancellano davvero dati e reminder; **[NUOVO] icona/splash/label propri** | Release Companion Expo |
| **G6 — Delivery** | CI su PR e push; `main` corrente; sorgenti canoniche definite; **[NUOVO] build release firmata, minificata e riproducibile; `LICENSE`; CI dedicata e `.env.example` di Alexa Companion; skill Alexa con modello versionato** | Release pubblica completa |

## 3. Fase P0-A — Alexa Companion

### 3.1 Runtime Manus fuori dalla produzione (V1)
Rimuovere `vitePluginManusRuntime()` e `jsxLocPlugin()` dalla build di produzione (o `apply: "serve"`); eliminare il fallback `sessionStorage["manus-cookie"]` → `Authorization: Bearer`; sessione solo via cookie sicuro `HttpOnly`. CSP `frame-ancestors 'none'` + `X-Frame-Options: DENY` da middleware centralizzato.
**Accettazione**: `dist/index.html` non contiene `manus-runtime`, `setCookie`, `manus-cookie`, `__MANUS_HOST_DEV__` né il debug collector (oggi `__MANUS_HOST_DEV__` compare due volte); una pagina esterna non può incorporare la dashboard.

### 3.2 OAuth Alexa (V2, V3)
GET authorize → schermata di consenso autenticata; solo un POST con token CSRF crea il code e completa il redirect. Allowlist esatta dei callback Amazon; conservare la richiesta originale durante il login e rivalidare `client_id`, `redirect_uri`, `scope`, `state`.
**Accettazione**: GET autenticato non crea code; redirect non in allowlist → 400; POST senza CSRF rifiutato; flusso valido completa il linking.

### 3.3 Unlink e re-link effettivi (V5, V6)
`getLinkedUser` rifiuta link con `revokedAt`; `recordAlexaVerification` non azzera la revoca; revoca opponibile ai token in circolazione (check DB per richiesta o token brevi + generation counter). Re-link con lo stesso `alexaUserHash` gestito esplicitamente, niente upsert ambiguo su due UNIQUE.
**Accettazione**: dopo `unlink` un access token precedente non legge dati né ripristina il collegamento.

### 3.4 Migrazioni, hardening e operabilità **[MODIFICATO]**
Rigenerare la migrazione `0002` con snapshot e journal; testare DB vuoto e upgrade da `0001`; separare `db:generate` da `db:migrate` (mai `generate` al deploy). Rate limiting su OAuth/Alexa/tRPC, body limit ≤ 1 MB, validazione env all'avvio (zod), `errorFormatter` che maschera gli errori interni, sessioni brevi con rotazione, verifica `appId`, `algorithms: ["HS256"]` sui token vocali, chiavi separate (HKDF).
**[NUOVO]**: porta rigida in produzione (niente `findAvailablePort`, exit ≠ 0 su EADDRINUSE); `GET /healthz`; `.env.example` con le 12 variabili; CI dedicata (`tsc`, `vitest`, `vite build` + asserzione sull'HTML); redazione del log mysql in `db.ts:86`; rimozione o sostituzione documentata della dipendenza dal portale OAuth Manus (`VITE_OAUTH_PORTAL_URL`/`OAUTH_SERVER_URL`) per host non-Manus; pulizia dei residui template (`template.json`, Umami placeholder, `__manus__/`). La dashboard resta una superficie tecnica per account linking e revoca, non l'app principale.
**Gate G1**.

## 4. Fase P0-B — Data layer GTFS

### 4.1 Mitigazione immediata dell'OOM e dei pack scaduti **[MODIFICATO]**
Rigenerare il pack AMT filtrato alla sola Genova–Casella (licenza permettendo), aggiornando `manifest.json` e `SHA256SUMS` nello stesso commit; altrimenti escluderlo temporaneamente. **[NUOVO]**: sostituire subito i pack scaduti (Cotral 06/09, Trentino 09/09, Toscana TFT 14/09) con i feed dell'orario in vigore e rigenerare AMT prima del 02/10; registrare `lastServiceDate` nel manifest.
**Accettazione**: nessun input utente attiva il parsing di 1,35 M righe in mappe di stringhe; nessun pack scaduto alla data di release.

### 4.2 Indice compatto
Sostituire `List<Map<String,String>>` con un preindice binario offline o un parser streaming a più passate; limiti espliciti su memoria, righe, dimensione decompressa e indici in cache. Test con feed sintetico realistico e heap 256 MB.

### 4.3 Dispatcher e download
Rete e stream su `Dispatchers.IO`; scansione/ordinamento/normalizzazione/parsing su `Dispatchers.Default`, di proprietà del data source. Lettura bounded sui byte realmente ricevuti anche senza `Content-Length`; hash atteso quando disponibile; cache dell'indice.
**Accettazione**: zero rete sul Main; risposte chunked oltre il limite interrotte; richieste concorrenti non duplicano download/parsing.

### 4.4 Correttezza dei dati **[MODIFICATO]**
Prima di abilitare le sorgenti realtime: falso "cancellato" (Cancello); gzip svizzero; `stop_sequence=0`; corse oltre le 24:00 valutando il giorno di servizio precedente (−1, −2 per ÖBB); "FERIALE" con sabato e calendario festività; caso residuo `OperatorId("other")` nel percorso opt-in. **[NUOVO]**: fuso orario `Europe/Rome` iniettato (pianificazione, RFI, allarmi); etichetta "programmato ufficiale" distinta da "verificato operativamente" (oggi uno snapshot solo-programmato è "Dato verificato · 90/100"); `withQuality` anche sul fallback live diretto; `ExternalRailCredentials.toString()` redatto. Ogni correzione con fixture di regressione.

### 4.5 Integrità e riproducibilità degli asset **[MODIFICATO]**
Test/task CI che confronta `manifest.json`, `SHA256SUMS`, `EmbeddedGtfsPacks.all` e gli hash reali; correggere l'hash stale STA. **[NUOVO]**: pipeline riproducibile `tools/build_gtfs_packs.py` che scarica, filtra **tutti** i pack (oggi solo 3 su 12 sono filtrati), copia negli asset e rigenera manifest + `SHA256SUMS` + `lastServiceDate`; `requirements.txt` (`bs4` oggi non dichiarato); check CI che fallisce se un pack è scaduto o scade entro 30 giorni; cadenza di refresh dichiarata (ogni cambio orario).
**Gate G2**.

## 5. Fase P1-A — Wear OS **[MODIFICATO]**

Coppia canonica: `app/` + `wear/`; il Wear del Companion resta sperimentale. Prima di PR-WEAR-1 decidere la distribuzione (Play track Wear o sideload). `standalone=false` è coerente con un'app Wear dipendente dal telefono; va documentato il comportamento quando l'app smartphone non è installata.

### 5.1 Coppia nativa
Stesso `applicationId` e stessa firma; stesso listing Play con form factor Wear e `versionCode` distinti; check automatico degli ID. Filtro `<data android:scheme="wear" android:host="*" android:pathPrefix="/binario/wear/snapshot"/>` sul listener. Listener telefono per `/binario/wear/request-refresh` con dedup e rate limit, poi ripubblicazione. Rimuovere `android:permission="com.google.android.gms.wearable.BIND_LISTENER"` dal manifest root dopo prova su device (protezione effettiva: validazione UID di Play Services + package/firma). Solo `TYPE_CHANGED`, chiavi UI univoche, cancellazione del DataItem alla disattivazione dello sharing; **[NUOVO]** rimuovere `INTERNET` dal modulo wear; sharing Wear **off di default** finché G4 non è verde.

### 5.2 Companion Expo e relativo Wear
Se resta: coppia con package e firma coerenti, altrimenti fuori dalla release. Bridge JS/Kotlin su `BinarioWidgetBridge` con `{widget, watch}`; shared-text su `BinarioSharedTextReceiver` con cold start ed evento a caldo con ack prima della cancellazione. Activity Wear non esportate se non richiesto. Test su funzioni e manifest generati. **[NUOVO]**: icona/adaptive-icon/splash propri (oggi placeholder Expo byte-identici al template) e label distinta da "Binario"; bloccare `ACCESS_FINE_LOCATION`.

### 5.3 Contratto condiviso
Documento di contratto versionato e fixture golden per Android→Wear, Companion→Wear, iOS→Apple Watch (enum con caso `unknown`, TTL, provenienza, stato operativo, compatibilità).
**Gate G4**.

## 6. Fase P1-B — Companion Expo e privacy locale
Cancellare il reminder prima di rimuovere la tratta; `clearItineraries` obbligatorio in `clearLocalJourneyArchive`; riconciliare gli itinerari dopo le eliminazioni. Agenda con `useFocusEffect`; route `/guide` e `binario-companion://plan` create o rimosse (+ `+not-found.tsx`); sezione Sincronizzazione nascosta finché il client non è reale. CI: `expo prebuild --clean --platform android --no-install` in workspace sacrificabile + `git status --porcelain -- android`. Clamp del timer di scadenza (>24,8 giorni); checksum del catalogo calcolato davvero.
**Gate G5**.

## 7. Fase P1-C — App Android

### 7.1 Navigazione e lifecycle **[MODIFICATO]**
`BackHandler` (o Navigation Compose); deep link one-shot; `singleTop`/`singleTask` con `onNewIntent` raggiungibile; id del dettaglio in `SavedStateHandle` o ritorno sicuro dopo process death. **[NUOVO]**: shortcut "Ricerca treno" senza numero mappata a `SearchMode.TRAIN` (oggi no-op); tap sul widget che legge `EXTRA_APPWIDGET_ID` e attiva la tratta di quell'istanza; `pendingStopAlarm`/`pendingAlert` salvabili (oggi persi ruotando durante il dialogo permessi).

### 7.2 Widget **[MODIFICATO]**
Collegare `widgetSurface()` a `renderPersonalSurface` o rimuovere l'impostazione; paginazione con offset in righe; errori e snapshot limitati alla tratta **e direzione** corrispondenti; refresh con `PeriodicWorkRequest` (≥15 min), `NetworkType.CONNECTED` e backoff. **[NUOVO]**: implementare i percorsi di fetch per i target "Stazione"/"Treno" o rimuoverli dalla configurazione; `WidgetStateStore.clear` alla riconfigurazione; freshness calcolata dall'età dello snapshot al render (non quella salvata); orario di aggiornamento nella meta visibile.

### 7.3 Allarmi e backup **[MODIFICATO]**
Fermate dopo mezzanotte; ricostruzione degli allarmi dopo reboot (`RECEIVE_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`), all'avvio e dopo import; allarmi esatti solo con `canScheduleExactAlarms()` e degrado esplicito (o wording "circa"). **[NUOVO]**: fascia silenziosa che oggi rende un avviso mai pianificabile mentre la UI dice "pianificato" → `schedule` restituisce l'esito e la UI lo mostra; ripianificazione anche quando POST_NOTIFICATIONS manca al fire; avvisi ONCE disabilitati dopo il fire; `contentIntent` sulle notifiche. Backup: limite ~1 MB e parse fuori dal Main, `LocalTime.parse` su `alert.time`, id riservati rifiutati, ultima tratta rimovibile; decidere se accettare i backup senza tratte (oggi rifiutati; il ramo del ViewModel è irraggiungibile).

### 7.4 Prestazioni, accessibilità, manutenzione **[MODIFICATO]**
Pre-normalizzare l'indice stazioni (regex hoisted, valori normalizzati al caricamento), suggerimenti memoizzati fuori dalla composizione o su `Dispatchers.Default`; autocomplete estratto dal `TrainViewModel` completo per la configurazione widget; eliminare `EmbeddedGtfsStationSearchIndex.search` se resta morto. Test font-scale e semantiche; stringhe a risorse; decode delle preferenze corrotte in `runCatching`; codec su `java.util.Base64` per testabilità JVM; rimuovere solo il codice dimostrato irraggiungibile (`NextDepartureHero`, `SearchScreen`, `route_widget_compact.xml`, stringhe `widget_empty_*`/`widget_column_*` — **non** `ListHeading` e `reconcileWidgetState`, che hanno chiamanti). **[NUOVO]**: l'informativa privacy è ora allineata in [PRIVACY.md](../PRIVACY.md), inclusi Wear, invio di identificativi/nome stazione alle fonti e notifiche. Per la distribuzione pubblica restano bloccanti titolare, recapito per i diritti e URL pubblico verificato; non vengono inventati nel repository.
**Accettazione**: test di navigazione, process death, widget, allarmi (incluso `nextTrigger` su avviso abilitato), backup e accessibilità verdi.

## 8. Fase P1-D — Apple Watch
Congelare come prototipo documentato oppure completare con app iOS produttrice e pipeline macOS (XcodeGen, WidgetKit incorporata, plist, `Shared` fuori dai sources del test, TTL sugli snapshot, intent di refresh con effetto reale).
**Accettazione**: `xcodegen`, `xcodebuild test`, ricezione iPhone→Watch, scadenza corretta.

## 9. Fase P2 — Repository, CI, release engineering, architettura **[MODIFICATO]**
Inventario del lavoro esclusivo in `surfaces/web-voice-bridge`, porting selettivo delle capacità non-Alexa nel target corretto (`app/`, `companion-expo/` o un backend companion dedicato), archiviazione come tag/bundle, poi rimozione di `surfaces/`. Nessun merge in blocco in `alexa-companion/`. CI su `push`, validazione wrapper Gradle, test checksum e scadenze, prebuild pulito. **[NUOVO]**: `buildTypes.release` in `app/` e `wear/` con R8, `shrinkResources`, `proguard-rules.pro` (keep per protobuf/gtfs-realtime-bindings, jsoup, Wear) e firma da segreti CI; `assembleRelease` + `lintVitalRelease` in CI; `versionCode`/`versionName` da un'unica sorgente; aggiornamento `gtfs-realtime-bindings` (0.0.4 → 0.0.8+); `org.json` come `testImplementation`; icona nativa adaptive. Documenti storici in `docs/releases/` e `docs/verifiche/`. Dopo: version catalog, `MainActivity` per feature, `TrainViewModel` con dipendenze iniettate e `UiState`.

### 9.1 Porting selettivo dallo snapshot WebDev **[NUOVO]**

Il triage completo è in `docs/architecture/TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md`. Dopo i gate P0:

1. **PR-APP-PLANNING**: dominio Kotlin `PlannedJourney` distinto da `SavedRoute`, agenda, itinerari multi-tratta, reminder/checklist, ricorrenze esplicite e test; successivamente import/export JSON e ICS con preview, limiti e deduplica.
2. **PR-ANDROID-VOICE-REVIEW**: portare dal prototipo web gli esiti `confirmed`/`needs-review`/`unresolved` e i candidati di stazione, mantenendo il riconoscitore di sistema Android e senza upload audio.
3. **PR-DATA-CATALOG-QA**: gate di build per unicità ID/codici, coordinate, alias, conteggio/checksum e tie-break deterministico nella prossimità.
4. **PR-COMPANION-SYNC** — solo dopo decisione prodotto: creare `services/companion-sync/` con pairing, token hash, revoca, consenso per device, privacy export/delete e route-sync limitato; `app/` usa Keystore e operazioni pull/push esplicite. Non usare Alexa o il Data Layer Wear come backend universale.
5. **PR-SURFACES-ARCHIVE — completata**: capacità marcate come portate, differite, rifiutate o preservate; snapshot salvato in `archive/surfaces-20260923` al commit `37a408d662374aa1c8f9765b20c9f2fd90e5b81f`, poi rimosso dalla linea attiva.

## 10. Ordine delle pull request **[MODIFICATO]**

0. **PR-REL-0 [NUOVO]**: `LICENSE`; `buildTypes.release` (R8/shrink/proguard, firma da env); README veritiero (file inesistenti, claim realtime, flag opt-in); `assembleRelease`/`lintVitalRelease` in CI. Indipendente da tutto, va subito.
1. **PR-SEC-1**: runtime Manus, fallback Bearer, CSP/X-Frame-Options, asserzione sull'HTML.
2. **PR-SEC-2**: consenso OAuth, allowlist, revoca/re-link, rate limiting, `errorFormatter`, env validation, porta rigida, `/healthz`, `.env.example`.
3. **PR-DB-1**: migrazione 0002, test DB vuoto/upgrade, separazione generate/migrate.
4. **PR-DATA-1**: `Dispatchers.IO`/`Default`, download bounded, `SHA256SUMS`, test di coerenza asset, `lastServiceDate` + avviso.
5. **PR-DATA-0 [NUOVO]**: sostituzione dei pack scaduti e rigenerazione AMT filtrato; check CI sulle scadenze; `build_gtfs_packs.py`.
6. **PR-DATA-2**: indice compatto e test heap 256 MB.
7. **PR-DATA-3**: Cancello, gzip, sequence, mezzanotte, FERIALE, fuso orario, etichette qualità.
8. **PR-WEAR-1**: decisione prodotto e distribuzione, package/firma, filtro URI, rimozione del permission gate errato, `INTERNET`, sharing off di default.
9. **PR-WEAR-2**: refresh telefono, lifecycle DataItem, test end-to-end.
10. **PR-COMP-1**: bridge nativi e shared-text.
11. **PR-COMP-2**: reminder, cancellazione archivio, Agenda, route, timer, checksum.
12. **PR-BRAND [NUOVO]**: icona/splash/label del companion, `ACCESS_FINE_LOCATION`.
13. **PR-ANDROID-UX**: navigazione, shortcut, widget (target Stazione/Treno inclusi), allarmi (fascia silenziosa, boot, import), backup, ricerca stazioni, privacy notice.
14. **PR-ALEXA-OPS** (già PR-VB-OPS) e **PR-ALEXA-MODEL**: CI e deploy riproducibile di `alexa-companion/`; skill Alexa con handler built-in (Stop/Cancel/Help/Fallback/SessionEnded) e `skill-package/` versionato. Prerequisiti della Release B.
15. **PR-DATA-CATALOG-QA** e **PR-ANDROID-VOICE-REVIEW**: porting a basso rischio delle migliorie WebDev nel prodotto principale, dopo P0.
16. **PR-APP-PLANNING**: nuovo dominio personale offline, agenda e portabilità, dopo la stabilizzazione.
17. **PR-COMPANION-SYNC**: solo se la sincronizzazione multi-device viene approvata come funzione di prodotto.
18. **PR-CI-GOV / PR-SURFACES-ARCHIVE**: CI su push, wrapper validation, prebuild clean, archivio e pulizia `surfaces`, riallineamento di `main`, chiusura PR #1/#2.
19. **PR-APPLE**: solo se Apple Watch entra nel perimetro.

Le PR 0–5 sono parallelizzabili per area; il merge rispetta l'ordine quando una modifica dipende dai contratti della precedente.

## 11. Strategia di rilascio **[MODIFICATO]**

**Release A — Stabilizzazione Android**: pubblicabile con G2 verde più PR-REL-0 e la parte Android di G3. Deve avere: pack non scaduti, build firmata e minificata, Wear sharing off di default (o modulo escluso), target widget Stazione/Treno e impostazione superficie nascosti o funzionanti, shortcut corretta, README e informativa privacy allineati, nessuna promessa di realtime o sincronizzazione non raggiungibile.

**Release B — Integrazioni**: richiede G1–G6, test end-to-end Wear, migrazione DB provata, bridge Companion funzionanti, skill Alexa certificabile, Alexa Companion avviabile con la sola documentazione del repo e sincronizzazione verificata con l'app principale.

## 12. Definition of Done **[MODIFICATO]**

- nessun finding High aperto;
- nessuna rete o parsing pesante sul Main thread;
- il feed più grande passa con heap limitato;
- **nessun pack embedded scaduto; avviso in-app oltre la validità; check CI sulle scadenze**;
- Alexa Companion production senza runtime di sviluppo, avviabile fuori da Manus con `.env.example` e CI propria;
- unlink Alexa revoca immediatamente l'accesso; skill con modello versionato e handler built-in;
- telefono e Wear comunicano in entrambe le direzioni (o Wear dichiarato sperimentale e off);
- widget, condivisione testo e cancellazione dati rispettano quanto mostrato all'utente;
- ogni feature non implementata è rimossa o dichiarata sperimentale;
- **build release firmata, minificata e riproducibile; `LICENSE` presente**;
- CI su pull request e push; `main` rappresenta il prodotto ed è protetto; ogni release è taggata.

## 13. Stato di avanzamento

| Voce | Stato | Riferimento |
|---|---|---|
| Tag baseline | creato in locale; il push dalla sessione è rifiutato (credenziali limitate ai branch, riprovato due volte). Da pushare dal PC del proprietario: `git fetch origin && git tag -a audit-baseline-2026-09-23 0b8f3c98487d24171053f318a86e538618218edb -m "Baseline audit 2026-09-23" && git push origin audit-baseline-2026-09-23` | `audit-baseline-2026-09-23` su `0b8f3c9` |
| Branch `stabilization/v0.20.4` | creato e pushato | da `0a39754` |
| Piano aggiornato | questo documento | `fab23c9` |
| PR-SEC-1 (runtime Manus, Bearer, CSP, verificatore build) | **fatto**, verificato in locale (tsc, 26 test, build, smoke header) | `68f2bd5` |
| PR-DB-1 (migrazione 0002, journal, script separati, test catena) | **fatto**, SQL rigenerato identico all'orfano | `0439be5` |
| PR-SEC-2 (consenso+CSRF, allowlist, revoca, re-link, hardening server, `.env.example`) | **fatto**, 49 test verdi, smoke `/healthz` + rate limit | `f591719` |
| PR-ALEXA-OPS (già PR-VB-OPS; workflow CI companion Alexa) | **fatto**; esecuzione precedente, ancora denominata Voice Bridge CI #2, **verde** su `98f54bc` | `d0bca14` |
| PR-ALEXA-MODEL (handler built-in, `skill-package/`) | **fatto**, 7 test | `abb84ee` |
| Separazione Alexa / pulizia template | **fatto nel branch architetturale**: rinomina `alexa-companion/`, contratto senza flag smartwatch, moduli template senza consumer e dipendenze AWS rimossi; 56 test e build verdi | ADR-0001 |
| PR-REL-0 — README veritiero | **fatto** | `8c6d6f5` |
| PR-REL-0 — `LICENSE` | in attesa della scelta della licenza | §1.6 |
| PR-REL-0 — `buildTypes.release`, firma, R8, CI release | da fare; richiede CI funzionante per la validazione | §9 |
| PR-DATA-1 (IO/Default, download bounded, `SHA256SUMS`, integrità asset, `lastServiceDate` + avviso) | **fatto e validato**: Android CI #195 **verde** su `98f54bc` (test, lint, assembleDebug di app e wear) | `8efede4`…`2c5aaf5`, merge `d4423bc` |
| PR-DATA-0 (pack scaduti) | in attesa dei feed aggiornati dalle sorgenti (bloccate dal proxy di questo ambiente) | §4.1 |
| PR-COMP-1/2 (bridge nativi, shared-text, reminder, itinerari, Agenda, route, timer, checksum) | **fatto e integrato** (merge `903754d`); verificato in locale (tsc con `tests/`, 52 test, config e release-inputs, prebuild idempotente); Companion CI in esecuzione | `e51cd4f`…`9701515` |
| CI GitHub Actions | sbloccata dal proprietario il 23/09; run #194/#1 falliti senza step erano dovuti al blocco dell'account | run `35809228873`, `35809230083` |
| Gerarchia prodotto e sorgente Alexa | **deciso**: `app/` principale, `alexa-companion/` companion canonico, snapshot WebDev archiviato e rimosso dalla linea attiva | ADR-0001; `archive/surfaces-20260923` |
| Triage funzioni WebDev non-Alexa | **completato**: capacità assegnate a `app/`, `companion-expo/`, futuro `services/companion-sync/` oppure archivio | `TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md` |
| PR-DATA-CATALOG-QA | **avviata**: input finiti, coordinate invalide, tie-break italiano, unicità ID/codici e range coordinate; test Gradle in attesa di SDK/CI | `StationProximity*`, `EmbeddedCuratedRailStationCatalogTest` |
| Correzioni successive | riprese limitatamente alla separazione architetturale e ai porting a basso rischio; il resto segue l'ordine di §10 | §10 |

## 14. Matrice di tracciabilità finding → PR → gate

Identificativi dal rapporto `docs/audit-independent-2026-09-23.md` (V, C, F, D, IG) e dall'addendum (A, B, C-addendum). Stato al commit di questo documento.

| Finding | PR | Gate | Stato |
|---|---|---|---|
| V1 runtime Manus / Bearer / frame | PR-SEC-1 | G1 | fatto (`68f2bd5`) |
| V2 authorize senza consenso/CSRF | PR-SEC-2 | G1 | fatto (`f591719`) |
| V3 redirect_uri senza allowlist | PR-SEC-2 | G1 | fatto |
| V4 (ritirato) | — | — | nessuna azione |
| V5 unlink non opponibile ai token / auto-de-revoca | PR-SEC-2 | G1 | fatto |
| V6 collisione upsert `alexaUserHash` | PR-SEC-2 | G1 | fatto |
| V7 download GTFS non limitato | PR-DATA-1 | G2 | fatto (`49e35f3`), validato da Android CI #195 |
| V8 hardening (algorithms, appId, sessioni, env, body, rate limit, proxy) | PR-SEC-2 | G1 | fatto; rimozione `storageProxy` → PR-ALEXA-OPS (da fare) |
| C1 OOM pack AMT | PR-DATA-0 + PR-DATA-2 | G2 | da fare (repack in attesa feed; indice compatto) |
| C2 rete sul Main nel fallback ES | PR-DATA-1 | G2 | fatto (`8efede4`), validato da Android CI #195 |
| C3/C4 crash latenti watch (TYPE_DELETED, chiave LazyColumn) | PR-WEAR-1 | G4 | da fare |
| C5 parsing CPU sul Main | PR-DATA-1 (resolver ES) + PR-DATA-2 (loadRoute) | G2 | parziale |
| C6 reminder superstite alla delete | PR-COMP-2 | G5 | fatto (`5b780d8`) |
| C7 itinerari superstiti al clear | PR-COMP-2 | G5 | fatto (`6954ef9`) |
| F1 mismatch applicationId/firma Wear (root e companion) | PR-WEAR-1 | G4 | da fare (decisione §5) |
| F2 handler refresh telefono assente | PR-WEAR-2 | G4 | da fare |
| F3 `<data>` mancante nel listener root | PR-WEAR-1 | G4 | da fare |
| F4 bridge JS↔Kotlin companion | PR-COMP-1 | G5 | fatto (`e51cd4f`) |
| F5 shared-text mai consegnato | PR-COMP-1 | G5 | fatto (`675ed1a`) |
| F6 superficie widget mai renderizzata | PR-ANDROID-UX | G3 | da fare |
| F7 README/realtime non veritieri, adapter morti | PR-REL-0 (README fatto `8c6d6f5`) + PR-DATA-3 (adapter: cablare o rimuovere) | G3 | parziale |
| F8 Apple Watch senza produttore, project.yml | PR-APPLE | G3 | decisione (§8) |
| F9 falso "Cancello" | PR-DATA-3 | G2 | da fare |
| Minori: gzip svizzero, stopSequence | PR-DATA-3 | G2 | da fare |
| Minori: shortcut `/plan`, `/guide`, Agenda stale, timer, checksum companion | PR-COMP-2 | G5 | fatto (`ac2ed77`, `ba11ea6`, `b8a5f8d`, `9701515`); sync stub: sezione ancora presente, da nascondere |
| Minori: BackHandler, launchMode | PR-ANDROID-UX | G3 | da fare |
| Prestazioni: indice stazioni, 12 ZIP per tasto | PR-ANDROID-UX | — | da fare |
| Prestazioni: shapes ARST/AMT nell'APK | PR-DATA-0 | G2 | da fare |
| Teorici: gtfs-realtime-bindings 0.0.4 | §9 | — | da fare |
| Teorici: `openService` senza direzione esplicita, cache `var` adapter | PR-ANDROID-UX / PR-DATA-3 | — | da fare |
| D-5 migrazione 0002 orfana | PR-DB-1 | G1 | fatto (`0439be5`) |
| Debito: MainActivity/TrainViewModel, stringhe, contratti snapshot | §9 / §5.3 | — | P2 (test contrattuali companion ora reali: `e51cd4f`) |
| IG-1 `surfaces/` e albero web canonico | ADR-0001 + PR-SURFACES-ARCHIVE | G6 | **fatto**: triage completo, archivio remoto al commit `37a408d6`, 1.412 file/120 MB rimossi dalla linea attiva |
| IG-2 `main` stale, tag assenti | PR-CI-GOV | G6 | tag baseline in locale; resto da fare |
| IG-3 `SHA256SUMS` stale, nessuna verifica | PR-DATA-1 | G2 | fatto (`58982c2`, `2c5aaf5`) |
| IG-4 CI: trigger push, wrapper validation, prebuild clean | PR-ALEXA-OPS (workflow companion fatto `d0bca14`) + PR-CI-GOV | G6 | parziale |
| IG-5 md in radice, todo di sessione, `org.json` | PR-CI-GOV | — | da fare |
| IG-6 placeholder analytics, residui template | PR-SEC-1 | G1 | fatto |
| IG-7 DataItem non cancellato, `INTERNET` su wear | PR-WEAR-1 | G4 | da fare |
| A1 pack GTFS scaduti | PR-DATA-1 (avviso + `lastServiceDate`, `1e7dc56`) + PR-DATA-0 (feed) | G2 | parziale: feed in attesa |
| A2 corse notturne giorno precedente | PR-DATA-3 | G2 | da fare |
| A3 FERIALE/festività | PR-DATA-3 | G2 | da fare |
| A4 pipeline pack non riproducibile, 9/12 non filtrati | PR-DATA-0 | G2 | da fare |
| A5 banda "verificato" per solo-programmato | PR-DATA-3 | G3 | da fare |
| A6 fuso orario dispositivo | PR-DATA-3 | — | da fare |
| A7 policy descriptor inattivi, supplemental senza cache | PR-DATA-3 | — | da fare |
| A8 hub RFI solo origine | PR-DATA-3 | — | da fare |
| A9 `ExternalRailCredentials.toString` | PR-DATA-3 | — | da fare |
| B1–B5 Back, deep link, process death, shortcut, tap widget | PR-ANDROID-UX | G3 | da fare |
| B6 target widget Stazione/Treno mai alimentati | PR-ANDROID-UX | G3 | da fare |
| B7–B10 widget (errori globali, direzione, refresh, VM in configurazione) | PR-ANDROID-UX | G3 | da fare |
| B11 allarmi (boot, mezzanotte, fascia silenziosa, permesso, import, ONCE, contentIntent) | PR-ANDROID-UX | G3 | da fare |
| B12 backup (limite, `alert.time`, ultima tratta, decode senza runCatching) | PR-ANDROID-UX | — | da fare |
| B13 ricerca stazioni in composizione | PR-ANDROID-UX | — | da fare |
| B14–B15 font scale, stringhe hardcoded | §9 | — | P2 |
| C1-add. `buildTypes.release` assente | PR-REL-0 | G6 | da fare (richiede CI) |
| C2-add. `LICENSE` assente | PR-REL-0 | G6 | decisione |
| C3-add. README non veritiero | PR-REL-0 | G3 | fatto; privacy policy URL da fare |
| C4-add. icona/splash/label companion | PR-BRAND | G5 | da fare |
| C5-add. skill Alexa non certificabile | PR-ALEXA-MODEL | G6 | fatto (`abb84ee`); icone e URL nel manifest da valorizzare |
| C6-add. Alexa Companion non operabile fuori da Manus | PR-SEC-2 + PR-ALEXA-OPS | G6 | fatto (porta, `/healthz`, env, CI, `.env.example`); dipendenza dal portale OAuth Manus: decisione |
| C7-add. Wear `standalone=false` vs policy Play | PR-WEAR-1 | G4 | chiarito: ammesso per companion dipendente; distribuzione ancora da decidere |
| C8-add. `ACCESS_FINE_LOCATION` companion | PR-BRAND | G5 | da fare |
| C9-add. informativa privacy in-app | PR-ANDROID-UX | G3 | da fare |
| C10-add. PR #1 aperta, `template.json`, `VERIFICA_RELEASE.md` | PR-CI-GOV | — | da fare |

## 15. Decisioni aperte (bloccano le PR indicate)

| Decisione | Blocca | Opzioni |
|---|---|---|
| Licenza del repository | PR-REL-0, Release A | MIT (come dichiara `alexa-companion/package.json`) o altra |
| Distribuzione Wear (Play track vs sideload) | PR-WEAR-1 | `standalone=false` è ammesso per un companion dipendente; scegliere il canale e documentare l'assenza dell'app telefono |
| Coppia Wear canonica (`app`+`wear` vs companion) | PR-WEAR-1/2, PR-COMP-1 | consigliata `app`+`wear`; companion Wear sperimentale |
| Apple Watch: congelare o completare | PR-APPLE, G3 | congelare come prototipo finché non esiste l'app iOS |
| Adapter realtime non cablati (RSS RFI, STA, SNCF-RT, enrichment) | PR-DATA-3, README | cablare a una superficie o rimuovere |
| Backup senza tratte | PR-ANDROID-UX | accettare (codec) o mantenere il rifiuto e togliere il ramo morto |
| Portale OAuth Manus per il login dashboard | Release B su host non-Manus | mantenere (solo hosting Manus) o sostituire il provider |
| Sblocco GitHub Actions (minuti/limite di spesa) | validazione di ogni PR Kotlin | impostazioni GitHub → Billing → Actions |
| Feed GTFS aggiornati (Cotral, Trentino, TFT, AMT) | PR-DATA-0, G2 | download dalle sorgenti in `manifest.json` dal PC del proprietario |
