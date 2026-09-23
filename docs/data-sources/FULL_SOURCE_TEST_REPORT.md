# Report completo dei test delle fonti

**Data di verifica:** 14 agosto 2026  
**Fonti catalogate:** 31  
**Esiti consolidati:** 22 PASS, 7 LIMITED, 2 FAIL.

Il test ha usato un import ZIP/CSV per i feed GTFS scaricabili, un probe protobuf per GTFS-Realtime e una richiesta pubblica read-only per pagine e tabelloni. Gli esiti LIMITATI o FAIL non sono ignorati: restano nel catalogo con il motivo tecnico e non vengono attivati automaticamente.

| Esito | Fonti |
|---|---:|
| PASS | 22 |
| LIMITED | 7 |
| FAIL | 2 |

## Stato per fonte

| ID | Fonte | Esito | Disposizione | Metodo | Nota |
|---|---|---|---|---|---|
| `arst-gtfs` | ARST Sardegna — GTFS | **LIMITED** | `OFFLINE_RETRY` | gtfs_import | download_timeout |
| `trentino-gtfs` | Trentino Trasporti — GTFS | **LIMITED** | `OFFLINE_RETRY` | gtfs_import | download_timeout |
| `alto-adige-sta-gtfs` | Open Data Hub Alto Adige — STA GTFS | **LIMITED** | `OFFLINE_RETRY` | gtfs_import | download_timeout |
| `amt-genova-casella-gtfs` | AMT Genova–Casella — GTFS | **PASS** | `OFFLINE_GATEWAY` | gtfs_import | agency=1;stops=2754;routes=164;trips=67522 |
| `cotral-ferro-gtfs` | Cotral/ASTRAL — GTFS Ferro | **PASS** | `OFFLINE_GATEWAY` | gtfs_import | agency=1;stops=35;routes=3;trips=1066 |
| `lombardia-trenord-gtfs` | Regione Lombardia — GTFS ferroviario | **PASS** | `CATALOG_ONLY` | metadata_probe | page reachable |
| `eav-gtfs` | EAV Campania — Open Data | **PASS** | `CATALOG_ONLY` | landing_page | title=Open Data - EAV srl |
| `toscana-tft-gtfs` | Regione Toscana / TFT — GTFS | **PASS** | `CATALOG_ONLY` | landing_page | title=Regione Toscana - Orari trasporto pubblico - Dataset - OpenData - Regione Toscana |
| `hzpp-gtfs` | HŽ Putnički prijevoz — GTFS | **PASS** | `DATA_PACK_READY` | gtfs_import | agency=1;stops=460;routes=141;trips=44888 |
| `sncf-gtfs` | SNCF — GTFS statico | **PASS** | `DATA_PACK_READY` | gtfs_import | agency=6;stops=8912;routes=728;trips=49065 |
| `sncf-gtfs-rt-trip-updates` | SNCF — GTFS-RT Trip Updates | **PASS** | `PROTOCOL_READY` | gtfs_rt_probe | protobuf payload sampled |
| `sncf-gtfs-rt-alerts` | SNCF — GTFS-RT Service Alerts | **PASS** | `PROTOCOL_READY` | gtfs_rt_probe | protobuf payload sampled |
| `ch-otd-gtfs` | Open Data Platform Mobility Switzerland — GTFS | **PASS** | `CATALOG_ONLY` | landing_page | title=Fahrplan 2026 (GTFS2020) - Datensatz - opentransportdata.swiss - CKAN data catalog |
| `ch-otd-gtfs-rt` | Open Data Platform Mobility Switzerland — GTFS-RT | **PASS** | `CATALOG_ONLY` | landing_page | title=GTFS Realtime (GTFS-RT) &#8211; Open data platform mobility Switzerland |
| `oebb-gtfs` | ÖBB — GTFS Fahrplan | **LIMITED** | `OFFLINE_RETRY` | gtfs_import | download_timeout |
| `viaggiatreno` | ViaggiaTreno — stato corse | **PASS** | `APP_LIVE_ENABLED` | landing_page | Tabellone stazione importato e parser live attivo |
| `rfi-station-board` | RFI — Monitor Arrivi/Partenze | **PASS** | `APP_LIVE_ENABLED` | landing_page | Tabellone stazione importato e parser live attivo |
| `rfi-infomobility` | RFI — Infomobilità | **LIMITED** | `GATEWAY_REVIEW` | landing_page | RSS/avvisi verificati; richiede parser e classificazione avvisi |
| `italo-status` | Italo in Viaggio | **LIMITED** | `GATEWAY_REVIEW` | landing_page | Tabella browser verificata; il contenuto Android richiede rendering dinamico o canale stabilizzato |
| `trenord-realtime` | Trenord — tempo reale e avvisi | **LIMITED** | `GATEWAY_REVIEW` | landing_page | Modulo pubblico verificato; record live non confermato senza interazione dinamica |
| `sz-passenger` | SŽ Potniški promet — Italia | **FAIL** | `BLOCKED` | landing_page | HTTPError |
| `fuc-udine-cividale` | Ferrovie Udine–Cividale | **PASS** | `CATALOG_ONLY` | landing_page | title=Orari UDINE &ndash; CIVIDALE DEL FRIULI \| Ferrovie Udine Cividale |
| `fse-puglia` | Ferrovie del Sud Est | **PASS** | `CATALOG_ONLY` | landing_page | title=FS SUD EST |
| `fal-basilicata-puglia` | Ferrovie Appulo Lucane | **PASS** | `CATALOG_ONLY` | landing_page | title=Quadri Orari e Tariffe - Ferrovie Appulo Lucane |
| `ferrotramviaria-bari-barletta` | Ferrotramviaria | **PASS** | `CATALOG_ONLY` | landing_page | title=Avvisi - Ferrotramviaria SPA |
| `fce-circumetnea` | Ferrovia Circumetnea | **PASS** | `CATALOG_ONLY` | landing_page | title=News Archivi - Ferrovia Circumetnea |
| `fdc-calabria` | Ferrovie della Calabria | **PASS** | `CATALOG_ONLY` | landing_page | title=Orari dei Trasporti &#8211; Ferrovie della Calabria Srl |
| `tua-abruzzo` | TUA Abruzzo | **PASS** | `CATALOG_ONLY` | landing_page | title=&raquo; Comunicazioni Autobus - TUA SpA |
| `arenaways` | Arenaways | **FAIL** | `BLOCKED` | landing_page | HTTPError |
| `fs-tourist-trains` | FS Treni Turistici Italiani / Fondazione FS | **PASS** | `CATALOG_ONLY` | landing_page | title=homepage |
| `european-sleeper` | European Sleeper verso Milano | **PASS** | `CATALOG_ONLY` | landing_page | title=Discover Europe by Night Train with European Sleeper \| European Sleeper |

## Fonti realmente attive nell’app

`ViaggiaTrenoStationAdapter` e `RfiStationBoardAdapter` sono attivi nella composition root. Il primo carica il tabellone Trenitalia della stazione d’origine; il secondo è il fallback RFI. Entrambi usano timeout, cache, provenienza e fallback esplicito. Lo smoke test su Roma Termini ha ottenuto HTTP 200 per entrambe le fonti.

## Data pack pronti

AMT Genova–Casella, Cotral/ASTRAL Ferro, HŽPP e SNCF hanno superato l’import ZIP/CSV. I primi due restano offline/gateway perché i loro URL sono HTTP; HŽPP e SNCF sono candidati a data pack importabili, con SNCF da filtrare al tratto italiano. Alto Adige, ARST, ÖBB e Trentino non sono stati invalidati: richiedono un import offline con maggiore finestra di rete.

## Limiti osservati

Italo e Trenord espongono interfacce pubbliche con dati o moduli di ricerca, ma il contenuto non è ancora disponibile come risposta Android stabile senza rendering dinamico. Restano quindi in revisione anziché essere forzati con endpoint interni. Arenaways e SŽ hanno restituito HTTP 403 alla richiesta automatica e sono mantenute come fonti catalogate, non abilitate.

## Artefatti di verifica

- `source_test_matrix.csv`: metodo previsto per ogni fonte.
- `full_source_test_2026-08-14.csv`: probe HTTP completo delle 31 fonti.
- `gtfs_import_test_2026-08-14.csv`: import e metriche dei feed GTFS diretti.
- `live_source_probe_2026-08-14.tsv`: smoke test dei due adapter live attivi.
- `consolidated_source_test_2026-08-14.csv`: vista machine-readable dell’esito finale.
