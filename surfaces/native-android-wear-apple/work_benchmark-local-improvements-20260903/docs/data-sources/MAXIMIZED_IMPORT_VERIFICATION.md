# Verifica dell’import esteso

**Data:** 14 agosto 2026

| Controllo | Esito |
|---|---|
| JSON del manifest GTFS | Valido |
| SHA-256 dei data pack asset | 9/9 corrispondenti |
| Data pack embedded | 9 |
| Test unitari | 47 completati, 0 errori |
| Build `:app:assembleDebug` | Superata |
| APK debug risultante | Circa 62 MB |

I pack inclusi sono AMT Genova–Casella, ARST Sardegna, Cotral/ASTRAL Ferro, EAV Campania, HŽPP, Lombardia/Trenord, SNCF, Toscana/TFT e Trentino Trasporti extraurbano.

Le fonti statiche di Alto Adige, Svizzera e ÖBB restano configurate per import offline separato: i loro artefatti pubblici sono troppo grandi o non sono stati scaricabili in modo affidabile durante questa sessione. Italo e Trenord rimangono fonti dinamiche da parser stabilizzato, non data pack statici.
