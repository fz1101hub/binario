# Adapter delle fonti ferroviarie e fixture di test

## Decisione

Binario introdurrà un **adapter GTFS statico riutilizzabile** per tutti i feed ZIP che rispettano il set minimo `agency.txt`, `stops.txt`, `routes.txt`, `trips.txt` e `stop_times.txt`. L’adapter non scarica dati automaticamente dall’interfaccia: riceve un `InputStream` o un data pack già acquisito e validato. Questo mantiene il parsing deterministico, testabile offline e separato dalla politica di rete.

| Componente | Responsabilità |
|---|---|
| `GtfsSourceDefinition` | Origine, access class, URL di landing/download, licenza nota, copertura e stato di test |
| `GtfsArchiveInspector` | Limiti anti-zip-bomb, presenza dei file obbligatori, parsing CSV difensivo e riepilogo feed |
| `GtfsStaticProvider` | Espone catalogo stazioni e orario statico solo da un data pack già validato |
| `SourceProbe` esterno | Esegue HEAD/GET read-only durante sviluppo o CI; non entra nell’APK |
| Adapter GTFS-RT | Livello separato, attivabile soltanto quando il protobuf e le condizioni di accesso sono definiti |
| Adapter best-effort | Livello separato, feature flag disabilitato, gateway/cache obbligatori prima dell’uso produttivo |

## Fonti classificate

| Fonte | Stato di test al 14 agosto 2026 | Integrazione proposta |
|---|---|---|
| ARST GTFS | ZIP HTTPS 200, set minimo GTFS verificato | `GtfsStaticProvider`, data pack italiano |
| HŽPP GTFS | ZIP HTTPS 200, set minimo GTFS verificato | `GtfsStaticProvider`, copertura internazionale da filtrare sul tratto italiano |
| SNCF GTFS | ZIP HTTPS 200, set minimo GTFS verificato | `GtfsStaticProvider`, TGV/TER transfrontalieri |
| SNCF GTFS-RT | protobuf HTTPS 200 per Trip Updates e Service Alerts | Adapter realtime futuro; fixture binarie e parser protobuf separati |
| Trentino Trasporti GTFS | ZIP HTTPS 200 | `GtfsStaticProvider`, validazione contenuto prima di attivazione |
| Open Data Hub Alto Adige | ZIP HTTPS 200, circa 135 MB | Data pack tramite import offline; non scaricare nel client a ogni avvio |
| AMT Genova–Casella | ZIP HTTP 200 | Import offline/gateway HTTPS; cleartext vietato nell’APK |
| Cotral Ferro | ZIP HTTP 200 | Import offline/gateway HTTPS; cleartext vietato nell’APK |
| ÖBB GTFS | ZIP HTTPS 200, circa 174 MB, CC BY 4.0 | Data pack tramite import offline; filtrare fermate italiane |
| Open Data Svizzera | Catalogo HTTPS 200; file e servizi con termini specifici | Statico via data pack e realtime mediante canale tokenizzato |
| Lombardia/Trenord | Metadato Socrata HTTPS 200 | Adapter statico dopo acquisizione del download ufficiale e controllo licenza versione |
| EAV | Pagina open data HTTPS 200 | Adapter statico dopo URL di distribuzione/manifest ufficiale stabile |
| RFI, ViaggiaTreno, Italo, Trenord realtime | Pagine pubbliche raggiungibili o best-effort | Nessun client live nella tranche: solo descriptor, feature flag, fixture e gateway futuro |

## Fixture

Le fixture unit test sono ZIP GTFS sintetici, piccoli e privi di dati personali. Coprono: presenza del set minimo, virgole e virgolette CSV, timezone, stop IDs, limitazione dimensione entry, file mancanti e input corrotto. I feed reali sono usati solo da un controllo di sviluppo read-only e non vengono committati nel repository.

## Regole di sicurezza

L’ispettore applica limiti su numero di entry, dimensione compressa e non compressa, righe per file e lunghezza delle righe. Un feed non conforme restituisce errore tipizzato, non un catalogo parziale presentato come completo.

## Riferimenti

[1]: https://gtfs.org/documentation/schedule/reference/ "GTFS Schedule Reference"
[2]: https://www.arst.sardegna.it/open-data/ "ARST — Open Data"
[3]: https://www.hzpp.hr/en/timetable "HŽ Putnički prijevoz — timetable e GTFS"
[4]: https://ressources.data.sncf.com/pages/accueil/ "SNCF Open Data"
[5]: https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~ "ÖBB GTFS e CC BY 4.0"
[6]: https://opentransportdata.swiss/en/terms-of-use/ "Open Data Platform Mobility Switzerland — condizioni d’uso"
