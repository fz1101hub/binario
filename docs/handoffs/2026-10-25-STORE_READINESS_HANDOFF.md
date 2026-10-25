# Passaggio di consegne: preparazione alla pubblicazione sugli store (25 ottobre 2026)

Stato del branch `claude/git-repo-audit-cdcsm6` al momento dell'interruzione del lavoro autonomo. Il file
`2026-10-25-store-readiness-remaining.json` accanto a questo documento elenca in forma strutturata i pacchetti
di correzioni non ancora integrati e le 222 segnalazioni degli audit precedenti ancora da ricontrollare.

## Fatto e integrato nel branch

- Integrazione del lavoro upstream `fix/live-delay-search` (47 commit) sopra le correzioni precedenti.
- Round 1 Android: pacchetto `android-data` (13 correzioni al livello dati: calendario RFI, periodicità,
  fallback GTFS, `lastServiceDate` reale, resilienza parser) e pacchetto `android-l10n` (7 correzioni: testi di
  widget, Wear e messaggi UI resi localizzabili tramite chiavi invece di italiano fisso).
- Round 2 store: pacchetto `ci-tools-docs` (artefatti AAB in CI, job di firma su dispatch, verifica `--signed`,
  versionCode Wear separato, allineamento ELF 16 KB, `docs/store/PLAY_SUBMISSION.md`,
  `docs/store/PLAY_DATA_SAFETY.md`, icona Play 512) e pacchetto `appstore` (icona Watch 1024, chiave di
  export compliance, firma automatica con `BINARIO_APPLE_TEAM_ID`, esclusione Mac/Vision, harness Watch in
  Swift 6, step di archive Release in CI, elenco licenze feed nelle impostazioni iOS, PRIVACY.md per iOS e
  Watch, `docs/store/APP_STORE_SUBMISSION.md`).
- Correzioni CI dopo i merge: lint `AndroidGradlePluginVersion` disattivato con motivazione, override delle
  dipendenze npm con advisory, testability del target Watch nel progetto integrato, codec del widget su
  `java.util.Base64` (il test falliva solo con lo stub `android.jar` di Gradle).
- Esito CI all'ultimo controllo: GTFS verde; Android CI e Apple Watch CI in esecuzione sui due ultimi fix;
  Companion e Alexa verdi sull'ultimo commit che li riguarda.

## Non integrato

- Pacchetto `wear` (7 correzioni Play per Wear OS): implementato con test sul branch `fix/r2-wear`, pushato su
  origin come backup, NON revisionato e non integrato. Va rebasato su `claude/git-repo-audit-cdcsm6`, validato
  (test unitari app e wear, `python3 -m unittest discover -s tests`) e revisionato prima del merge.
- Pacchetto `android-vm` (15 correzioni a `TrainViewModel`, `MainActivity`, widget, backup, limiti): non iniziato.
- Pacchetto `play-app` (7 correzioni Play per l'app telefono): non iniziato; tocca gli stessi file di `android-vm`,
  quindi va eseguito dopo.
- Audit indipendente finale di tutto il repository (aree: dati Android, UI Android, piattaforma Android, Wear,
  app iOS, Apple Watch, Companion, Alexa; lenti: localizzazione, privacy e documentazione, CI e release, flussi
  end-to-end, sicurezza; più ricontrollo delle 222 segnalazioni precedenti): non eseguito.
- Documentazione di release definitiva e rapporto finale: non prodotti; le checklist in `docs/store/` sono la base.

## Azioni che spettano al proprietario

- Keystore di upload Android e segreti `BINARIO_RELEASE_STORE_FILE`, `BINARIO_RELEASE_STORE_PASSWORD`,
  `BINARIO_RELEASE_KEY_ALIAS`, `BINARIO_RELEASE_KEY_PASSWORD` (vedi `docs/store/PLAY_SUBMISSION.md`).
- Team ID Apple (`BINARIO_APPLE_TEAM_ID`), account App Store Connect, App ID e App Group dei target Watch
  (vedi `docs/store/APP_STORE_SUBMISSION.md`).
- URL pubblico della privacy policy e compilazione delle schede store con i testi proposti in `docs/store/`.
- Refresh dei pack GTFS prima della scadenza del 2026-12-12 (branch `data/refresh-live-gtfs-38842400398`,
  procedura in `docs/data-sources/GTFS_PACK_REFRESH.md`), poi rigenerazione dell'indice iOS con
  `tools/build_ios_gtfs_schedule.py`.
- Decidere se la build di release deve usare le fonti realtime personali (`PERSONAL_REALTIME_SOURCES`, oggi
  attivo anche in release) e allineare di conseguenza `docs/store/PLAY_DATA_SAFETY.md` e PRIVACY.md.
- Portare il branch su `main`, che oggi è una linea separata senza questo lavoro, tramite pull request.
