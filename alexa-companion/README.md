# Binario Alexa Companion

`alexa-companion/` contiene la **companion server-side per la Custom Skill Alexa di Binario**. L'app Android in `app/` resta il prodotto principale e la fonte di verità del dominio ferroviario e delle preferenze utente.

## Responsabilità

Il companion gestisce:

- webhook HTTPS della Custom Skill Alexa;
- risposte sulla puntualità di un treno o di una tratta salvata e sul treno più puntuale di una tratta nelle prossime tre ore, dalle statistiche non ufficiali di Binario su dati ViaggiaTreno incluse nel bundle ([`docs/ALEXA_SETUP.md`](docs/ALEXA_SETUP.md#statistiche-di-puntualità));
- prossimi treni di una tratta salvata o fra due stazioni dette a voce, e corsa programmata di un numero di treno, dall'orario programmato che l'iPhone include (lo stesso indice, costruito dai pack di Android), detti sempre come orari programmati, senza binario e senza stato in tempo reale; come nelle app, anche le corse di metro, tram, funicolari e Marconi Express che partono da una fermata a pochi minuti a piedi dalla stazione, con la fermata e i minuti a piedi («prossimi treni da Bologna Centrale a Bologna Aeroporto») ([`docs/ALEXA_SETUP.md`](docs/ALEXA_SETUP.md#orario-programmato));
- schermate Echo Show (APL) che proiettano le stesse risposte vocali, solo sui dispositivi che dichiarano APL;
- account linking OAuth 2.0 con consenso esplicito e protezione CSRF;
- revoca e nuovo collegamento dell'account;
- profilo vocale minimo, versionato e autenticato;
- dashboard tecnica per collegamento, revoca e diagnostica;
- modello di interazione Alexa conservato in `skill-package/`.

Non è una seconda app Binario, non sostituisce l'app smartphone e non mantiene una fonte ferroviaria indipendente. Non espone proxy di storage, upload, notifiche generiche al proprietario o pagine showcase del template WebDev.

## Fonte dei dati

Le risposte Alexa devono derivare dallo stesso contratto dati dell'app principale o da snapshot sincronizzati che includano provider, timestamp, TTL e stato operativo. In assenza di una fonte autorizzata e fresca, la risposta resta fail-closed: **“dati non disponibili in tempo reale”**. Le domande di puntualità usano invece il file settimanale delle statistiche dell'app (`app/src/main/assets/punctuality/stats_v1.json.gz`), che la build copia in `dist/data/` e verifica; senza file la skill dice che le statistiche non sono ancora disponibili. I prossimi treni e la corsa di un numero vengono dall'orario programmato dell'iPhone (`ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib` con i suoi metadati), copiato e verificato allo stesso modo in `dist/data/timetable/`: la skill dice sempre che gli orari sono programmati e che lo stato in tempo reale è nell'app, e non interroga mai ViaggiaTreno dal server.

Wear OS e Apple Watch sono companion separati. Le loro preferenze locali non fanno parte dell'API pubblica Alexa.

## Stato

Il servizio dispone di hardening per build di produzione, cookie di sessione `HttpOnly`, consenso OAuth, allowlist dei redirect, CSRF, revoca effettiva, migrazioni versionate, limiti di richiesta e health check. Il boot di produzione richiede endpoint OAuth HTTPS validi, incluso `VITE_OAUTH_PORTAL_URL` usato dalla dashboard. Alexa richiede una modalità esplicita: `ALEXA_ENABLED=false` senza valori Alexa oppure `ALEXA_ENABLED=true` con configurazione completa; il boot di produzione rifiuta stati intermedi. La skill resta non pubblicabile finché non sono completati configurazione Amazon, test end-to-end, privacy policy e collegamento dati autenticato con l'app principale.

## Sviluppo

```bash
pnpm install --frozen-lockfile
pnpm run check
pnpm test
pnpm run build
```

Il comando di build verifica anche che il bundle di produzione non contenga runtime o token di anteprima Manus, che le copie in `dist/data/` delle statistiche di puntualità e del catalogo stazioni coincidano con i file di `app/src/main/assets/`, e che l'indice orario copiato in `dist/data/timetable/` coincida con quello di `ios/Resources/` e sia il file che i suoi metadati descrivono (la build va quindi eseguita dal checkout completo del repository).

Il server richiede **Node 22.13 o successivo** (`engines` di `package.json`; la CI usa 22.23.2): l'indice orario si legge con il modulo integrato `node:sqlite`, disponibile senza flag da Node 22.13. Node lo segnala ancora come sperimentale e stampa una sola volta `ExperimentalWarning: SQLite is an experimental feature` all'avvio; nessuna dipendenza npm è stata aggiunta.

In esecuzione `GET /healthz` dice soltanto che il processo risponde; `GET /readyz` risponde 200 se il database risponde entro 3 secondi e, con la skill configurata, statistiche e catalogo di `dist/data/` sono leggibili e l'indice orario di `dist/data/timetable/` supera i controlli, altrimenti 503 con lo stato di ciascuno (`database`, `railData`, `timetable`), così il proxy o un monitor vedono un deploy difettoso. Su SIGTERM o SIGINT il server smette di accettare connessioni, lascia finire fino a 10 secondi le richieste in corso (risposte Alexa, scambi di token), chiude il pool del database ed esce.

`pnpm run check` controlla i tipi sia del codice sia dei test (`tsconfig.test.json`): Vitest da solo non li controlla.

Il database è **MariaDB** (verificato in CI) o **TiDB**; MySQL 8 non è supportato dalle migrazioni e `pnpm db:migrate` lo rifiuta prima di iniziare (vedi [`docs/DATABASE_MIGRATIONS.md`](docs/DATABASE_MIGRATIONS.md)).

La suite standard non richiede un database locale. La verifica reale delle migrazioni, eseguita dal workflow CI con MariaDB, è opt-in anche in locale:

```bash
RUN_DB_MIGRATION_TESTS=1 DATABASE_URL=... pnpm run test:db-migrations
```

Il test crea database effimeri, applica la catena 0000→0013 a un database vuoto, adotta un database storico 0000→0011 e la breve catena Alexa 0000→0003, verifica revoca e rotazione dei token OAuth, poi li elimina.

## Documentazione

- [`docs/ALEXA_SETUP.md`](docs/ALEXA_SETUP.md): configurazione e pubblicazione Alexa.
- [`docs/DATABASE_MIGRATIONS.md`](docs/DATABASE_MIGRATIONS.md): motori supportati e catena delle migrazioni.
- [`docs/ECHO_SHOW_APL_UI_SPEC.md`](docs/ECHO_SHOW_APL_UI_SPEC.md): schermate Echo Show (APL), implementate ma attive solo dopo l’abilitazione dell’interfaccia APL da parte del titolare (vedi `ALEXA_SETUP.md`).
- [`docs/ANDROID_COMPANION_CONTRACT.md`](docs/ANDROID_COMPANION_CONTRACT.md): relazione tra app principale e companion.
- [`docs/REMOTE_PROFILE_SYNC.md`](docs/REMOTE_PROFILE_SYNC.md): contratto del profilo vocale remoto.
- [`../docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`](../docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md): decisione architetturale.
- [`../docs/architecture/TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md`](../docs/architecture/TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md): destinazione delle funzioni più avanzate che non appartengono ad Alexa.
