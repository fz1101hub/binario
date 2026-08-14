# Policy delle fonti ferroviarie

## Scopo

Questa policy determina quali fonti possono entrare in Binario e con quali salvaguardie. Binario è un progetto personale e gratuito, ma la gratuità non trasforma automaticamente una pagina pubblica in un’API riutilizzabile. L’accessibilità tecnica e l’autorizzazione al riuso sono valutate separatamente.

## Classi di accesso

| Classe | Regola | Esempi | Stato nel client |
|---|---|---|---|
| **Open diretto** | Licenza o condizioni di riuso esplicite; adapter e data pack sottoposti a contract test | GTFS regionali approvati, Open Data Svizzera, SNCF Open Data, ÖBB GTFS | Può essere abilitato dopo verifica della singola distribuzione |
| **Gateway** | Token, protezione dei segreti, caching condivisa o regole di accesso richiedono un canale server personale | GTFS-RT Svizzera con token, eventuali fonti con API key gratuita | Non attivo direttamente nell’APK distribuito |
| **Best effort pubblico** | Interfaccia o endpoint raggiungibile, ma non API open documentata e stabile | ViaggiaTreno, RFI tabelloni, Italo in Viaggio | Disabilitato di default; feature flag, cache, rate policy e spegnimento immediato |
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
