# Catalogo canonico delle stazioni curate

## Scopo

Il file utente `stazioni_ferroviarie_curato_v4.sqlite` è l’unica sorgente del catalogo stazioni di Binario dalla versione 0.20.0. L’app non usa più l’asset geografico OpenStreetMap né i `stops.txt` GTFS per popolare l’autocompletamento. Il database viene convertito in un asset JSON offline, privo di dati di circolazione, tramite `tools/build_curated_station_catalog.py`. **Provenienza e licenza da confermare dal titolare** (audit A7-09): il repository non registra da quali fonti derivano nomi, alias e coordinate del database; il manifest `app/src/main/assets/stations/manifest.json` lo dichiara nei campi `provenance` e `licence`, che `tools/verify_embedded_assets.py` segnala come avviso finché non sono compilati. Il credito in app (Impostazioni › Info) descrive il catalogo come database curato, senza attribuzione OpenStreetMap: se la conferma indica dati OSM, vanno aggiunti l'avviso ODbL 1.0 e la licenza del JSON distribuito.

## Evidenza dell’importazione

| Campo | Valore |
|---|---|
| Database origine | `stazioni_ferroviarie_curato_v4` |
| Hash SHA-256 origine | `2db59242ec45816a73a37c4748dc400dafaba762b6df3105673f63a44c882efe` |
| Stazioni | 5.249 (5.239 del database `v4` e 10 della ferrovia del Renon) |
| Codici ferroviari | 3.430 |
| Location ID Trenitalia univoci | 3.047 |
| Location ID Trenitalia ambigui | 40, esclusi automaticamente dal mapping operativo |
| Asset app | `app/src/main/assets/stations/curated_it_rail_stations.json` |
| Hash SHA-256 asset | `0a39d29757ce48f4b56a22961dbfb6a9c6dbeff23b50f74729b76630e0102a23` |

## Correzioni dell'asset (novembre 2026, audit2 tools-data-3)

L'asset diverge dal database `v4` in dodici voci, corrette solo con valori del pack Trenitalia incluso (nome e posizione della fermata con lo stesso codice); dove il pack non dà il valore giusto, il valore errato è stato tolto: S02049 è «Ala» con location ID 830002049 (aveva nome e ID di Roma Val d'Ala, che torna a S08014); S00213 è «S. Ambrogio» (830000213) e l'ID 830002055 passa a Domegliara (S02055); S12137 Palermo Fiera ha 830012137 (aveva quello di Fiera di Roma); S12075 Palermo Libertà (era in Finlandia), S00822 S. Vittoria e S09022 S. Marco d'Alunzio-Torrenova hanno la posizione della loro fermata, S12075 anche l'ID 830012075; Ansedonia (NC000010) e Brianco (NC000062) non hanno più gli ID di Guidonia e Agognate, Palmi Tonnara (NC001070) non ha più la posizione in mare; NC001521 è «Ercolano - Miglio D'Oro». `tools/verify_embedded_assets.py` controlla che ogni posizione cada nell'area servita (35–55 N, 10 O–25 E: Italia e le città europee raggiunte dai treni), che ogni location ID compaia una volta e disti al massimo 10 km dalla fermata Trenitalia con lo stesso `stop_code`, e che i conteggi del manifest corrispondano al file. Il codice ferroviario `S…` non è confrontato: i suoi numeri coincidono con location ID di altre stazioni (24 casi) e Trenord usa codici `S…` propri (tools-data-6).

## Voci con nome o posizione di un'altra stazione (novembre 2026)

Due voci avevano il nome o la posizione di un'altra stazione; il codice della voce e il pack incluso dicono quale è giusto, e l'ID resta lo stesso (preferiti e viaggi salvati usano l'ID):

- **S00239 è «Tronzano»**, non «Pino-Tronzano»: il suo codice S00239 è la fermata `830000239` «TRONZANO» del pack Trenitalia, a 1 m dalla posizione della voce, che aveva già gli alias «Tronzano» e «TRONZANO». Il location ID 830001110 non è una fermata del pack (le sue cifre sono il codice di S01110 Pino-Tronzano, sulla linea di Luino, a 95 km): la voce prende `830000239`, e l'alias «Tronzano», ora uguale al nome, è tolto. Il tabellone di ViaggiaTreno, che riceve il nome, chiede ora Tronzano.
- **S09526 «Borgo Lombardo»** stava a 343 m dalla stazione di Castiglione del Lago (Umbria), dove non c'è un'altra stazione; il nome e il location ID 830001830 (le cifre di S01830) sono di Borgolombardo, la fermata Trenord `S01830`. La voce prende la posizione di quella fermata (45.404507, 9.270465). Il codice ferroviario S09526 non compare in nessun pack: resta da confermare dal titolare.

Restano doppioni, documentati e non uniti perché nessun lettore (Android, iPhone, Alexa) ha un campo per dire che due voci sono la stessa stazione: S09526 e S01830 (Borgolombardo, nomi scritti in modo diverso); S01110 e S19006 «Pino Tronzano» (S19006 non ha coordinate e il suo codice non compare in nessun pack, quindi nessuna fermata dice dove metterla); S13653 e S19005 «Maccagno» (300 m). Baden S03118 e Ales NC000004 restano senza coordinate: nessun pack ha una fermata con il loro codice (S03118 sarebbe 8503118, Benken SG; 830042306 non è nel pack Trenitalia), quindi la scelta tra Baden AG e Baden bei Wien e tra Ales (Sardegna) e Alès (Francia) spetta al titolare.

## Ferrovia del Renon (novembre 2026, decisione del titolare)

Il database `v4` non aveva le fermate della ferrovia del Renon, quindi l'indice orario dell'iPhone scartava le sue corse e la ricerca non le offriva. Il titolare ha approvato di aggiungerle all'asset dal pack STA incluso (`alto-adige-sta-gtfs`, CC0 1.0): sono le dieci fermate servite dalla linea 160 (`route_type` 2), una voce per stazione (la `parent_station` quando la fermata ne ha una, altrimenti la fermata), con il nome del pack e la sua posizione arrotondata a sei decimali, senza codici ferroviari né location ID, alias vuoti e confidenza «Indicativa» come le altre stazioni senza codici. Gli ID proseguono la serie `NC`:

| ID | Nome | Lat | Lon | Fermata del pack (`stop_id`) |
|---|---|---|---|---|
| NC001810 | L'Assunta | 46.524415 | 11.394129 | `it:22021:598:50:1171` |
| NC001811 | Soprabolzano | 46.528152 | 11.405612 | `Parentit:22021:597` (`it:22021:597:50:1170`) |
| NC001812 | Linzbach | 46.53007 | 11.414968 | `Parentit:22021:2526` (`it:22021:2526:50:1648`) |
| NC001813 | Rinner | 46.528917 | 11.420094 | `it:22021:599:50:1649` |
| NC001814 | Costalovara | 46.525827 | 11.424494 | `Parentit:22021:595` (`it:22021:595:50:1172`) |
| NC001815 | Stella | 46.52892 | 11.435246 | `it:22021:600:50:1183` |
| NC001816 | Colle Renon | 46.532597 | 11.439205 | `it:22021:601:50:1181` |
| NC001817 | Ebenhof | 46.536368 | 11.443835 | `it:22021:602:50:1650` |
| NC001818 | Weidacher | 46.53835 | 11.449232 | `it:22021:603:50:1651` |
| NC001819 | Collalbo | 46.539404 | 11.459257 | `Parentit:22021:2704` (`it:22021:2704:50:1192`) |

Il pack pubblica solo i nomi italiani: i nomi tedeschi (Oberbozen, Klobenstein, Wolfsgruben…) non sono nell'asset, perché nessun file del repository li riporta. Le fermate si legano alle nuove voci per nome su Android e per nome e distanza nell'indice dell'iPhone; nessun'altra fermata di alcun pack cambia stazione (`RenonRailwayStationsTest`, `tests/test_ios_gtfs_schedule.py`). Come le correzioni sopra, queste voci sono solo nell'asset: una rigenerazione dal database `v4` con `tools/build_curated_station_catalog.py` le perderebbe.

## Regole di utilizzo

Il catalogo fornisce identificativo, nome, alias, coordinate, codice ferroviario e — se non ambiguo — `trenitalia_location_id`. Ricerca, tratte, preferiti, widget e vicinanza usano lo stesso oggetto `Station`; non esistono più liste concorrenti.

> Il codice non è un dato di ritardo. È una chiave di riconciliazione: un ritardo, un binario o una soppressione sono mostrati esclusivamente quando un provider operativo restituisce uno snapshot qualificato.

Il form pubblico attualmente usato da ViaggiaTreno riceve il nome stazione, non il `trenitalia_location_id`: una verifica controllata ha confermato risposta con tabellone per il nome di Roma Termini e assenza di tabellone per il relativo location ID. Perciò l’app non invia codici a un endpoint che non li accetta; utilizza il codice univoco per identità cache e il nome/alias curato per il matching prudente del tabellone. Le collisioni vengono lasciate senza mapping automatico.

Il database sorgente è stato fornito dall’utente e resta il riferimento di curation. Ogni sostituzione del database richiede nuova verifica di integrità SQLite, rigenerazione asset, aggiornamento manifest, test, verifica dell’asset nell’APK con `tools/verify_station_catalog_in_apk.py` e commit.
