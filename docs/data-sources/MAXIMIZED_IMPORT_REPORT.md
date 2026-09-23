# Import massimo delle fonti ferroviarie

**Data:** 14 agosto 2026  
**Obiettivo:** includere nell’APK il maggior numero possibile di feed statici ufficiali e verificati, senza token di app, endpoint interni o download automatici all’avvio.

## Data pack ora inclusi nell’APK

| Fonte | Dimensione | Evidenza ferroviaria | Trattamento |
|---|---:|---|---|
| AMT Genova–Casella | 9,4 MB | 1 route GTFS ferroviaria | Offline, origine HTTP |
| ARST Sardegna | 20,0 MB | 4 route `route_type=2` | Filtrare feed multimodale |
| Cotral/ASTRAL Ferro | 0,09 MB | 3 route ferroviarie | Offline, origine HTTP |
| EAV Campania | 3,2 MB | 16 route `route_type=2` | Filtrare feed multimodale |
| HŽPP | 2,8 MB | 136 route `route_type=2` | Filtrare eventuale tratto italiano |
| Lombardia/Trenord | 1,7 MB | 60 route `route_type=2` | Data pack ferroviario regionale |
| SNCF | 4,8 MB | 594 route `route_type=2` | Filtrare servizi con tratto italiano |
| Toscana/TFT | 0,02 MB | Arezzo–Stia e Arezzo–Sinalunga per nome tratta | Revisione normalizzazione: il feed usa `route_type=3` |
| Trentino Trasporti extraurbano | 7,4 MB | R35 Ferrovia Trento–Malè–Mezzana | Filtrare feed multimodale |

I nove archivi occupano circa **48 MB** non compressi nell’albero di asset e vengono caricati solo in modo lazy da `EmbeddedGtfsPackLoader`. Ogni asset è tracciato nel manifest con URL di origine, attribuzione e SHA-256.

## Fonti mantenute esterne o da importare offline

| Fonte | Motivo | Strategia mantenuta |
|---|---|---|
| Alto Adige/STA | GTFS circa 135,7 MB | Catalogo e import offline separato; non gonfiare l’APK |
| Svizzera OTD | GTFS circa 200,2 MB | Catalogo/manifest e import offline; realtime richiede accesso dedicato |
| ÖBB | Download 2025 non completato per timeout SSL | Ripresa tramite Range o import offline controllato |
| Italo, Trenord, RFI Infomobilità | Fonti dinamiche, non feed GTFS statici | Parser/gateway separato, senza endpoint interni |
| Operatori minori senza feed strutturato | Orari o pagine pubbliche ma non data pack | Catalogo e richiesta di feed al titolare |

## Regola di visualizzazione ferroviaria

Per i feed multimodali, l’app deve esporre soltanto route ferroviarie (`route_type=2`) o quelle riconosciute mediante una regola documentata di normalizzazione. Il caso TFT è esplicitamente segnato perché le due tratte ferroviarie sono pubblicate con `route_type=3`; non devono essere rese visibili come ferrovia fino alla normalizzazione controllata.

## Verifica

Gli archivi sono stati verificati come ZIP GTFS con `agency.txt`, `stops.txt`, `routes.txt`, `trips.txt` e `stop_times.txt`. Il controllo di integrità usa `app/src/main/assets/gtfs/SHA256SUMS` e il manifest. Nessun asset è stato eseguito.
