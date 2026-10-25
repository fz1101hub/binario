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
| Italia, realtime | ViaggiaTreno `infomobilitamobile/resteasy/viaggiatreno` (`andamentoTreno`, `partenze`, `cercaNumeroTrenoTrenoAutocomplete`), in parte su `http://` | Andamento treno, partenze, ricerca per numero | Sì, famiglia `infomobilita/resteasy/viaggiatreno` su HTTPS | Best effort pubblico (già attiva) |
| Italia, avvisi | ViaggiaTreno `news/infomobility?trainNumber=` e `news/smartcaring?commercialTrainNumber=` | Avvisi di infomobilità per singolo treno, con notifiche | No: Binario usa il feed RSS infomobilità RFI | Best effort pubblico, candidata |
| Italia, tabelloni | RFI `iechub.rfi.it/ArriviPartenze/.../Monitor` | Monitor arrivi e partenze | Sì, stessa fonte | Best effort pubblico (già attiva) |
| Italia, Trenitalia | Canale mobile LeFrecce `app.lefrecce.it/Channels.Mobile.BFF` (stato mobilità, orario, ricerca treno, biglietti, registrazione dispositivo) e login `PicoAuth` | Ultima rilevazione, ricerca soluzioni, biglietti e area personale con account Trenitalia | No | Esclusa: canale non documentato per terzi, già escluso in `OPERATIONAL_DETECTION_PROVENANCE.md` |
| Italia, Trenitalia | Sito LeFrecce `www.lefrecce.it/Channels.Website.BFF.WEB` (località, soluzioni, recupero viaggio) | Ricerca soluzioni e biglietti | No | Esclusa: backend del sito, non un'API per terzi |
| Italia, Italo | Solo link a `biglietti.italotreno.com` e un elenco stazioni Italo cifrato negli asset | Prenotazione esterna | Binario usa il tracker pubblico Italo in Viaggio | Nessuna fonte nuova; dataset dell'app escluso |
| Italia, Trenord | `www.trenord.it/mgmt/store-management-api/mia/direttrici/` | News per direttrice con notifiche | No: solo link alla pagina "tempo reale" | Best effort pubblico, candidata; il realtime strutturato resta E015 (`REALTIME_ACCESS_GAPS.md`) |
| Italia, scioperi | `scioperi.mit.gov.it` | Calendario scioperi | No | Fonte istituzionale pubblica, candidata come link o lettura best effort |
| Italia, EAV | Elenco stazioni EAV in JSON negli asset | Ricerca stazioni | Sì, tramite il pack GTFS EAV | Asset dell'app escluso; nessuna fonte nuova |
| Italia/Alto Adige | Interrogazioni EFA (`XML_DM_REQUEST`, `stopEvents`, `useRealtime=1`); l'host non è nel binario e arriva probabilmente dalla Remote Config Firebase | Partenze in tempo reale su rete EFA | Binario usa GTFS e GTFS-RT STA da Open Data Hub | Da identificare: verificare se esiste un endpoint EFA pubblico documentato del gestore |
| Francia | `api.sncf.com/v1/coverage/sncf` (Navitia) | Fermate e corse SNCF | Binario usa i GTFS SNCF da transport.data.gouv.fr | Gateway: API ufficiale con chiave personale, solo lato server |
| Francia | `www.sncf-connect.com/bff/api` (tabelloni, dettaglio veicolo, autocompletamento), `www.sncf-voyageurs.com/api/bff`, `www.garesetconnexions.sncf/schedule-table` | Tabelloni e andamento SNCF | No | Esclusa: backend di app e siti, non API per terzi |
| Svizzera | Gateway dell'app mobile SBB in `app.sbb.ch` (`/ws/fahrt/`) | Andamento treno SBB | Binario usa GTFS e GTFS-RT opentransportdata.swiss | Esclusa: gateway dell'app ufficiale |
| Svizzera | `transport.opendata.ch/v1/locations` | Ricerca località svizzere | No | API pubblica documentata con limiti d'uso, candidata (offre anche `stationboard` e `connections`) |
| Mappe | Tile `tile.openstreetmap.org` e `tiles.openrailwaymap.org` | Mappa con la rete ferroviaria | No, Binario non ha una mappa | Solo con un fornitore di tile adatto: le policy dei tile server OSM non ammettono un'app distribuita senza accordo |
| Meteo | Open-Meteo `forecast` e `geocoding` | Meteo a destinazione | No | Open (CC BY 4.0, uso non commerciale gratuito), fuori dal perimetro attuale |
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

Fonti nuove compatibili con la policy, in ordine di utilità:

1. **Avvisi ViaggiaTreno per treno** (`news/infomobility`): stessa famiglia best effort già attiva, porta gli avvisi sul
   dettaglio del treno.
2. **transport.opendata.ch**: API pubblica documentata per tabelloni e collegamenti svizzeri senza token, utile come
   alternativa al GTFS-RT svizzero che richiede un gateway.
3. **News Trenord per direttrice**: best effort, solo avvisi; i ritardi strutturati restano legati all'adesione E015.
4. **Calendario scioperi MIT**: fonte istituzionale, almeno come link nell'area avvisi.
5. **Navitia SNCF**: API ufficiale con chiave, utilizzabile solo tramite il gateway server descritto nella policy.

Ogni fonte va verificata prima dell'adozione su licenza e condizioni d'uso, rate policy e stabilità, come richiesto dalle
salvaguardie di `SOURCE_POLICY.md`.
