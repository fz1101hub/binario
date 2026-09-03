# Fonti ferroviarie candidate — valutazione iniziale

**Data della ricognizione:** 3 settembre 2026.  
**Regola di ingresso:** Binario integra esclusivamente dati con licenza o autorizzazione verificabile, attributo di provenienza, indicazione temporale e fallback fail-closed. Non sono state usate API private, reverse engineering o scraping.

| Opzione | Copertura verificata | Dati | Titolo di utilizzo | Stato per Binario |
| --- | --- | --- | --- | --- |
| **Feed GTFS Regione Lombardia / Trenord** | Rete Trenord e Malpensa Express | Orari programmati, rete, fermate e linee | **CC BY 4.0**, dataset pubblicato nel portale regionale e fornito da Trenord | Integrabile senza credenziali per una prima copertura regionale; va etichettato “programmato”, non realtime. |
| **API E015 Trenord GTFS Static e Real-time** | Rete ferroviaria gestita da Trenord | GTFS statico; ritardi effettivi e previsti; soppressioni | Fonte ufficiale E015/Trenord; condizioni operative, credenziali e limiti di accesso da confermare prima dell’onboarding | Candidata per realtime limitato alla sola rete coperta; **non integrata** finché non esiste accesso autorizzato. |

Il catalogo regionale pubblica il feed `trenord_gtfs.zip` con licenza CC BY 4.0 e indica dati forniti da Trenord; la pagina registrava ultimo aggiornamento il 29 agosto 2026.[1] Ciò abilita un primo incremento sugli **orari pianificati** e non autorizza a presentare ritardi, binari o cancellazioni come dati in tempo reale.

La comunicazione E015 descrive l’API Trenord con feed statico e realtime: il primo comprende topologia, linee e orari; il secondo include ritardi effettivi/previsioni e soppressioni.[2] La scheda pubblica conferma il perimetro, ma non espone nella sessione attuale credenziali né dettagli sufficienti per chiamare un endpoint. Prima dell’uso servono iscrizione/accordo applicabile, chiave, limiti di chiamata e conferma delle condizioni di riuso.

## Decisione richiesta

Non è stata individuata una fonte nazionale italiana equivalente con API pubblica, licenza e condizioni realtime sufficientemente documentate per essere usata subito. Questa è una conclusione della ricognizione iniziale, non una dichiarazione di inesistenza.

Binario può procedere in una delle due direzioni seguenti: una prima integrazione **solo programmata** basata sul GTFS regionale con attribuzione CC BY 4.0, oppure l’onboarding della fonte ufficiale E015 per valutare il realtime Trenord dopo ricezione delle credenziali. Le due opzioni possono anche convivere: il feed statico rimane la base e il realtime, se autorizzato, è un livello separato con timestamp, TTL e indisponibilità esplicita.

## Verifica specifica: Viaggiatreno e monitor RFI

Viaggiatreno è un servizio ufficiale di Trenitalia che visualizza informazioni di circolazione, arrivi/partenze, binari e notizie di infomobilità; la stessa pagina avverte di controllare l’ora dell’ultimo rilevamento.[3] Il suo testo legale, tuttavia, non è una licenza dati o una documentazione API: riserva i diritti sui contenuti e vieta di copiarli, riprodurli, trasferirli, pubblicarli o distribuirli senza preventivo consenso scritto di Trenitalia. Consente il download/stampa solo per uso personale informativo e vieta inoltre il deep linking senza autorizzazione.[3]

La pagina FS/RFI per gli interventi infrastrutturali rimanda gli utenti a Viaggiatreno per monitorare il viaggio e a una pagina RFI per il monitor arrivi/partenze live nelle stazioni.[4] Quest’ultima avverte esplicitamente che il dato online può avere un ritardo fino a tre minuti rispetto agli annunci in stazione e richiede all’utente di aggiornare il monitor; non costituisce una specifica API, una licenza di riuso o un’autorizzazione al prelievo applicativo.[5] Di conseguenza, non sono stati chiamati endpoint, automatizzati browser, copiati dati o aggiunti link diretti da Binario.

L’unica via consentita per usare questi dati in Binario è ottenere da Trenitalia/RFI un’autorizzazione scritta o un contratto che specifichi endpoint, perimetro, attribuzione, frequenza, limiti, retention, responsabilità e canale di supporto. Fino ad allora, il comportamento corretto rimane `unavailable` per gli stati ferroviari nazionali, senza imitare tecniche non documentate di altre app.

## Riferimenti

[1]: https://www.dati.lombardia.it/Mobilit-e-trasporti/Orario-Ferroviario-Regionale-Gtfs/3z4k-mxz9 "Regione Lombardia — Orario Ferroviario Regionale GTFS"
[2]: https://www.e015.regione.lombardia.it/site/news-detail?id=196 "E015 — Nuova API Trenord GTFS Static e Real-time"
[3]: http://www.viaggiatreno.it/infomobilita/index.jsp "Viaggiatreno — servizio e note legali Trenitalia"
[4]: https://www.fsitaliane.it/it/interruzioni.html "FS Italiane/RFI — informazioni sulla viabilità ferroviaria"
[5]: https://www.rfi.it/it/stazioni/pagine-stazioni/servizi-di-qualita/informazioni-al-pubblico/monitor-arrivi-partenze-live.html "RFI — Monitor Arrivi Partenze live"
