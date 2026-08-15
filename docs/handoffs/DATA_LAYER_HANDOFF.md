# Handoff — Fondamenta del livello dati

**Ramo di lavoro:** `feature/data-foundations`  
**Baseline di partenza:** `main` v0.15.0, commit `1345111e03f3ac17cb08d5605a3fb9f09950aa6f`  
**Stato:** fondamenta architetturali introdotte; nessuna fonte live ancora attiva

> Questo file è il punto di ingresso obbligatorio per qualunque conversazione o sviluppatore che riprenda il lavoro su fonti ferroviarie. Il repository GitHub è la sola base canonica: non usare ZIP o APK precedenti come sorgente di sviluppo.

## Cosa è stato aggiunto

| Area | Percorso | Scopo |
|---|---|---|
| Modello canonico | `app/src/main/java/it/binario/app/data/core/RailCore.kt` | Identità di stazione/operatore/corsa, orari con timezone, fermate, viaggi, avvisi, posizione e provenienza per campo |
| Contratti provider | `app/src/main/java/it/binario/app/data/provider/ProviderContracts.kt` | Capability, richieste tipizzate, risultati parziali e errori distinguibili |
| Registro provider | `app/src/main/java/it/binario/app/data/provider/ProviderRegistry.kt` | Policy di abilitazione, ordine per capability, retry e circuit breaker |
| Catalogo fonti | `app/src/main/java/it/binario/app/data/provider/KnownProviders.kt` | Configurazione dichiarativa delle fonti candidate, tutte disabilitate fino a implementazione e verifica |
| Composition root | `app/src/main/java/it/binario/app/data/repository/BinarioDataGraph.kt` | Repository unico condiviso da ViewModel e widget |
| Compatibilità v0.x | `LegacyTrainDataSourceFacade` | Mantiene il contratto corrente `TrainDataSource` e quindi la UX esistente |
| Test | `app/src/test/java/it/binario/app/data/provider/ProviderRegistryTest.kt` | Ordinamento, disabilitazione e unicità degli ID provider |

`TrainViewModel` e `WidgetRefreshWorker` non istanziano più direttamente `RailDataRepository`; usano ora `BinarioDataGraph.routeDataSource()`. Questo è essenziale per far condividere a UI, widget e futuri companion la stessa configurazione delle fonti.

## Stato delle fonti

| Fonte logica | Stato nel codice | Modalità prevista | Nota |
|---|---|---|---|
| GTFS regionali italiani | Registrata, disabilitata | Data pack locale / open data | Prima tranche statica |
| GTFS Svizzera | Registrata, disabilitata | Open data | Copertura SBB, TILO, BLS, RhB e tratte italiane presenti |
| GTFS-RT Svizzera | Registrata, disabilitata | Gateway preferito | Richiede token, limiti e rispetto termini |
| SNCF statico/realtime | Registrata, disabilitata | Open data | TGV/TER e avvisi sul tratto italiano |
| ÖBB GTFS | Registrata, disabilitata | Open data | Statico per Railjet/EC/Nightjet |
| ViaggiaTreno | Registrata, disabilitata | Best effort sotto feature flag | Non è un’open API documentata; nessuna credenziale nell’app |
| RFI tabelloni | Registrata, disabilitata | Best effort/gateway | Non presentata come API open garantita |
| Italo in Viaggio | Registrata, disabilitata | Best effort/gateway | Non usare token o chiavi di app terze |

Nessun adapter HTTP, endpoint, token o parser live è stato aggiunto in questa modifica. L’app continua a usare il catalogo dimostrativo locale e lo dichiara esplicitamente.

## Vincoli non negoziabili

1. Non incorporare credenziali, token o chiavi di provider nell’APK.
2. Non riutilizzare API, chiavi, dataset, asset o codice di Treniamo, On The Rails o app ufficiali.
3. Non aggirare autenticazione, CAPTCHA, WAF, rate limit o condizioni tecniche delle fonti.
4. Tutti i campi operativi devono mantenere origine, timestamp, TTL e livello di confidenza.
5. Orari, realtime, stale, cache e indisponibilità restano stati distinti nella UI.
6. Le posizioni interpolate devono riportare sempre `PositionKind.INTERPOLATED`.
7. Le preferenze Lazio/Roma influenzano soltanto suggerimenti personali, mai la neutralità o la completezza del dato.
8. Widget e Wear leggono projection/cache del telefono; non iniziano polling concorrenti o conservano segreti.

## Prossimo incremento consigliato

Il prossimo branch dovrebbe partire dall’ultima `main` e integrare **solo le fondazioni A/B** della specifica:

1. spostare i contratti dal package app a moduli o package core stabili;
2. introdurre Room per catalogo stazioni, alias, provider health e snapshot;
3. importare una sola fonte GTFS con licenza aperta in un data pack di test;
4. sostituire gradualmente la lista `stations` hardcoded nel `TrainViewModel` mediante una facade compatibile;
5. aggiungere fixture, migration test e contract test senza attivare ancora realtime best-effort.

La successiva tranche potrà aggiungere SNCF e Open Data Svizzera. ViaggiaTreno, RFI e Italo restano separati in feature flag e non devono bloccare lo sviluppo statico/open.

## Verifica e build

```bash
./gradlew :app:testDebugUnitTest
```

La build richiede Android SDK API 36 e un JDK 21 completo. Il ramo conserva l’APK/debug e il companion Wear come superfici da verificare separatamente. Se il packaging del companion Wear fallisce per risorse mancanti nella baseline, non mascherare il problema: annotarlo in una issue distinta, perché non deriva dalle fondamenta dati.

## Aggiornamento fonti — 14 agosto 2026

Il ramo include ora `RailSourceCatalog` con 31 fonti censite e classificate. Il parser `GtfsArchiveInspector`, il provider locale `GtfsStationCatalogProvider` e `GtfsArtifactDownloader` sono pronti per data pack GTFS statici; i test bloccano HTTP cleartext, gateway, best-effort e fonti senza URL stabilizzato. Otto endpoint open — ARST, Trentino, Alto Adige, HŽPP, SNCF statico, SNCF GTFS-RT Trip Updates/Alerts e ÖBB — hanno superato un controllo HTTP read-only.

Sono ora attive nella composition root le due fonti live usate dalle app ferroviarie analizzate: `ViaggiaTrenoStationAdapter` per il tabellone Trenitalia della stazione di origine e `RfiStationBoardAdapter` come fallback live per tabelloni di stazione. Entrambe espongono provider, timestamp e freschezza alla UI e al widget; cache, timeout e fallback sono coperti da test. Lo smoke test del 14 agosto 2026 ha ottenuto HTTP 200 con 67 corse ViaggiaTreno e 40 partenze RFI per Roma Termini.

Il test completo ha elaborato tutte le 31 fonti catalogate. L’import massimo successivo include ora nove data pack GTFS validati: AMT Genova–Casella, ARST Sardegna, Cotral/ASTRAL Ferro, EAV Campania, HŽPP, Lombardia/Trenord, SNCF, Toscana/TFT e Trentino Trasporti extraurbano. `EmbeddedGtfsPackLoader` li carica in modo lazy e verificabile dagli asset; manifest e SHA-256 sono in `app/src/main/assets/gtfs/`. I feed multimodali devono essere filtrati per `route_type=2`; TFT richiede una normalizzazione esplicita, perché le sue due tratte ferroviarie sono codificate dal titolare con `route_type=3`. Alto Adige, Svizzera e ÖBB restano import offline per dimensione o timeout; Trenord resta catalogata e non attivata, poiché la sua pagina dinamica non dispone ancora di parser Android stabile e testato.

La baseline di qualità aggiunge `SnapshotQualityAssessor`, `SnapshotReconciler` e metadati di punteggio/fascia/warning in `RouteSnapshot`. Il repository live valuta la qualità delle fonti, non soltanto il loro ordine. Sono inoltre operativi `RfiInfomobilityRssAdapter` per alert RFI e `StaRealtimeAdapter` per Service Alerts e Vehicle Positions GTFS-Realtime di Open Data Hub/STA. Entrambi sono presenti nella composition root, con cache, rate limit, test fixture e smoke test. La UI mostra ora fascia e punteggio di qualità insieme alla provenienza.

È ora operativo anche `ItaloPublicStationAdapter`, anteposto ai fallback ViaggiaTreno e RFI per le stazioni Italo mappate: Roma Termini, Roma Tiburtina, Napoli Centrale, Firenze Santa Maria Novella, Milano Centrale, Torino Porta Nuova e Venezia Santa Lucia. Usa esclusivamente il tabellone HTML pubblico `Italo in Viaggio`, cache di 60 secondi e un parser best-effort. Nessuna API BIG o credenziale è usata. Quando la pagina non mostra corse o non è configurata per una stazione, l’adapter fallisce in modo esplicito e il repository prosegue con ViaggiaTreno/RFI. Dettagli e verifiche: `ITALO_PUBLIC_BOARD_DISCOVERY.md`, `ITALO_STATION_MAPPING.md`, `ITALO_PROVIDER_VERIFICATION.md` e il CSV dello smoke test.

## Documenti correlati

- `docs/data-sources/SOURCE_POLICY.md`
- `docs/data-sources/SOURCE_COVERAGE_AND_TESTS.md`
- `docs/data-sources/LIVE_PROVIDER_PROTOCOLS.md`
- `docs/data-sources/FULL_SOURCE_TEST_REPORT.md`
- `docs/data-sources/consolidated_source_test_2026-08-14.csv`
- `docs/data-sources/GTFS_IMPORT_RESULTS.md`
- `docs/data-sources/MAXIMIZED_IMPORT_REPORT.md`
- `docs/data-sources/ALTERNATE_IMPORT_PATHS.md`
- `docs/data-sources/DATA_QUALITY_MODEL.md`
- `docs/data-sources/PREMIUM_OFFICIAL_SOURCES.md`
- `docs/data-sources/DATA_QUALITY_SLA.md`
- `docs/data-sources/DATA_QUALITY_VERIFICATION.md`
- `docs/data-sources/ITALO_PUBLIC_BOARD_DISCOVERY.md`
- `docs/data-sources/ITALO_STATION_MAPPING.md`
- `docs/data-sources/ITALO_PROVIDER_VERIFICATION.md`
- `docs/data-sources/italo_public_board_probe_2026-08-15.csv`
- `docs/data-sources/live_source_probe_2026-08-14.tsv`
- `docs/data-sources/open_source_probe_2026-08-14.tsv`
- `docs/architecture/GTFS_SOURCE_ADAPTERS.md`
- `docs/architecture/DATA_LAYER_MIGRATION.md`
- `tools/verify_open_sources.py`
- `SPECIFICA_LIVELLO_DATI_BINARIO.md` e documenti allegati nella conversazione che ha svolto la ricerca fonti.
