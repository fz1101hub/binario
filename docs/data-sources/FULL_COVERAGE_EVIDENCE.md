# Copertura completa delle fonti ferroviarie utilizzabili

**Data di verifica:** 15 agosto 2026  
**Baseline:** `feature/data-foundations`  
**Criterio:** una fonte è considerata coperta solo quando l’app dispone di un adapter operativo, di un data pack GTFS verificato oppure di un fallback ufficiale che ne copre le corse sulle stazioni italiane. Le fonti senza feed pubblico strutturato non vengono rappresentate come realtime nativo.

## Copertura operativa

| Ambito | Fonte applicata | Modalità nell’app | Stato |
|---|---|---|---|
| Trenitalia | ViaggiaTreno | Tabellone live; prima fonte per fermate, ritardi e binari | Best-effort attivo |
| Rete nazionale multivettore | RFI Monitor Arrivi/Partenze | Tabellone live e fallback | Best-effort attivo |
| Avvisi italiani | RFI Infomobilità RSS | Adapter XML con cache e provenienza | Attivo |
| Italo | Italo in Viaggio | Tabellone pubblico mappato su sette stazioni | Best-effort attivo |
| Trenord e reti Ferrovienord | Regione Lombardia / Trenord GTFS | Data pack statico ferroviario | Offline attivo |
| Alto Adige e tratte transfrontaliere | Open Data Hub / STA GTFS + GTFS-RT | Data pack statico, avvisi e posizioni realtime | Attivo |
| Svizzera e TILO/SBB/BLS/RhB | Open Data Platform Mobility Switzerland GTFS | Data pack ferroviario filtrato | Offline attivo; GTFS-RT richiede token gateway |
| Austria | ÖBB GTFS 2026 | Data pack ferroviario filtrato | Offline attivo |
| Francia | SNCF GTFS | Data pack statico ferroviario | Offline attivo |
| Croazia e collegamenti transfrontalieri | HŽPP GTFS | Data pack statico ferroviario | Offline attivo |
| Reti regionali e isolate italiane | AMT, ARST, Cotral/ASTRAL, EAV, TFT, Trentino | Data pack GTFS o import offline verificato | Offline attivo |

## Evidenza dei data pack embedded

L’APK include dodici archivi GTFS con manifest, impronte SHA-256 e verifica dello schema. Il controllo del 15 agosto ha prodotto **12/12 impronte valide** e **12/12 set di tabelle GTFS obbligatorie presenti**. I feed contengono, tra l’altro, 594 route ferroviarie SNCF, 407 svizzere, 259 ÖBB, 161 STA Alto Adige, 136 HŽPP e 60 Lombardia/Trenord.

> Il feed TFT mantiene due linee ferroviarie note ma le codifica con `route_type=3`; l’app conserva il data pack e lo identifica come eccezione semantica. Non viene usato il valore GTFS errato per presentare la linea come servizio autobus.

## Riconciliazione e qualità

Il repository privilegia le snapshot realtime in base a fonte, freschezza e completezza. Italo, ViaggiaTreno e RFI vengono valutati prima; quando non producono una corsa utilizzabile, il fallback GTFS embedded restituisce un **orario programmato** con confidenza `STATIC_ONLY`, senza inventare ritardi, binari o soppressioni. Le divergenze fra fonti vengono conservate come warning, non nascoste.

## Limiti residui, esplicitamente classificati

| Fonte | Perché non è attiva come realtime nativo | Copertura sostitutiva |
|---|---|---|
| Trenord realtime | Nessun feed realtime pubblico strutturato verificato; canale E015 da valutare con accesso dedicato | GTFS Lombardia/Trenord, RFI e ViaggiaTreno nelle stazioni interoperabili |
| Svizzera GTFS-RT | Token gratuito/access code obbligatorio; non può essere incluso nell’APK | GTFS svizzero offline, RFI sulle stazioni italiane |
| SNCF GTFS-RT | Service Alerts e Trip Updates sono disponibili al livello dati; l’associazione di un Trip Update alla UI richiede ancora matching verificato | GTFS SNCF offline e RFI sulle stazioni italiane |
| ÖBB realtime | Non risulta un feed realtime pubblico equivalente al GTFS statico | GTFS ÖBB offline e RFI sul tratto italiano |
| Operatori locali senza feed | Il titolare pubblica solo HTML/PDF o richiede accordo | Orari ufficiali statici quando importabili; nessun realtime simulato |

Queste non sono fonti “mancanti” dal catalogo: sono canali che non possono essere attivati nel client senza token, gateway o accordo. L’app dichiara il limite al posto di presentare dati non verificabili come realtime.

## Riferimenti

[1]: https://www.dati.lombardia.it/Mobilit-e-trasporti/Orario-Ferroviario-Regionale-Gtfs/3z4k-mxz9 "Regione Lombardia — GTFS ferroviario"
[2]: https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables "Open Data Hub Alto Adige — STA GTFS"
[3]: https://data.opentransportdata.swiss/dataset/timetable-2026-gtfs2020 "Open Data Platform Mobility Switzerland — GTFS"
[4]: https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~ "ÖBB Open Data — GTFS Fahrplan"
[5]: https://ressources.data.sncf.com/pages/accueil/ "SNCF Open Data"
[6]: https://www.rfi.it/it/news-e-media/feed---rss.html "RFI — Feed RSS"
[7]: https://italoinviaggio.italotreno.com/ "Italo in Viaggio"
