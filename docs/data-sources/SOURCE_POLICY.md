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

Alla baseline corrente ViaggiaTreno, RFI Monitor e Italo in Viaggio sono presenti come adapter `PUBLIC_BEST_EFFORT` abilitati nel `ProviderRegistry`, ciascuno con limiti, cache e fallback. Questa abilitazione non ne modifica la classe di accesso: il connettore deve poter essere sospeso immediatamente se la salute, le condizioni o il comportamento della fonte non restano compatibili con questa policy. Trenord realtime rimane fuori dal registry attivo fino a un canale E015 autorizzato.

## Mappe di base

Le mappe dell'app non sono una fonte ferroviaria: sono uno sfondo cartografico su cui l'app disegna soltanto coordinate del catalogo stazioni curato. Non determinano orari, binari, ritardi, soppressioni o posizioni dei treni, e Binario non disegna mai una posizione del treno che nessuna fonte ha pubblicato.

### OpenFreeMap: fornitore di mappe open con attribuzione

Classe: **Open diretto — fornitore di mappe open**. OpenFreeMap pubblica gratuitamente, senza chiave API e con uso consentito anche nelle app, stili e tessere vettoriali generate da dati OpenStreetMap (ODbL 1.0) nello schema OpenMapTiles. Il client Android usa soltanto l'host `tiles.openfreemap.org`: stile `liberty` nel tema chiaro e `dark` nel tema scuro, con tessere, glifi e sprite referenziati dallo stesso stile; il rendering è locale con MapLibre Native (BSD-2-Clause).

Salvaguardie: le mappe partono solo da un'azione esplicita dell'utente (scheda espansa o *Carica la mappa* per le stazioni vicine, con avviso); le richieste HTTPS `GET` non contengono nomi di fermata, orari o coordinate dell'utente, ma l'host vede i normali metadati di rete (indirizzo IP, data e ora, intestazioni) e la zona mostrata; un errore di caricamento mostra un messaggio con *Riprova* e le stesse informazioni restano disponibili come testo; la mappa non è mai l'unico modo di leggere un dato.

Attribuzione obbligatoria: il controllo di attribuzione di MapLibre resta attivo, ogni scheda mappa mostra «OpenFreeMap © OpenMapTiles, dati © OpenStreetMap contributors» e le impostazioni riportano lo stesso credito, con la licenza di MapLibre Native, accanto al credito OpenStreetMap del catalogo stazioni.

Esclusi: le tessere di `tile.openstreetmap.org` (la tile usage policy di OSMF non ammette app distribuite senza autorizzazione) e le tessere OpenRailwayMap (la loro policy pone la stessa restrizione). Non vanno introdotti senza un permesso scritto del gestore.

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
