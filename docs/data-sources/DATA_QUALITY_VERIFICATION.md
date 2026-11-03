# Verifica della baseline di qualità dati

**Data:** 14 agosto 2026

| Controllo | Esito |
|---|---|
| Test unitari Android | 54 completati, 0 failure, 0 errori |
| Adapter RFI RSS | Fixture XML, parser difensivo e smoke test reale superati |
| Adapter STA GTFS-Realtime | Fixture JSON per alert/posizioni e smoke test reale superati |
| Ranking e riconciliazione | Testati su freschezza, qualità, fallback e conflitti |
| Integrità data pack GTFS | SHA-256 9/9 corrispondenti |
| APK debug | Compilato correttamente, circa 64 MB |
| Controllo formale diff | Superato |

## Fonti attive di qualità

Il tabellone ViaggiaTreno resta la prima fonte per le partenze Trenitalia; RFI Monitor Arrivi/Partenze è fallback live. RFI Infomobilità RSS fornisce ora avvisi ufficiali nazionali e regionali. Open Data Hub/STA fornisce Service Alerts e Vehicle Positions GTFS-Realtime, con timestamp della sorgente e cache di 90 secondi.

Il repository non sceglie più soltanto la prima risposta valida: assegna punteggio di qualità, conserva warning e privilegia il candidato più affidabile. I conflitti su ritardo, binario o stato generano un warning esplicito e non vengono eliminati silenziosamente.
