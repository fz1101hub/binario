# Catalogo canonico delle stazioni curate

## Scopo

Il file utente `stazioni_ferroviarie_curato_v4.sqlite` è l’unica sorgente del catalogo stazioni di Binario dalla versione 0.20.0. L’app non usa più l’asset geografico OpenStreetMap né i `stops.txt` GTFS per popolare l’autocompletamento. Il database viene convertito in un asset JSON offline, privo di dati di circolazione, tramite `tools/build_curated_station_catalog.py`. **Provenienza e licenza da confermare dal titolare** (audit A7-09): il repository non registra da quali fonti derivano nomi, alias e coordinate del database; il manifest `app/src/main/assets/stations/manifest.json` lo dichiara nei campi `provenance` e `licence`, che `tools/verify_embedded_assets.py` segnala come avviso finché non sono compilati. Il credito in app (Impostazioni › Info) descrive il catalogo come database curato, senza attribuzione OpenStreetMap: se la conferma indica dati OSM, vanno aggiunti l'avviso ODbL 1.0 e la licenza del JSON distribuito.

## Evidenza dell’importazione

| Campo | Valore |
|---|---|
| Database origine | `stazioni_ferroviarie_curato_v4` |
| Hash SHA-256 origine | `2db59242ec45816a73a37c4748dc400dafaba762b6df3105673f63a44c882efe` |
| Stazioni | 5.239 |
| Codici ferroviari | 3.430 |
| Location ID Trenitalia univoci | 3.047 |
| Location ID Trenitalia ambigui | 40, esclusi automaticamente dal mapping operativo |
| Asset app | `app/src/main/assets/stations/curated_it_rail_stations.json` |
| Hash SHA-256 asset | `7a309d97494f0ff85c1fe5bd57059848f6c87c900139cd9a3ce4d65bf509ed7f` |

## Correzioni dell'asset (novembre 2026, audit2 tools-data-3)

L'asset diverge dal database `v4` in dodici voci, corrette solo con valori del pack Trenitalia incluso (nome e posizione della fermata con lo stesso codice); dove il pack non dà il valore giusto, il valore errato è stato tolto: S02049 è «Ala» con location ID 830002049 (aveva nome e ID di Roma Val d'Ala, che torna a S08014); S00213 è «S. Ambrogio» (830000213) e l'ID 830002055 passa a Domegliara (S02055); S12137 Palermo Fiera ha 830012137 (aveva quello di Fiera di Roma); S12075 Palermo Libertà (era in Finlandia), S00822 S. Vittoria e S09022 S. Marco d'Alunzio-Torrenova hanno la posizione della loro fermata, S12075 anche l'ID 830012075; Ansedonia (NC000010) e Brianco (NC000062) non hanno più gli ID di Guidonia e Agognate, Palmi Tonnara (NC001070) non ha più la posizione in mare; NC001521 è «Ercolano - Miglio D'Oro». `tools/verify_embedded_assets.py` controlla che ogni posizione cada nell'area servita (35–55 N, 10 O–25 E: Italia e le città europee raggiunte dai treni), che ogni location ID compaia una volta e disti al massimo 10 km dalla fermata Trenitalia con lo stesso `stop_code`, e che i conteggi del manifest corrispondano al file. Il codice ferroviario `S…` non è confrontato: i suoi numeri coincidono con location ID di altre stazioni (24 casi) e Trenord usa codici `S…` propri (tools-data-6).

## Regole di utilizzo

Il catalogo fornisce identificativo, nome, alias, coordinate, codice ferroviario e — se non ambiguo — `trenitalia_location_id`. Ricerca, tratte, preferiti, widget e vicinanza usano lo stesso oggetto `Station`; non esistono più liste concorrenti.

> Il codice non è un dato di ritardo. È una chiave di riconciliazione: un ritardo, un binario o una soppressione sono mostrati esclusivamente quando un provider operativo restituisce uno snapshot qualificato.

Il form pubblico attualmente usato da ViaggiaTreno riceve il nome stazione, non il `trenitalia_location_id`: una verifica controllata ha confermato risposta con tabellone per il nome di Roma Termini e assenza di tabellone per il relativo location ID. Perciò l’app non invia codici a un endpoint che non li accetta; utilizza il codice univoco per identità cache e il nome/alias curato per il matching prudente del tabellone. Le collisioni vengono lasciate senza mapping automatico.

Il database sorgente è stato fornito dall’utente e resta il riferimento di curation. Ogni sostituzione del database richiede nuova verifica di integrità SQLite, rigenerazione asset, aggiornamento manifest, test, verifica dell’asset nell’APK con `tools/verify_station_catalog_in_apk.py` e commit.
