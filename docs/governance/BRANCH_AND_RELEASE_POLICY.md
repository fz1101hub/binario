# Politica branch e release

## Scopo

Questa politica definisce il flusso minimo per modifiche che devono poter entrare in una release di **Binario**. Si applica al codice Android principale, al companion Expo, ai workflow GitHub Actions e agli asset dati inclusi nell'APK. Non sostituisce le verifiche funzionali e di sicurezza specifiche della release: rende invece esplicito il percorso con cui tali verifiche diventano requisiti ripetibili.

## Branch protetti e pull request

`main` è il ramo di integrazione ordinaria. Ogni ramo `stabilization/<versione>` è una linea di stabilizzazione della corrispondente release; per esempio, `stabilization/v0.20.4` contiene esclusivamente correzioni, verifiche e documentazione necessarie per quella versione. Le modifiche arrivano su entrambi i rami solo tramite pull request, con una descrizione che indichi lo scopo, i rischi e le verifiche eseguite. Una pull request destinata a una linea di stabilizzazione deve avere come base quella stessa linea e non deve includere cambiamenti non indispensabili alla release.

Le feature e le correzioni vivono in branch brevi e descrittivi. Il branch di una pull request non deve essere riutilizzato per un obiettivo diverso dopo che la revisione è iniziata. I conflitti si risolvono aggiornando il branch della pull request rispetto alla sua base, non modificando o ritargettando pull request non correlate. Il merge resta un atto di revisione esplicito: l'automazione non esegue merge né modifica le destinazioni delle pull request.

## Workflow CI obbligatori

I workflow verificano i branch di lavoro tramite `pull_request` con filtri per percorso. Il trigger `push` copre soltanto `main` e `stabilization/**`: il secondo assicura i gate post-merge sulla linea che produce la release. `integration/**` non ha trigger `push`, così un suo aggiornamento destinato a `main` non avvia anche una run equivalente alla pull request. I filtri includono anche contratti e strumenti condivisi consumati dal rail: per esempio, una modifica a `contracts/wear-snapshot/**` attiva sia Android/Wear sia Companion e Apple Watch. `workflow_dispatch` resta disponibile per un rerun esplicito dopo il ripristino dell'infrastruttura GitHub Actions.

| Gate | Ambito | Criterio di superamento |
| --- | --- | --- |
| Validazione Gradle Wrapper | Repository | Tutti i wrapper Gradle tracciati superano `gradle/actions/wrapper-validation`; file eseguibili e JAR non validi bloccano la build. |
| Input CI revisionati | Repository | GitHub Actions a SHA completo, immagini OCI a digest, runtime patch-pinned, runner in allowlist versionata e download XcodeGen con SHA-256; `tools/verify_ci_pinning.py` blocca riferimenti o trigger mobili/incompleti. |
| Dipendenze Gradle verificate | Android/Wear e Companion | I lock root e Companion contengono un SHA-256 revisionato per ogni artifact e metadata risolto; il prebuild copia il lock Companion sorgente senza modificarlo, mentre verifier statici e Gradle strict bloccano assenze, alterazioni e trust bypass. |
| Integrità asset Android | Android | I pacchetti GTFS, `manifest.json`, `SHA256SUMS` e il catalogo stazioni concordano per nomi, hash, struttura e conteggi. |
| Freschezza asset GTFS | Android | Ogni `lastServiceDate` è almeno 30 giorni oltre la data del gate. Il workflow esegue questo controllo senza `--integrity-only`, separatamente dal controllo di integrità. Un feed scaduto o troppo vicino alla scadenza richiede refresh, rigenerazione degli hash e revisione prima della release. |
| Test, lint e build Android | Android | I test unitari debug, lint debug, lint release di `app` e `wear` e assemble debug completano senza errori. L'unica eccezione lint ammessa è `OldTargetApi`, documentata in modo identico nei due moduli finché la toolchain resta su API 36. |
| Android App Bundle | Android/Wear | `bundleRelease` produce due AAB separati; il verifier controlla struttura, DEX, CRC, percorsi, limiti, assenza di firma e SHA-256. Dal manifest protobuf compilato verifica anche package, `versionName` e `versionCode`; dai program header ELF verifica l'allineamento a 16 KB dei segmenti `PT_LOAD` delle librerie native a 64 bit. Prima della firma, due full rebuild con `--rerun-tasks` devono essere byte-identiche. |
| Companion managed | Companion Expo | Dipendenze congelate, TypeScript, test e verifiche di configurazione/release completano senza errori. |
| Prebuild companion | Companion Expo | `expo prebuild --platform android --clean --no-install` non modifica `android/`; una seconda esecuzione pulita produce lo stesso albero tracciato. |
| Android App Bundle Companion | Companion Expo/Wear | Una release identity separata fissa `im.binario.companion`, `versionName` e i due `versionCode`. Il workflow produce due AAB unsigned e il verifier confronta i rispettivi manifest protobuf con tale identità canonica. Il packaging single-worker deve consentire due full rebuild byte-identiche prima della firma. |

I workflow fissano ogni GitHub Action a un commit SHA completo, ogni immagine di servizio a un digest OCI, ogni runtime installato a una patch revisionata e i runner a una label versionata allowlistata. Gli strumenti scaricati direttamente, come XcodeGen, hanno versione e SHA-256 verificati; ogni modifica della relativa trust chain attiva anche il rail macOS che esegue il binario. Il commento accanto allo SHA conserva la release leggibile; Dependabot propone gli aggiornamenti, ma non esiste auto-merge. Ogni aggiornamento di questi input è una modifica di dipendenza e deve rieseguire i gate nella stessa pull request. `python3 tools/verify_ci_pinning.py` rende la regola fail-closed.

## Procedura di release

1. Aprire o aggiornare `stabilization/<versione>` dalla revisione approvata di `main`; limitare le modifiche a correzioni di release, gate e documentazione direttamente necessaria.
2. Per ogni modifica, aprire una pull request verso la linea di stabilizzazione corretta e attendere i gate CI applicabili. Eseguire localmente i comandi dei gate coinvolti prima della revisione.
3. Prima del tag, verificare gli asset embedded con `python3 tools/verify_embedded_assets.py --min-valid-days 30`. Se un pacchetto GTFS è scaduto o entra nella finestra di 30 giorni, aggiornare l'asset, il manifest e `SHA256SUMS` nella stessa pull request oppure escluderlo in modo sicuro dalla release.
4. Registrare esito, vincoli residui e prove nel changelog e nella documentazione di stabilizzazione della versione. Il tag viene creato soltanto dalla revisione approvata della linea di stabilizzazione, dopo l'esito verde dei gate richiesti.
5. Dopo la release, riportare su `main` soltanto correzioni e documentazione che devono restare nella linea di sviluppo, tramite pull request separata e revisionata.

## Documenti storici

I report storici alla radice non vengono spostati automaticamente. I manifest di consegna sotto `archives/session-versions/manifests/` ne registrano i percorsi originali come contenuto storico. Spostarli senza rigenerare e verificare tali manifest romperebbe la corrispondenza tra una consegna archiviata e il suo inventario. Quando si pianificherà un archivio strutturale, il cambiamento dovrà aggiornare insieme i riferimenti, i manifest interessati e una tabella di reindirizzamento verificata; fino ad allora i percorsi vengono preservati deliberatamente.

## Verifiche locali

```bash
# Asset Android: prima integrità e struttura, poi freshness release a 30 giorni.
python3 tools/verify_embedded_assets.py --integrity-only
python3 tools/verify_embedded_assets.py --min-valid-days 30
python3 tools/verify_ci_pinning.py
python3 tools/verify_gradle_dependency_metadata.py
python3 tools/verify_gradle_dependency_metadata.py --profile companion

# Android principale (richiede JDK 17 e Android SDK configurato).
./gradlew --no-daemon --dependency-verification=strict testDebugUnitTest lintDebug assembleDebug bundleRelease
python3 tools/verify_android_app_bundles.py

# Companion Expo.
cd companion-expo
pnpm install --frozen-lockfile
pnpm run check
pnpm test
pnpm run verify:managed-config
pnpm exec expo prebuild --platform android --clean --no-install
git diff --exit-code -- android
pnpm exec expo prebuild --platform android --clean --no-install
git diff --exit-code -- android
```

La verifica asset usa la data corrente per impostazione predefinita. Per riprodurre una verifica storica senza mascherare la release corrente, è possibile passare una data esplicita solo a fini diagnostici, ad esempio `--today 2026-08-15`.
