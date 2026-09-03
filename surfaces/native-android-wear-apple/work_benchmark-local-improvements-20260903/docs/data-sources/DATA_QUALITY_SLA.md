# Qualità operativa, SLA interno e test reale

## Principio

Binario non attribuisce agli operatori un livello di servizio contrattuale. Le soglie seguenti sono **SLO interni dell’app**: servono a classificare freschezza, degradazione e priorità della fonte, non a promettere disponibilità del fornitore.

## Smoke test del 14 agosto 2026

| Fonte | HTTP | Record/forma verificata | Latenza misurata | Esito |
|---|---:|---|---:|---|
| RFI Infomobilità RSS | 200 | 60 item XML | 7.438 ms | PASS |
| STA Trip Updates | 200 | 230 entità GTFS-RT JSON | 116.284 ms | PASS |
| STA Vehicle Positions | 200 | 302 entità GTFS-RT JSON | 16.925 ms | PASS |
| STA Service Alerts | 200 | 30 entità GTFS-RT JSON | 19.954 ms | PASS |
| RFI Monitor Arrivi/Partenze | 200 | HTML tabellone Roma Termini | 7.026 ms | PASS |
| ViaggiaTreno stazione | 200 | HTML tabellone Roma Termini | 6.251 ms | PASS |

Le latenze sono una singola osservazione nel sandbox e non devono essere interpretate come garanzia. Il report machine-readable è `quality_smoke_test_2026-08-14.csv`.

## SLO applicati dall’app

| Area | Target | Degradazione sicura |
|---|---|---|
| Tabellone live | Cache 45–60 s, freshness entro 90 s | RFI come fallback di ViaggiaTreno; poi ultimo snapshot etichettato |
| RSS RFI | Cache 10 min, parser XML difensivo | Nessun avviso inventato; errore distinto da assenza di avvisi |
| STA realtime | Cache 90 s, timestamp header GTFS-RT conservato | Escludere feed scaduti e mantenere provider/timestamp |
| Feed statici | Hash asset e manifest per release | Mostrare solo pianificazione, mai ritardo derivato |
| Conflitto fra fonti | Warning e conservazione dei valori osservati | Privilegiare punteggio maggiore, non occultare il conflitto |

## Stato della qualità

La baseline include ora una valutazione `0..100`, una fascia pubblicabile, warning espliciti, riconciliazione di conflitti e due nuovi canali ufficiali: **RFI RSS** e **Open Data Hub/STA GTFS-Realtime**. Italo e Trenord restano in gateway review: nessuna loro UI dinamica viene spacciata per API stabile.

## Riferimenti

[1]: https://www.rfi.it/it/news-e-media/feed---rss.html "RFI — Feed RSS"
[2]: https://gtfs.api.opendatahub.com/v1/realtime "Open Data Hub — GTFS-Realtime"
[3]: https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Home "RFI — Monitor Arrivi/Partenze"
[4]: https://www.viaggiatreno.it/ "Trenitalia — ViaggiaTreno"
