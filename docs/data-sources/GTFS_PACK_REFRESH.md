# Refresh dei pack GTFS embedded

I pack GTFS sono **artefatti di release offline**: l'app non scarica mai dati di orario. Il comando `tools/build_gtfs_packs.py` acquisisce solo le URL registrate in `manifest.json`, applica un limite di byte anche alle risposte chunked, filtra ogni feed, e pubblica ZIP, manifest e checksum con un singolo replace atomico della directory di output. Nessun download incompleto, feed inventato o precedente asset viene presentato come aggiornamento riuscito.

## Aggiornamento settimanale

Un solo dispatch aggiorna tutti i pack scaricabili liberamente (tutti tranne ÖBB), sul ramo su cui poi si applica il risultato:

```sh
gh api -X POST repos/fz1101hub/binario/actions/workflows/gtfs-assets-ci.yml/dispatches \
  -f ref=<ramo> -f 'inputs[transit_packs]=build=all refresh-core=all'
gh run list --workflow gtfs-assets-ci.yml --branch <ramo> --event workflow_dispatch --limit 1   # id della run
```

Il workflow `GTFS Asset Integrity` avvia due job sui runner GitHub (il container di sviluppo non raggiunge gli editori): `transit-packs` ricostruisce le ferrovie regionali e le reti urbane di `gtfs-transit/` e `gtfs-personal/`, `core-packs` i dodici pack di `gtfs/` (`refresh-core=id1,id2` per alcuni soltanto). I file cambiati sono stampati nel log; il riepilogo della run elenca ogni pack con `lastServiceDate`, giorni residui e soglia (`minValidDays`, 30 se non dichiarata), con `SOTTO SOGLIA` o `SCADUTO` dove serve un intervento e ÖBB come `manuale`. Un job `core-packs` rosso con le altre fonti aggiornate significa che una fonte non ha risposto o non ha superato i controlli: il suo pack precedente resta valido, la run va ripetuta più avanti.

Poi, in locale, sullo stesso ramo:

```sh
python3 tools/apply_ci_gtfs_refresh.py --run <run_id>     # --dry-run per vedere solo la tabella
```

L'helper verifica prima che la run sia un `workflow_dispatch` di `gtfs-assets-ci.yml` sullo stesso ramo locale, con `head_sha` uguale a `HEAD` o a un suo antenato, e che ogni step fino a quello di stampa sia riuscito; legge solo l'unico blocco `BINARIO_BEGIN <nonce>` … `BINARIO_DONE <nonce> <file>` stampato da quello step (nella sua finestra oraria) e rifiuta il log se trova un secondo blocco o righe `BINARIO_*` fuori dal blocco: il testo dei feed stampato da step precedenti non può aggiungere o sostituire file (audit A7-02). Con `--log` (log salvati) restano solo i controlli sul blocco. Poi ricostruisce i file verificandone dimensione e SHA-256, li copia in `app/src/main/assets/gtfs/`, `gtfs-transit/` e `app/src/personal/assets/gtfs-personal/` lasciando byte per byte i pack con orario invariato, aggiorna i pin Trenitalia/Italo (manifest, `ios/Resources/binario_gtfs_source_policy_v1.json`, `RailSourceCatalog.kt`) e `lastServiceDate` in `EmbeddedGtfsPacks.kt` e `TransitGtfsPacks.kt`, esegue i due `--check` per directory, `verify_embedded_assets.py` e, se il manifest principale è cambiato, ricostruisce l'indice iOS e lo verifica (`verify_ios_gtfs_schedule.py`, `verify_ios_gtfs_rebuild.py`). Stampa una tabella: pack, `lastServiceDate` vecchia → nuova, viaggi, cambiato sì/no, note ed errori. Non esegue commit.

Cosa controllare prima del commit:

1. **Tabella.** Le date devono andare avanti; un pack che perderebbe più di metà delle corse o dei passaggi in fermata, che terminerebbe prima di quello pubblicato, o la cui `lastServiceDate` cade più di un mese dopo l'ultimo giorno con almeno un quarto delle corse del giorno di punta (un feed che tiene viva una corsa al giorno per anni: EAV a novembre 2026) è `RIFIUTATO` e resta quello precedente: guardare la fonte e, solo se il cambiamento è reale (fine di un servizio, nuovo orario ridotto), rilanciare con `--accept <sourceId>`. `NON AGGIORNATO dal job` indica una fonte non raggiungibile o rifiutata dalla pipeline; `--skip <sourceId>` esclude un pack dall'applicazione.
2. **Verifiche** tutte `ok`. Un `--check --min-valid-days 30` in errore vuol dire che un pack è sotto soglia: blocca le PR verso `stabilization/**`, `release-sign` e l'archivio iOS, non la CI ordinaria (che lo riporta come avviso).
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

Se il file ha un nuovo nome (un archivio per anno orario), aggiornare prima `sourceUrl` di `oebb-gtfs` nel manifest, nella policy iOS e in `RailSourceCatalog.kt`; poi la data in `EmbeddedGtfsPacks.kt`, i controlli e l'indice iOS come sopra.

**Cambio orario del 13 dicembre 2026.** STA, Svizzera, ÖBB, Trenord, TFT, HŽPP, Italo e Mi.Co.Tra. terminano il 12 dicembre 2026: dal 13 novembre 2026 il gate a 30 giorni li blocca (ARST, EAV e gran parte delle reti urbane dal 2 dicembre); il controllo settimanale li segnala come avviso già 45 giorni prima. Ripetere il dispatch ogni settimana finché gli editori pubblicano l'orario 2027 e la tabella li mostra aggiornati. Il pack svizzero è registrato su un file datato (`gtfs_fp2026_2025-08-14.zip`): il job riscarica sempre lo stesso archivio, quindi per l'orario 2027 serve un nuovo URL rivisto (manifest, policy iOS e `RailSourceCatalog.kt`), come per ÖBB.

## Cadenza e verifica

Il refresh va eseguito **a ogni cambio orario**, prima della release. La CI ordinaria verifica sempre struttura, filtri, hash e coerenza delle date dichiarate, anche mentre una sorgente esterna è temporaneamente bloccata:

```sh
python3 tools/build_gtfs_packs.py --check --integrity-only
```

Il gate della release richiede invece almeno 30 giorni di copertura residua e non deve mai usare `--integrity-only`:

```sh
python3 tools/build_gtfs_packs.py --check --as-of "$(date --utc +%F)" --min-valid-days 30
```

Entrambi i comandi confrontano il digest di ciascun ZIP con `manifest.json` e `SHA256SUMS` e derivano `lastServiceDate` come ultimo giorno dell'ultimo periodo di orario reale: i giorni di esercizio ricavati da `calendar.txt` (con i flag dei giorni della settimana) e dalle eccezioni di `calendar_dates.txt`, distanti al massimo 7 giorni l'uno dall'altro e per almeno una settimana. Una data isolata mesi dopo o una riga che termina in un giorno in cui il servizio non circola non estendono la copertura. Il secondo blocca inoltre pack scaduti o entro la soglia ed è il gate vincolante per un candidato release. La regola è una sola (`build_gtfs_packs.is_release_fresh`, usata anche da `verify_embedded_assets.py` e dal riepilogo dei job): un pack passa se la data di fine derivata dal calendario è almeno `as-of` + 30 giorni (o il suo `minValidDays`, mai più lungo); con il 12 dicembre come ultimo giorno passa fino al 12 novembre compreso. La freschezza è un gate di release, non di correttezza (audit A7-06): il workflow `GTFS Asset Integrity` esegue sempre il controllo strutturale, test e verifiche, e il gate a 30 giorni solo nelle PR verso `stabilization/**`; nelle altre run riporta la validità come avviso. Android CI applica il gate nelle stesse PR e in `release-sign`, Apple Watch CI nello step dell'archivio Release; il lunedì una run pianificata (`freshness-watch`) segnala come avviso ogni pack con meno di 45 giorni residui. La checklist release deve registrare separatamente l'esito del controllo freshness.

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

Le sorgenti `GATEWAY_ONLY` non sono scaricate da `build_gtfs_packs.py --refresh` né dal job `core-packs`. **AMT Genova–Casella** e **Cotral Ferro**, registrate `GATEWAY_ONLY` nei manifest perché pubblicate solo in HTTP, sono invece acquisite dal job `transit-packs` (`tools/transit_packs.json`, `transport: http`): AMT solo se l'archivio HTTP coincide byte per byte con la copia HTTPS del Mobility Database (`corroborateWith`), Cotral, senza copia HTTPS, solo come pack delle build personali (`httpReason`). Storia: al 23 settembre 2026, Transitland conferma un feed AMT aggiornato fino al 6 novembre 2026, ma l'origine pubblicata è solo HTTP e il mirror HTTPS richiede credenziali; l'URL Cotral usa invece un certificato valido per `*.cotralspa.it` ma non per il nome a tre livelli `travel.mob.cotralspa.it`, quindi fallisce la verifica TLS. Servono un gateway HTTPS approvato o un nuovo endpoint ufficiale con certificato valido e un archivio GTFS completo. Il filtro AMT è volutamente stretto: mantiene solo l'identificatore revisionato `FGC-191` (Manin–Casella, `FGC-164` fino al feed di settembre 2026); non usa un matching fuzzy sul nome e fallisce se la route cambia identificatore, imponendo una nuova revisione. Da novembre 2026 entrambi sono di nuovo inclusi (sezione «Ferrovie regionali e reti urbane»).

## Pack principali (`gtfs/`, job `core-packs`)

`tools/refresh_core_gtfs.py` gira solo sul runner (`python3 -I`), per ogni pack selezionato del manifest principale:

- `oebb-gtfs` non è mai scaricato (`manuale` nel report);
- un pack con `upstream` (Trenitalia, Italo) è scaricato dall'URL raw dell'ultimo commit upstream, come descritto sotto;
- ogni altro pack `OPEN_DIRECT` è scaricato dal suo `sourceUrl` HTTPS con lo stesso downloader della pipeline `transit-packs` (limite di 400 MiB, ripresa con `Range`, nuovi tentativi sui 5xx) e deve essere uno ZIP.

Un archivio identico a quello registrato (`sourceArchiveSha256`) non è ricostruito. Ogni archivio nuovo è filtrato da solo con `build_gtfs_packs.py --only <id>` su una copia della directory: se il download o la build falliscono, il pack e la sua voce di manifest restano quelli precedenti e il report registra l'errore; se l'orario filtrato non cambia (`contentSha256` uguale, per esempio quando cambiano solo gli autobus), il pack pubblicato è rimesso byte per byte. La directory risultante supera `--check --integrity-only`; nel log sono stampati solo `manifest.json`, `SHA256SUMS`, gli ZIP cambiati e `core-refresh.json` (esito per pack, conteggi, pin proposti con `feed_info.txt` e `attributions.txt`). Il testo letto dagli archivi è stampato con i comandi del workflow sospesi (`::stop-commands::`). Il job fallisce, dopo aver stampato i pack riusciti, se una fonte non è stata aggiornata.

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

Dal novembre 2026 le ferrovie regionali pubblicate sul National Access Point e le reti di metropolitane, tram e funicolari sono pack separati dai dodici del primo rilascio (`gtfs/`, job `core-packs`):

- `app/src/main/assets/gtfs-transit/`: dati aperti, in ogni build;
- `app/src/personal/assets/gtfs-personal/`: licenza non commerciale o non dichiarata, solo nelle build personali (`-Pbinario.personalSources=true`; predefinito `true` per debug e `false` per release, vedi `SOURCE_POLICY.md`). Il job `transit-packs` li stampa nel log, che in questo repository pubblico è pubblico, solo con l'input `print_personal_packs=true` (predefinito no): senza, l'helper non li aggiorna (decisione del titolare in `SOURCE_POLICY.md`, audit A7-04).

Il registro delle sorgenti è `tools/transit_packs.json` (per ogni pack: `sourceId`, directory, distribuzione, `kind` `rail` o `urban`, formato `gtfs` o `netex`, URL, licenza con la sua evidenza, attribuzione, `minValidDays`; per un'origine solo HTTP la copia HTTPS da confrontare, `corroborateWith`, oppure, per un pack personale, il motivo `httpReason`). Il container di sviluppo non raggiunge questi host: acquisizione e build avvengono **solo sui runner GitHub**, nel job `transit-packs` del workflow `GTFS Asset Integrity`. Nessun codice di terzi è eseguito: i file scaricati (ZIP GTFS, XML NeTEx compresso) sono dati non fidati letti da `tools/acquire_transit_sources.py`, `tools/netex_to_gtfs.py` e `tools/build_gtfs_packs.py` (libreria standard, `python3 -I`).

Il refresh è quello settimanale descritto in testa (`build=all`; `build=id1,id2` ricostruisce solo alcuni pack e l'helper lascia invariati gli altri; `validate-netex` ripete la validazione del convertitore sul dataset Trenitalia del NAP contro il pack `trenitalia-gtfs`). Il job `transit-packs`:

- scarica ogni sorgente (resume e riavvio da zero se il server non supporta `Range`; un 500/502/503/504, che il NAP restituisce a volte per decine di minuti, è ritentato con pause crescenti e, se persiste, il job fallisce senza pubblicare nulla e va rilanciato più tardi; un'origine HTTP deve coincidere byte per byte con la copia HTTPS indicata);
- converte i NeTEx, scrive un manifest per directory e costruisce i pack con `build_gtfs_packs.py` (filtro ferroviario per i pack `rail`, `gtfs-urban-route-types` per quelli `urban`: metro, tram, funicolari e ascensori, autobus esclusi, nessun `shapes.txt`), verificati con `--check --integrity-only`;
- stampa ogni file nel log come righe `BINARIO_FILE percorso byte sha256`, `BINARIO_B64 percorso blocco`, `BINARIO_END percorso`, racchiuse fra `BINARIO_BEGIN <nonce>` e `BINARIO_DONE <nonce> <file>` con un nonce casuale (gli artifact non sono scaricabili dal container di sviluppo) e riporta nel riepilogo la validità di ogni pack. Gli step che scaricano e leggono i feed girano con i comandi del workflow sospesi (`::stop-commands::<nonce>`) e i messaggi d'errore riportano il testo dei dati su una sola riga, con i caratteri di controllo in escape.

`minValidDays` del pack abbassa la soglia per i feed urbani pubblicati poche settimane alla volta (Milano circa cinque settimane): 14 giorni per Milano, Genova, Firenze, Roma, Napoli e Mestre, 30 per gli altri. Un pack nuovo nel registro non è applicato dall'helper: va aggiunto a mano a `TransitGtfsPacks.kt` (lo controlla `EmbeddedGtfsAssetIntegrityTest`) con le sue corse attese in `EmbeddedGtfsTransitPacksTest`.

Il convertitore NeTEx (profilo italiano livello 1) legge `ServiceJourney`, `TimetabledPassingTime` con `DayOffset`, `ServiceJourneyPattern` con salita e discesa ammesse, `DayType`/`UicOperatingPeriod`/`DayTypeAssignment`, `StopPlace`/`Quay`/`ScheduledStopPoint`/`PassengerStopAssignment`, `Line` e `Operator`; rifiuta `DOCTYPE` ed entità a livello di parser expat (in qualunque codifica e in qualunque punto prima dell'elemento radice), limita i byte decompressi di ogni XML (anche `.xml.gz`) e il numero di corse e passaggi convertiti, ignora le geometrie dei `ServiceLink` e scrive un GTFS con `feed_info.txt`. Le corse senza giorni di servizio sono contate e scartate.

## Sorgenti temporaneamente escluse dalla release

Dal 23 settembre al novembre 2026, `amt-genova-casella-gtfs` e `cotral-ferro-gtfs` non hanno fatto parte degli asset embedded (oggi sono in `gtfs-transit/` e, per Cotral, in `gtfs-personal/`): il primo non garantiva i 30 giorni minimi di copertura e il secondo era già scaduto. Lasciare dati vecchi nell'APK avrebbe violato il gate G2; l'esclusione riduce la copertura ma mantiene veritiera la qualità dichiarata.

La voce di catalogo e la provenienza storica restano documentate per facilitarne il ripristino. La reintroduzione richiede, nello stesso commit:

1. un archivio completo acquisito da HTTPS valido o gateway approvato;
2. filtro ferroviario revisionato e testato;
3. `lastServiceDate` con almeno 30 giorni residui alla data prevista di release;
4. rigenerazione atomica di ZIP, `manifest.json`, `SHA256SUMS` e `EmbeddedGtfsPacks.all`;
5. esito verde sia del controllo integrità sia del gate freshness.

## Controparti statiche dei feed live

Il refresh settimanale (`core-packs`, sezione «Aggiornamento settimanale») copre anche STA e SNCF, le controparti statiche dei Trip Updates GTFS-Realtime: sono scaricate dai loro URL HTTPS `OPEN_DIRECT` registrati e applicate con `tools/apply_ci_gtfs_refresh.py` come gli altri pack, con la provenienza (`sourceArchiveSha256`, `lastServiceDate`) nel manifest. Il vecchio workflow `Refresh static counterparts of live feeds` (`refresh-live-gtfs.yml`) è stato rimosso: non poteva più riuscire (l'allineamento delle date Kotlin non riconosceva le dichiarazioni su più righe di Trenitalia e Italo e il suo primo step richiedeva già 30 giorni di validità ai pack da aggiornare) e aveva un token con permesso di scrittura mentre leggeva feed di terzi (audit A7-07). L'ultimo refresh STA/SNCF è quello del 3 novembre 2026 (commit `6032ee4`, run 41556267386).

I pack dell'app contengono orari e coordinate delle fermate. La pipeline non apre né include `shapes.txt` e rimuove i riferimenti opzionali `shape_id`: il dataset attuale può contenere oltre 512 MiB di geometrie che il runtime non utilizza. Per questo solo quel membro ignorato non è soggetto al limite per singolo file; restano i limiti complessivi dell'archivio, delle entry, del rapporto di compressione e di tutte le tabelle lette. Il digest dell'archivio sorgente completo rimane registrato e i member effettivamente distribuiti sono attestati separatamente.
