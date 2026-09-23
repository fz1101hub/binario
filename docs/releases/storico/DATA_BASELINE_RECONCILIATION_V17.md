# Riallineamento alla baseline dati v0.17.0

## Base canonica e verifica

Il lavoro UX è stato trasferito sul branch `feature/data-foundations`, derivato dal commit canonico `0e0867ad33c3dd94fbadf7afa1ffc0240a6498f9`. Il branch conserva `BinarioDataGraph`, `ProviderRegistry`, `EmbeddedGtfsRouteAdapter` e i contratti `data/core` e `data/provider`. Non contiene token, chiavi, endpoint interni, scraping aggressivo o credenziali nel client.

| Verifica | Esito |
|---|---|
| Data pack GTFS embedded | 12 archivi e 12 impronte SHA-256 verificate |
| Fallback | Realtime identificato → orario GTFS programmato → indisponibilità dichiarata |
| Widget | Refresh tramite `BinarioDataGraph`; provider, confidenza, score, freshness e warning persistiti e mostrati |
| Evidenza operativa | Giallo solo per ritardo/soppressione da confidenza operativa e freshness utilizzabile |
| Dati statici/demo | Etichetta `PROGRAMMATO` o `DEMO`; mai esposti come variazione operativa |

## Fonti attive e limiti

ViaggiaTreno, RFI e Italo sono canali best-effort per tabelloni; RFI RSS è attivo per infomobilità; STA espone alert e posizioni dei soli veicoli contenuti nel feed; SNCF GTFS-Realtime espone alert e Trip Updates, con matching UI rinviato quando l’identità non è verificata. I dodici GTFS embedded coprono anche Svizzera, ÖBB, SNCF, Trenord/Lombardia e reti regionali. Svizzera GTFS-RT e Trenord E015 restano disabilitati senza gateway e autorizzazione. [1] [2] [3]

> Un orario GTFS programmato può essere utile per pianificare, ma non è prova di ritardo, binario, cancellazione o posizione reale. La UI non elimina questa distinzione.

## Riferimenti

[1] [Handoff del livello dati](docs/handoffs/DATA_LAYER_HANDOFF.md)

[2] [Evidenza della copertura completa](docs/data-sources/FULL_COVERAGE_EVIDENCE.md)

[3] [Verifica realtime e gap di accesso](docs/data-sources/REALTIME_COVERAGE_VERIFICATION.md)
