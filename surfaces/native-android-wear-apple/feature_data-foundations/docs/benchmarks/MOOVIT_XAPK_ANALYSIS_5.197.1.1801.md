# Analisi statica Moovit 5.197.1.1801

## Perimetro e metodo

L’analisi è limitata al pacchetto XAPK fornito dall’utente. È stata eseguita in modo statico: struttura archivio, manifest, permessi, componenti dichiarati, risorse localizzate e stringhe non eseguibili. Il pacchetto non è stato installato o avviato; non sono stati riutilizzati codice, risorse, database, endpoint, token o dati dell’app analizzata.

| Elemento | Evidenza |
|---|---|
| Pacchetto | `com.tranzmate` |
| Versione | `5.197.1.1801` |
| Target SDK dichiarato | 36 |
| SHA-256 XAPK analizzato | `e4c50462b6f6661dcd966e62ff15c0eb6ac5cef0338c3fae2c9a3507ba2bcca0` |
| Struttura | APK base, 14 split lingua, split densità e split ABI arm64-v8a |
| Metodo | Ispezione passiva con utility archivio e Android build tools |

## Segnali funzionali osservati

Le stringhe italiane e le classi/nomi non offuscati indicano un prodotto multimodale incentrato su fermata, itinerario e navigazione. Sono emersi segnali di ricerca fermate con indice testuale, arrivi alla fermata, linee e preferiti, avvisi di servizio, condivisione itinerario, notifiche, accessibilità con preferenze per sedia a rotelle, affollamento della stazione e informazioni di accesso/percorso interno.

| Area | Segnale statico | Valutazione per Binario |
|---|---|---|
| Ricerca locale | Schema `stops`, indice `stop_search_fts`, nome e codice fermata | **Adottabile come pattern**: la lista stazioni curata di Binario deve restare l’unico indice locale, con alias e codice. |
| Pagina stazione | Arrivi, linee, preferiti e avvisi relativi all’operatore | **Alta priorità**: un tabellone stazione ferroviario con prossimo arrivo, provenienza, freshness e avvisi RFI. |
| Itinerario | Pianificazione, partenza/arrivo, alternative e condivisione | **Parzialmente già presente**: migliorare comparazione alternative e condivisione con provenienza dati. |
| Accessibilità | Percorsi/linee accessibili in sedia a rotelle | **Adottabile solo con fonte verificata**: preferenza personale e dichiarazione di copertura; nessuna promessa di accessibilità senza dati della stazione. |
| Affollamento | Segnalazioni e banner affollamento stazione | **Da rimandare**: richiede fonte ufficiale, metodologia e timestamp; non usare inferenze locali. |
| Entrate e percorsi interni | Percorsi, fotografie e amenities di fermata | **Da valutare**: utile per grandi stazioni, ma solo con open data o accordo esplicito. |
| Notifiche | Avvisi e stato viaggio | **Già coerente con Binario**: estendere gli avvisi pendolare usando WorkManager e consenso esplicito. |
| Ticketing e ride-hailing | Flussi commerciali e servizi multimodali | **Fuori perimetro**: non necessari per il consultatore ferroviario personale. |

## Dati e architettura osservabili

Il pacchetto contiene riferimenti a un modello locale di fermate con identificativo, nome, codice, coordinate, immagini, percorsi interni, servizi e appartenenza alle linee. Questo segnala un’architettura orientata alla consultazione offline e a aggiornamenti per area/revisione, ma non autorizza Binario a riutilizzare né quel modello concreto né i dati associati.

Per Binario, il riferimento corretto resta il catalogo utente `stazioni_ferroviarie_curato_v4`: identità, alias, coordinate e codici vengono mantenuti in un asset offline; l’orario e la circolazione provengono esclusivamente dalla catena qualificata realtime → GTFS programmato → indisponibilità. Il pattern utile è quindi la **separazione fra catalogo di scoperta e stato operativo**, non il database dell’app analizzata.

## Privacy e sicurezza

Il manifest dichiara molte superfici non necessarie a Binario: localizzazione in background, fotocamera, activity recognition, overlay, Bluetooth, NFC, advertising ID, servizi pubblicitari e billing. Le stringhe rilevano inoltre componenti associabili a analytics, attribuzione, messaggistica e contenuti marketing. Queste osservazioni motivano il mantenimento della scelta Binario: permessi minimi, nessun SDK advertising/analytics e localizzazione solo su consenso puntuale per l’ordinamento delle stazioni vicine.

## Raccomandazioni

| Priorità | Proposta per Binario | Dati necessari | Stato |
|---|---|---|---|
| P0 | Tabellone stazione come superficie primaria: prossime corse, freshness, provider, alert e azione monitoraggio | ViaggiaTreno/RFI già integrati, con degradazione esplicita | Da progettare |
| P0 | Ricerca stazioni con nome, alias e codice in un unico campo | Catalogo curato v4 già integrato | Da rifinire nella UI |
| P1 | Sezione “stazione” con preferito, monitoraggio, coordinate e collegamenti a tratte recenti | Catalogo curato e persistenza locale | Da progettare |
| P1 | Confronto alternative di viaggio con motivazione di qualità/fonte | Provider e GTFS già presenti | Da progettare |
| P2 | Preferenza accessibilità nel pianificatore | Fonte verificata per infrastrutture/accessibilità | Bloccata da dati |
| P2 | Affollamento e percorsi interni | Fonte ufficiale, licenza, freshness e metodo | Bloccata da dati |

## Limiti di riuso

Non sono riutilizzabili codici, risorse, traduzioni, layout, database, endpoint, token, regole di ranking, dati di fermata, feed o contenuti operativi dell’app analizzata. Sono invece riutilizzabili idee astratte: organizzazione per stazione, disclosure della qualità del dato, preferenze di accessibilità, alternative di viaggio, notifiche consensuali e ricerca locale rapida.
