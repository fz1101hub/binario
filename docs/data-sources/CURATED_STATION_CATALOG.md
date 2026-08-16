# Catalogo canonico delle stazioni curate

## Scopo

Il file utente `stazioni_ferroviarie_curato_v4.sqlite` è l’unica sorgente del catalogo stazioni di Binario dalla versione 0.20.0. L’app non usa più l’asset geografico OpenStreetMap né i `stops.txt` GTFS per popolare l’autocompletamento. Il database viene convertito in un asset gzip offline, privo di dati di circolazione, tramite `tools/build_curated_station_catalog.py`.

## Evidenza dell’importazione

| Campo | Valore |
|---|---|
| Database origine | `stazioni_ferroviarie_curato_v4` |
| Hash SHA-256 origine | `2db59242ec45816a73a37c4748dc400dafaba762b6df3105673f63a44c882efe` |
| Stazioni | 5.239 |
| Codici ferroviari | 3.430 |
| Location ID Trenitalia univoci | 3.086 |
| Location ID Trenitalia ambigui | 40, esclusi automaticamente dal mapping operativo |
| Asset app | `app/src/main/assets/stations/curated_it_rail_stations.json.gz` |
| Hash SHA-256 asset | `1aa20f47ac0b8bb5ae9307bfbf9e4ad067ae77151c0b30814cc87f53f8775293` |

## Regole di utilizzo

Il catalogo fornisce identificativo, nome, alias, coordinate, codice ferroviario e — se non ambiguo — `trenitalia_location_id`. Ricerca, tratte, preferiti, widget e vicinanza usano lo stesso oggetto `Station`; non esistono più liste concorrenti.

> Il codice non è un dato di ritardo. È una chiave di riconciliazione: un ritardo, un binario o una soppressione sono mostrati esclusivamente quando un provider operativo restituisce uno snapshot qualificato.

Il form pubblico attualmente usato da ViaggiaTreno riceve il nome stazione, non il `trenitalia_location_id`: una verifica controllata ha confermato risposta con tabellone per il nome di Roma Termini e assenza di tabellone per il relativo location ID. Perciò l’app non invia codici a un endpoint che non li accetta; utilizza il codice univoco per identità cache e il nome/alias curato per il matching prudente del tabellone. Le collisioni vengono lasciate senza mapping automatico.

Il database sorgente è stato fornito dall’utente e resta il riferimento di curation. Ogni sostituzione del database richiede nuova verifica di integrità SQLite, rigenerazione asset, aggiornamento manifest, test e commit.
