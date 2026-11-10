# Azioni e decisioni del titolare

Stato al 3 novembre 2026, dopo l'audit indipendente finale. Il codice di tutte le piattaforme è su `main` (pull request #45); ogni modifica successiva arriva con una pull request; qui restano solo le cose che il repository non può fare da sé. Le checklist complete per gli store restano in [PLAY_SUBMISSION.md](store/PLAY_SUBMISSION.md) e [APP_STORE_SUBMISSION.md](store/APP_STORE_SUBMISSION.md).

## 1. Decisioni aperte

| # | Decisione | Perché conta | Dove è descritta |
|---|---|---|---|
| D1 | **Repository pubblico e pack personali.** Tenere `app/src/personal/assets/` fuori dall'albero pubblico con un secondo repository privato (consigliato), oppure rendere privato `fz1101hub/binario`. Un repository privato col piano gratuito ha 2.000 minuti di CI al mese e i minuti macOS valgono dieci volte tanto: le CI Android e Apple li esaurirebbero in pochi giorni, mentre per un repository pubblico sono illimitati. | I pack `gtfs-personal/` hanno licenza non commerciale o non dichiarata e oggi sono leggibili da chiunque. Finché il repository è pubblico, l'aggiornamento settimanale **non** rinnova questi pack: Roma Servizi per la Mobilità scade il **9 dicembre 2026**. | [SOURCE_POLICY.md](data-sources/SOURCE_POLICY.md), audit A7-04 |
| D2 | **Origine e licenza del catalogo stazioni** (`app/src/main/assets/stations/`). | `stations/manifest.json` riporta «Da confermare dal titolare». Se deriva da OpenStreetMap va ripristinata la nota ODbL sulla riga del catalogo. | [CURATED_STATION_CATALOG.md](data-sources/CURATED_STATION_CATALOG.md), audit A7-09 |
| D3 | **Licenze di cinque pack principali**: `ch-otd-gtfs`, `hzpp-gtfs`, `lombardia-trenord-gtfs`, `sncf-gtfs`, `trentino-gtfs`. | `verify_embedded_assets.py` le segnala come avviso. Dopo la conferma registrare ciascuna licenza in `app/src/main/assets/gtfs/manifest.json` e in `ios/Resources/binario_gtfs_source_policy_v1.json` (cinque voci `null`, le stesse cinque), poi rigenerare i crediti Android (`tools/build_android_gtfs_credits.py`) e l'indice iOS (`tools/build_ios_gtfs_schedule.py`). | [PLAY_SUBMISSION.md](store/PLAY_SUBMISSION.md) §0 |
| D4 | **Fonti in tempo reale personali nella build store** (oggi `true`, fissato in `release-sign`). | Passare a `false` richiede di riscrivere insieme `PLAY_DATA_SAFETY.md` e il listing. | `tools/verify_android_release_config.py` |
| D5 | **Licenze delle reti urbane in tempo reale** (Roma, ANM, ACTV, Cotral, GTT). | Restano solo nelle build personali finché non c'è una licenza utilizzabile. | [SOURCE_POLICY.md](data-sources/SOURCE_POLICY.md) |
| D6 | **Soglie del pianificatore** scelte nell'audit: attesa lunga 120 min, aggancio delle fermate entro 150 m, 5 min di tolleranza fra le copie dello stesso treno in pack diversi. Facoltativo: paginazione «Più tardi / Più presto» e il messaggio «nessun treno fino alle HH:mm». | Valori ragionevoli, modificabili in `data/journey/`. | `CHANGELOG.md`, voce dell'audit |
| D7 | **Invocazione italiana Alexa**: «binario treni» (attuale) oppure «binario» con prova del marchio. | Amazon accetta un'invocazione di una sola parola solo per un marchio. | `alexa-companion/docs/ALEXA_SETUP.md` |
| D8 | **Locale Alexa aggiuntivi** `en-CA`, `en-AU`, `es-MX`, `fr-CA`. | Richiedono i paesi di distribuzione CA, AU e MX. | [APPLE_ALEXA_LOCALES.md](l10n/APPLE_ALEXA_LOCALES.md) |

## 2. Azioni manuali

### Dati orari

- **ÖBB**: il pack attuale vale fino al 12 dicembre 2026, e dal 13 novembre il controllo dei 30 giorni blocca già `release-sign` e l'archivio iOS. Appena ÖBB pubblica l'orario 2027, scaricare dal portale dei dati aperti ÖBB lo zip «GTFS Fahrplan» (serve l'accettazione delle condizioni, per questo nessun job lo scarica) e ricostruire il pack in locale: `python3 tools/build_gtfs_packs.py --only oebb-gtfs --source-archive oebb-gtfs=/percorso/GTFS_Fahrplan_2027.zip --generated-at AAAA-MM-GG`. Se il file ha un nuovo nome, aggiornare prima `sourceUrl` di `oebb-gtfs` nel manifest, nella policy iOS e in `RailSourceCatalog.kt`; poi la data in `EmbeddedGtfsPacks.kt`, i controlli e l'indice iOS ([GTFS_PACK_REFRESH.md](data-sources/GTFS_PACK_REFRESH.md), «ÖBB (manuale)»). Nessuno strumento legge un allegato di release.
- **Svizzera**: il pack è registrato su un file datato (`gtfs_fp2026_2025-08-14.zip`), quindi il refresh riscarica sempre lo stesso archivio; per l'orario 2027 serve un nuovo URL da rivedere (manifest, policy iOS e `RailSourceCatalog.kt`), come per ÖBB.
- **Cambio orario del 13 dicembre**: STA, Svizzera, ÖBB, Trenord, TFT, HŽPP, Italo e Mi.Co.Tra. finiscono il 12 dicembre. L'aggiornamento settimanale del lunedì li rinnova appena gli editori pubblicano il nuovo orario; finché non succede, dal 13 novembre il controllo dei 30 giorni blocca `release-sign` e l'archivio iOS della CI Apple (i test restano visibili), e dal 2 dicembre anche ARST, EAV e gran parte delle reti urbane. Dettagli in [GTFS_PACK_REFRESH.md](data-sources/GTFS_PACK_REFRESH.md).
- **Release bozza «Confronto-app-1»** (id 431066971): contiene l'APK di Moovit caricato per il confronto; eliminarla da GitHub › Releases (la sessione non può cancellare release).

### Google Play (dettagli in [PLAY_SUBMISSION.md](store/PLAY_SUBMISSION.md))

- Verifica dell'identità dell'account e, per un account personale, test chiuso con almeno 12 tester per 14 giorni.
- Pubblicare `PRIVACY.md` a un URL stabile con **titolare e recapito**, e collegarlo nell'app e nella Console.
- Keystore di upload e i quattro secret di firma **nell'environment protetto `release`**, non nel repository: in Settings › Environments creare `release` con il titolare come revisore obbligatorio e i soli rami `main` e `stabilization/*`, aggiungervi i quattro secret, eliminare quelli del repository e proteggere i due rami ([RELEASE_BUILD.md](RELEASE_BUILD.md), «Environment `release`»). Poi dispatch con `sign_release=true` su `main` o `stabilization/*` e approvazione della run.
- Dichiarazione del servizio in primo piano di tipo `location`, con video, e risposte di Sicurezza dei dati da [PLAY_DATA_SAFETY.md](store/PLAY_DATA_SAFETY.md).
- **Sveglie esatte**: il manifest dichiara già `SCHEDULE_EXACT_ALARM` e l'app ha il pulsante «Consenti sveglie esatte»; decidere se citarlo nelle note per i revisori.

### App Store (dettagli in [APP_STORE_SUBMISSION.md](store/APP_STORE_SUBMISSION.md))

- Account Apple Developer e Team ID per `ios/Scripts/generate-project.sh`, archivio e caricamento da Xcode (o `xcodebuild`), TestFlight; risposta App Privacy «Data Not Collected».

### Alexa (dettagli in `alexa-companion/docs/ALEXA_SETUP.md`)

- Nella console: schema di autenticazione **HTTP Basic**, APL attivo, account di prova per i revisori al posto di `REPLACE_WITH_REVIEWER_ACCOUNT_AT_SUBMISSION`.
- Revisione legale di `/terms`; su `/privacy` inserire identità e recapito del titolare e i tempi di conservazione di log e backup.
- Controllo visivo delle icone generate (`client/public/alexa/`).

## 3. Verifiche su dispositivo

La CI compila e prova tutto ciò che si può provare senza hardware. Restano da provare su dispositivi reali, seguendo [PROTOCOLLO_COLLAUDO_ANDROID_WEAR_2026-09-03.md](PROTOCOLLO_COLLAUDO_ANDROID_WEAR_2026-09-03.md):

- **Wear OS**: richiesta di aggiornamento al telefono, conto alla rovescia della complicazione, tocco su Tile e complicazione, TalkBack.
- **Apple Watch**: complicazione con `containerBackground` e cambio a ogni partenza, pulsante «Aggiorna» con l'iPhone in background, selettore dell'ora in ora italiana.
- **Android**: sveglie dopo riavvio e aggiornamento, nuovo canale «Cambi del viaggio seguito», GPS a basso consumo, «Copia salvata» senza rete.
- **Traduzioni**: una lettura di madrelingua per i testi nuovi, in particolare birmano e thai.
