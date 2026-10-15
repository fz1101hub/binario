# Matrice del doppio ritardo

Binario conserva sempre il **ritardo ufficiale** comunicato dalla fonte e aggiunge il **ritardo alla rilevazione operativa** soltanto quando la fonte identifica lo stesso evento con località, orario programmato e orario effettivo. I due valori non sono intercambiabili.

| Ecosistema | Ritardo ufficiale | Rilevazione calcolabile | Stato nell’app | Limite esplicito |
|---|---|---|---|---|
| ViaggiaTreno / RFI | Sì, tramite tabelloni pubblici | Non dal solo tabellone di stazione; richiede dettaglio corsa autorizzato con evento reale | Ritardo ufficiale attivo | Nessuna ultima rilevazione dedotta da un orario previsto |
| Italo in Viaggio | Sì, quando il tabellone lo espone | Non verificata nel canale pubblico corrente | Ritardo ufficiale attivo | Non si espone una boa o posizione dedotta |
| STA Alto Adige | Sì, se il Trip Update pubblica `delay` o `time` | Sì, se lo stesso update/Vehicle Position identifica stop e timestamp | Adapter realtime disponibile | Dipende dalla completezza del feed per veicolo/corsa |
| SNCF GTFS-Realtime | Sì, tramite `StopTimeEvent.delay` | Solo se un evento contiene stop, orario statico corrispondente e timestamp reale | Alert e Trip Updates nel livello dati | Un update può essere previsione, non conferma di passaggio |
| Svizzera GTFS-RT | Sì, tramite Trip Updates | Solo per stop con update confrontabile con GTFS statico | Predisposto dietro gateway token | Token gratuito non incluso nell’APK |
| Trenord E015 | Sì, se il payload espone Trip Updates | Solo con evento completo e feed autorizzato | GTFS statico; realtime non attivo | Richiede E015/autorizzazione |
| ÖBB Scotty / Open Data | Canali pubblici mostrano aggiornamenti, ma non è disponibile un feed machine-readable verificato | Non integrabile nel client senza canale documentato | GTFS statico | Nessuna estrazione da API interne o interfacce non documentate |
| Reti regionali/isolate | Dipende dal singolo gestore | Solo se il gestore pubblica un evento realtime confrontabile | GTFS statico | Non si calcola da orari programmati da soli |

## Regola UI

Quando entrambi i valori sono disponibili, la corsa deve mostrare due righe distinte:

> **Ritardo ufficiale:** +7 min — fonte e ora di aggiornamento.
> **All’ultima rilevazione:** Bologna Centrale, 10:12 effettivo / 10:05 programmato, +7 min.

Se la differenza supera tre minuti, l’app conserva entrambi e visualizza l’avvertenza **“Ritardi divergenti”**. Se manca l’orario effettivo dell’evento, l’app non calcola un ritardo alla rilevazione.
