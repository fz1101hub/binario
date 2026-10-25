# Google Play — modulo Sicurezza dei dati per Binario

**Stato del documento:** risposte derivate dal codice di `app/` e `wear/` alla revisione corrente, da inserire nel modulo *Sicurezza dei dati* della Play Console per `it.binario.app`. Le risposte sono proposte motivate: la dichiarazione finale resta responsabilità del titolare, che deve confermarle leggendo le definizioni correnti di Google e mantenerle coerenti con [PRIVACY.md](../../PRIVACY.md). Dove il codice cambia, cambia anche questa pagina: `tools/verify_privacy_policy.py` fallisce se un host presente nel codice Android manca dalla tabella in fondo o se la tabella elenca un host non più presente nel codice.

## Fatti verificabili nel codice

| Aspetto | Comportamento del codice | Riferimento |
|---|---|---|
| Backend Binario | Nessun server, account o sincronizzazione remota per l'app Android e Wear; i dati restano nelle `SharedPreferences` private. | `app/src/main/java/it/binario/app/*Store.kt`, `PRIVACY.md` |
| SDK di terze parti | Nessun SDK pubblicitario, di analytics o di crash reporting; le dipendenze sono AndroidX, Compose, WorkManager, jsoup, `gtfs-realtime-bindings` e Play Services Wearable. | `app/build.gradle.kts`, `wear/build.gradle.kts` |
| Rete | HTTPS con trust store di sistema. Il traffico in chiaro è disabilitato per ogni dominio tranne `www.viaggiatreno.it` (senza sottodomini), consentito dal commit `adb3481` per l'API pubblica legacy ViaggiaTreno: il client prova sempre HTTPS e passa in HTTP solo quando ViaggiaTreno reindirizza alla stessa risorsa sullo stesso host. Usano questo passaggio le richieste realtime ViaggiaTreno e, con le fonti personali, gli avvisi per treno e l'infomobilità Trenitalia; queste richieste non sono cifrate. Il modulo Wear **non** dichiara `INTERNET`. | `app/src/main/res/xml/network_security_config.xml`, `data/live/ViaggiaTrenoRealtimeAdapter.kt`, `data/live/PublicSourceHttp.kt`, manifest Wear |
| Permessi dichiarati | `INTERNET`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`; `queries` per `RECOGNIZE_SPEECH`. Nessun `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, posizione in background, contatti, file o foto. | `app/src/main/AndroidManifest.xml` |
| Posizione | Letta una volta, su azione esplicita, per ordinare in memoria il catalogo stazioni offline; non viene salvata, inclusa nei backup o nei widget, né inviata a un host. | `StationProximity.kt`, `PRIVACY.md` § Posizione effimera |
| Notifiche | Avvisi pendolare e sveglia fermata sono notifiche locali pianificate dal dispositivo; nessun push server. | `CommuterAlerts.kt`, `StopAlarmPlanner.kt` |
| Dettatura | Avvia il riconoscitore vocale di sistema e riceve solo il testo; l'app non apre il microfono. | `PlannerVoiceParser.kt` |
| Backup | `allowBackup="false"`, regole di esclusione per cloud backup e trasferimento; l'export JSON è manuale e scelto dall'utente. | `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` |
| Wear OS | Il telefono invia tramite Google Play Services Data Layer etichetta tratta, provenienza, freschezza, avviso e al massimo due corse; nessuna credenziale, posizione o audio. La revoca cancella il DataItem e la cache del watch. | `app/src/main/java/it/binario/app/wear/`, `wear/src/main/java/it/binario/wear/sync/` |

## Flussi di rete

Le fonti best-effort (categoria B) dipendono da `BuildConfig.PERSONAL_REALTIME_SOURCES`. Oggi `app/build.gradle.kts` lo imposta a `true` per impostazione predefinita **sia in debug sia in release**: una release costruita senza `-PBINARIO_PERSONAL_REALTIME_SOURCES=false` contatta quindi anche gli host B. Se la build per lo store resta con il valore predefinito, queste risposte vanno lette con le righe B attive; con `false` valgono solo i flussi A seguenti.

1. **`prm.rfi.it` — quadri orario programmati RFI.** Ricerca tratta, tabellone stazione e refresh del widget inviano una richiesta `GET` con l'identificativo pubblico PRM della stazione di partenza (o della stazione del tabellone), `lin=it` e un `User-Agent` che identifica Binario. Nessun account, posizione, numero treno dell'utente o testo libero. RFI vede i metadati ordinari di rete della richiesta.
2. **`jbb.ghsq.de` — fallback community European Sleeper.** Solo quando RFI e i GTFS embedded non hanno restituito la tratta richiesta, l'app scarica l'archivio GTFS statico e i Trip Updates dallo stesso mirror con richieste `GET` prive di parametri utente; l'archivio resta in memoria per sei ore. L'host vede solo i metadati di rete.
3. **`gtfs.api.opendatahub.com` e `proxy.transport.data.gouv.fr` — Trip Updates STA Alto Adige e SNCF.** Quando una ricerca tratta restituisce corse dei pack GTFS STA o SNCF, l'app scarica il feed GTFS-Realtime completo del rispettivo host e lo riconcilia sul dispositivo; la richiesta non contiene la tratta né altri parametri utente.
4. **`www.rfi.it` — feed RSS infomobilità RFI.** Solo quando l'utente apre l'area «Avvisi e infomobilità»: `GET` del feed nazionale, senza parametri utente; cache di dieci minuti.
5. **`api.open-meteo.com` — meteo a destinazione.** Quando l'utente apre il dettaglio di un treno, con l'impostazione «Meteo a destinazione» attiva (predefinita) e la stazione di arrivo presente nel catalogo con coordinate: `GET` con le **coordinate della stazione** arrotondate a due decimali (circa 1 km) e parametri fissi. Mai la posizione dell'utente. Cache di tre ore per luogo e giorno; l'impostazione disattivata elimina la richiesta.
6. **Google Play Services Data Layer.** Trasferimento fra i dispositivi dell'utente (telefono e orologio associato) gestito da Google; Binario non riceve una copia e non usa un proprio servizio.

Con le fonti personali attive si aggiungono gli host B della tabella finale: tabelloni e tratte live RFI, Italo e ViaggiaTreno, tabelloni svizzeri `transport.opendata.ch`, avvisi per treno e infomobilità Trenitalia su `www.viaggiatreno.it`, avvisi Trenord su `www.trenord.it`. Tutti gli altri host presenti nel codice sono classificati nella tabella finale: adapter non collegati ad alcuna superficie, provenienza offline dei data pack, soli metadati del catalogo fonti o collegamenti aperti nel browser su tocco.

## Risposte proposte al modulo

### Panoramica

| Domanda della Console | Risposta proposta | Motivazione |
|---|---|---|
| L'app raccoglie o condivide uno dei tipi di dati utente richiesti? | **No** | Nessun dato viene trasmesso a Binario (non esiste un backend) né a un fornitore di servizi per conto di Binario. La posizione è trattata solo sul dispositivo. L'identificativo pubblico della stazione inviato a `prm.rfi.it`, le coordinate arrotondate della stazione di arrivo inviate a Open-Meteo e, con le fonti personali, i codici stazione e numeri treno inviati alle fonti best-effort sono l'oggetto stesso della richiesta avviata dall'utente, elaborati in modo effimero per restituire orari, avvisi o previsione; rientrano nell'eccezione per i trasferimenti dovuti a un'azione esplicita dell'utente che si aspetta ragionevolmente quell'invio. Il trasferimento Wear avviene fra dispositivi dello stesso utente tramite Google Play Services. |
| Tutti i dati utente raccolti dall'app sono criptati in transito? | Non applicabile senza raccolta; se la Console la propone, **No** finché le fonti personali sono attive | Le richieste a `www.viaggiatreno.it` (codici stazione, data/ora, numeri treno, avvisi) viaggiano in HTTP non cifrato dopo il reindirizzamento della fonte; tutte le altre richieste sono HTTPS. Con `PERSONAL_REALTIME_SOURCES=false` nessuna richiesta è in chiaro. |
| Fornisci un modo per richiedere la cancellazione dei dati? | Non applicabile senza raccolta; in app esiste la rimozione locale | Tratte, preferiti, monitoraggi e avvisi si eliminano dall'app; la cancellazione dati dell'app o la disinstallazione rimuovono tutto. |
| Revisione di sicurezza indipendente (MASA) | No | Non eseguita. |

**Alternativa prudente.** Se il titolare o la revisione Google ritiene che l'invio dell'identificativo stazione a RFI vada dichiarato, la dichiarazione coerente con il codice è: tipo di dato *Attività nell'app → Cronologia delle ricerche nell'app*, **condiviso** con terze parti (gestore ferroviario e, con le fonti personali, imprese ferroviarie e `transport.opendata.ch`), **non raccolto** da Binario, trattamento **effimero**, **necessario** per la funzionalità dell'app e **non facoltativo**, finalità *Funzionalità dell'app*. Le coordinate arrotondate della stazione di arrivo inviate a Open-Meteo possono rivelare la destinazione cercata: nella stessa logica rientrano in *Cronologia delle ricerche nell'app*, **condivise** con Open-Meteo, effimere, **facoltative** (impostazione «Meteo a destinazione»), finalità *Funzionalità dell'app*; non sono posizione dell'utente. Nessun'altra riga cambia.

### Tipi di dati, uno per uno

| Categoria Play | Tipo | Raccolto | Condiviso | Note derivate dal codice |
|---|---|---|---|---|
| Posizione | Posizione approssimativa | No | No | `ACCESS_COARSE_LOCATION` su azione esplicita, solo in memoria, mai trasmessa. |
| Posizione | Posizione esatta | No | No | Permesso non dichiarato. |
| Informazioni personali | Nome, email, ID utente, indirizzo, telefono, altro | No | No | Nessun account; nessun campo di profilo. |
| Informazioni finanziarie | Tutte | No | No | Nessun acquisto, pagamento o biglietteria. |
| Salute e fitness | Tutte | No | No | Non trattate. |
| Messaggi | Tutte | No | No | Non trattate. |
| Foto e video | Tutte | No | No | Non trattate. |
| File audio | Registrazioni vocali, musica, altri file audio | No | No | La dettatura usa il riconoscitore di sistema; l'app riceve solo testo e non lo conserva come trascrizione autonoma. |
| File e documenti | Tutti | No | No | L'export JSON è scritto nella destinazione scelta dall'utente; Binario non lo riceve. |
| Calendario | Eventi | No | No | Non trattati. |
| Contatti | Contatti | No | No | Non trattati. |
| Attività nell'app | Interazioni, cronologia ricerche, app installate, contenuti generati, altre azioni | No | No (vedi alternativa prudente) | Ricerche, tratte e preferiti restano locali; la stazione richiesta a RFI è l'oggetto della richiesta stessa. |
| Navigazione web | Cronologia | No | No | Nessun WebView o browser integrato. |
| Informazioni e prestazioni dell'app | Log di arresto anomalo, diagnostica, altro | No | No | Nessun SDK di crash reporting o analytics. |
| Dispositivo o altri ID | ID dispositivo | No | No | Nessun identificatore pubblicitario, IMEI o ID installazione raccolto. |

### Dichiarazioni collegate che la Console richiede

| Sezione | Risposta proposta | Note |
|---|---|---|
| Norme relative alle autorizzazioni per la posizione | Nessuna autorizzazione in background: non serve la dichiarazione di posizione in background. | Solo `ACCESS_COARSE_LOCATION` in primo piano. |
| `SCHEDULE_EXACT_ALARM` | Usato per *Sveglia fermata* e *Avvisi pendolare*, che l'utente crea esplicitamente; l'app continua a funzionare se l'utente nega l'accesso speciale. | Da Android 14 il permesso non è concesso automaticamente alle app che non sono sveglie o calendari: descriverlo nelle note per il revisore se richiesto. |
| `POST_NOTIFICATIONS` | Richiesto solo quando l'utente crea un avviso. | Notifiche locali. |
| Accesso all'app | Tutte le funzionalità sono disponibili senza credenziali: nessuna istruzione di accesso. | Nessun login. |
| Annunci | L'app non contiene annunci. | Nessun SDK pubblicitario. |
| Norme relative all'URL dell'informativa sulla privacy | **Blocco aperto**: serve un URL pubblico verificato che pubblichi il contenuto di `PRIVACY.md` con titolare e recapito. | Vedi il blocco dichiarato in `PRIVACY.md`. |

## Wear OS

Le stesse risposte valgono per il bundle Wear dello stesso listing: il modulo non dichiara `INTERNET`, non interroga fonti ferroviarie e conserva soltanto lo snapshot ricevuto dal telefono. Nessuna raccolta o condivisione aggiuntiva.

## Inventario degli host presenti nel codice Android

La tabella copre ogni host `http(s)://` trovato in `app/src/main/java`, `wear/src/main/java`, `app/src/main/assets/gtfs/manifest.json` e `app/src/main/assets/stations/manifest.json`, esclusi gli URI di namespace dei parser XML, che non sono endpoint di rete. Le categorie:

- **A — build distribuita:** contattato a runtime dall'app pubblicata, qualunque sia `PERSONAL_REALTIME_SOURCES`.
- **B — fonti best-effort personali:** contattato solo con `PERSONAL_REALTIME_SOURCES=true`, che oggi è il **valore predefinito anche in release**; `-PBINARIO_PERSONAL_REALTIME_SOURCES=false` lo esclude dalla build.
- **C — adapter non collegato:** esiste un client nel data layer, ma nessuna superficie lo invoca nella build corrente, oppure richiede credenziali che nessun chiamante fornisce.
- **D — provenienza dei data pack:** URL da cui `tools/build_gtfs_packs.py` ha scaricato offline i GTFS embedded; l'app legge solo gli asset inclusi.
- **E — catalogo fonti:** `landingPage` o `downloadOrProbeUri` del censimento fonti; nessun adapter, nessun traffico.
- **F — collegamento esterno:** pagina aperta nel browser di sistema solo quando l'utente tocca il collegamento; l'app non contatta l'host.

### Host contattabili a runtime (A, B, C)

| Host | Categoria | Dove nel codice | Quando viene contattato | Dati inviati |
|---|---|---|---|---|
| `prm.rfi.it` | A | `data/planned/RfiProgrammedTimetableAdapter.kt`, `RfiProgrammedStationBoardAdapter.kt`, `RfiSupplementalHubTimetableAdapter.kt` | Ricerca tratta, tabellone stazione e refresh widget per le stazioni hub RFI mappate | `GET` con identificativo pubblico PRM della stazione, `lin=it`, `User-Agent` Binario |
| `jbb.ghsq.de` | A | `data/sources/InternationalSourceCatalog.kt` (URL del catalogo, scaricati da `data/gtfs/GtfsArtifactDownloader.kt` per `RemoteGtfsFallbackDataSource.kt`), `data/live/EuropeanSleeperGtfsRealtimeAdapter.kt` | Solo dopo che le fonti primarie non hanno restituito la tratta: GTFS statico e Trip Updates European Sleeper | `GET` senza parametri utente; soli metadati di rete |
| `api.open-meteo.com` | A | `data/weather/OpenMeteoForecastAdapter.kt`, catalogo | Dettaglio di un treno, con l'impostazione «Meteo a destinazione» attiva (predefinita) e la stazione di arrivo nel catalogo con coordinate | `GET` con le coordinate della stazione di arrivo arrotondate a due decimali e parametri fissi; mai la posizione dell'utente |
| `iechub.rfi.it` | B | `data/live/RfiStationBoardAdapter.kt` | Tabellone stazione, arricchimento e ricerca live delle tratte; catalogo pubblico delle stazioni del monitor | `GET` con `placeId` pubblico della stazione |
| `italoinviaggio.italotreno.com` | B | `data/live/ItaloPublicStationAdapter.kt`, `ItaloTrainNumberAdapter.kt`, catalogo | Tabellone stazione, arricchimento delle tratte, ricerca per numero treno | `GET` con codice pubblico della stazione o numero treno |
| `transport.opendata.ch` | B | `data/live/SwissOpenTransportStationBoardAdapter.kt`, catalogo | Tabellone stazione e arricchimento delle tratte con partenza da una stazione svizzera (UIC `85…`) o, una sola volta per stazione, ricerca del nome di una stazione di confine | `GET` con numero UIC della stazione, tipo di tabellone, limite e, per orari non correnti, data/ora; per la sola ricerca di identità il nome della stazione |
| `www.trenord.it` | B | `data/live/TrenordLineNoticesAdapter.kt`, catalogo | Area avvisi aperta dall'utente, dettaglio di un treno Trenord o di una tratta nell'area Trenord, tabellone di una stazione dell'area Trenord | `GET` dell'elenco pubblico delle direttrici, senza parametri utente |
| `www.viaggiatreno.it` | B | `data/live/ViaggiaTrenoRealtimeAdapter.kt`, `ViaggiaTrenoTrainNoticesAdapter.kt`, `TrenitaliaInfomobilityNewsAdapter.kt`, catalogo (`ViaggiaTrenoStationAdapter.kt` esiste ma non è istanziato) | Tabellone stazione, ricerca tratta live, ricerca per numero treno, dettaglio corsa; avvisi nel dettaglio di un treno Trenitalia; area avvisi aperta dall'utente | `GET` con codice stazione e data/ora, numero treno, codice di origine e giorno della corsa; numero treno per gli avvisi; nessun parametro per l'elenco infomobilità. HTTPS, poi **HTTP non cifrato** sulla stessa risorsa quando la fonte reindirizza |
| `www.rfi.it` | A | `data/live/RfiInfomobilityRssAdapter.kt`, catalogo | Area avvisi aperta dall'utente | Feed RSS nazionale, nessun dato utente |
| `gtfs.api.opendatahub.com` | A, D | `data/live/StaRealtimeAdapter.kt`, catalogo, provenienza del pack `alto-adige-sta-gtfs` | Ricerca tratta con corse del pack STA (Trip Updates); posizioni e avvisi STA non hanno chiamanti; il pack è scaricato offline | Feed GTFS-Realtime JSON, nessun dato utente |
| `proxy.transport.data.gouv.fr` | A | `data/live/SncfGtfsRealtimeAdapter.kt`, catalogo | Ricerca tratta con corse del pack SNCF (Trip Updates); gli avvisi SNCF non hanno chiamanti | Feed GTFS-Realtime protobuf, nessun dato utente |
| `api.opentransportdata.swiss` | C | `data/live/SwissGtfsRealtimeAdapter.kt` | Mai: richiede un token `ExternalRailCredentials` che nessun chiamante fornisce | — |
| `apis.deutschebahn.com` | C | `data/live/DbTimetablesStationBoardAdapter.kt` | Mai: richiede `DB-Client-Id`/`DB-Api-Key` custoditi fuori dall'APK; nessun chiamante | — |

### Host presenti solo come provenienza o metadati (D, E)

| Host | Categoria | Ruolo |
|---|---|---|
| `arstspa.info` | D | Provenienza del pack `arst-gtfs`; anche nel catalogo |
| `data.opentransportdata.swiss` | D | Provenienza del pack `ch-otd-gtfs`; anche nel catalogo |
| `dati.toscana.it` | D | Provenienza del pack `toscana-tft-gtfs`; anche nel catalogo |
| `eu.ftp.opendatasoft.com` | D | Provenienza del pack `sncf-gtfs`; anche nel catalogo |
| `static.web.oebb.at` | D | Provenienza del pack `oebb-gtfs`; anche nel catalogo |
| `www.dati.lombardia.it` | D | Provenienza del pack `lombardia-trenord-gtfs`; anche nel catalogo |
| `www.hzpp.hr` | D | Provenienza del pack `hzpp-gtfs`; anche nel catalogo |
| `www.trentinotrasporti.it` | D | Provenienza del pack `trentino-gtfs`; anche nel catalogo |
| `www.wimob.it` | D | Provenienza del pack `eav-gtfs`; anche nel catalogo |
| `arenaways.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `data.oebb.at` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `developer-docs.deutschebahn.com` | E | Catalogo fonti (`InternationalSourceCatalog.kt`) |
| `developers.deutschebahn.com` | E | Catalogo fonti (`InternationalSourceCatalog.kt`) |
| `ferrovieappulolucane.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `github.com` | E | Catalogo fonti (`InternationalSourceCatalog.kt`): landing page del mirror DELFI community |
| `infomobilita.astralspa.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `opendatagoogletransit.comune.genova.it` | E | Catalogo fonti (`RailSourceCatalog.kt`): feed HTTP, pack escluso dall'APK |
| `opentransportdata.swiss` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `potniski.sz.si` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `regiojet.com` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `ressources.data.sncf.com` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `scraped.data.public-transport.earth` | E | Catalogo fonti (`InternationalSourceCatalog.kt`): mirror DELFI community, nessun adapter |
| `travel.mob.cotralspa.it` | E | Catalogo fonti (`RailSourceCatalog.kt`): feed HTTP, pack escluso dall'APK |
| `urlaubs-express.de` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `web.ferroviedellacalabria.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `westbahn.at` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `www.arst.sardegna.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.cd.cz` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `www.circumetnea.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.data.gouv.fr` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.eavsrl.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.europeansleeper.eu` | E | Catalogo fonti (`InternationalSourceCatalog.kt`, `RailSourceCatalog.kt`): landing page, nessun traffico |
| `www.ferrotramviaria.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.ferroviagenovacasella.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.ferrovieudinecividale.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.fseonline.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.fstrenituristici.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |
| `www.gtt.to.it` | E | Catalogo fonti (`RailSourceCatalog.kt`): licenza non compatibile, mai abilitato |
| `www.mavcsoport.hu` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `www.opendata-oepnv.de` | E | Catalogo fonti (`InternationalSourceCatalog.kt`): DELFI ufficiale dietro registrazione |
| `www.snalltaget.se` | E | Catalogo fonti (`InternationalSourceCatalog.kt`), monitor-only |
| `www.trenitalia.com` | E | Catalogo fonti (`RailSourceCatalog.kt`): pagina pubblica dell'infomobilità Trenitalia |
| `www.tuabruzzo.it` | E | Catalogo fonti (`RailSourceCatalog.kt`) |

### Collegamenti aperti nel browser su tocco (F)

| Host | Categoria | Ruolo |
|---|---|---|
| `open-meteo.com` | F | Attribuzione CC BY 4.0 della previsione e condizioni d'uso nel catalogo fonti (`OpenMeteoForecastAdapter.kt`, `RailSourceCatalog.kt`) |
| `scioperi.mit.gov.it` | F | Calendario scioperi del Ministero delle infrastrutture e dei trasporti, dall'area avvisi (`data/notices/RailExternalLinks.kt`) |

Nessun SDK di terze parti aggiunge host propri: le uniche librerie con accesso alla rete sono quelle AndroidX/Play Services elencate sopra, e Play Services Wearable comunica solo con l'infrastruttura Google del dispositivo associato.
