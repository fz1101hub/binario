# Statistiche di puntualità (non ufficiali)

Binario mostra per ogni treno Trenitalia l'affidabilità media degli ultimi 30 giorni, fermata per fermata. Le statistiche sono **calcolate da Binario** osservando ViaggiaTreno e sono presentate come «Statistiche non ufficiali di Binario su dati ViaggiaTreno»: non sono dati Trenitalia o RFI, né una loro certificazione di puntualità.

## Decisione del titolare e piano di ritiro

**3 novembre 2026.** Il titolare ha deciso di mostrare l'affidabilità media di ogni treno nelle build personali **e** in quelle degli store, calcolandola da osservazioni ViaggiaTreno «come fa Treniamo», e di ritirare la funzione se Trenitalia o RFI si oppongono. La decisione supera, per questa sola funzione, l'indicazione del benchmark storico (`docs/releases/storico/BENCHMARK_GAP_V14.md`) di adottare le statistiche solo con dati autorizzati. Restano fermi i vincoli della [policy delle fonti](SOURCE_POLICY.md): nessun dato, API, chiave o codice di Treniamo; ViaggiaTreno resta una fonte **best effort pubblico**.

Ne derivano tre regole di progetto:

1. **Solo aggregati.** Nell'app e in qualunque file pubblico entrano soltanto conteggi, medie e soglie per treno e fermata; mai risposte ViaggiaTreno, orari reali, testi, nomi o binari.
2. **Attribuzione onesta.** Il file dichiara `"source": "ViaggiaTreno, elaborazione Binario (non ufficiale)"`; l'app mostra «Statistiche non ufficiali di Binario su dati ViaggiaTreno».
3. **Ritiro rapido.** Su richiesta di Trenitalia/RFI, o se ViaggiaTreno cambia condizioni:
   1. disattivare subito il workflow (`gh workflow disable punctuality.yml`, poi rimuovere `.github/workflows/punctuality.yml`): le richieste cessano;
   2. pubblicare una release con la proprietà Gradle `binario.punctualityStats=false` (asset escluso dall'APK, interfaccia nascosta) e rimuovere `app/src/main/assets/punctuality/`;
   3. cancellare le cache `punctuality-raw-*` (`gh cache list --key punctuality-raw- --json id -q '.[].id' | xargs -n1 gh cache delete`, o dalla pagina Actions → Caches);
   4. aggiornare questo documento e la riga in `SOURCE_POLICY.md`.

## Cosa si raccoglie e da dove

La raccolta gira **solo sui runner GitHub** (`.github/workflows/punctuality.yml`): il telefono non fa nessuna nuova richiesta, legge il file incluso nell'APK.

- **Treni.** Le corse ferroviarie del pack incluso `app/src/main/assets/gtfs/trenitalia-gtfs.zip` che circolano ieri o oggi secondo `calendar.txt` e `calendar_dates.txt` (orari GTFS in Europe/Rome, da mezzogiorno meno 12 ore del giorno di servizio). Numero = `trip_short_name`, categoria = suffisso di `route_short_name` (`0083_REG` → `REG`). Esclusi gli autobus (3.013 corse del pack di novembre 2026, numeri non numerici) e le 245 corse con origine all'estero (Zurigo, Parigi, Monaco, Vienna…), il cui codice ViaggiaTreno non si ricava dal pack.
- **Origine.** Codice ViaggiaTreno della prima fermata, dal suo `stop_code` (location id Trenitalia, `830008409`): la stazione del catalogo curato con quel `trenitaliaLocationId`, confermata dalla posizione entro 10 km (o da una parola del nome), come `GtfsStationCodeIndex` nell'app; altrimenti `S` + le ultime cinque cifre (`S08409`). Per 100 fermate del pack le due regole divergono (Fidene `830008225` è `S08235`; Torino Porta Susa `830000222` è `S00035`), per circa 600 corse su 18.784. Un codice sbagliato costa una richiesta e diventa «sconosciuto» o «identità diversa», mai un'osservazione sbagliata.
- **Richiesta.** `http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/andamentoTreno/{origine}/{numero}/{mezzanotte}`, con mezzanotte locale del giorno di partenza in millisecondi; una sola richiesta per treno e run, `User-Agent: Binario-punctuality/1.0 (personal timetable app; read-only)` (nello stile dell'app: la prima versione con un indirizzo web riceveva 403, mentre gli altri client di Binario ricevono risposta dagli stessi runner), nessun cookie o login. Al primo 403 o 429 la run si ferma e rimanda i treni alla successiva; se ViaggiaTreno rifiuta in modo stabile anche questa forma, la raccolta va spenta (decisione del titolare: «se ci dovessero fare problemi la togliamo»), senza travestirsi da browser né cambiare indirizzo. Un reindirizzamento è seguito solo verso la stessa risorsa su `www.viaggiatreno.it` (HTTP) o `(www.)viaggiatreno.it` (HTTPS).
- **Quando.** Un treno è chiesto quando il suo arrivo programmato all'ultima fermata è fra 4 ore e 20 minuti prima della run. Se non risulta ancora arrivato (nessun orario reale all'ultima fermata servita, `arrivato` non vero, non cancellato) resta in sospeso e le run successive lo richiedono fino a 8 ore dopo l'arrivo programmato; poi è «sconosciuto» e non conta. Un treno noto (arrivato o cancellato) non è più richiesto.
- **Cosa si tiene.** Per fermata: codice stazione (`fermate[].id`), ritardo in minuti interi, soppressione. Per la corsa: arrivata o cancellata. Nient'altro.

Regole di lettura (dai campi che l'app già usa in `ViaggiaTrenoRealtimeAdapter`):

| Dato | Campi ViaggiaTreno | Regola |
|---|---|---|
| Identità | `numeroTreno`, `idOrigine`, `dataPartenzaTreno` | Devono coincidere con numero, origine e giorno chiesti, altrimenti «identità diversa» |
| Ritardo all'origine | `partenza_teorica` (o `programmata`), `partenzaReale` | Reale − programmato, troncato verso zero come `Duration.toMinutes` nell'app |
| Ritardo alle altre fermate | `arrivo_teorico` (o `programmata`), `arrivoReale` | Idem; un anticipo dà un ritardo ≤ 0 |
| Fermata soppressa | `actualFermataType` = 3 | Conta nei giorni soppressi, non nei campioni |
| Fermata straordinaria | `actualFermataType` = 2 | Ignorata: non ha orario programmato con cui confrontarsi |
| Corsa cancellata | `provvedimento` = 1, `tipoTreno` = `ST`, o tutte le fermate soppresse | Giorno noto e cancellato |

Ogni risposta è trattata come non attendibile: al più 1 MB, JSON UTF-8 rigoroso (niente `NaN`, chiavi duplicate o testo non JSON), al più 400 fermate, orari entro un giorno prima e tre dopo la mezzanotte del giorno, ritardi fra −60 e +720 minuti (oltre: scartati e contati). Nulla di ciò che arriva è eseguito; log e riepilogo della run mostrano solo conteggi (`log_safe`) e lo step di raccolta gira con i comandi del workflow sospesi (`::stop-commands::`). Non si usano `ritardoArrivo`, `ritardoPartenza` ed `effettiva`: l'app non li legge e il ritardo si ricava dagli orari.

## Cadenza, volume e limiti

| Voce | Valore |
|---|---|
| Cadenza | Ogni 2 ore (`41 */2 * * *`, solo dal ramo predefinito) e a ogni dispatch; una run alla volta (gruppo di concorrenza: al più una in attesa, nessuna annullata mentre gira) |
| Treni al giorno (pack di novembre 2026) | 6.350 circa da lunedì a venerdì, 5.290 il sabato, 3.570 la domenica; 5.800 in media |
| Prime richieste per run | Da 0–10 di notte a circa 900 nelle ore di punta (≤ 7,5 minuti a 2 richieste al secondo); la prima run con cache vuota fino a circa 1.550 |
| Richieste aggiuntive | Una per run per ogni treno in sospeso (ancora in viaggio, o senza rilevazione finale), fino a 3 oltre la prima nelle 8 ore |
| Limiti | 2 richieste al secondo al massimo, 15 s per richiesta, 3.000 richieste e 30 minuti per run (job: 45 minuti); stop dopo 20 errori consecutivi e subito con HTTP 403 o 429; i treni non chiesti restano in sospeso |

`python3 -I tools/collect_punctuality.py --estimate AAAA-MM-GG` ricalcola dal pack i treni di un giorno, senza rete.

## Conservazione

Le osservazioni grezze (una riga JSON compatta per treno e giorno: codice stazione, minuti, soppressione) restano nella **cache di GitHub Actions** con prefisso `punctuality-raw-`: ogni run ripristina la più recente (del proprio ramo o del ramo predefinito), aggiunge le nuove e salva una nuova voce con chiave unica. I giorni oltre i 35 sono eliminati a ogni run; GitHub elimina inoltre le voci non usate da 7 giorni. Le osservazioni non sono mai stampate nei log, caricate come artifact o committate. Le cache non sono scaricabili da chi visita il repository, ma un workflow eseguito nel repository (anche quello di una pull request, previa approvazione per chi contribuisce la prima volta) può ripristinarle: per questo contengono solo numeri derivati, mai risposte ViaggiaTreno.

## Il file `stats_v1.json.gz`

`tools/build_punctuality_stats.py` aggrega gli **ultimi 30 giorni completi** (fino al giorno prima della generazione) in `app/src/main/assets/punctuality/stats_v1.json.gz`: gzip deterministico (senza nome né data) di un unico oggetto JSON canonico.

```json
{"schema": "binario-punctuality-v1", "generatedAt": "2026-11-10T04:00:00Z",
 "windowStart": "2026-10-11", "windowEnd": "2026-11-09", "windowDays": 30,
 "source": "ViaggiaTreno, elaborazione Binario (non ufficiale)", "buckets": [0, 2, 5, 10, 15, 20, 30, 60],
 "trains": [{"n": "9558", "o": "S08409", "c": "FR", "d": 28, "x": 1,
             "s": [["S08409", 27, 0.8, [20, 24, 26, 27, 27, 27, 27, 27], 0],
                   ["S00219", 26, 4.6, [9, 14, 19, 23, 25, 26, 26, 26], 1]]}]}
```

- Un treno è identificato da numero `n` e origine `o` (stesso numero con origini diverse = treni diversi); i treni sono ordinati per numero e origine e compaiono se noti almeno un giorno. `c` è la categoria del pack, `d` i giorni noti (arrivato o cancellato), `x` quelli cancellati.
- Per fermata, in ordine di corsa e solo con almeno un campione: codice, campioni (giorni in cui il treno ha circolato e la fermata ha un orario reale), ritardo medio con un decimale (gli anticipi contano 0), conteggi cumulati dei campioni con ritardo ≤ ciascuna soglia (non decrescenti; oltre 60 minuti solo nei campioni), giorni di soppressione della fermata con il treno in circolazione.
- `--check FILE` verifica schema, codifica canonica, gzip deterministico, dimensioni (≤ 3 MB compressi), intervalli, cumulati monotoni e compatibili con la media, ordine e unicità. Con 9.000 treni e 14 fermate ciascuno il file resta sotto 1,5 MB.

**Soglie, come Treniamo.** In orario = ritardo ≤ 5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo; ≤ 15 minuti per tutte le altre categorie (REG, RV, IC, ICN, …). 5 e 15 sono soglie del file, quindi il conteggio è esatto. Sotto 5 campioni alla fermata considerata l'app non mostra nulla; per un margine di coincidenza fra due soglie interpola linearmente i cumulati.

## Applicazione con l'aggiornamento settimanale

Il lunedì, sullo stesso ramo del refresh degli orari ([GTFS_PACK_REFRESH.md](GTFS_PACK_REFRESH.md#aggiornamento-settimanale)):

```sh
gh api -X POST repos/fz1101hub/binario/actions/workflows/punctuality.yml/dispatches \
  -f ref=<ramo> -f 'inputs[print_stats]=true'
gh run list --workflow punctuality.yml --branch <ramo> --event workflow_dispatch --limit 1   # id della run
python3 tools/apply_ci_punctuality.py --run <run_id>     # --dry-run per il solo confronto
```

Il dispatch esegue anche una normale raccolta (in coda dietro a quella pianificata) e stampa il file in un unico blocco `BINARIO_BEGIN <nonce>` … `BINARIO_DONE <nonce> 1`. L'helper usa gli stessi controlli di `apply_ci_gtfs_refresh.py` (`tools/ci_log_frames.py`): `workflow_dispatch` di `punctuality.yml` sul ramo locale, `head_sha` uguale a `HEAD` o a un suo antenato, step riusciti fino a quello di stampa, blocco dentro la sua finestra oraria; poi accetta solo `punctuality/stats_v1.json.gz`, lo verifica con `--check`, rifiuta un file senza treni o con una finestra che termina prima di quella già inclusa (`--allow-older` dopo una verifica) e scrive l'asset. Non esegue commit: il file entra nel commit degli orari, o in `data(punctuality): statistics AAAA-MM-GG`.

Nelle prime settimane la finestra di 30 giorni è solo in parte coperta: `d` dice quanti giorni sono davvero osservati e la soglia dei 5 campioni tiene nascosti i treni con pochi dati.

## Limiti noti

- `actualFermataType` 2 è trattato come fermata straordinaria (non prevista): l'app lo mostra come fermata saltata insieme al 3, ma il significato non è documentato da Trenitalia.
- Alcuni regionali non hanno la rilevazione all'ultima fermata: senza `arrivoReale` né `arrivato` restano in sospeso e dopo 8 ore diventano «sconosciuti» (non contano).
- Le corse con origine all'estero non sono raccolte; un treno con lo stesso numero e origine ma orario diverso in giorni diversi resta una sola voce.
- Le run pianificate di GitHub possono slittare o saltare: con una run saltata si perdono i treni arrivati in circa 20 minuti fra le due finestre.
