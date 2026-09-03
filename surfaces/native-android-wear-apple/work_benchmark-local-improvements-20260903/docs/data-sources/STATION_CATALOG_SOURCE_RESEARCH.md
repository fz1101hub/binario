# Ricerca fonti — catalogo nazionale stazioni

**Stato:** valutazione iniziale; nessuna nuova fonte è ancora stata integrata nell’APK.

## Evidenze raccolte il 15 agosto 2026

| Fonte | Evidenza | Copertura utile | Licenza / aggiornamento | Valutazione iniziale |
|---|---|---|---|---|
| Regione Toscana — Orari trasporto pubblico | Il dataset pubblica la risorsa `TRENITALIA.gtfs`, con fermate e orari per i servizi ferroviari indicati dal portale. | Toscana, non catalogo nazionale completo. | **CC BY**; editore Regione Toscana; ultima modifica indicata l’11 agosto 2026; aggiornamento continuo dichiarato. | Adatto come fonte primaria regionale aggiuntiva, non sufficiente da solo per il Lazio. |
| Mobility Database — mdb-1319 | Catalogo terzo che registra il feed del produttore Regione Toscana, rilevando 196 località e 14 linee. | Stessa copertura del feed toscano. | La pagina rimanda al feed del produttore e alla licenza CC BY; non va usata come fonte primaria per il bundle. | Utile per scoprire e validare feed, non preferibile rispetto al download diretto dell’editore. |
| Transitland — feed Trenitalia | Il catalogo dichiara licenza CC BY 4.0 e uso derivato consentito con attribuzione, ma non dimostra da solo la copertura o l’aggiornamento del produttore. | Da verificare tramite sorgente primaria. | Metadati del catalogo, non titolarità dell’operatore. | Soltanto evidenza secondaria. |
| Roma Capitale / Roma Servizi per la Mobilità — `rome_static_gtfs.zip` | Il catalogo open data primario pubblica GTFS statico e realtime per il trasporto pubblico di Roma; l’editore e titolare dichiarati sono Roma Servizi per la Mobilità. | Roma; può contribuire con fermate urbane e ferroviarie locali, non dimostra copertura regionale nazionale. | **CC BY-SA**; frequenza quotidiana dichiarata; download diretto dal catalogo ufficiale; pagina sorgente aggiornata il 15 agosto 2026. | Candidato idoneo per un pack aggiuntivo di **catalogo stazioni Roma**, con attribuzione e manifest. Non usarlo per inferire orari o stato dei treni Trenitalia. |
| OpenStreetMap — catalogo geografico ferroviario Italia | Un’unica interrogazione read-only del 15 agosto 2026, filtrata su `railway=station` e `railway=halt`, ha prodotto 3.749 nomi univoci dopo l’esclusione di elementi marcati dismessi, abbandonati o merci. La verifica include Roma Termini, Roma Tiburtina, Roma Trastevere, Fiumicino Aeroporto, Viterbo Porta Fiorentina, Cassino e Latina. | Italia; scoperta geografica nazionale, non fonte di servizio ferroviario. | **ODbL 1.0**, con attribuzione obbligatoria e dati derivati distribuiti alle stesse condizioni. Snapshot, query, hash e attribuzione sono inclusi nell’asset. | **Integrato solo nell’autocompletamento**. Non partecipa a provider, orari, binari, ritardi, soppressioni o realtime. |
| dati.gov.it / MIT | Le pagine consultate non espongono un catalogo nazionale aggiornato di fermate ferroviarie utilizzabile come GTFS. | Locale, geospaziale o infrastrutturale. | Variabile. | Non candidato per il catalogo operativo dell’app. |

> **Regola di integrazione:** l’autocompletamento può incorporare soltanto stazioni provenienti da un editore pubblico o da un operatore con termini di riuso verificabili. Un APK di terzi resta materiale di benchmark UX e non è una sorgente dati riutilizzabile.

## Prossima verifica

Occorre identificare una fonte primaria pubblica o autorizzata che copra le stazioni del Lazio e, preferibilmente, altre stazioni nazionali. Per ogni candidato saranno obbligatori manifest di provenienza, licenza, data di prelievo, checksum, fixture e test della ricerca.

## Riferimenti

1. [Regione Toscana — Orari trasporto pubblico](https://dati.toscana.it/dataset/rt-oraritb)
2. [Mobility Database — feed mdb-1319](https://mobilitydatabase.org/feeds/gtfs/mdb-1319)
3. [Transitland — feed Trenitalia](https://www.transit.land/feeds/f-sp-trenitaliaspa)
4. [dati.gov.it — ricerca “ferrovia”](https://www.dati.gov.it/index.php/view-dataset?tags=ferrovia&page=1)
5. [Transitland — feed Roma Mobilità](https://www.transit.land/feeds/f-sr-atac~romatpl~trenitalia)
6. [Roma Capitale — Dati del Trasporto Pubblico (GTFS)](https://dati.comune.roma.it/catalog/dataset/c_h501-d-9000)
7. [Roma Mobilità — Open Data](https://romamobilita.it/sistemi-e-tecnologie/open-data/)
8. [OpenStreetMap — copyright e attribuzione](https://www.openstreetmap.org/copyright)
9. [OpenStreetMap Wiki — `railway=station`](https://wiki.openstreetmap.org/wiki/Tag:railway%3Dstation)
