# Handoff — livello dati operativo di Binario

**Ramo canonico di integrazione:** `feature/data-foundations`

**Baseline iniziale:** `main` v0.15.0, commit `1345111e03f3ac17cb08d5605a3fb9f09950aa6f`

**Stato:** fonti live, data pack statici, ranking di qualità e fallback GTFS sono operativi. Il repository GitHub è l’unica sorgente canonica: non usare ZIP o APK storici come base di sviluppo.

## Componenti operativi

| Area | Percorso | Stato |
|---|---|---|
| Modello e contratti | `data/core`, `data/provider` | Canonico, con provenienza per campo e capability |
| Ranking e conflitti | `data/DataQuality.kt`, `data/DataReconciliation.kt` | Attivo: punteggio, fascia, freshness e warning |
| Tabelloni live | `data/live` | Italo, ViaggiaTreno e RFI, con cache e fallback |
| Avvisi e posizioni | `RfiInfomobilityRssAdapter`, `StaRealtimeAdapter` | Attivi; RFI RSS e STA GTFS-Realtime |
| Orari statici offline | `data/gtfs/EmbeddedGtfsRouteAdapter.kt` | Attivo come fallback dopo le fonti live |
| Asset GTFS | `app/src/main/assets/gtfs` | Dodici data pack, manifest e SHA-256 |
| Composition root | `data/repository/BinarioDataGraph.kt` | UI e widget passano il contesto Android e condividono le fonti |

## Fonti realmente disponibili all’app

| Fonte | Funzione | Stato |
|---|---|---|
| Italo in Viaggio | Tabelloni delle sette stazioni mappate | Best-effort attivo |
| ViaggiaTreno | Tabellone stazione Trenitalia | Best-effort attivo |
| RFI Monitor Arrivi/Partenze | Fallback multivettore per tabelloni | Best-effort attivo |
| RFI Infomobilità RSS | Avvisi ufficiali | Attivo |
| Open Data Hub Alto Adige / STA | Alert, posizioni realtime e GTFS statico | Attivo |
| GTFS embedded | Orari programmati come fallback | Attivo |

I dodici data pack GTFS inclusi sono: Alto Adige/STA, AMT Genova–Casella, ARST, Open Data Mobility Switzerland, Cotral/ASTRAL, EAV, HŽPP, Lombardia/Trenord, ÖBB, SNCF, Toscana/TFT e Trentino Trasporti. Il loro manifest è `app/src/main/assets/gtfs/manifest.json`; ogni build deve controllare `SHA256SUMS`.

Il repository usa prima fonti realtime, valutandole per fonte, freschezza e completezza. Se nessuna restituisce una corsa utile, `EmbeddedGtfsRouteAdapter` cerca una corsa diretta nei data pack e mostra esclusivamente **orario programmato**, senza stimare ritardi, binari o soppressioni.

## Fonti ancora non attive come realtime nativo

| Fonte | Motivo | Copertura disponibile |
|---|---|---|
| Trenord realtime | Canale strutturato pubblico non verificato; E015 da configurare in gateway | GTFS Lombardia/Trenord, RFI e ViaggiaTreno |
| Svizzera GTFS-RT | Token/access code richiesto; non inserire nell’APK | GTFS svizzero statico e RFI in Italia |
| SNCF GTFS-RT | Feed disponibile, decoder runtime non ancora collegato alla UI legacy | GTFS SNCF e RFI in Italia |
| ÖBB realtime | Feed realtime pubblico equivalente non verificato | GTFS ÖBB e RFI in Italia |
| Reti senza feed strutturato | L’operatore pubblica solo HTML/PDF o richiede accordo | Nessun realtime simulato |

## Vincoli non negoziabili

1. Non inserire token, chiavi o credenziali nell’APK.
2. Non riutilizzare API, dati, chiavi, asset o codice di Treniamo, On The Rails o app ufficiali.
3. Non aggirare autenticazione, CAPTCHA, WAF, rate limit o condizioni tecniche.
4. Distinguere sempre realtime, cache, orario statico, stima e indisponibilità.
5. Widget e Wear leggono la stessa composition root e non avviano polling parallelo.
6. Se si aggiunge una fonte, aggiungere adapter, fixture, smoke test, manifest/licenza e test di fallback prima di abilitarla.

## Verifica standard

```bash
(cd app/src/main/assets/gtfs && sha256sum -c SHA256SUMS)
python3 tools/verify_embedded_gtfs_coverage.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

La build richiede Android SDK API 36 e JDK 21. La verifica più recente ha superato 61 test unitari, 12/12 data pack e la compilazione dell’APK debug.

## Documenti da leggere

- `docs/data-sources/FULL_COVERAGE_EVIDENCE.md`
- `docs/data-sources/DATA_QUALITY_MODEL.md`
- `docs/data-sources/DATA_QUALITY_SLA.md`
- `docs/data-sources/ITALO_PROVIDER_VERIFICATION.md`
- `docs/data-sources/OEBB_GTFS_ACCESS.md`
- `docs/architecture/GTFS_SOURCE_ADAPTERS.md`
- `docs/architecture/DATA_LAYER_MIGRATION.md`

## Aggiornamento realtime — 15 agosto 2026

Il ramo include `SncfGtfsRealtimeAdapter`, che decodifica i feed protobuf ufficiali SNCF per `Service Alerts` e rende disponibili anche i `Trip Updates` al livello dati. Il feed alert è collegato alla composition root insieme a RFI e STA; il matching dei trip update alla UI legacy sarà attivato soltanto dopo la risoluzione verificata tra identificativi GTFS statici e realtime. Nessun ritardo viene quindi associato a una corsa con identità ambigua.

La verifica read-only ha ottenuto HTTP 200 per gli endpoint SNCF, con payload protobuf validi per Trip Updates e Service Alerts. La baseline ha superato 63 test unitari e il packaging dell’APK debug. Consultare `docs/data-sources/REALTIME_COVERAGE_VERIFICATION.md`, `REALTIME_ACCESS_GAPS.md` e `sncf_realtime_probe_2026-08-15.tsv`.

Il realtime svizzero richiede un token gratuito da custodire in gateway; il realtime Trenord richiede adesione/autorizzazione E015. ÖBB e Italo non espongono un feed realtime machine-readable pubblico verificato ulteriore rispetto ai canali già attivati. Non sostituire tali limiti con scraping di endpoint interni, token di terzi o posizioni stimate presentate come reali.

## Verifica aggiornata

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

L’APK risultante ha circa 72 MB e conserva dodici data pack GTFS embedded con SHA-256 verificabili.
