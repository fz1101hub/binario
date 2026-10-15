# Verifica del doppio ritardo

## Esito tecnico

La baseline conserva ora il ritardo pubblicato dalla fonte in `officialDelayMinutes` e può aggiungere un secondo valore `detectionDelayMinutes` soltanto tramite `OperationalDetection`: località, orario programmato e timestamp effettivo del medesimo evento.

| Controllo | Esito |
|---|---:|
| Test unitari del contratto `OperationalTiming` | Superati |
| Test di divergenza fra i due ritardi | Superato |
| Test del modello UI/widget con nuovo warning | Superato |
| ViaggiaTreno, tabellone Roma Termini | HTTP 200, 19 corse parsabili |
| RFI, partenze Roma Termini | HTTP 200, 40 corse parsabili |
| Italo in Viaggio, stazioni mappate | 7/7 pagine HTTPS 200 |

## Regola applicata

Il ritardo del tabellone ViaggiaTreno, RFI o Italo viene sempre conservato come **ritardo ufficiale**. Il solo fatto che un tabellone mostri `orario` e `ritardo` non prova un passaggio già avvenuto: non viene quindi creato un evento di ultima rilevazione dalla partenza prevista, dall’orario stimato o da una fermata programmata.

Il ritardo alla rilevazione viene calcolato soltanto quando un adapter fornisce:

1. località o punto operativo identificato;
2. orario programmato per lo stesso evento;
3. timestamp effettivo, osservato o pubblicato, del medesimo evento.

Quando entrambi esistono, la differenza oltre tre minuti attiva il warning `DELAY_DIVERGENCE`; nessuno dei due viene eliminato.

## Copertura effettiva

| Fonte | Ritardo ufficiale | Ritardo alla rilevazione |
|---|---|---|
| ViaggiaTreno tabellone | Sì | Non dal tabellone di stazione corrente |
| RFI Monitor | Sì | Non dal tabellone di stazione corrente |
| Italo in Viaggio | Sì, se esposto | Non verificato come evento completo pubblico |
| STA GTFS-Realtime | Se pubblica Trip Updates completi | Possibile con stop/evento completo |
| SNCF GTFS-Realtime | Se pubblica Trip Updates completi | Possibile solo per eventi con timestamp reale confrontabile |
| Svizzera GTFS-RT | Predisposto, richiede token gateway | Predisposto, richiede token gateway |
| Trenord E015 | Predisposto, richiede autorizzazione | Predisposto, richiede autorizzazione |

La matrice dettagliata resta in [`DUAL_DELAY_SOURCE_MATRIX.md`](DUAL_DELAY_SOURCE_MATRIX.md).
