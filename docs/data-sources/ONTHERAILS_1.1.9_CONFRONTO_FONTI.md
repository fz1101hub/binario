# On The Rails 1.1.9 — confronto delle fonti dati con Binario

Analisi statica del 25 ottobre 2026 dell'XAPK `references/On_The_Rails_1.1.9.xapk` presente su `main`, caricato il
20 ottobre 2026 (package `it.pixeldev.ontherails`, app Flutter con componenti Kotlin per sveglie e notifiche). Sono
state lette soltanto le stringhe di `libapp.so`, `classes.dex`, `classes2.dex` e l'elenco degli asset. Nessun codice è
stato eseguito e nessun endpoint è stato contattato. Hostname interni, identificativi di gateway e credenziali eventualmente
presenti non sono riportati.

Come stabilito in `SOURCE_POLICY.md`, On The Rails è un caso di studio per individuare le **fonti originarie**: API
private, chiavi, asset, dataset e codice dell'app non si riusano. La colonna "Classe per Binario" applica quella policy.

## Fonti usate da On The Rails

| Ambito | Fonte usata da On The Rails | Uso nell'app | In Binario | Classe per Binario |
|---|---|---|---|---|
| Italia, realtime | ViaggiaTreno `infomobilitamobile/resteasy/viaggiatreno` (`andamentoTreno`, `partenze`, `cercaNumeroTrenoTrenoAutocomplete`), in parte su `http://` | Andamento treno, partenze, ricerca per numero | Sì, famiglia `infomobilita/resteasy/viaggiatreno`: richiesta HTTPS e, quando la fonte reindirizza, la stessa risorsa in HTTP su `www.viaggiatreno.it` | Best effort pubblico (già attiva) |
| Italia, avvisi | ViaggiaTreno `news/infomobility?trainNumber=` e `news/smartcaring?commercialTrainNumber=` | Avvisi di infomobilità per singolo treno, con notifiche | Sì da ottobre 2026: `news/smartcaring` per treno nel dettaglio e `news/infomobility` (elenco generale, filtrato per `trainTags`), senza notifiche; accanto al feed RSS RFI | Best effort pubblico (adottata) |
| Italia, tabelloni | RFI `iechub.rfi.it/ArriviPartenze/.../Monitor` | Monitor arrivi e partenze | Sì, stessa fonte | Best effort pubblico (già attiva) |
| Italia, Trenitalia | Canale mobile LeFrecce `app.lefrecce.it/Channels.Mobile.BFF` (stato mobilità, orario, ricerca treno, biglietti, registrazione dispositivo) e login `PicoAuth` | Ultima rilevazione, ricerca soluzioni, biglietti e area personale con account Trenitalia | No | Esclusa: canale non documentato per terzi, già escluso in `OPERATIONAL_DETECTION_PROVENANCE.md` |
| Italia, Trenitalia | Sito LeFrecce `www.lefrecce.it/Channels.Website.BFF.WEB` (località, soluzioni, recupero viaggio) | Ricerca soluzioni e biglietti | No | Esclusa: backend del sito, non un'API per terzi |
| Italia, Italo | Solo link a `biglietti.italotreno.com` e un elenco stazioni Italo cifrato negli asset | Prenotazione esterna | Binario usa il tracker pubblico Italo in Viaggio | Nessuna fonte nuova; dataset dell'app escluso |
| Italia, Trenord | `www.trenord.it/mgmt/store-management-api/mia/direttrici/` | News per direttrice con notifiche | Sì da ottobre 2026: avvisi per direttrice nell'area avvisi, nel dettaglio e nei tabelloni, senza notifiche | Best effort pubblico (adottata); il realtime strutturato resta E015 (`REALTIME_ACCESS_GAPS.md`) |
| Italia, scioperi | `scioperi.mit.gov.it` | Calendario scioperi | Solo collegamento nell'area avvisi, più un'etichetta «Sciopero» sugli avvisi che ne parlano | Fonte istituzionale pubblica; lettura non adottata (vedi Esito) |
| Italia, EAV | Elenco stazioni EAV in JSON negli asset | Ricerca stazioni | Sì, tramite il pack GTFS EAV | Asset dell'app escluso; nessuna fonte nuova |
| Italia/Alto Adige | Interrogazioni EFA (`XML_DM_REQUEST`, `stopEvents`, `useRealtime=1`); l'host non è nel binario e arriva probabilmente dalla Remote Config Firebase | Partenze in tempo reale su rete EFA | Binario usa GTFS e GTFS-RT STA da Open Data Hub | Da identificare: verificare se esiste un endpoint EFA pubblico documentato del gestore |
| Francia | `api.sncf.com/v1/coverage/sncf` (Navitia) | Fermate e corse SNCF | Binario usa i GTFS SNCF da transport.data.gouv.fr | Gateway: API ufficiale con chiave personale, solo lato server |
| Francia | `www.sncf-connect.com/bff/api` (tabelloni, dettaglio veicolo, autocompletamento), `www.sncf-voyageurs.com/api/bff`, `www.garesetconnexions.sncf/schedule-table` | Tabelloni e andamento SNCF | No | Esclusa: backend di app e siti, non API per terzi |
| Svizzera | Gateway dell'app mobile SBB in `app.sbb.ch` (`/ws/fahrt/`) | Andamento treno SBB | Binario usa GTFS e GTFS-RT opentransportdata.swiss | Esclusa: gateway dell'app ufficiale |
| Svizzera | `transport.opendata.ch/v1/locations` | Ricerca località svizzere | Sì da ottobre 2026: `stationboard` per i tabelloni svizzeri e `locations` solo per individuare il numero UIC di una stazione | API pubblica documentata con limiti d'uso, trattata come best effort (adottata); `connections` non usata |
| Mappe | Tile `tile.openstreetmap.org` e `tiles.openrailwaymap.org` | Mappa con la rete ferroviaria | No, Binario non ha una mappa | Solo con un fornitore di tile adatto: le policy dei tile server OSM non ammettono un'app distribuita senza accordo |
| Meteo | Open-Meteo `forecast` e `geocoding` | Meteo a destinazione | Sì da ottobre 2026: solo `forecast` per le coordinate di catalogo della stazione di arrivo; `geocoding` non serve | Open (CC BY 4.0, uso non commerciale gratuito, adottata) |
| Rimborsi | `smartrefund-trenitalia.trakti.com` | Link al servizio rimborsi | No | Solo link, non è una fonte dati |
| Backend proprio | Database Turso dell'autore, sito `ontherails-9eb03.web.app` | Statistiche treni e link condivisibili | No: Binario non ha backend | Esclusa |
| SDK | Firebase Analytics, Crashlytics, Remote Config, Google Ads, Google Sign-In e Drive, Google Pay | Telemetria, configurazione remota, pubblicità, backup | No: nessun SDK di terze parti (`docs/store/PLAY_DATA_SAFETY.md`) | Non pertinente |

## Fonti di Binario assenti in On The Rails

- Pack GTFS incorporati per Lombardia/Trenord, Alto Adige, Trentino, Toscana TFT, ARST, EAV, ÖBB, SNCF, Svizzera e HŽPP,
  con l'indice orario iOS derivato.
- GTFS-RT di Alto Adige (Open Data Hub) e della Svizzera (opentransportdata.swiss), più le Service Alerts svizzere.
- Quadri orario RFI `prm.rfi.it` e feed RSS infomobilità RFI.
- Tracker pubblico Italo in Viaggio.
- European Sleeper tramite il mirror comunitario JBB.

## Esito

On The Rails ottiene più dati di Binario soprattutto da canali che la policy esclude: i backend LeFrecce con login
Trenitalia, SNCF Connect e il gateway dell'app SBB. Da qui vengono la ricerca soluzioni e biglietti, l'ultima
rilevazione Trenitalia e l'andamento dettagliato SNCF e SBB. Binario resta più ampio sugli orari ufficiali aperti
incorporati e sul realtime GTFS-RT.

### Adottate (ottobre 2026)

Le fonti compatibili con la policy sono state verificate su risposte reali, raccolte con la selezione `discovery` del
workflow *Live source acceptance*, e adottate con le salvaguardie di `SOURCE_POLICY.md` (sezione «Fonti aggiunte a
ottobre 2026»):

1. **Avvisi ViaggiaTreno per treno** (`news/smartcaring`): mostrati nel dettaglio come «Avvisi per questo treno»,
   filtrati per giorno della corsa, numero e stazione di origine. L'endpoint HTTPS reindirizza alla stessa risorsa in
   HTTP su `www.viaggiatreno.it`, il dominio in chiaro già consentito dall'app: il client segue solo quel passaggio e
   la configurazione di rete non è stata ampliata. Best effort, attivi con le fonti personali.
2. **Infomobilità Trenitalia** (`news/infomobility`, elenco generale): nell'area avvisi accanto al feed RSS RFI,
   deduplicata per titolo, e nel dettaglio dei treni Trenitalia citati nei `trainTags`. Stesso dominio e stesse regole.
   La variante `?trainNumber=` campionata restituiva sempre un elenco vuoto ed è sostituita dal filtro sui `trainTags`.
3. **transport.opendata.ch** (`stationboard`): tabelloni realtime delle stazioni svizzere e transfrontaliere con UIC
   `85…`, uniti al GTFS svizzero e agli altri tabelloni senza duplicare le corse. Best effort, attivi con le fonti
   personali.
4. **Avvisi Trenord per direttrice**: nell'area avvisi raggruppati per linea, nel dettaglio dei treni Trenord e delle
   tratte su una direttrice, nei tabelloni delle stazioni dell'area Trenord; le notizie promozionali vengono scartate.
   I ritardi strutturati Trenord restano legati all'adesione E015.
5. **Open-Meteo** (`forecast`): meteo a destinazione nel dettaglio, con attribuzione CC BY 4.0, disattivabile; riceve
   solo le coordinate arrotondate della stazione di arrivo.

### Non adottate

- **Calendario scioperi MIT**: solo collegamento. `scioperi.mit.gov.it` va in timeout dai runner cloud e il formato
  della pagina non è verificato, quindi nessun dato ne viene letto; gli avvisi RFI, Trenitalia e Trenord che citano uno
  sciopero ricevono un'etichetta.
- **LeFrecce, SNCF Connect, SNCF Voyageurs, Gares & Connexions e gateway dell'app SBB**: backend di app e siti non
  documentati per terzi, esclusi dalla policy.
- **Navitia SNCF**: API ufficiale con chiave personale, utilizzabile solo tramite il gateway server descritto nella
  policy; non integrata nel client.
- **EFA Alto Adige**: endpoint non identificato nel binario (probabilmente configurato da remoto); Binario resta su
  GTFS e GTFS-RT STA.
- **Tile OpenStreetMap e OpenRailwayMap**: le policy dei tile server non ammettono un'app distribuita senza accordo.
- **Notifiche push degli avvisi, dataset, asset e backend di On The Rails**: fuori perimetro o esclusi.
