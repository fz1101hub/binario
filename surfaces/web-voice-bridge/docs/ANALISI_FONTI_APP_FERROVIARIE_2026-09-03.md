# Analisi delle fonti dichiarate dalle app ferroviarie

**Data della ricognizione:** 3 settembre 2026.  
**Metodo:** sono state considerate esclusivamente pagine ufficiali degli operatori, delle app, store e informative pubbliche. Non sono stati ispezionati traffico di rete, endpoint, SDK o codice delle app concorrenti. Di conseguenza, il documento distingue le **partnership o coperture dichiarate** dalla **catena tecnica dei dati**, che nella maggior parte dei casi non è resa pubblica.

> Una funzione “ritardi in tempo reale” nell’interfaccia di un’altra app prova che quell’app mostra tale funzione; non prova che usi Viaggiatreno, un particolare endpoint RFI o una fonte liberamente riutilizzabile da terzi.

| App o servizio | Cosa risulta pubblicamente dichiarato | Cosa non risulta pubblicamente dimostrato | Lettura corretta per Binario |
| --- | --- | --- | --- |
| **Trenitalia** | L’app proprietaria permette di monitorare il treno e attivare notifiche sullo stato della circolazione e sul progresso del viaggio in tempo reale.[1] | Endpoint, licenza dati o API concessa a sviluppatori terzi. | È l’app dell’operatore; non è una prova di API pubblica. |
| **Trenord** | Il portale della Regione Lombardia pubblica un GTFS con licenza CC BY 4.0; E015 descrive un’API GTFS static/realtime di Trenord.[2] [3] | Che l’accesso realtime sia anonimo o senza onboarding. | È l’evidenza più vicina a un canale riusabile, ma il realtime richiede condizioni/accesso E015 verificati. |
| **Italo** | L’informativa “Connessioni” elenca rapporti di servizio con Itabus, Trenitalia, Trenitalia Tper, Trenord, SNAV e GNV.[4] | Origine tecnica di orari, ritardi o binari nell’app Italo; API terze pubbliche. | È un ecosistema contrattuale dell’operatore, non un catalogo API pubblico. |
| **Trainline** | Per Trenord, Trainline si descrive come venditore affidabile e mostra orari, opzioni e acquisto.[5] | API, schema, TTL o diritto di riuso degli stessi dati da parte di altri soggetti. | L’ampiezza dell’offerta è spiegabile con accordi commerciali/distributivi; non trasferisce tali diritti a Binario. |
| **Omio** | La pagina dedicata a Trenitalia afferma una partnership con il vettore e offre ricerca/prenotazione.[6] | Contratto dati, endpoint o autorizzazione per soggetti terzi. | Anche qui la prova pubblica riguarda una partnership propria di Omio. |
| **Orario Treni** | La privacy policy dichiara orari, stato realtime, ritardi e binari, oltre a collegamenti a fornitori terzi per l’acquisto.[7] | Quale fonte tecnica alimenti ogni dato, inclusi Viaggiatreno/RFI, e con quale titolo. | Vi sono funzionalità dichiarate, non una disclosure tecnica replicabile. |
| **Trenìt!** | I termini dichiarano ricerca/comparazione, informazioni realtime e scioperi; indicano che le informazioni ferroviarie provengono dalle compagnie pertinenti e rinviano a operatori terzi per l’acquisto.[8] | Endpoint, licenze o modalità di acquisizione di tali informazioni. | Copertura dichiarata non equivale a permesso per Binario di prelevare dati dagli stessi servizi. |
| **Moovit** | Il sito descrive pianificazione multimodale, arrivi realtime e avvisi in aree idonee.[9] | La fonte puntuale per ogni rete ferroviaria italiana e un titolo di riuso indipendente. | Può combinare canali diversi per città/operatore; non è una fonte nazionale unica da imitare. |

## Sintesi operativa

Le app degli operatori, come Trenitalia, Trenord e Italo, ricevono o gestiscono dati nel proprio ecosistema. Gli aggregatori commerciali, come Trainline e Omio, rendono pubbliche partnership di vendita o copertura, non i contratti tecnici sottostanti. Le app informative indipendenti rendono spesso pubblica la funzionalità, ma non l’origine tecnica puntuale del dato. **Non emerge alcuna prova pubblica che trasformi Viaggiatreno o il monitor RFI in una API liberamente usabile da altre app.**

Il caso più concreto per Binario resta distinto: il GTFS Trenord pubblicato da Regione Lombardia ha licenza CC BY 4.0 e può servire ai dati programmati; E015 dichiara un canale statico/realtime di Trenord, ma va attivato solo con accesso conforme.[2] [3] Per i dati nazionali di Trenitalia/RFI, una partnership di un’altra app non sostituisce un’autorizzazione diretta.

## Riferimenti

[1]: https://www.trenitalia.com/en/information/trenitalia-app.html "Trenitalia — app ufficiale"
[2]: https://www.dati.lombardia.it/Mobilit-e-trasporti/Orario-Ferroviario-Regionale-Gtfs/3z4k-mxz9 "Regione Lombardia — Orario Ferroviario Regionale GTFS"
[3]: https://www.e015.regione.lombardia.it/site/news-detail?id=196 "E015 — API Trenord GTFS Static e Real-time"
[4]: https://www.italotreno.com/it/privacy-policy/connessioni "Italo — Privacy Policy Connessioni"
[5]: https://www.thetrainline.com/en-us/train-companies/trenord "Trainline — Trenord"
[6]: https://www.omio.com/companies/trains/trenitalia "Omio — Trenitalia"
[7]: https://orariotreni.app/privacy "Orario Treni — Privacy Policy"
[8]: https://www.trenit.app/terms-of-use "Trenìt! — Terms of Use"
[9]: https://moovitapp.com/ "Moovit — sito ufficiale"
