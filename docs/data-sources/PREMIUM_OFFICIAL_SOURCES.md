# Fonti ufficiali ad alta qualità

La verifica del 14 agosto 2026 distingue le fonti che meritano un adapter di qualità da quelle che restano soltanto catalogo o gateway review.

| Fonte | Dati | Modalità consigliata | Priorità |
|---|---|---|---|
| Open Data Hub Alto Adige / STA | GTFS statico, Trip Updates, Vehicle Positions, Service Alerts | Adapter HTTPS diretto GTFS-RT; import statico offline | Alta |
| RFI Infomobilità | RSS nazionale/regionale e tabelloni stazione | Adapter RSS diretto per avvisi; RFI station board già fallback live | Alta |
| ViaggiaTreno | Tabelloni, ritardi e binari per corse Trenitalia | Adapter già operativo, con RFI come fallback | Alta |
| Svizzera OTD | GTFS-RT e Service Alerts transfrontalieri | Gateway/adapter con API key gratuita; non attivare senza configurazione utente | Alta condizionata |
| ÖBB | GTFS e CSV periodici su ritardi/soppressioni | Import offline/gateway, non realtime diretto Android | Media |
| Italo in Viaggio | Tabelloni e stato da UI dinamica | Gateway review; nessuna API pubblica documentata | Media condizionata |
| Trenord | UI realtime, orari e avvisi | GTFS statico embedded; realtime in gateway review | Media condizionata |
| SŽ, FSE, Ferrotramviaria | Orari/avvisi via web ufficiale | Catalogo e gateway review; nessun contratto machine-readable stabile | Bassa condizionata |

La fonte non viene resa attiva soltanto perché è visualizzabile sul web. Per entrare nella UI realtime deve offrire un formato strutturato o un parser stabile coperto da fixture, con provenienza, timestamp, rate limit e degradazione espliciti.

## Riferimenti ufficiali

- Open Data Hub GTFS: https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables
- Open Data Hub Realtime: https://gtfs.api.opendatahub.com/v1/realtime
- RFI Infomobilità: https://www.rfi.it/it/news-e-media/infomobilita.html
- Open Data Transport Switzerland: https://opentransportdata.swiss/en/cookbook/realtime-prediction-cookbook/gtfs-rt/
- ÖBB Open Data: https://data.oebb.at/de/datensaetze
- Italo in Viaggio: https://italoinviaggio.italotreno.com/
- Trenord Tempo Reale: https://www.trenord.it/linee-e-orari/circolazione/tempo-reale/
