# Modello di qualità dei dati ferroviari

## Obiettivo

Binario non deve soltanto mostrare più dati: deve distinguere con precisione **dato operativo ufficiale**, **dato statico pianificato**, **dato derivato** e **dato non sufficientemente affidabile per la visualizzazione**. Il modello applica una valutazione per campo e non soltanto per fonte: ritardo, binario, soppressione, orario, fermata e posizione possono avere origine e qualità differenti nella stessa corsa.

## Punteggio di qualità

Ogni osservazione riceve un punteggio `0..100`, calcolato in modo deterministico. Il punteggio non sostituisce la provenienza: la UI deve sempre mantenere provider e timestamp visibili.

| Dimensione | Peso | Regola |
|---|---:|---|
| Autorità della fonte | 30 | Operatore o gestore dell’infrastruttura supera open data; una fonte derivata non può prevalere senza evidenza più recente |
| Freschezza | 25 | Dato realtime entro TTL riceve il massimo; il punteggio decade progressivamente fino a stato stale |
| Completezza | 15 | Sono premiati record con identità, orari, stato e binario coerenti; campi assenti riducono il punteggio |
| Coerenza | 20 | Confronto con altre fonti e con invarianti temporali/di percorso; conflitti riducono il punteggio e generano warning |
| Integrità tecnica | 10 | Schema previsto, identificativi tracciabili, timestamp valido e nessuna anomalia di parsing |

| Fascia | Nome | Regola di presentazione |
|---:|---|---|
| 85–100 | Verificato | Può essere mostrato come aggiornamento operativo, con provider e orario |
| 65–84 | Affidabile | Mostrabile con indicatore di freschezza e eventuale nota di copertura parziale |
| 40–64 | Limitato | Mostrabile solo con avvertenza esplicita e mai come informazione garantita |
| 0–39 | Non pubblicabile | Conservare diagnostica locale, ma non sostituire dati utente o stato operativo |

## Gerarchia per tipo di campo

| Campo | Prima scelta | Fallback | Divieto |
|---|---|---|---|
| Ritardo, soppressione, binario | Operatore o RFI con timestamp realtime | Seconda fonte ufficiale di stazione | Non derivare un ritardo da solo orario statico |
| Fermate e orari pianificati | GTFS o orario ufficiale con validità | Secondo GTFS con data compatibile | Unire corse con identità ambigua |
| Posizione | GPS o evento operativo esplicito | Interpolazione marcata come stima | Mostrare interpolazione come posizione reale |
| Avvisi | Messaggio ufficiale del titolare | Fonte istituzionale di settore | Copiare o reinterpretare testi editoriali senza attribuzione |
| Identità stazione/corsa | Codice ufficiale, UIC, RFI o alias provider | Matching normalizzato con confidenza | Associare automaticamente alias ambigui |

## Soglie operative iniziali

| Tipo di dato | Fresco | Invecchiato | Stale | TTL cache massimo |
|---|---:|---:|---:|---:|
| Tabellone realtime | ≤ 90 s | ≤ 5 min | > 5 min | 45 s |
| Stato della corsa | ≤ 2 min | ≤ 10 min | > 10 min | 60 s |
| Avvisi | ≤ 10 min | ≤ 60 min | > 60 min | 10 min |
| GTFS statico | Validità della release | — | Scaduto alla data di servizio | Nessun refresh automatico |

## Contratto di trasparenza verso l’utente

La UI deve mostrare il nome della fonte, l’istante dell’ultimo aggiornamento, lo stato di freschezza e un’avvertenza quando la qualità è limitata. Quando due fonti discordano, l’app conserva entrambi i riferimenti, privilegia il campo con punteggio più alto e visualizza `CONFLICTING_SOURCES`; non nasconde il conflitto con una falsa precisione.

## Criterio di ammissione di una nuova fonte

Una fonte può essere resa operativa soltanto se dispone di identificativi tracciabili, un contratto di parsing testato, una policy di cache e rate limit, fixture offline, gestione esplicita di cambi schema e una strategia di degradazione. Le fonti senza questi requisiti restano catalogate o disponibili soltanto come data pack offline.
