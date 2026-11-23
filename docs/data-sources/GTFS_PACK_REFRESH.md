# Refresh dei pack GTFS embedded

I pack GTFS sono **artefatti di release offline**: l'app non li scarica dalle fonti e non li aggiorna dalla rete. Fa eccezione solo, per i paesi i cui orari completi non sono inclusi nell'app, l'orario completo che il progetto pubblica come release di GitHub e che prende il posto del pack ridotto alla zona di confine: scaricato solo dopo il tocco dell'utente su «Scarica» e accettato solo con l'impronta SHA-256 fissata nella versione dell'app (licenza e redistribuzione in [SOURCE_POLICY.md](SOURCE_POLICY.md), dati visibili a GitHub in [PRIVACY.md](../../PRIVACY.md)). Il comando `tools/build_gtfs_packs.py` acquisisce solo le URL registrate in `manifest.json`, applica un limite di byte anche alle risposte chunked, filtra ogni feed, e pubblica ZIP, manifest e checksum con un singolo replace atomico della directory di output. Nessun download incompleto, feed inventato o precedente asset viene presentato come aggiornamento riuscito.

## Aggiornamento settimanale

Un solo dispatch aggiorna tutti i pack scaricabili liberamente (tutti tranne ÖBB), sul ramo su cui poi si applica il risultato:

```sh
gh api -X POST repos/fz1101hub/binario/actions/workflows/gtfs-assets-ci.yml/dispatches \
  -f ref=<ramo> -f 'inputs[transit_packs]=build=all refresh-core=all'
gh run list --workflow gtfs-assets-ci.yml --branch <ramo> --event workflow_dispatch --limit 1   # id della run
```

Il workflow `GTFS Asset Integrity` avvia due job sui runner GitHub (il container di sviluppo non raggiunge gli editori): `transit-packs` ricostruisce le ferrovie regionali e le reti urbane di `gtfs-transit/` e `gtfs-personal/`, `core-packs` i pack di `gtfs/` (`refresh-core=id1,id2` per alcuni soltanto; un pack candidato solo se nominato, vedi «Pack nuovi»). I file cambiati sono stampati nel log; il riepilogo della run elenca ogni pack con `lastServiceDate`, giorni residui e soglia (`minValidDays`, 30 se non dichiarata), con `SOTTO SOGLIA` o `SCADUTO` dove serve un intervento e ÖBB come `manuale`. Un job `core-packs` rosso con le altre fonti aggiornate significa che una fonte non ha risposto o non ha superato i controlli: il suo pack precedente resta valido, la run va ripetuta più avanti.

Poi, in locale, sullo stesso ramo:

```sh
python3 tools/apply_ci_gtfs_refresh.py --run <run_id>     # --dry-run per vedere solo la tabella
```

L'helper verifica prima che la run sia un `workflow_dispatch` di `gtfs-assets-ci.yml` sullo stesso ramo locale, con `head_sha` uguale a `HEAD` o a un suo antenato, e che ogni step fino a quello di stampa sia riuscito; legge solo l'unico blocco `BINARIO_BEGIN <nonce>` … `BINARIO_DONE <nonce> <file>` stampato da quello step (nella sua finestra oraria) e rifiuta il log se trova un secondo blocco o righe `BINARIO_*` fuori dal blocco: il testo dei feed stampato da step precedenti non può aggiungere o sostituire file (audit A7-02). Con `--log` (log salvati) restano solo i controlli sul blocco. Poi ricostruisce i file verificandone dimensione e SHA-256, li copia in `app/src/main/assets/gtfs/`, `gtfs-transit/` e `app/src/personal/assets/gtfs-personal/` lasciando byte per byte i pack con orario invariato, aggiorna i pin Trenitalia/Italo (manifest, `ios/Resources/binario_gtfs_source_policy_v1.json`, `RailSourceCatalog.kt`) e `lastServiceDate` in `EmbeddedGtfsPacks.kt` e `TransitGtfsPacks.kt`, esegue i due `--check` per directory, `verify_embedded_assets.py` e, se il manifest principale è cambiato, rigenera con `build_foreign_packs.py` l'indice iOS, gli indici per paese, i pin dei pack esteri e il registro e li verifica (`verify_ios_gtfs_schedule.py`, `verify_ios_gtfs_rebuild.py`; vedi [Paesi confinanti](#paesi-confinanti-zona-di-confine-e-pack-completi)). Stampa una tabella: pack, `lastServiceDate` vecchia → nuova, viaggi, cambiato sì/no, per un pack di confine MB del pack completo, MB del sottoinsieme e corse tenute / corse del pack completo, note ed errori. Non esegue commit.

Cosa controllare prima del commit:

1. **Tabella.** Le date devono andare avanti; un pack che perderebbe più di metà delle corse o dei passaggi in fermata, che terminerebbe prima di quello pubblicato, o la cui `lastServiceDate` cade più di un mese dopo l'ultimo giorno con almeno un quarto delle corse del giorno di punta (un feed che tiene viva una corsa al giorno per anni) è `RIFIUTATO` e resta quello precedente: guardare la fonte e, solo se il cambiamento è reale (fine di un servizio, nuovo orario ridotto), rilanciare con `--accept <sourceId>`. Le fonti rivedute in `THIN_TAIL_CLIP_SOURCES` (`build_gtfs_packs.py`; oggi solo `eav-gtfs`, il cui feed di novembre 2026 ha l'orario completo fino al 31 dicembre 2027 e poi al più una corsa al giorno fino al 2030) arrivano invece già tagliate dalla build a quell'ultimo giorno completo (vedi sotto, `serviceClippedAfter`) e non sono rifiutate; la regola è una sola (`build_gtfs_packs.thin_tail`), usata da build e helper. `NON AGGIORNATO dal job` indica una fonte non raggiungibile o rifiutata dalla pipeline; `--skip <sourceId>` esclude un pack dall'applicazione.
2. **Verifiche** tutte `ok`. Un `--check --min-valid-days 30` in errore vuol dire che un pack è sotto soglia: blocca le PR verso `stabilization/**`, `release-sign` e l'archivio iOS di una run avviata a mano, non la CI ordinaria (che lo riporta come avviso).
3. **Test Android**: `EmbeddedGtfsAssetIntegrityTest` (date Kotlin), `EmbeddedGtfsJourneyRealPacksTest` ed `EmbeddedGtfsTransitPacksTest` (corse attese): aggiornare le attese solo se l'orario è davvero cambiato, mai per far passare un pack sospetto.
4. **Commit** `data(gtfs): refresh timetables AAAA-MM-GG`, poi Android CI con le schermate di design (`-f 'inputs[capture_design_screens]=true'`) e Apple Watch CI se l'indice iOS è cambiato.

**Statistiche di puntualità.** Sullo stesso ramo, un secondo dispatch stampa le statistiche aggregate degli ultimi 30 giorni e l'helper le applica con gli stessi controlli su run, step e blocco ([PUNCTUALITY_STATS.md](PUNCTUALITY_STATS.md#applicazione-con-laggiornamento-settimanale)):

```sh
gh api -X POST repos/fz1101hub/binario/actions/workflows/punctuality.yml/dispatches \
  -f ref=<ramo> -f 'inputs[print_stats]=true'
python3 tools/apply_ci_punctuality.py --run <run_id>     # id da: gh run list --workflow punctuality.yml --branch <ramo>
```

`app/src/main/assets/punctuality/stats_v1.json.gz` entra nello stesso commit degli orari; un file senza treni o con una finestra più vecchia di quella inclusa è rifiutato.

**Trenitalia e Italo (revisione del pin).** Il job risolve l'ultimo commit di [deryclem/trenitalia-gtfs](https://github.com/deryclem/trenitalia-gtfs) e [deryclem/italo-gtfs](https://github.com/deryclem/italo-gtfs) che ha toccato l'archivio, lo scarica e, se differisce da quello fissato, ricostruisce il pack con il pin proposto (`commit`, `archiveSha256`, `sourceUrl`). Il log e l'helper mostrano il blocco proposto con `feed_info.txt` e `attributions.txt` del nuovo archivio: controllare le date di validità (`feed_start_date`, `feed_end_date`) e che l'attribuzione sia ancora Trenitalia o Italo via NAP CCISS con la conversione CC BY 4.0 di Clément Desouche. Se cambiano licenza o attribuzione non adottare il pin (`--skip trenitalia-gtfs` o `--skip italo-gtfs`) finché `SOURCE_POLICY.md` e il campo `attribution` non sono rivisti. L'adozione è sempre un commit esplicito.

**ÖBB (manuale).** Il portale ÖBB richiede che il titolare accetti di persona le condizioni d'uso: nessun job lo scarica. Scaricare lo ZIP dalla pagina dei dati aperti ÖBB (`OEBB_GTFS_ACCESS.md`), poi:

```sh
python3 tools/build_gtfs_packs.py --only oebb-gtfs \
  --source-archive oebb-gtfs=/percorso/GTFS_Fahrplan_2027.zip --generated-at "$(date --utc +%F)"
```

Il pack completo va in `data/gtfs-foreign/android/oebb-gtfs.zip`, il sottoinsieme di confine negli asset; poi `python3 -I tools/build_foreign_packs.py` (indici iOS, pin, registro). Se il file ha un nuovo nome (un archivio per anno orario), aggiornare prima `sourceUrl` di `oebb-gtfs` nel manifest, nella policy iOS e in `RailSourceCatalog.kt`; poi la data in `EmbeddedGtfsPacks.kt` e i controlli come sopra.

**Cambio orario del 13 dicembre 2026.** STA, Svizzera, ÖBB, Trenord, TFT, Italo e Mi.Co.Tra. terminano il 12 dicembre 2026: dal 13 novembre 2026 il gate a 30 giorni li blocca (ARST, EAV e gran parte delle reti urbane dal 2 dicembre); il controllo settimanale li segnala come avviso già 45 giorni prima. Ripetere il dispatch ogni settimana finché gli editori pubblicano l'orario 2027 e la tabella li mostra aggiornati. Il pack svizzero è registrato sul permalink dell'orario 2026 (`https://data.opentransportdata.swiss/de/dataset/timetable-2026-gtfs2020/permalink`), che rimanda sempre all'ultima versione pubblicata di quell'anno orario (il log e il riepilogo del job riportano il file datato a cui ha rimandato), con il campo rivisto `nextTimetable` (`from`, `sourceUrl`, `landingPage`): dal giorno `from`, il 13 dicembre 2026, primo giorno dell'orario 2027, il job scarica invece il permalink `timetable-2027-gtfs2020/permalink` e propone la voce con quel `sourceUrl` e senza `nextTimetable`; `apply_ci_gtfs_refresh.py` accetta un `sourceUrl` nuovo senza `upstream` solo se è quello di `nextTimetable` del manifest pubblicato e del report del job, e lo riporta, con la pagina del dataset, nella policy iOS e in `RailSourceCatalog.kt`. Il file 2027 parte dal 13 dicembre: scaricarlo prima toglierebbe all'app gli ultimi giorni dell'orario 2026, ma il pack 2026 non supera il gate dei 30 giorni dal 13 novembre. Per una release prima del cambio orario si può anticipare `from` con un commit rivisto (l'app non avrebbe treni svizzeri fino al 12 dicembre). Il permalink segue i reindirizzamenti solo in HTTPS (`acquire_transit_sources.download`); un permalink che non rimanda a uno ZIP fa fallire la fonte e il pack precedente resta. Per l'orario 2028 aggiungere un nuovo `nextTimetable` con il permalink `timetable-2028-gtfs2020` dopo averlo verificato.

## Cadenza e verifica

Il refresh va eseguito **a ogni cambio orario**, prima della release. La CI ordinaria verifica sempre struttura, filtri, hash e coerenza delle date dichiarate, anche mentre una sorgente esterna è temporaneamente bloccata:

```sh
python3 tools/build_gtfs_packs.py --check --integrity-only
```

Il gate della release richiede invece almeno 30 giorni di copertura residua e non deve mai usare `--integrity-only`:

```sh
python3 tools/build_gtfs_packs.py --check --as-of "$(date --utc +%F)" --min-valid-days 30
```

Entrambi i comandi confrontano il digest di ciascun ZIP con `manifest.json` e `SHA256SUMS` e derivano `lastServiceDate` come ultimo giorno dell'ultimo periodo di orario reale: i giorni di esercizio ricavati da `calendar.txt` (con i flag dei giorni della settimana) e dalle eccezioni di `calendar_dates.txt`, distanti al massimo 7 giorni l'uno dall'altro e per almeno una settimana. Una data isolata mesi dopo o una riga che termina in un giorno in cui il servizio non circola non estendono la copertura. Accanto, `fullServiceUntil` (manifest, verificato da entrambi i comandi) è la fine della copertura piena: l'ultimo giorno, entro `lastServiceDate`, con almeno l'80 % della mediana delle corse dello stesso giorno della settimana nelle prime quattro settimane del pack (audit2 tools-data-2: il pack Trenitalia arriva all'11 dicembre 2027 ma dopo il cambio orario del 13 dicembre 2026 contiene circa il 60 % dei treni). Un festivo con meno corse sposta la fine solo se è l'ultimo giorno. Il secondo blocca inoltre pack scaduti o entro la soglia ed è il gate vincolante per un candidato release. La regola è una sola (`build_gtfs_packs.is_release_fresh`, usata anche da `verify_embedded_assets.py` e dal riepilogo dei job): un pack passa se la data di fine derivata dal calendario, o `fullServiceUntil` se è prima, è almeno `as-of` + 30 giorni (o il suo `minValidDays`, mai più lungo); con il 12 dicembre come ultimo giorno passa fino al 12 novembre compreso. La freschezza è un gate di release, non di correttezza (audit A7-06): il workflow `GTFS Asset Integrity` esegue sempre il controllo strutturale, test e verifiche, e il gate a 30 giorni solo nelle PR verso `stabilization/**`; nelle altre run riporta la validità come avviso. Android CI applica il gate nelle stesse PR e in `release-sign`, Apple Watch CI nello step dell'archivio Release; il lunedì una run pianificata (`freshness-watch`) segnala come avviso ogni pack con meno di 45 giorni residui. La checklist release deve registrare separatamente l'esito del controllo freshness.

## Build da archivi completi già acquisiti

Conservare gli ZIP GTFS completi in una directory privata/revisionata; non usare una risposta parziale o un HTML di errore. Poi compilare in una directory separata e verificare prima di sostituire gli asset di release:

```sh
python3 tools/build_gtfs_packs.py \
  --input-dir /percorso/agli-archivi-completi \
  --manifest app/src/main/assets/gtfs/manifest.json \
  --output-dir /tmp/binario-gtfs \
  --generated-at "$(date --utc +%F)"
python3 tools/build_gtfs_packs.py --check --input-dir /tmp/binario-gtfs \
  --as-of "$(date --utc +%F)" --min-valid-days 30
```

Per la revisione, il tool conserva nel pack filtrato il checksum dell'archivio completo sorgente (`sourceArchiveSha256`) e scrive il criterio, i conteggi e `lastServiceDate` in `binario_filter_manifest.json`. Dal novembre 2026 (audit2) il filtro inoltre: toglie le corse il cui servizio non circola più da `generatedAt` meno due giorni (`tripsRunFrom` nel manifest del filtro; una corsa della sera prima può essere ancora in viaggio dopo mezzanotte); nel pack Italo tiene una corsa per treno e giorno, senza le copie degli itinerari combinati né gli itinerari pubblicati come una corsa (`tripRules: ["itinerary-copies"]`); riscrive il `route_type` delle linee ferroviarie TFT pubblicate come bus (`routeTypeOverrides` nel filtro); espande `frequencies.txt` in una corsa per partenza; per le fonti di `THIN_TAIL_CLIP_SOURCES` (solo EAV), quando dopo l'ultimo giorno con almeno un quarto delle corse del giorno di punta il calendario prosegue per più di un mese con una coda sottile, taglia il servizio a quel giorno: `end_date` di `calendar.txt` limitata, eccezioni successive e corse che non circolano più tolte (`serviceClippedAfter` nel manifest del filtro; `lastServiceDate` e `fullServiceUntil` sono calcolate sul pack tagliato, un feed senza coda resta identico byte per byte). `--check` verifica ciascuna regola sul pack pubblicato; rifiltrare un pack pubblicato con lo stesso `generatedAt` dà gli stessi byte.

## Download diretto consentito

Il tool permette un refresh diretto esclusivamente per sorgenti registrate come `OPEN_DIRECT` e `https://`:

```sh
python3 tools/build_gtfs_packs.py --refresh --source-id toscana-tft-gtfs \
  --max-download-bytes $((200 * 1024 * 1024)) \
  --output-dir /tmp/binario-gtfs --generated-at "$(date --utc +%F)"
```

Le sorgenti `GATEWAY_ONLY` non sono scaricate da `build_gtfs_packs.py --refresh` né dal job `core-packs`. **AMT Genova–Casella** e **Cotral Ferro**, registrate `GATEWAY_ONLY` nei manifest perché pubblicate solo in HTTP, sono invece acquisite dal job `transit-packs` (`tools/transit_packs.json`, `transport: http`): AMT solo se l'archivio HTTP coincide byte per byte con la copia HTTPS del Mobility Database (`corroborateWith`), Cotral, senza copia HTTPS, solo come pack delle build personali (`httpReason`). Storia: al 23 settembre 2026, Transitland conferma un feed AMT aggiornato fino al 6 novembre 2026, ma l'origine pubblicata è solo HTTP e il mirror HTTPS richiede credenziali; l'URL Cotral usa invece un certificato valido per `*.cotralspa.it` ma non per il nome a tre livelli `travel.mob.cotralspa.it`, quindi fallisce la verifica TLS. Servono un gateway HTTPS approvato o un nuovo endpoint ufficiale con certificato valido e un archivio GTFS completo. Il filtro AMT è volutamente stretto: mantiene solo l'identificatore revisionato `FGC-191` (Manin–Casella, `FGC-164` fino al feed di settembre 2026); non usa un matching fuzzy sul nome e fallisce se la route cambia identificatore, imponendo una nuova revisione. Da novembre 2026 entrambi sono di nuovo inclusi (sezione «Ferrovie regionali e reti urbane»).

## Pack principali (`gtfs/`, job `core-packs`)

`tools/refresh_core_gtfs.py` gira solo sul runner (`python3 -I`), per ogni pack selezionato del manifest principale:

- `oebb-gtfs` non è mai scaricato (`manuale` nel report);
- un pack con `upstream` (Trenitalia, Italo) è scaricato dall'URL raw dell'ultimo commit upstream, come descritto sotto;
- ogni altro pack `OPEN_DIRECT` è scaricato dal suo `sourceUrl` HTTPS (dal giorno `from` di un `nextTimetable` rivisto, dal suo `sourceUrl`: vedi «Cambio orario» sopra) con lo stesso downloader della pipeline `transit-packs` (limite di 400 MiB, ripresa con `Range`, nuovi tentativi sui 5xx, reindirizzamenti solo verso URL HTTPS) e deve essere uno ZIP.

Un archivio identico a quello registrato (`sourceArchiveSha256`) non è ricostruito. Ogni archivio nuovo è filtrato da solo con `build_gtfs_packs.py --only <id>` su una copia della directory: se il download o la build falliscono, il pack e la sua voce di manifest restano quelli precedenti e il report registra l'errore; se l'orario filtrato non cambia (`contentSha256` uguale, per esempio quando cambiano solo gli autobus), il pack pubblicato è rimesso byte per byte. La directory risultante supera `--check --integrity-only`; nel log sono stampati solo `manifest.json`, `SHA256SUMS`, gli ZIP cambiati e `core-refresh.json` (esito per pack, conteggi, pin proposti con `feed_info.txt` e `attributions.txt`). Il testo letto dagli archivi è stampato con i comandi del workflow sospesi (`::stop-commands::`). Il job fallisce, dopo aver stampato i pack riusciti, se una fonte non è stata aggiornata.

**Pack nuovi (candidati).** Un pack non ancora incluso, rivisto con fonte, licenza, evidenza e attribuzione, è registrato in `tools/core_pack_candidates.json` (oggi `trenitalia-france-gtfs`). Il job lo scarica e lo costruisce come gli altri solo quando il dispatch lo nomina (`refresh-core=trenitalia-france-gtfs`; mai con `refresh-core=all`): esito `nuovo (candidato)`, stampato con il manifest che lo elenca. `apply_ci_gtfs_refresh.py` accetta un pack nuovo solo se i campi stampati sono quelli del candidato (attribuzione datata a parte, vedi sotto) e allora lo aggiunge: voce del manifest nell'ordine dei `sourceId`, ZIP, voce in `EmbeddedGtfsPacks.kt` (con la data del manifest), voce `androidOnlySources` della policy iOS con il motivo registrato, e lo toglie dal registro; la voce di `RailSourceCatalog.kt` (paesi del pack, quindi la classe in `PlannedPackOrder`) è codice rivisto e deve già esserci. Si aggiunge senza `--add-new`, che vale solo per i candidati di `tools/transit_packs.json` ([Fonti candidate](#fonti-candidate)): un `sourceId` sta in un solo dei due registri, e `--add-new` con un candidato principale è rifiutato. Un candidato è sempre incluso intero (mai un pack estero ridotto alla zona di confine). Un pack con `attributionDated` (Licence Ouverte Etalab: fonte e data dell'ultimo aggiornamento) porta nell'attribuzione, e quindi nei crediti, il giorno in cui la build ha preso un archivio nuovo (`Trenitalia France — dati del AAAA-MM-GG`); un archivio identico o con lo stesso orario filtrato lascia la data precedente.

## Paesi confinanti: zona di confine e pack completi

Parte tecnica; le regole generali dei pack esteri sono in `SOURCE_POLICY.md`.

**Regola della licenza.** I tre pack esteri (`ch-otd-gtfs`, `oebb-gtfs`, `sncf-gtfs`) sono inclusi come *sottoinsieme di confine* solo se il campo `licence` del manifest è confermato (non «Da confermare dal titolare»); dal 22 novembre 2026 tutti e tre (ÖBB CC BY 4.0; Svizzera e Francia con le licenze registrate dal titolare, vedi `SOURCE_POLICY.md`). Un pack senza licenza confermata resta incluso intero come prima: nessun sottoinsieme, nessun pin, nessun file pubblicato. Tool e test gestiscono qualsiasi combinazione.

**Sottoinsieme (regola `frontalieri-corridors-v1`).** Le corse intere che fermano almeno una volta in una *fermata di zona*: una fermata di una stazione italiana (abbinata dal matcher del catalogo a una stazione con `trenitaliaLocationId` che inizia per `83`, oppure con il prefisso dell'Italia nel proprio `stop_id`: `it:` nel pack ÖBB, codice UIC `83` nei pack SBB e SNCF) o di una stazione di uno dei **corridoi transfrontalieri** del pack (`CORRIDORS` in `tools/foreign_pack_rules.py`, elencati con il motivo in [SOURCE_POLICY.md](SOURCE_POLICY.md#corridoi-dei-frontalieri)). I treni che entrano in Italia restano quindi sempre interi. Ogni corridoio ha un estremo italiano e le stazioni dell'intera tratta oltre il confine (tutte le fermate dei suoi treni fino alla stazione più lontana); una stazione è indicata con l'id del catalogo quando il matcher del catalogo vi abbina le fermate del pack, altrimenti con il nome della fermata GTFS e le coordinate lette dal pack completo (entro 1 km, con le sue banchine). Le tabelle collegate sono ridotte alle righe usate.

**Stazioni servite e di zona (pin).** Una stazione del catalogo è *servita* (`servedStationIds`) se una fermata con corse del pack completo vi è abbinata dal matcher del catalogo degli strumenti (quello dell'iPhone) o da quello dell'app Android, riprodotto in `tools/android_stop_matcher.py` (omonimi senza coordinate o lontani, doppioni vicini: Maccagno ha due voci a 300 m). È *di zona* (`zoneStationIds`) se ogni sua fermata servita è *completa*: ogni corsa del pack completo che vi ferma è nel sottoinsieme. Le app non usano un pack ridotto per un estremo servito fuori zona (lo lasciano all'orario completo e ne propongono il download); un estremo di zona o che il pack non serve resta coperto. `EmbeddedGtfsBorderZoneTest` confronta queste liste, fermata per fermata, con il matcher Android vero sui pack completi: se il matcher Android cambia, va aggiornato `android_stop_matcher.py`.

Il `--check` ricava di nuovo ogni sottoinsieme dal pack completo e dal catalogo e richiede lo stesso contenuto, controlla `fullPack` e i due `SHA256SUMS`, che i sottoinsiemi insieme restino entro 1,2 MB e, per i corridoi:
- ogni coppia di un corridoio (estremo italiano – ciascuna sua stazione) ha nel sottoinsieme tutte le corse del pack completo fra le due;
- nessuna fermata servita fra l'estremo italiano e una stazione del corridoio resta fuori zona (una fermata nuova o rinominata: aggiungerla al corridoio);
- le coppie obbligatorie del pack (Brennero–Innsbruck Hbf; Domodossola–Brig e Como S. Giovanni–Lugano per la Svizzera, Ventimiglia–Nice-Ville per la Francia, per id di catalogo) hanno almeno una corsa.

Una stazione di corridoio senza fermate nel pack completo (linea chiusa per lavori, fermata rinominata) e una coppia senza corse dirette non fanno fallire il controllo: il generatore le stampa come «Avviso corridoi».

| File | Contenuto | Nell'app |
|---|---|---|
| `data/gtfs-foreign/android/<id>.zip`, `SHA256SUMS` | pack completi filtrati come prima | no |
| `data/gtfs-foreign/ios/<id>.sqlite.zlib`, `<id>.metadata.json` | indice iPhone per paese (stesso schema di `binario_gtfs_schedule_v1`) | no |
| `data/gtfs-foreign/published.json` | registro append-only di ogni file mai fissato (tag, asset, SHA-256, dimensione, `firstPinnedOn`) | no |
| `app/src/main/assets/gtfs/<id>.zip` | sottoinsieme di confine; nel manifest `fullPack` e `filter.borderZone.rule` | sì |
| `app/src/main/assets/gtfs/foreign_packs.json`, `ios/Resources/binario_gtfs_foreign_packs_v1.json` | pin (schema 1, stessi byte): tag `gtfs-full-<id>-<sha12>` / `gtfs-ios-<id>-<sha12>`, URL `https://github.com/fz1101hub/binario/releases/download/<tag>/<asset>`, stazioni servite e di zona | sì |

**Un solo comando** rigenera tutto ciò che deriva dai pack esteri e dal manifest principale (circa 100 s): regola della licenza, sottoinsiemi, indice iPhone di base e indici per paese (i byte committati restano se il contenuto non cambia, quindi un pack invariato tiene il suo pin), i due file di pin, il registro e tutte le verifiche offline:

```sh
python3 -I tools/build_foreign_packs.py            # rigenera e verifica
python3 -I tools/build_foreign_packs.py --check    # verifica soltanto
```

Per cambiare un corridoio: `CORRIDORS` in `tools/foreign_pack_rules.py` (stazioni lette dal pack completo, mai inventate), poi il comando. Il campo `borderZone` dei pin riporta la regola (`rule`), i nomi dei corridoi di ogni pack, la regola delle stazioni italiane e l'impronta del catalogo; le app leggono soltanto `zoneStationIds` e `servedStationIds` (i pin scritti prima, con `radiusKm`, restano leggibili). I file generati non si modificano mai a mano e vanno in un commit separato dal codice.

**Aggiornamento settimanale.** Per un pack di confine il job `core-packs` stampa il pack **completo** in `gtfs-foreign-android/<id>.zip` (non il sottoinsieme) con il manifest. `apply_ci_gtfs_refresh.py` lo confronta con `fullPack`, applica le regole di sospetto al pack completo, lo scrive in `data/gtfs-foreign/android/`, ricava di nuovo il sottoinsieme negli asset (il suo contenuto deve essere quello ricavato dal job) e tra le verifiche esegue `build_foreign_packs.py`. Un pack completo stampato senza `fullPack` nel manifest stampato fa fallire l'applicazione.

**Pubblicazione.** Il workflow `gtfs-foreign-release.yml` parte con un push su `main` o `stabilization/**` che tocca `data/gtfs-foreign/**` o `foreign_packs.json`, oppure con un dispatch del titolare; mai da una pull request. Il suo unico job, `publish`, è l'unico del repository con `contents: write` (`REVIEWED_WRITE_SCOPES` in `verify_ci_pinning.py`) e usa solo `gh` con il token del job: `verify_foreign_packs.py`; `publish_foreign_packs.py --target "$GITHUB_SHA"`, che crea le pre-release mancanti (mai *latest*) con fonte, attribuzione, licenza, SHA-256 e commit nelle note, mentre una release già esistente deve avere gli stessi byte, altrimenti il job fallisce e la release non viene mai modificata; infine `verify_foreign_pack_releases.py --online --pins`, un download anonimo di ogni pin che segue i redirect solo verso `release-assets.githubusercontent.com` e `objects.githubusercontent.com`, confronta dimensione e SHA-256 e si ferma al primo 403 o 429. I tag dipendono dal contenuto: un pack invariato tiene tag e release.

**Prima di una build per gli store.** `release-sign` (`android-ci.yml`) e l'archivio iPhone di un dispatch (`apple-watch-ci.yml`) eseguono `verify_foreign_pack_releases.py --online --pins` prima della firma o di `xcodebuild`: una build con pin non ancora pubblici non si produce. L'ordine è quindi push su `main` o `stabilization/**`, pubblicazione, firma.

**Registro.** `published.json` si può solo estendere. Il job `gtfs-assets` lo confronta con il commit padre (la base di una pull request) con `build_foreign_packs.py --check --ledger-base`, e ogni voce aggiunta deve essere un pin attuale, perché il workflow pubblica solo i pin del commit spinto. Se sul ramo una seconda rigenerazione ha sostituito un pin, rigenerare indicando il registro della base:

```sh
git show origin/main:data/gtfs-foreign/published.json > /tmp/published-base.json \
  || printf '{"schemaVersion": 1, "repository": "fz1101hub/binario", "artifacts": []}\n' > /tmp/published-base.json
python3 -I tools/build_foreign_packs.py --ledger-base /tmp/published-base.json
```

Il lunedì `freshness-watch` scarica di nuovo ogni voce del registro (`verify_foreign_pack_releases.py --online --ledger`) e fallisce se una release manca o è cambiata: un'app installata tiene il suo pin per sempre.

**Una volta, nelle impostazioni del repository (titolare).** Attivare *release immutability* e una regola sui tag che vieti aggiornamento e cancellazione di `gtfs-full-*` e `gtfs-ios-*`.

## Pack nazionali Trenitalia e Italo (fissati a un commit)

`trenitalia-gtfs` e `italo-gtfs` derivano dagli orari ufficiali NeTEx pubblicati sul National Access Point italiano (CCISS, dataset [1077621](https://www.cciss.it/nap/mmtis/public/en/catalog/Dataset/1077621) e [1814124](https://www.cciss.it/nap/mmtis/public/en/catalog/Dataset/1814124)), convertiti in GTFS ogni settimana da [deryclem/trenitalia-gtfs](https://github.com/deryclem/trenitalia-gtfs) e [deryclem/italo-gtfs](https://github.com/deryclem/italo-gtfs) (CC BY 4.0). Nel manifest ogni pack porta `upstream` con repository, commit, percorso e `archiveSha256`; `sourceUrl` è l'URL raw di quel commit. La build rifiuta qualsiasi archivio con un digest diverso, quindi un aggiornamento è sempre una scelta esplicita e rivista. Il digest confrontato è lo SHA-256 dei byte dell'archivio indicato: un `binario_filter_manifest.json` contenuto nell'archivio conta solo quando l'archivio è, byte per byte, il pack già pubblicato (ricostruzione dagli asset), e solo al primo livello dello ZIP (`tests/test_build_gtfs_packs.py`, archivio contraffatto dell'audit A7-01).

Il filtro tiene treni e bus dell'operatore (`gtfs-rail-and-operator-coach-route-types`: `route_type` 2, 100–199 e 3) e non apre né distribuisce `shapes.txt`.

Il refresh avviene con il job `core-packs` (sezione «Aggiornamento settimanale»): il commit proposto è l'ultimo che ha toccato `upstream.path`, letto dall'API GitHub con il token del job (inviato solo ad `api.github.com`, mai sui redirect) o, in mancanza, da `git ls-remote`; l'archivio è scaricato dall'URL raw di quel commit e trattato come dato non fidato. La nota `Pinned to the upstream commit of …` del manifest prende la data del commit. Il job non modifica il ramo: il pin entra solo con il commit dei file applicati da `tools/apply_ci_gtfs_refresh.py`, che verifica che manifest e blocco stampato coincidano.

A mano, da un archivio già scaricato e rivisto (`python3 -I`, fuori dalla sua directory): aggiornare nel manifest `upstream.commit`, `upstream.archiveSha256` e `sourceUrl` (`https://raw.githubusercontent.com/<repo>/<commit>/<file>`), poi ricostruire **solo** quei pack; gli altri restano invariati byte per byte:

```sh
python3 tools/build_gtfs_packs.py --only trenitalia-gtfs --only italo-gtfs \
  --source-archive trenitalia-gtfs=/percorso/gtfs-trenitalia.zip \
  --source-archive italo-gtfs=/percorso/gtfs-italo.zip \
  --generated-at "$(date --utc +%F)"
```

In alternativa `--refresh --source-id trenitalia-gtfs --only trenitalia-gtfs` scarica l'archivio dall'URL fissato. Restano da allineare `lastServiceDate` in `EmbeddedGtfsPacks.all`, l'URL in `RailSourceCatalog.kt` e in `androidOnlySources` della policy iOS, e l'indice iOS (`tools/build_ios_gtfs_schedule.py` legge il manifest anche se i due pack sono `androidOnlySources`).

**Italo: scadenza il 12 dicembre 2026.** Il calendario Italo del commit fissato al 9 novembre 2026 (`calendar.txt` fino al 12 dicembre 2026, una data isolata il 2 febbraio 2027) termina con il cambio orario: va rinnovato appena l'upstream pubblica l'orario dal 13 dicembre 2026 (vedi «Cambio orario» sopra). Trenitalia è valido fino all'11 dicembre 2027.

## Ferrovie regionali e reti urbane (`gtfs-transit/`, `gtfs-personal/`)

Dal novembre 2026 le ferrovie regionali pubblicate sul National Access Point e le reti di metropolitane, tram e funicolari sono pack separati da quelli del primo rilascio (`gtfs/`, job `core-packs`; undici da quando, nel novembre 2026, è stato tolto il pack croato HŽPP, senza stazioni italiane):

- `app/src/main/assets/gtfs-transit/`: dati aperti, in ogni build;
- `app/src/personal/assets/gtfs-personal/`: licenza non commerciale o non dichiarata, solo nelle build personali (`-Pbinario.personalSources=true`; predefinito `true` per debug e `false` per release, vedi `SOURCE_POLICY.md`). Il job `transit-packs` li stampa nel log, che in questo repository pubblico è pubblico, solo con l'input `print_personal_packs=true` (predefinito no): senza, l'helper non li aggiorna (decisione del titolare in `SOURCE_POLICY.md`, audit A7-04).

Il registro delle sorgenti è `tools/transit_packs.json` (per ogni pack: `sourceId`, directory, distribuzione, `kind` `rail` o `urban`, formato `gtfs` o `netex`, URL, licenza con la sua evidenza, attribuzione, `minValidDays`; per un'origine solo HTTP la copia HTTPS da confrontare, `corroborateWith`, oppure, per un pack personale, il motivo `httpReason`). Il container di sviluppo non raggiunge questi host: acquisizione e build avvengono **solo sui runner GitHub**, nel job `transit-packs` del workflow `GTFS Asset Integrity`. Nessun codice di terzi è eseguito: i file scaricati (ZIP GTFS, XML NeTEx compresso) sono dati non fidati letti da `tools/acquire_transit_sources.py`, `tools/netex_to_gtfs.py` e `tools/build_gtfs_packs.py` (libreria standard, `python3 -I`).

Il refresh è quello settimanale descritto in testa (`build=all`; `build=id1,id2` ricostruisce solo alcuni pack e l'helper lascia invariati gli altri; `validate-netex` ripete la validazione del convertitore sul dataset Trenitalia del NAP contro il pack `trenitalia-gtfs`). Il job `transit-packs`:

- scarica ogni sorgente (resume e riavvio da zero se il server non supporta `Range`; un 500/502/503/504, che il NAP restituisce a volte per decine di minuti, è ritentato con pause crescenti e, se persiste, il job fallisce senza pubblicare nulla e va rilanciato più tardi; un'origine HTTP deve coincidere byte per byte con la copia HTTPS indicata);
- converte i NeTEx, scrive un manifest per directory e costruisce i pack con `build_gtfs_packs.py` (filtro ferroviario per i pack `rail`, `gtfs-urban-route-types` per quelli `urban`: metro, tram, funicolari e ascensori, autobus esclusi, nessun `shapes.txt`), verificati con `--check --integrity-only`;
- stampa ogni file nel log come righe `BINARIO_FILE percorso byte sha256`, `BINARIO_B64 percorso blocco`, `BINARIO_END percorso`, racchiuse fra `BINARIO_BEGIN <nonce>` e `BINARIO_DONE <nonce> <file>` con un nonce casuale (gli artifact non sono scaricabili dal container di sviluppo) e riporta nel riepilogo la validità di ogni pack. Gli step che scaricano e leggono i feed girano con i comandi del workflow sospesi (`::stop-commands::<nonce>`) e i messaggi d'errore riportano il testo dei dati su una sola riga, con i caratteri di controllo in escape.

`minValidDays` del pack abbassa la soglia per i feed urbani pubblicati poche settimane alla volta (Milano circa cinque settimane): 14 giorni per Milano, Genova, Firenze, Roma, Napoli e Mestre, 30 per gli altri. Un pack nuovo entra nel registro come candidato e si aggiunge con `--add-new` (sezione seguente); le sue corse attese vanno poi in `EmbeddedGtfsTransitPacksTest`.

Il convertitore NeTEx (profilo italiano livello 1) legge `ServiceJourney`, `TimetabledPassingTime` con `DayOffset`, `ServiceJourneyPattern` con salita e discesa ammesse, `DayType`/`UicOperatingPeriod`/`DayTypeAssignment`, `StopPlace`/`Quay`/`ScheduledStopPoint`/`PassengerStopAssignment`, `Line` e `Operator`; rifiuta `DOCTYPE` ed entità a livello di parser expat (in qualunque codifica e in qualunque punto prima dell'elemento radice), limita i byte decompressi di ogni XML (anche `.xml.gz`) e il numero di corse e passaggi convertiti, ignora le geometrie dei `ServiceLink` e scrive un GTFS con `feed_info.txt`. Le corse senza giorni di servizio sono contate e scartate.

## Fonti candidate

Una fonte nuova di `gtfs-transit/` o `gtfs-personal/` entra in `tools/transit_packs.json` con `"candidate": true` e un blocco `android` (aree della città per un pack urbano, `{"name", "latitude", "longitude", "radiusKm"}` con le coordinate prese dal catalogo stazioni; suffissi dei nomi del catalogo, `catalogueNameSuffixes`). Un candidato non è nel refresh settimanale: `build=all` lo salta, e solo `build=<sourceId>` lo scarica e lo costruisce. Le fonti, le licenze e le condizioni sono in [SOURCE_POLICY.md](SOURCE_POLICY.md#fonti-candidate-23-novembre-2026). Sequenza, sullo stesso ramo:

1. **Scoperta e licenze** (sola lettura; il job `transit-discovery` stampa nel log composizione e licenze):

   ```sh
   gh api -X POST repos/fz1101hub/binario/actions/workflows/gtfs-assets-ci.yml/dispatches -f ref=<ramo> \
     -f 'inputs[transit_discovery]=licences page:https://solweb.tper.it/web/tools/open-data/open-data.aspx gtfs:https://opendata.comune.palermo.it/js/server/uploads/dataset/gtfs/amat_gtfs.zip page:https://opendata.comune.palermo.it/ gtfs:https://mobilita.regione.calabria.it/gtfs/otp_gtfs.zip page:https://mobilita.regione.calabria.it/ gtfs:https://b2b.nap.si/data/b2b.gtfs page:https://nap.si/en/datasets_details?id=6fc3966a-5e3f-cf10-1df3-70d3af14d03d page:https://www.circumetnea.it/pubblicazione-orari-del-trasporto-pubblico-locale-in-formato-gtfs/ gtfs:https://it.mangonese.dev/gtfs/fce.gtfs.zip'
   ```

   Per ogni `gtfs:` controllare `agency` (operatore), `route_types` e le righe `route type=…` (treni 2 o 100–199, tram 0, metro 1), `calendar … end=` e `calendar_dates … last=` (orario in corso), `size`; per ogni `page:` le righe `licence-text` e `link`; per `licences` le righe `LICENCE`. Se un collegamento `.zip` della pagina Circumetnea è un GTFS nuovo, ripetere il dispatch con `gtfs:<quel link>`.
2. **Decisione.** Con la licenza confermata dall'editore spostare il candidato in `gtfs-transit/` (`"directory": "gtfs-transit"`, `"distribution": "all"`) e scrivere licenza ed evidenza; senza treni (Calabria), senza corse in corso (Palermo) o senza SŽ (Slovenia) il candidato non va costruito. Treni di un operatore già incluso (per esempio Trenitalia nel feed calabrese) vanno esclusi prima con un selettore rivisto in `build_gtfs_packs.py`.
3. **Build** dei soli candidati scelti, in un dispatch che non includa `build=all` (una fonte senza route ammesse ferma la build della sua directory):

   ```sh
   gh api -X POST repos/fz1101hub/binario/actions/workflows/gtfs-assets-ci.yml/dispatches -f ref=<ramo> \
     -f 'inputs[transit_packs]=build=marconi-express-gtfs,palermo-amat-tram-gtfs,calabria-rail-gtfs,si-nap-rail-gtfs' \
     -f 'inputs[print_personal_packs]=true'
   ```

   `print_personal_packs=true` stampa solo i pack di `gtfs-personal/` di questa build (i candidati; non GTT né gli altri pack personali, che il dispatch non costruisce). Nel log: la riga `VERSION marconi-express-gtfs {…}` con le versioni TPER elencate e quella usata, `ACQUIRE … {download}` con byte e SHA-256, gli errori del filtro, la tabella di validità nel riepilogo.
4. **Aggiunta**: prima la voce del catalogo in `RailSourceCatalog.kt` (già presente per Marconi Express, Palermo e Slovenia; per la Calabria va scritta con l'operatore trovato), con `readiness` coerente con la directory (`PERSONAL_GTFS_PACK` per `gtfs-personal/`, `READY_FOR_GTFS_PACK` o `IMPORT_VIA_GATEWAY` per `gtfs-transit/`), poi

   ```sh
   python3 tools/apply_ci_gtfs_refresh.py --run <run_id> --add-new marconi-express-gtfs --add-new si-nap-rail-gtfs
   ```

   L'helper aggiunge ZIP, voce del manifest e `SHA256SUMS`, la voce di `TransitGtfsPacks.kt` dal blocco `android` (data di fine dal manifest, `personalOnly` per `gtfs-personal/`), rigenera i crediti Android ed esegue le verifiche. Poi: togliere `"candidate": true` dal registro, aggiungere le righe della tabella in `SOURCE_POLICY.md` (pack, corse, fermate, fine) e le corse attese in `EmbeddedGtfsTransitPacksTest`; per il Marconi Express valutare la voce Bologna Centrale (`curated:S05043`) ↔ Bologna Aeroporto (`curated:NC000683`) in `AirportRoutes.kt`, solo se `AirportRoutesTest` la trova sui pack reali (oggi il test vuole pack di ogni build: un pack personale non basta).

### Licenze dichiarate dagli editori

`licences` nel dispatch di scoperta legge, una richiesta ciascuna, le API di metadati di `LICENCE_METADATA` (`tools/transit_source_discovery.py`): vista Socrata del GTFS Trenord su dati.lombardia.it, ricerca CKAN di dati.trentino.it, scheda CKAN del dataset TFT su dati.toscana.it, ricerca nel catalogo nazionale dati.gov.it per EAV. `licence:<URL>` fa lo stesso per un solo URL (vista Socrata o `package_show`/`package_search` CKAN). Una licenza registrata cambia solo con questa stampa: per un pack principale (decisione D3 in [AZIONI_TITOLARE.md](../AZIONI_TITOLARE.md)) scrivere `licence` e `licenceEvidence` (URL dell'API e data della run) in `app/src/main/assets/gtfs/manifest.json`, la stessa licenza in `ios/Resources/binario_gtfs_source_policy_v1.json`, poi `python3 -I tools/build_android_gtfs_credits.py` e `python3 -I tools/build_ios_gtfs_schedule.py`, e le verifiche di sempre.

## Sorgenti temporaneamente escluse dalla release

Dal 23 settembre al novembre 2026, `amt-genova-casella-gtfs` e `cotral-ferro-gtfs` non hanno fatto parte degli asset embedded (oggi sono in `gtfs-transit/` e, per Cotral, in `gtfs-personal/`): il primo non garantiva i 30 giorni minimi di copertura e il secondo era già scaduto. Lasciare dati vecchi nell'APK avrebbe violato il gate G2; l'esclusione riduce la copertura ma mantiene veritiera la qualità dichiarata.

La voce di catalogo e la provenienza storica restano documentate per facilitarne il ripristino. La reintroduzione richiede, nello stesso commit:

1. un archivio completo acquisito da HTTPS valido o gateway approvato;
2. filtro ferroviario revisionato e testato;
3. `lastServiceDate` e `fullServiceUntil` con almeno 30 giorni residui alla data prevista di release;
4. rigenerazione atomica di ZIP, `manifest.json`, `SHA256SUMS` e `EmbeddedGtfsPacks.all`;
5. esito verde sia del controllo integrità sia del gate freshness.

## Controparti statiche dei feed live

Il refresh settimanale (`core-packs`, sezione «Aggiornamento settimanale») copre anche STA e SNCF, le controparti statiche dei Trip Updates GTFS-Realtime: sono scaricate dai loro URL HTTPS `OPEN_DIRECT` registrati e applicate con `tools/apply_ci_gtfs_refresh.py` come gli altri pack, con la provenienza (`sourceArchiveSha256`, `lastServiceDate`) nel manifest. Il vecchio workflow `Refresh static counterparts of live feeds` (`refresh-live-gtfs.yml`) è stato rimosso: non poteva più riuscire (l'allineamento delle date Kotlin non riconosceva le dichiarazioni su più righe di Trenitalia e Italo e il suo primo step richiedeva già 30 giorni di validità ai pack da aggiornare) e aveva un token con permesso di scrittura mentre leggeva feed di terzi (audit A7-07). L'ultimo refresh STA/SNCF è quello del 3 novembre 2026 (commit `6032ee4`, run 41556267386).

I pack dell'app contengono orari e coordinate delle fermate. La pipeline non apre né include `shapes.txt` e rimuove i riferimenti opzionali `shape_id`: il dataset attuale può contenere oltre 512 MiB di geometrie che il runtime non utilizza. Per questo solo quel membro ignorato non è soggetto al limite per singolo file; restano i limiti complessivi dell'archivio, delle entry, del rapporto di compressione e di tutte le tabelle lette. Il digest dell'archivio sorgente completo rimane registrato e i member effettivamente distribuiti sono attestati separatamente.
