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

Le sorgenti `GATEWAY_ONLY`, inclusi **AMT Genova–Casella** e **Cotral Ferro**, non vengono scaricate da endpoint non verificabili. Al 23 settembre 2026, Transitland conferma un feed AMT aggiornato fino al 6 novembre 2026, ma l'origine pubblicata è solo HTTP e il mirror HTTPS richiede credenziali; l'URL Cotral usa invece un certificato valido per `*.cotralspa.it` ma non per il nome a tre livelli `travel.mob.cotralspa.it`, quindi fallisce la verifica TLS. Servono un gateway HTTPS approvato o un nuovo endpoint ufficiale con certificato valido e un archivio GTFS completo. Il filtro AMT è volutamente stretto: mantiene solo l'identificatore revisionato `FGC-164` (Manin–Casella); non usa un matching fuzzy sul nome e fallisce se la route cambia identificatore, imponendo una nuova revisione.

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

## Sorgenti temporaneamente escluse dalla release

Dal 23 settembre 2026, `amt-genova-casella-gtfs` e `cotral-ferro-gtfs` non fanno parte degli asset embedded: il primo non garantiva i 30 giorni minimi di copertura e il secondo era già scaduto. Lasciare dati vecchi nell'APK avrebbe violato il gate G2; l'esclusione riduce la copertura ma mantiene veritiera la qualità dichiarata.

La voce di catalogo e la provenienza storica restano documentate per facilitarne il ripristino. La reintroduzione richiede, nello stesso commit:

1. un archivio completo acquisito da HTTPS valido o gateway approvato;
2. filtro ferroviario revisionato e testato;
3. `lastServiceDate` con almeno 30 giorni residui alla data prevista di release;
4. rigenerazione atomica di ZIP, `manifest.json`, `SHA256SUMS` e `EmbeddedGtfsPacks.all`;
5. esito verde sia del controllo integrità sia del gate freshness.

## Controparti statiche dei feed live

`Refresh static counterparts of live feeds` scarica soltanto STA e SNCF dagli URL HTTPS `OPEN_DIRECT` registrati, ricostruisce i pack con la stessa pipeline e ne controlla integrità e validità minima. Salva asset, date Kotlin e provenienza su un ramo `data/refresh-live-gtfs-*` separato da main. Il ramo va verificato e incorporato nel candidato APK prima della consegna.

I pack dell'app contengono orari e coordinate delle fermate. La pipeline non apre né include `shapes.txt` e rimuove i riferimenti opzionali `shape_id`: il dataset attuale può contenere oltre 512 MiB di geometrie che il runtime non utilizza. Per questo solo quel membro ignorato non è soggetto al limite per singolo file; restano i limiti complessivi dell'archivio, delle entry, del rapporto di compressione e di tutte le tabelle lette. Il digest dell'archivio sorgente completo rimane registrato e i member effettivamente distribuiti sono attestati separatamente.
