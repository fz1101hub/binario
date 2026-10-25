# Policy delle fonti ferroviarie

## Scopo

Questa policy determina quali fonti possono entrare in Binario e con quali salvaguardie. Binario è un progetto personale e gratuito, ma la gratuità non trasforma automaticamente una pagina pubblica in un’API riutilizzabile. L’accessibilità tecnica e l’autorizzazione al riuso sono valutate separatamente.

## Classi di accesso

| Classe | Regola | Esempi | Stato nel client |
|---|---|---|---|
| **Open diretto** | Licenza o condizioni di riuso esplicite; adapter e data pack sottoposti a contract test | GTFS regionali approvati, Open Data Svizzera, SNCF Open Data, ÖBB GTFS | Può essere abilitato dopo verifica della singola distribuzione |
| **Gateway** | Token, protezione dei segreti, caching condivisa o regole di accesso richiedono un canale server personale | GTFS-RT Svizzera con token, eventuali fonti con API key gratuita | Non attivo direttamente nell’APK distribuito |
| **Best effort pubblico** | Interfaccia o endpoint raggiungibile, ma non API open documentata e stabile | ViaggiaTreno, RFI tabelloni, Italo in Viaggio | Abilitabile solo nel registry con cache, rate policy, circuit breaker e spegnimento immediato; non è una garanzia di servizio |
| **Accordo necessario** | Dati richiesti per copertura completa ma nessun feed aperto strutturato verificato | NTV ufficiale, realtime regionale e reti isolate senza feed | Non implementare come dipendenza finché manca canale autorizzato |
| **Esclusa** | Provenienza non primaria, segreti di terzi, scraping di app, asset o licenza non chiara | API/chiavi Treniamo, asset/dataset On The Rails, credenziali di app ufficiali | Mai integrare |

## Fonti prioritarie

| Famiglia | Orario statico | Realtime e avvisi | Funzione nella roadmap |
|---|---|---|---|
| Italia regionale | GTFS Lombardia, EAV, Alto Adige, Trentino, ARST, AMT Genova, Cotral previa verifica feed | Da accordo o fonte strutturata disponibile | Primo data pack italiano |
| Svizzera e tratte italiane | GTFS Open Data Platform Mobility Switzerland | GTFS-RT e Service Alerts, con token e limiti | Primo realtime open dopo statico |
| Francia–Italia | SNCF GTFS/NeTEx | SNCF GTFS-RT e avvisi | Secondo realtime open |
| Austria–Italia | ÖBB GTFS | Controllo lato italiano da fonte separata | Statico internazionale |
| Trenitalia/RFI | Feed o accordi quando disponibili | ViaggiaTreno/RFI solo best effort | Feature flag successiva |
| Italo | Nessun GTFS open ufficiale individuato | Italo in Viaggio best effort o canale NTV | Feature flag o accordo futura |

## Salvaguardie obbligatorie

Ogni adapter deve dichiarare `SourceId`, licenza/access class, capability, coverage, rate policy, TTL, timeout e comportamento in caso di errore. Nessuna fonte è chiamata a ogni render UI: il repository applica request coalescing, cache, stale-while-revalidate, retry limitato e circuit breaker.

Un provider best-effort non genera mai promesse implicite di disponibilità. Se diventa indisponibile, l’app conserva l’ultimo snapshot con timestamp e mostra uno stato di fonte non aggiornata o non disponibile. Non sostituisce una risposta fallita con un ritardo inventato.

### European Sleeper / JBB: fallback di comunità

European Sleeper è catalogata con il mirror **JBB** come fonte **community best-effort** `PUBLIC_BEST_EFFORT` / `BEST_EFFORT_FALLBACK`, non come feed ufficiale dell’operatore. Il client usa l'host `jbb.ghsq.de` per l'archivio statico `https://jbb.ghsq.de/gtfs/eu-es.gtfs.zip` e, per gli aggiornamenti riconciliabili con lo stesso archivio, `https://jbb.ghsq.de/gtfs/european-sleeper/TripUpdates`.

Il gate runtime a tier consulta questo fallback solo dopo che il **tier primario** non riesce a costruire la tratta richiesta: una risposta primaria riuscita evita qualsiasi chiamata JBB. Il fallback non può quindi scalzare RFI, un operatore, un gestore dell’infrastruttura o un data pack ufficiale che abbia prodotto la tratta. Il risultato deve restare marcato come copertura limitata/best-effort e con la relativa provenienza.

Le chiamate JBB sono richieste HTTPS `GET` dirette che impostano `Accept` e un `User-Agent` Binario. L'host `jbb.ghsq.de` può vedere i metadati di rete ordinari della richiesta — inclusi indirizzo IP del collegamento, data e ora, risorsa richiesta e intestazioni HTTP — e li tratta fuori dal controllo di questo repository. Questa descrizione documenta il comportamento del client; non attribuisce a JBB una policy di conservazione o un consenso che il codice non gestisce.

Alla baseline corrente ViaggiaTreno, RFI Monitor e Italo in Viaggio sono presenti come adapter `PUBLIC_BEST_EFFORT` abilitati nel `ProviderRegistry`, ciascuno con limiti, cache e fallback; dall'ottobre 2026 si aggiungono i tabelloni svizzeri `transport.opendata.ch`, gli avvisi ViaggiaTreno per treno, l'infomobilità Trenitalia e gli avvisi Trenord per direttrice (sezione seguente). Questa abilitazione non ne modifica la classe di accesso: il connettore deve poter essere sospeso immediatamente se la salute, le condizioni o il comportamento della fonte non restano compatibili con questa policy. Tutti dipendono da `BuildConfig.PERSONAL_REALTIME_SOURCES`, oggi `true` per impostazione predefinita anche in release. Trenord realtime rimane fuori dal registry attivo fino a un canale E015 autorizzato: gli avvisi per direttrice sono testo, non ritardi.

### Fonti aggiunte a ottobre 2026

| Fonte | `SourceId` | Classe | Capability | Copertura | Rate policy / TTL | Timeout e limiti | In caso di errore | Attivazione |
|---|---|---|---|---|---|---|---|---|
| transport.opendata.ch, `v1/stationboard` (e `v1/locations` per l'identità) | `ch-transport-opendata` | **Best effort pubblico**: API pubblica documentata senza token, con limiti di uso corretto e senza garanzia di servizio | `STATION_BOARD` | Stazioni svizzere e transfrontaliere con UIC `85…` | 30 s / 60 s; identità per nome in cache 7 giorni | 8 s connessione, 12 s lettura, 15 s totali; 3 MB | Tabellone precedente al più 10 minuti, marcato `STALE_SOURCE` e con osservazioni scadute; altrimenti errore tipizzato | Fonti personali |
| ViaggiaTreno, `news/smartcaring` | `viaggiatreno-train-notices` | **Best effort pubblico**, famiglia ViaggiaTreno | `SERVICE_ALERTS` | Avvisi Trenitalia per numero treno | 30 s / 5 min | 15 s totali; 2 MB | Ultime note al più 30 minuti, marcate come non aggiornate | Fonti personali |
| ViaggiaTreno, `news/infomobility` | `trenitalia-infomobility-news` | **Best effort pubblico**, famiglia ViaggiaTreno | `SERVICE_ALERTS` | Notizie Trenitalia nazionali e regionali con numeri treno | 5 min / 10 min | 15 s totali; 2 MB | Ultimo elenco al più un'ora, marcato come non aggiornato | Fonti personali |
| Trenord, `mgmt/store-management-api/mia/direttrici/` | `trenord-line-notices` | **Best effort pubblico** | `SERVICE_ALERTS` | Avvisi testuali per direttrice | 10 min / 15 min | 15 s totali; 1 MB (risposta attuale circa 90 KB) | Ultimo elenco al più due ore, marcato come non aggiornato | Fonti personali |
| Open-Meteo, `v1/forecast` | `open-meteo` | **Open diretto**: CC BY 4.0, gratuito per uso non commerciale, senza chiave; attribuzione «Dati meteo: Open-Meteo.com (CC BY 4.0)» | `WEATHER_FORECAST` (non è un dato ferroviario) | Stazione di arrivo del dettaglio treno, oggi e domani | 1 min / 3 h per luogo arrotondato e giorno | 15 s totali; 64 KB | Previsione precedente al più tre ore, marcata come non aggiornata; altrimenti la sezione dice che non è disponibile | Sempre, salvo impostazione «Meteo a destinazione» disattivata |
| MIT, calendario scioperi | — | Fonte istituzionale, **solo collegamento** | — | — | — | Nessuna richiesta dell'app | — | Link nell'area avvisi, aperto nel browser su tocco |

Salvaguardie comuni (`data/live/PublicSourceHttp.kt`, `data/live/ResilientSourceCache.kt`): richieste avviate sempre in HTTPS (con la sola eccezione ViaggiaTreno descritta sotto), fuori dal thread principale con `User-Agent` Binario, timeout e corpo limitato (un corpo più grande è rifiutato, mai troncato); una sola richiesta in corso per chiave (request coalescing); cache con TTL e valore precedente servito come non aggiornato (stale-while-revalidate); un solo nuovo tentativo, e solo per errori di rete transitori, mai per stati HTTP o risposte malformate; circuit breaker che dopo tre errori consecutivi sospende la fonte per un minuto, raddoppiando fino a quindici. Una risposta malformata diventa un errore tipizzato (`PARSE_ERROR`), mai un'eccezione verso l'interfaccia. Il feed RSS RFI, ora mostrato nell'area avvisi, legge a sua volta un corpo limitato con circuit breaker e ultimo elenco come fallback.

Regole che impediscono di inventare dati:

- **transport.opendata.ch.** Il numero pubblico della riga è il numero di corsa (`name` senza zeri iniziali), lo stesso del GTFS svizzero e dei tabelloni italiani, così aggregatore e riconciliatore uniscono le righe invece di duplicarle; la linea commerciale («RE 80») è solo etichetta. Una previsione è accettata soltanto se è del lato del tabellone (partenza per le partenze, arrivo per gli arrivi) ed entro una finestra plausibile dall'orario programmato (partenze da −1 a +360 minuti, arrivi da −15 a +360): i campioni reali contengono previsioni di arrivo anteriori alla partenza programmata sulla prima riga. Senza previsione valida né `delay` la riga resta solo programmata. In una richiesta di arrivi le righe senza orario di arrivo vengono scartate.
- **Avvisi ViaggiaTreno per treno.** La risposta contiene molti giorni passati: si tengono solo le note la cui validità copre il giorno della corsa, per lo stesso numero e, quando l'app lo conosce dall'identità ViaggiaTreno della corsa, per la stessa stazione di origine.
- **Cleartext.** Le API ViaggiaTreno rispondono in HTTPS con un reindirizzamento alla stessa risorsa su `http://www.viaggiatreno.it`, l'unico dominio in chiaro di `network_security_config.xml`. Il client segue solo quel passaggio (stesso host, percorso e query) e nessun altro; la configurazione non è stata ampliata.
- **Infomobilità Trenitalia.** Le descrizioni sono HTML con entità codificate due volte: si decodificano le entità e jsoup riduce il markup a testo; niente viene reso come HTML e si conservano solo link http(s).
- **Avvisi Trenord.** La stessa notizia ripetuta su più direttrici diventa un solo avviso con l'elenco delle linee. Una notizia è tenuta solo se il testo, senza distinzione di maiuscole e accenti, contiene una radice operativa: `scioper`, `soppress`, `ritard`, `lavori`, `variazion`, `sostitui`/`sostitutiv`, `interruz`/`interrott`, `circolazion`, `sospes`, `cancellat`, `limitazion`, `guast`, `deviat`, `modific`, `autobus`, oppure «servizio (di) bus», «corse (di/con) bus», «bus tra/fra». La sola parola «bus» non basta: la promozione reale «Ricaricami» parla di «treni, metro, tram e bus». La regola è coperta da `TrenordLineNoticesAdapterTest`.
- **Scioperi.** Non esiste una fonte strutturata verificata: `scioperi.mit.gov.it` non risponde dai runner cloud e il formato non è verificato. L'app non lo interroga; mostra il collegamento al calendario e un'etichetta «Sciopero» sugli avvisi RFI, Trenitalia e Trenord che citano «sciopero» o «scioperi».
- **Meteo.** Le coordinate sono quelle del catalogo curato per la stazione di arrivo; una stazione senza coordinate non mostra nulla. A Open-Meteo arrivano solo quelle coordinate arrotondate a due decimali, mai la posizione dell'utente.

## Attribuzione e presentazione

Ogni snapshot visualizzabile espone nome della fonte, ultimo aggiornamento, freshness e confidence. I dati open data mantengono l’attribuzione richiesta dal titolare. Marchi, loghi, testi editoriali e layout degli operatori non vengono copiati senza permesso.

La schermata Info dell’app dovrà dichiarare:

> Binario è un’app indipendente e non affiliata agli operatori ferroviari. I dati possono essere incompleti, differire dai canali ufficiali o essere temporaneamente non aggiornati. Prima di viaggiare, verificare sempre monitor e annunci ufficiali.

## Riferimenti

[1]: https://opentransportdata.swiss/en/terms-of-use/ "Open Data Platform Mobility Switzerland — condizioni d’uso"
[2]: https://ressources.data.sncf.com/pages/accueil/ "SNCF Open Data"
[3]: https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~ "ÖBB Open Data — GTFS"
[4]: https://www.dati.lombardia.it/Mobilit-e-trasporti/Orario-Ferroviario-Regionale-Gtfs/3z4k-mxz9 "Regione Lombardia — GTFS ferroviario"
[5]: https://www.eavsrl.it/open-data/ "EAV — Open Data"
[6]: https://www.rfi.it/it/stazioni/pagine-stazioni/servizi-di-qualita/informazioni-al-pubblico/monitor-arrivi-partenze-live.html "RFI — Monitor Arrivi/Partenze live"
