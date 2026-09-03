# Protocolli live verificati

## RFI IECHub — primo adapter operativo

Il monitor pubblico RFI risponde via HTTPS a `GET /ArriviPartenze/ArrivalsDepartures/Monitor?placeId=<id>&arrivals=True|False`. Il selettore della pagina Home espone una mappa pubblica nome-stazione → `placeId`. Un campione reale per S. Bonifacio ha restituito HTTP 200 e righe HTML `tr[name=treno]` con campi `RTreno`, `RStazione`, `ROrario`, `RRitardo`, `RBinario` e messaggi aggiuntivi. Questo è il primo connettore live implementabile direttamente nel client con una richiesta per stazione e caching prudente.

RFI rappresenta un **tabellone di stazione**, non un pianificatore completo per una coppia origine/destinazione. Nell’app verrà quindi usato per popolare le corse in partenza dalla stazione della tratta, filtrando sul nodo di destinazione quando tale informazione è disponibile e segnalando la natura di tabellone in caso contrario.

## ViaggiaTreno — adapter diretto con fallback

Le pagine mobili pubbliche rispondono a POST read-only verso `/vt_pax_internet/mobile/numero` e dispongono di moduli per ricerca per stazione e tratta. La ricerca per numero ha risposto HTTP 200 durante il sondaggio, mentre il modulo tratta Roma–Napoli ha restituito HTTP 500 nel test del 14 agosto 2026. Il connettore verrà pertanto isolato dietro una policy di retry limitata e non potrà sostituire il tabellone RFI quando il servizio risponde con errore o non presenta una corsa.

L’endpoint reindirizza a HTTP. Il client mantiene il cleartext disabilitato per default e, se il dominio verrà autorizzato, l’eccezione dovrà essere limitata esclusivamente a `www.viaggiatreno.it`; nessun altro dominio potrà usare HTTP.

## Italo e Trenord — stati distinti

Le pagine pubbliche di Italo in Viaggio e Trenord Tempo Reale hanno risposto HTTP 200. I dati sono però resi da interfacce dinamiche e non è stato individuato un endpoint JSON pubblico stabile. Non vengono inventati endpoint né riusate API/app/token proprietari.

Il parser del tabellone pubblico Italo è ora attivo come `PUBLIC_BEST_EFFORT` nelle sole stazioni mappate e continua a richiedere fixture, timeout, cache e fallback. Trenord realtime non è attivo: il GTFS Lombardia copre l’orario programmato e il canale realtime strutturato richiede E015/adesione.

## SNCF GTFS-RT

I due endpoint pubblici di Trip Updates e Service Alerts rispondono HTTPS `application/x-protobuf`. Sono già verificati nel catalogo; il parser protobuf sarà collegato dopo la prima integrazione del tabellone RFI e dei data pack statici.

## Regole runtime

Ogni adapter live deve usare un User-Agent identificativo, timeout breve, al massimo una richiesta concorrente per fonte, cache/stale fallback e messaggio UI che mostri provider e momento dell’ultimo aggiornamento. Errori HTTP, parsing e timeout devono degradare al provider successivo o alla cache, mai a dati dimostrativi etichettati come live.

## Riferimenti

[1]: https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Home "RFI — Arrivi e Partenze"
[2]: https://www.viaggiatreno.it/ "Trenitalia — ViaggiaTreno"
[3]: https://italoinviaggio.italotreno.com/ "Italo in Viaggio"
[4]: https://www.trenord.it/linee-e-orari/circolazione/tempo-reale/ "Trenord — Tempo reale"

## Esito dello smoke test — 14 agosto 2026

Il comando `python3 tools/verify_live_sources.py` ha eseguito una richiesta read-only per ogni fonte abilitata nella composition root. **ViaggiaTreno** ha restituito HTTP 200 e 67 righe di tabellone per Roma Termini; **RFI** ha restituito HTTP 200 e 40 partenze per la stessa stazione. Il dettaglio è versionato in `docs/data-sources/live_source_probe_2026-08-14.tsv`.

La composition root usa ora l’ordine seguente: `ViaggiaTrenoStationAdapter` come sorgente primaria, `RfiStationBoardAdapter` come fallback live e `DemoTrainDataSource` soltanto come fallback locale etichettato. I test unitari coprono sia i due parser sia la degradazione ViaggiaTreno → RFI.

## Italo in Viaggio — verifica tabellone browser

Il 14 agosto 2026 la pagina pubblica `https://italoinviaggio.italotreno.com/it/stazione/roma-termini` ha renderizzato una tabella HTML con colonne **N. TRENO**, **Destinazione**, **Orario**, **Orario effettivo**, **Stato del treno**, **Binario** e **Informazioni**. Le prime righe hanno mostrato, per esempio, i treni Italo 8922 verso Venezia S. Lucia e 8913 verso Reggio di Calabria Centrale; i valori effettivi, stato e binario erano presenti, con `N/A` quando il binario non era disponibile.

Un adapter Italo può quindi usare le celle `table tbody tr > td` e l’ordine dichiarato dall’intestazione, evitando classi Tailwind o endpoint interni. Resta comunque separato dal percorso di produzione finché non dispone di fixture e test unitari dedicati.
