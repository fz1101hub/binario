# Materiale di lavoro della sessione del 25 ottobre 2026

Copia di tutto ciò che esisteva solo nel container della sessione di preparazione agli store e che sarebbe
andato perso al suo riciclo. Il contesto e lo stato del lavoro sono in `../2026-10-25-STORE_READINESS_HANDOFF.md`.
I percorsi assoluti sotto `/tmp/claude-0/...` citati nei file sono quelli del container originale.

## `android-jvm-harness/`

Compila tutti i sorgenti Kotlin/Compose di `app/` e `wear/` ed esegue i loro test unitari JVM senza Android SDK,
senza Gradle e senza accesso a `dl.google.com`, usando i jar desktop di Compose Multiplatform e stub scritti a mano
per androidx, Play Services e Wear. Dettagli e limiti in `android-jvm-harness/README.md`.

Uso: copiare la cartella fuori dal repository (lo script scrive `lib/`, `cache/`, `build/` e `out/` accanto a sé),
poi `./bootstrap.sh` per riscaricare le dipendenze da Maven Central secondo `deps-lock.json`, infine
`REPO=/percorso/del/checkout ./android-check.sh all`. Richiede JDK 17 e il compilatore Kotlin 2.0.21 incluso nella
distribuzione Gradle 8.14.x. Non sostituisce la CI: non esegue aapt, lint o R8 e usa la piattaforma Robolectric
`android-all`, che esegue codice reale dove `android.jar` di Gradle lancia "not mocked" (vedi il fix del codec del
widget del 25 ottobre). `mariadb-up.sh` avvia il MariaDB locale per i test di migrazione di `alexa-companion`.

## `workflow-scripts/`

- `fix-round.js`: per ogni pacchetto un implementatore in un worktree dedicato, un revisore avversariale e una
  revisione se il verdetto richiede modifiche. Argomenti: `{round, base, packagesPath, packages:[{id, area, suites}]}`.
- `audit.js`: audit indipendente finale preparato ma non eseguito: revisori per area e per lente, scettici
  avversariali, ricontrollo delle segnalazioni precedenti a blocchi, critico di completezza. Argomenti:
  `{mode: "find"|"critic", keys:[...], lowChunks:{...}, skeptic}`; i blocchi sono in
  `fix-packages/previous-findings-chunks.json`.
- `store-review.js`: revisione di prontezza per gli store, una dimensione per agente.

## `fix-packages/`

- `round1.json`, `round2.json`: i pacchetti di correzioni con testo della segnalazione, sintesi del fix, file e
  piano di verifica. Integrati: `android-data`, `android-l10n`, `ci-tools-docs`, `appstore`. Da fare: `wear`
  (implementato sul branch `fix/r2-wear`, non revisionato), `android-vm`, `play-app`.
- `previous-findings-2026-09-30.json`: le 272 segnalazioni degli audit precedenti estratte dal rapporto di
  integrazione del 30 settembre (branch `docs/audit-integration-20260930`); 222 non sono coperte dai pacchetti.
- `previous-findings-chunks.json`: le 222 suddivise per area, come input di `audit.js`.

## `results/`

- `workflow-*.json`: rapporti degli implementatori (commit, esito per voce, test) e verdetti dei revisori per ogni
  pacchetto eseguito. `workflow-r2-wear.json` contiene solo l'implementazione: la revisione è stata interrotta.
- `store-review-appstore-ios.json`: le 17 segnalazioni della revisione App Store da cui nasce il pacchetto `appstore`.
- `wear-review-partial-android-check.log`: l'ultimo esito dell'harness sul branch Wear durante la revisione interrotta.
