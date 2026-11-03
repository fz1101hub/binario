# Percorsi alternativi di import — fonti limitate

Le fonti seguenti sono state riesaminate il 14 agosto 2026 per massimizzare l’import senza token né endpoint non pubblici.

| Fonte | Percorso ufficiale alternativo | Dato rilevante | Azione prevista |
|---|---|---|---|
| ARST Sardegna | https://www.arst.sardegna.it/open-data/ ; https://www.sardegnamobilita.it/open-data/trasporti | Feed GTFS pubblicato da ARST; dimensione non esposta nel risultato testuale | Import offline con timeout esteso; catalogo regionale come manifest |
| Trentino Trasporti | https://www.trentinotrasporti.it/opendata/google_transit_urbano_tte.zip ; https://dati.trentino.it/api/3/action/package_show?id=trasporti-pubblici-del-trentino-formato-gtfs | Feed urbano circa 0,8 MB; extraurbano circa 7,4 MB | Importare prima il feed urbano e recuperare extraurbano dal manifest CKAN |
| Alto Adige STA | https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables ; https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw | GTFS circa 135,7 MB; catalogo pubblico CC0 | Registrare catalogo e importare offline con una singola richiesta atomica |
| ÖBB | https://static.oebb.at/open-data/soll-fahrplan-gtfs/GTFS_OP_2025_obb.zip ; https://data.oebb.at/de/datensaetze~soll-fahrplan-gtfs~ | Supporto HTTP Range verificato sul file 2025 | Scaricare release 2025 più gestibile o riprendere con Range; poi aggiornare al 2026 |
| Lombardia/Trenord | https://www.dati.lombardia.it/download/3z4k-mxz9/application/zip | GTFS `trenord_gtfs.zip` circa 1,66 MB | Import diretto, dopo conferma SHA e licenza dal manifest Socrata |
| EAV | https://www.eavsrl.it/open-data/ ; https://www.eavsrl.it/download/elenco-delle-stazioni-ferroviarie-sulle-linee-vesuviane-e-flegree/ | Indice ufficiale e dataset ferroviari pubblicati | Estrarre risorse statiche dall’indice e includere i dati ferroviari disponibili |
| Toscana/TFT | https://dati.toscana.it/dataset/8bb8f8fe-fe7d-41d0-90dc-49f2456180d1/resource/59aeacbc-99ee-410b-ac1e-622b5574a666/download/tft.gtfs | GTFS circa 19 KB, licenza CC BY | Import diretto immediato |
| Svizzera OTD | https://data.opentransportdata.swiss/en/dataset/timetable-2026-gtfs2020 ; https://opendata.swiss/en/dataset/fahrplan-2026-gtfs2020 | GTFS circa 200,2 MB | Registrare manifest e mantenere import offline; evitare download APK diretto |

Questi percorsi sono dati esterni da verificare nuovamente a ogni aggiornamento del data pack. Non vengono utilizzati token di app né meccanismi per aggirare limiti del titolare.
