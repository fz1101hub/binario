# Refresh dei pack GTFS embedded

I pack GTFS sono **artefatti di release offline**: l'app non scarica mai dati di orario. Il comando `tools/build_gtfs_packs.py` acquisisce solo le URL registrate in `manifest.json`, applica un limite di byte anche alle risposte chunked, filtra ogni feed, e pubblica ZIP, manifest e checksum con un singolo replace atomico della directory di output. Nessun download incompleto, feed inventato o precedente asset viene presentato come aggiornamento riuscito.

## Cadenza e verifica

Il refresh va eseguito **a ogni cambio orario**, prima della release. La CI ordinaria verifica sempre struttura, filtri, hash e coerenza delle date dichiarate, anche mentre una sorgente esterna è temporaneamente bloccata:

```sh
python3 tools/build_gtfs_packs.py --check --integrity-only
```

Il gate della release richiede invece almeno 30 giorni di copertura residua e non deve mai usare `--integrity-only`:

```sh
python3 tools/build_gtfs_packs.py --check --as-of "$(date --utc +%F)" --min-valid-days 30
```

Entrambi i comandi confrontano il digest di ciascun ZIP con `manifest.json` e `SHA256SUMS` e derivano `lastServiceDate` come ultimo giorno dell'ultimo periodo di orario reale: i giorni di esercizio ricavati da `calendar.txt` (con i flag dei giorni della settimana) e dalle eccezioni di `calendar_dates.txt`, distanti al massimo 7 giorni l'uno dall'altro e per almeno una settimana. Una data isolata mesi dopo o una riga che termina in un giorno in cui il servizio non circola non estendono la copertura. Il secondo blocca inoltre pack scaduti o entro la soglia ed è il gate vincolante per un candidato release. Il workflow `GTFS Asset Integrity` esegue offline il controllo strutturale; la checklist release deve registrare separatamente l'esito del controllo freshness.

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

Per la revisione, il tool conserva nel pack filtrato il checksum dell'archivio completo sorgente (`sourceArchiveSha256`) e scrive il criterio, i conteggi e `lastServiceDate` in `binario_filter_manifest.json`.

## Download diretto consentito

Il tool permette un refresh diretto esclusivamente per sorgenti registrate come `OPEN_DIRECT` e `https://`:

```sh
python3 tools/build_gtfs_packs.py --refresh --source-id toscana-tft-gtfs \
  --max-download-bytes $((200 * 1024 * 1024)) \
  --output-dir /tmp/binario-gtfs --generated-at "$(date --utc +%F)"
```

Le sorgenti `GATEWAY_ONLY`, inclusi **AMT Genova–Casella** e **Cotral Ferro**, non vengono scaricate da endpoint non verificabili. Al 23 settembre 2026, Transitland conferma un feed AMT aggiornato fino al 6 novembre 2026, ma l'origine pubblicata è solo HTTP e il mirror HTTPS richiede credenziali; l'URL Cotral usa invece un certificato valido per `*.cotralspa.it` ma non per il nome a tre livelli `travel.mob.cotralspa.it`, quindi fallisce la verifica TLS. Servono un gateway HTTPS approvato o un nuovo endpoint ufficiale con certificato valido e un archivio GTFS completo. Il filtro AMT è volutamente stretto: mantiene solo l'identificatore revisionato `FGC-191` (Manin–Casella, `FGC-164` fino al feed di settembre 2026); non usa un matching fuzzy sul nome e fallisce se la route cambia identificatore, imponendo una nuova revisione. Da novembre 2026 entrambi sono di nuovo inclusi dalla pipeline `transit-packs` (sezione «Ferrovie regionali e reti urbane»): AMT dall'origine HTTP verificata contro la copia HTTPS del Mobility Database, Cotral solo nelle build personali.

## Pack nazionali Trenitalia e Italo (fissati a un commit)

`trenitalia-gtfs` e `italo-gtfs` derivano dagli orari ufficiali NeTEx pubblicati sul National Access Point italiano (CCISS, dataset [1077621](https://www.cciss.it/nap/mmtis/public/en/catalog/Dataset/1077621) e [1814124](https://www.cciss.it/nap/mmtis/public/en/catalog/Dataset/1814124)), convertiti in GTFS ogni settimana da [deryclem/trenitalia-gtfs](https://github.com/deryclem/trenitalia-gtfs) e [deryclem/italo-gtfs](https://github.com/deryclem/italo-gtfs) (CC BY 4.0). Nel manifest ogni pack porta `upstream` con repository, commit, percorso e `archiveSha256`; `sourceUrl` è l'URL raw di quel commit. La build rifiuta qualsiasi archivio con un digest diverso, quindi un aggiornamento è sempre una scelta esplicita e rivista.

Il filtro tiene treni e bus dell'operatore (`gtfs-rail-and-operator-coach-route-types`: `route_type` 2, 100–199 e 3) e non apre né distribuisce `shapes.txt`.

Passi di refresh (azione del titolare, almeno a ogni cambio orario):

1. Scegliere il nuovo commit upstream e verificarne `feed_info.txt` (date di validità) e `attributions.txt`; trattare l'archivio come dato non fidato (`python3 -I`, fuori dalla sua directory).
2. Aggiornare nel manifest `upstream.commit`, `upstream.archiveSha256` (lo SHA-256 dell'archivio scaricato) e `sourceUrl` (`https://raw.githubusercontent.com/<repo>/<commit>/<file>`), poi ricostruire **solo** quei pack; gli altri restano invariati byte per byte:

   ```sh
   python3 tools/build_gtfs_packs.py --only trenitalia-gtfs --only italo-gtfs \
     --source-archive trenitalia-gtfs=/percorso/gtfs-trenitalia.zip \
     --source-archive italo-gtfs=/percorso/gtfs-italo.zip \
     --generated-at "$(date --utc +%F)"
   ```

   In alternativa `--refresh --source-id trenitalia-gtfs --only trenitalia-gtfs` scarica l'archivio dall'URL fissato.
3. Allineare `lastServiceDate` in `EmbeddedGtfsPacks.all`, eseguire i due controlli `--check` sopra, i test e rigenerare l'indice iOS (`tools/build_ios_gtfs_schedule.py`, che legge il manifest anche se i due pack sono `androidOnlySources`), poi `tools/verify_ios_gtfs_schedule.py`.
4. Aggiornare in `EmbeddedGtfsJourneyRealPacksTest` i treni attesi se l'orario è cambiato.

**Italo: scadenza il 12 dicembre 2026.** Il calendario Italo del commit fissato (`calendar.txt` fino al 12 dicembre 2026, poche date sparse fino al 2 febbraio 2027) termina con il cambio orario: il gate a 30 giorni lo blocca dal 12 novembre 2026, come gli altri pack che terminano lo stesso giorno (STA, svizzero, ÖBB, Trenord, TFT, HŽPP). Il pack va rinnovato appena l'upstream pubblica l'orario dal 13 dicembre 2026, prima della release successiva. Trenitalia è valido fino all'11 dicembre 2027.

## Ferrovie regionali e reti urbane (`gtfs-transit/`, `gtfs-personal/`)

Dal novembre 2026 le ferrovie regionali pubblicate sul National Access Point e le reti di metropolitane, tram e funicolari sono pack separati dai dodici del primo rilascio, che restano invariati byte per byte:

- `app/src/main/assets/gtfs-transit/`: dati aperti, in ogni build;
- `app/src/personal/assets/gtfs-personal/`: licenza non commerciale o non dichiarata, solo nelle build personali (`-Pbinario.personalSources=true`; predefinito `true` per debug e `false` per release, vedi `SOURCE_POLICY.md`).

Il registro delle sorgenti è `tools/transit_packs.json` (per ogni pack: `sourceId`, directory, distribuzione, `kind` `rail` o `urban`, formato `gtfs` o `netex`, URL, licenza con la sua evidenza, attribuzione, `minValidDays`; per un'origine solo HTTP la copia HTTPS da confrontare, `corroborateWith`, oppure, per un pack personale, il motivo `httpReason`). Il container di sviluppo non raggiunge questi host: acquisizione e build avvengono **solo sui runner GitHub**, nel job `transit-packs` del workflow `GTFS Asset Integrity`. Nessun codice di terzi è eseguito: i file scaricati (ZIP GTFS, XML NeTEx compresso) sono dati non fidati letti da `tools/acquire_transit_sources.py`, `tools/netex_to_gtfs.py` e `tools/build_gtfs_packs.py` (libreria standard, `python3 -I`).

Passi di refresh (azione del titolare, settimanale per le reti urbane, a ogni cambio orario per le ferrovie):

1. Avviare il job sul ramo di lavoro:

   ```sh
   gh api -X POST repos/<owner>/binario/actions/workflows/gtfs-assets-ci.yml/dispatches \
     -f ref=<ramo> -f 'inputs[transit_packs]=build=all'
   ```

   `build=id1,id2` ricostruisce solo alcuni pack (la directory pubblicata contiene allora solo quelli: unire a mano con il manifest esistente); aggiungere `validate-netex` (`'inputs[transit_packs]=validate-netex build=all'`) per ripetere la validazione del convertitore sul dataset Trenitalia del NAP contro il pack `trenitalia-gtfs`.
2. Il job scarica ogni sorgente (resume e riavvio da zero se il server non supporta `Range`; un 500/502/503/504, che il NAP restituisce a volte per decine di minuti, è ritentato con pause crescenti e, se persiste, il job fallisce senza pubblicare nulla e va rilanciato più tardi; un'origine HTTP deve coincidere byte per byte con la copia HTTPS indicata), converte i NeTEx, scrive un manifest per directory, costruisce i pack con `build_gtfs_packs.py` (filtro ferroviario per i pack `rail`, `gtfs-urban-route-types` per quelli `urban`: metro, tram, funicolari e ascensori, autobus esclusi, nessun `shapes.txt`), li verifica con `--check --integrity-only` e stampa ogni file nel log come righe `BINARIO_FILE percorso byte sha256`, `BINARIO_B64 percorso blocco`, `BINARIO_END percorso`, perché gli artifact non sono scaricabili dal container di sviluppo.
3. Ricostruire i file dal log (`gh api repos/<owner>/binario/actions/jobs/<job_id>/logs`), verificando dimensione e SHA-256 di ciascuno, e copiarli in `app/src/main/assets/gtfs-transit/` e `app/src/personal/assets/gtfs-personal/` (ZIP, `manifest.json`, `SHA256SUMS`).
4. Verificare offline:

   ```sh
   for d in app/src/main/assets/gtfs-transit app/src/personal/assets/gtfs-personal; do
     python3 tools/build_gtfs_packs.py --check --integrity-only --input-dir "$d"
     python3 tools/build_gtfs_packs.py --check --min-valid-days 30 --input-dir "$d"
   done
   python3 tools/verify_embedded_assets.py
   ```

   `minValidDays` del pack abbassa la soglia per i feed urbani pubblicati poche settimane alla volta (Milano circa cinque settimane): 14 giorni per Milano, Genova, Firenze, Roma, Napoli e Mestre, 30 per gli altri.
5. Allineare `lastServiceDate` in `TransitGtfsPacks.kt` (lo controlla `EmbeddedGtfsAssetIntegrityTest`), aggiornare in `EmbeddedGtfsTransitPacksTest` le corse attese se l'orario è cambiato, poi i test Android e Python.

Il convertitore NeTEx (profilo italiano livello 1) legge `ServiceJourney`, `TimetabledPassingTime` con `DayOffset`, `ServiceJourneyPattern` con salita e discesa ammesse, `DayType`/`UicOperatingPeriod`/`DayTypeAssignment`, `StopPlace`/`Quay`/`ScheduledStopPoint`/`PassengerStopAssignment`, `Line` e `Operator`; rifiuta `DOCTYPE` ed entità, ignora le geometrie dei `ServiceLink` e scrive un GTFS con `feed_info.txt`. Le corse senza giorni di servizio sono contate e scartate.

## Sorgenti temporaneamente escluse dalla release

Dal 23 settembre al novembre 2026, `amt-genova-casella-gtfs` e `cotral-ferro-gtfs` non hanno fatto parte degli asset embedded (oggi sono in `gtfs-transit/` e, per Cotral, in `gtfs-personal/`): il primo non garantiva i 30 giorni minimi di copertura e il secondo era già scaduto. Lasciare dati vecchi nell'APK avrebbe violato il gate G2; l'esclusione riduce la copertura ma mantiene veritiera la qualità dichiarata.

La voce di catalogo e la provenienza storica restano documentate per facilitarne il ripristino. La reintroduzione richiede, nello stesso commit:

1. un archivio completo acquisito da HTTPS valido o gateway approvato;
2. filtro ferroviario revisionato e testato;
3. `lastServiceDate` con almeno 30 giorni residui alla data prevista di release;
4. rigenerazione atomica di ZIP, `manifest.json`, `SHA256SUMS` e `EmbeddedGtfsPacks.all`;
5. esito verde sia del controllo integrità sia del gate freshness.

## Controparti statiche dei feed live

`Refresh static counterparts of live feeds` scarica soltanto STA e SNCF dagli URL HTTPS `OPEN_DIRECT` registrati, ricostruisce i pack con la stessa pipeline e ne controlla integrità e validità minima. Salva asset, date Kotlin e provenienza su un ramo `data/refresh-live-gtfs-*` separato da main. Il ramo va verificato e incorporato nel candidato APK prima della consegna.

I pack dell'app contengono orari e coordinate delle fermate. La pipeline non apre né include `shapes.txt` e rimuove i riferimenti opzionali `shape_id`: il dataset attuale può contenere oltre 512 MiB di geometrie che il runtime non utilizza. Per questo solo quel membro ignorato non è soggetto al limite per singolo file; restano i limiti complessivi dell'archivio, delle entry, del rapporto di compressione e di tutte le tabelle lette. Il digest dell'archivio sorgente completo rimane registrato e i member effettivamente distribuiti sono attestati separatamente.
