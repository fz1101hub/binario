# Copertura delle fonti e risultati dei test

**Data di verifica:** 14 agosto 2026  
**Ramo:** `feature/data-foundations`  
**Catalogo applicativo:** `RailSourceCatalog`

## Sintesi

Il catalogo contiene **31 definizioni di fonte** per coprire i servizi ferroviari passeggeri sul territorio italiano, incluse reti regionali e isolate, servizi transfrontalieri e vettori esteri. Il catalogo non attiva automaticamente chiamate live: registra gli endpoint, le capability, la copertura, l’access class e lo stato di readiness affinché ogni integrazione sia testabile e sostituibile.

| Classe di accesso | Fonti | Trattamento nel ramo |
|---|---:|---|
| `OPEN_DIRECT` | 12 | Data pack o protocol test dopo fixture e validazione |
| `GATEWAY_ONLY` | 3 | Import offline o gateway HTTPS; il client Android non usa HTTP cleartext |
| `PUBLIC_BEST_EFFORT` | 5 | Descriptor presenti, ma feature flag disabilitato e nessun polling client |
| `AGREEMENT_REQUIRED` | 11 | Catalogate per copertura; nessun adapter runtime attivo |

| Stato di readiness | Fonti | Significato |
|---|---:|---|
| `READY_FOR_GTFS_PACK` | 5 | Feed ZIP HTTPS con schema GTFS minimo già verificato o pronto al test di import |
| `READY_FOR_PROTOCOL_TEST` | 2 | Feed GTFS-RT protobuf raggiungibili, da implementare in modulo separato |
| `IMPORT_VIA_GATEWAY` | 3 | Feed tecnicamente disponibili ma voluminosi o HTTP-only; no download app diretto |
| `LANDING_PAGE_ONLY` | 4 | Catalogo/pagina raggiungibile; resource URL o versione devono essere stabilizzati |
| `BEST_EFFORT_DISABLED` | 5 | Pagine operative pubbliche, non open API documentate |
| `AGREEMENT_REQUIRED` | 10 | Feed strutturato o autorizzazione non ancora disponibili |
| `MONITOR_ONLY` | 2 | Servizi futuri/stagionali da aggiungere solo dopo conferma operativa |

## Test read-only superati

Il comando `python3 tools/verify_open_sources.py` ha completato il 14 agosto 2026 con esito positivo per tutti gli otto endpoint. Il comando effettua solo `HEAD`, non scarica feed completi e non usa token.

| Source ID | Formato verificato | HTTP | Dimensione dichiarata |
|---|---|---:|---:|
| `arst-gtfs` | ZIP GTFS | 200 | 20.0 MB |
| `trentino-gtfs` | ZIP GTFS | 200 | 7.4 MB |
| `alto-adige-sta-gtfs` | ZIP GTFS | 200 | 135.7 MB |
| `hzpp-gtfs` | ZIP GTFS | 200 | 2.8 MB |
| `sncf-gtfs` | ZIP GTFS | 200 | 4.8 MB |
| `sncf-gtfs-rt-trip-updates` | protobuf GTFS-RT | 200 | circa 0.8 MB |
| `sncf-gtfs-rt-alerts` | protobuf GTFS-RT | 200 | circa 1.0 MB |
| `oebb-gtfs` | ZIP GTFS | 200 | 173.6 MB |

Il dettaglio puntuale del sondaggio è salvato in `open_source_probe_2026-08-14.tsv`. Sono stati inoltre aperti localmente, senza eseguire contenuti esterni, i feed ARST, HŽPP e SNCF: tutti contengono il set minimo GTFS `agency.txt`, `stops.txt`, `routes.txt`, `trips.txt` e `stop_times.txt`.

## Fonti già utilizzabili nel codice

`GtfsArtifactDownloader` accetta esclusivamente una fonte che sia contemporaneamente `OPEN_DIRECT`, `READY_FOR_GTFS_PACK`, in formato `GTFS_ZIP` e con URL `HTTPS`. `GtfsArchiveInspector` valida l’archivio offline applicando limiti a file, byte decompressi, numero stazioni e dimensione delle righe CSV. `GtfsStationCatalogProvider` espone poi le stazioni estratte tramite il contratto provider esistente.

Questo significa che ARST, Trentino, HŽPP e SNCF possono entrare nella prima pipeline di data pack dopo una scelta esplicita del feed e l’archiviazione di licenza/versione/hash. Alto Adige e ÖBB usano lo stesso parser, ma devono essere importati in un gateway o pipeline offline per le loro dimensioni. AMT Genova–Casella e Cotral Ferro sono catalogati ma bloccati dal client, perché gli URL verificati usano HTTP in chiaro.

## Fonti volutamente non attive

ViaggiaTreno, RFI Monitor, RFI Infomobilità, Italo in Viaggio e Trenord realtime restano `PUBLIC_BEST_EFFORT`. Il loro inserimento nel catalogo non autorizza estrazione automatizzata, non aggira protezioni e non comporta richieste dal dispositivo. Le integrazioni richiedono un adapter separato, cache/backoff/circuit breaker, un gateway quando opportuno e una nuova verifica delle condizioni tecniche.

Le fonti di operatori con soli orari HTML/PDF o feed non disponibili sono presenti per evitare buchi di copertura; il loro stato `AGREEMENT_REQUIRED` impedisce invece che siano accidentalmente considerate dati live.

## Come rieseguire i controlli

```bash
python3 tools/verify_open_sources.py
./gradlew :app:testDebugUnitTest
```

I test Kotlin verificano parser GTFS, ZIP non conformi, limiti di sicurezza, catalogo senza ID duplicati, blocco di fonti HTTP/best-effort e registro provider disabilitato per default.

## Riferimenti

[1]: https://www.arst.sardegna.it/open-data/ "ARST — Open Data"
[2]: https://www.trentinotrasporti.it/it/opendata-it "Trentino Trasporti — Open Data"
[3]: https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables "Open Data Hub Alto Adige — STA time tables"
[4]: https://www.hzpp.hr/en/timetable "HŽ Putnički prijevoz — GTFS"
[5]: https://ressources.data.sncf.com/pages/accueil/ "SNCF Open Data"
[6]: https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~ "ÖBB GTFS — CC BY 4.0"
[7]: https://opentransportdata.swiss/en/terms-of-use/ "Open Data Platform Mobility Switzerland — termini d’uso"
[8]: https://www.rfi.it/it/stazioni/pagine-stazioni/servizi-di-qualita/informazioni-al-pubblico/monitor-arrivi-partenze-live.html "RFI — Monitor Arrivi/Partenze"
