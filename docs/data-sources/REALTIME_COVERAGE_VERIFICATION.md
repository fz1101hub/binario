# Verifica della copertura realtime

> Rapporto storico del 15 agosto 2026. Per protocolli correnti, collegamento alla ricerca e limitazioni osservate nei controlli reali del 16 ottobre vedere [LIVE_PROVIDER_PROTOCOLS.md](LIVE_PROVIDER_PROTOCOLS.md). La sola presenza di un adapter o una risposta HTTP 200 non confermano la disponibilità di ritardi live.

**Data:** 15 agosto 2026  
**Obiettivo:** massimizzare ritardi, avvisi e posizioni effettive usando esclusivamente fonti ufficiali o pubbliche verificabili.

## Esiti dei canali attivi

| Fonte | Dati forniti | Verifica | Stato |
|---|---|---|---|
| ViaggiaTreno | Tabelloni, ritardi e binari quando esposti | Adapter e smoke test esistenti | Attivo best-effort |
| RFI Monitor Arrivi/Partenze | Tabelloni multivettore, ritardi e binari quando esposti | Adapter e smoke test esistenti | Attivo best-effort |
| Italo in Viaggio | Stato, ritardi e binari sulle stazioni mappate | Sette pagine pubbliche verificate | Attivo best-effort |
| RFI Infomobilità RSS | Avvisi ufficiali | Adapter XML attivo | Attivo |
| Open Data Hub/STA | Alert e posizioni veicolo; copertura del feed STA | Adapter JSON attivo | Attivo |
| SNCF GTFS-RT Trip Updates | Aggiornamenti di corsa e ritardi per corse presenti nel feed | HTTP 200, protobuf 20.768 byte | Decoder attivo; matching UI successivo |
| SNCF GTFS-RT Service Alerts | Avvisi SNCF | HTTP 200, protobuf 749.276 byte | Adapter alert attivo |

## Copertura per ambito

| Ambito | Ritardi effettivi | Posizioni effettive | Nota |
|---|---|---|---|
| Trenitalia | Sì, attraverso ViaggiaTreno/RFI quando disponibili | Non esposte come GPS continuo | Nessuna interpolazione presentata come posizione reale |
| Italo | Sì, nel tabellone pubblico quando disponibile | Non pubblicate come feed continuo | Copertura stazione, non radar GPS |
| Alto Adige/STA | Alert; realtime del feed | Sì, Vehicle Positions GTFS-Realtime | Solo veicoli contenuti nel feed |
| SNCF | Trip Updates e alert disponibili | Non esposte nei feed usati | Il matching definitivo richiede identità GTFS per corsa |
| Trenord | Indiretto tramite RFI/ViaggiaTreno se esposto | Non disponibile nel canale pubblico verificato | GTFS statico incluso; E015 richiede adesione |
| Svizzera | Indiretto sulle stazioni italiane RFI | Non disponibile nel GTFS-RT documentato | GTFS-RT richiede token gateway |
| ÖBB | Indiretto sulle stazioni italiane RFI | Nessun feed open verificato | GTFS statico incluso |

## Degradazione sicura

Quando il realtime non esiste, non è accessibile o non può essere identificato con certezza, l’app usa il data pack GTFS ufficiale e mostra **orario programmato**. Non trasforma il programma in ritardo, binario, soppressione o posizione stimata.

## Azioni residue

La copertura restante non dipende da altro codice Android: richiede l’ottenimento di un token gratuito svizzero per il gateway o l’adesione/autorizzazione E015 per Trenord. I dettagli sono in `REALTIME_ACCESS_GAPS.md`.

## Riferimenti

[1]: https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates "SNCF GTFS-RT Trip Updates"
[2]: https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts "SNCF GTFS-RT Service Alerts"
[3]: https://opentransportdata.swiss/en/cookbook/realtime-prediction-cookbook/gtfs-rt/ "Open Data Platform Mobility Switzerland — GTFS-RT"
[4]: https://www.e015.regione.lombardia.it/site/api-detail?id=347 "E015 — Trenord GTFS e realtime"
