# Ritardi prima degli orari statici — 27 ottobre 2026

La prima build pendolari lasciava disattivati i tabelloni italiani tramite un flag di compilazione e richiedeva troppo spesso una corsa statica prima di applicare il realtime. Non soddisfaceva il requisito principale. Questo intervento rende il monitor pubblico RFI attivo nelle build normali e lo interroga per primo nelle consultazioni di oggi su Android e iPhone. Alexa usa la stessa fonte per le tratte dell’account collegato.

## Riscontro della fonte

La [verifica HTTPS del 27 ottobre](https://github.com/fz1101hub/binario/actions/runs/40428514667) ha restituito HTTP 200 per Home, Roma Termini e Milano Centrale. Le osservazioni dei due monitor erano rispettivamente `2026-10-27T00:46:33Z` e `2026-10-27T00:46:38Z`, pochi secondi prima del download. Queste risposte non dimostrano disponibilità perpetua della fonte.

Il selettore Home contiene 2.437 opzioni, inclusa l’opzione selezionata Milano Centrale. La mappa [condivisa](../contracts/rail-sources/rfi_stations.v1.json) è inclusa come risorsa nelle tre implementazioni. I place ID non sono ricavati da codici ferroviari né scoperti attraverso richieste speculative. Le fixture in `contracts/rail-live-fixtures` mantengono le righe dei tabelloni, senza immagini, script o handler della pagina.

## Comportamento verificato

- Una corsa con fermata intermedia, per esempio Milano Centrale → Lodi, viene trovata attraverso le fermate pubblicate nel tabellone, anche senza copertura nel GTFS locale.
- Ritardo pubblicato, partenza prevista, soppressione e binario arrivano ai modelli e alle schermate. Le risposte vocali e APL usano gli stessi campi verificati.
- L’osservazione è l’ora dichiarata da RFI, non il momento del download. Risposte vecchie di almeno due minuti, future di oltre un minuto, prive di timestamp o riferite a un’altra stazione non diventano dati live.
- Cache per stazione, richieste condivise e intervalli minimi evitano duplicati; i telefoni aggiornano in primo piano. Una temporanea indisponibilità non disabilita definitivamente il tentativo successivo.
- Le date future usano il pianificatore programmato. Un orario statico non viene etichettato in orario o live. Non sono inventate previsioni alle fermate successive.

I test automatici ordinari usano risposte registrate; i casi di ritardo positivo modificano esplicitamente il campo della fixture e non sono presentati come ritardi realmente osservati nella verifica notturna. Le pipeline includono inoltre richieste online con i fetcher di produzione, abilitate da `BINARIO_VERIFY_LIVE=true`: un parsing di fixture riuscito non basta alla verifica live.

## Copertura

La copertura è quella dei monitor pubblicati da RFI, nella loro finestra corrente di partenze. Non comprende ogni rete o una posizione continua del treno. RFI rimane una fonte pubblica best effort: non viene dichiarato uno SLA. Le fonti protette non sono state abilitate e nessuna credenziale o API privata di On The Rails è stata copiata. La firma store e il collaudo su dispositivi reali restano attività di rilascio distinte dai controlli del collegamento live.
