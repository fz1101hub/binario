# Build release Android

I moduli Android canonici sono `app` e `wear`. Entrambi leggono il `versionName` dalla proprietà non sensibile canonica `BINARIO_VERSION_NAME` in `gradle.properties` e il `versionCode` dalla stessa sorgente `BINARIO_APP_VERSION_CODE`: `app` lo usa così com'è, `wear` lo sposta nel proprio intervallo sommando `wearVersionCodeOffset = 1_000_000` (`wear/build.gradle.kts`). Per la release `v0.20.4` i valori sono `35` per il telefono, `1000035` per Wear e `0.20.4`. Google Play richiede `versionCode` distinti fra i form factor dello stesso listing e rifiuta un codice già caricato in precedenza: gli intervalli disgiunti (`1..999999` per il telefono, `1000001..1999999` per Wear) rendono impossibile una collisione anche quando l'override `-PBINARIO_VERSION_CODE=…` sostituisce il codice telefono per una build coordinata, perché Wear applica sempre l'offset al valore risolto. Non passare mai `-PBINARIO_VERSION_CODE` per un upload Play: si incrementa `BINARIO_APP_VERSION_CODE` a ogni release. `tools/verify_android_release_config.py` rifiuta una proprietà `BINARIO_WEAR_VERSION_CODE` residua, un offset diverso e un codice telefono fuori dal proprio intervallo.

Le varianti `release` eseguono R8 e resource shrinking. Le regole locali preservano in modo prudente i tipi generati protobuf/GTFS-Realtime, jsoup e le callback del Data Layer Wear. Questa scelta privilegia l'affidabilità della prima build minificata; potrà essere ristretta soltanto dopo prove su dispositivi rappresentativi.

## Build CI unsigned

La build release non richiede né cerca chiavi nel repository. In assenza della configurazione di firma, i comandi seguenti producono APK release unsigned, adatti alla verifica CI:

```sh
./gradlew --no-daemon :app:assembleRelease :wear:assembleRelease \
  :app:bundleRelease :wear:bundleRelease \
  :app:lintVitalRelease :wear:lintVitalRelease \
  :app:lintRelease :wear:lintRelease \
  --dependency-verification=strict
python3 tools/verify_android_app_bundles.py
```

## Artifact per Google Play

Google Play richiede Android App Bundle (AAB) per le nuove app. Un bundle è un formato di pubblicazione dal quale Play genera APK ottimizzati; non sostituisce un APK installabile direttamente.[2] Il gate produce `app-release.aab` e `wear-release.aab` separati, poi verifica ZIP, DEX, CRC, percorsi, duplicati, limiti dimensionali e SHA-256. Il manifest protobuf compilato viene letto direttamente: package, `versionName` e `versionCode` devono coincidere con la configurazione release canonica. Il verifier enumera inoltre le librerie native `*/lib/<abi>/*.so` (Compose UI porta `androidx.graphics:graphics-path`), legge i program header ELF senza strumenti esterni e richiede che ogni segmento `PT_LOAD` delle ABI a 64 bit (`arm64-v8a`, `x86_64`) sia allineato ad almeno 16 KB (`p_align >= 0x4000`), come richiesto da Google Play per i dispositivi con pagine da 16 KB;[4] le librerie a 32 bit vengono solo validate, perché quei dispositivi eseguono esclusivamente codice a 64 bit. L'elenco delle ABI presenti viene stampato nel `PASS`. Gli artifact restano unsigned finché non vengono fornite le credenziali di firma.

Il modulo Wear usa lo stesso package dell'app telefono, dichiara `android.hardware.type.watch` e `standalone=false`, ma conserva un proprio `versionCode` nell'intervallo dedicato. Google indica che l'app Wear viene caricata e aggiornata separatamente e raccomanda codici versione indipendenti tra form factor.[3] La scelta del track Play, la firma e l'upload restano azioni proprietarie esterne a questo gate.

### AAB del Companion separato

`companion-expo/` non sostituisce l'app smartphone principale. Mantiene una propria identità release in `native/android/release-identity.json`: telefono e Wear Companion condividono `im.binario.companion` e `versionName`, ma usano `versionCode` distinti. Il prebuild applica questa identità sia alla configurazione Expo sia al modulo Wear generato. Il workflow Companion produce i due AAB unsigned e verifica il loro manifest compilato con un profilo dedicato:

```sh
cd companion-expo
CI=1 pnpm exec expo prebuild --platform android --clean --no-install
cd android
./gradlew --no-daemon --dependency-verification=strict \
  :app:bundleRelease :wear:bundleRelease
cd ../..
python3 tools/verify_android_app_bundles.py --profile companion
```

Il gate blocca una divergenza fra il file canonico, il progetto generato e gli AAB effettivi. Il prebuild fissa inoltre `org.gradle.parallel=false` e `org.gradle.workers.max=1`: senza questa serializzazione, il contenuto dell'AAB telefono era identico ma l'ordine dei member ZIP poteva cambiare. Con il packaging single-worker, la prova completa precedente aveva prodotto 50.988.125 byte e SHA-256 `29ee0716464decf69cae7961c50f8e4b141a5079a58cd5149cb6f271a84104e0` per il telefono Companion. Dopo la correzione accessibilità Wear, due nuove full rebuild del modulo Wear hanno prodotto un AAB byte-identico da 4.309.214 byte e SHA-256 `3611c8218ca27dcb5868ac558269b979af6a27d8248c9319c376cd359063698a`. Prima della firma finale va comunque rieseguito il gate congiunto sui due artifact dell'esatto SHA candidato. Firma e distribuzione restano separate da quelle dell'app principale.

## Prova di riproducibilità degli AAB

Prima della firma, due build complete devono produrre artifact byte-identici. Entrambe le esecuzioni devono usare `--rerun-tasks`: confrontare una full rebuild con un output incrementale precedente non prova la riproducibilità della stessa procedura. Il verifier rifiuta anche firme JAR inattese, così il confronto riguarda gli artifact unsigned prodotti dal codice revisionato.

```sh
./gradlew --no-daemon --rerun-tasks --dependency-verification=strict \
  :app:bundleRelease :wear:bundleRelease
cp app/build/outputs/bundle/release/app-release.aab /tmp/binario-app-reference.aab
cp wear/build/outputs/bundle/release/wear-release.aab /tmp/binario-wear-reference.aab

./gradlew --no-daemon --rerun-tasks --dependency-verification=strict \
  :app:bundleRelease :wear:bundleRelease
python3 tools/verify_android_app_bundles.py \
  --reference-phone /tmp/binario-app-reference.aab \
  --reference-wear /tmp/binario-wear-reference.aab
```

Nella validazione post-audit del 24 settembre 2026, due full rebuild locali da 98 task ciascuna sono risultate byte-identiche. Il telefono produce 18.495.638 byte con SHA-256 `1ed739459f9cdd3065426a5e28f6dda253adb88bccb72a71823c2de188e2569a`; Wear produce 3.894.315 byte con SHA-256 `f319c81cbaace5aa9bee584371e2e1f90df54d844130a14243de59743b107064`.

Questa è una **prova esplicita riferita allo SHA eseguito**, non un costo imposto a ogni pull request. I rail ordinari costruiscono e verificano struttura/identità di un solo set di AAB. Una pull request verso `stabilization/*` attiva invece automaticamente quattro step aggiuntivi: ciascun rail esegue due ulteriori `bundleRelease --rerun-tasks`, conserva il primo set in `RUNNER_TEMP` e passa entrambi i riferimenti al verifier. Lo stesso percorso può essere richiesto manualmente con l'input booleano `verify_reproducibility`, una volta che la versione del workflow che dichiara l'input è disponibile sul default branch GitHub.

| Trigger | Build AAB | Evidenza prodotta |
|---|---:|---|
| Pull request ordinaria o push su `main` | 1 set | Struttura, identità, limiti e assenza di firma inattesa |
| Pull request verso `stabilization/*` | 1 set ordinario + 2 full rebuild | Stessi controlli più confronto byte-per-byte telefono/Wear |
| Dispatch con `verify_reproducibility=false` | 1 set | Equivalente al rail ordinario |
| Dispatch con `verify_reproducibility=true` | 1 set ordinario + 2 full rebuild | Equivalente al gate automatico della PR release |

```sh
gh workflow run android-ci.yml \
  --repo fz1101hub/binario \
  --ref integration/stabilization-complete-v0204 \
  -f verify_reproducibility=true

gh workflow run companion-ci.yml \
  --repo fz1101hub/binario \
  --ref integration/stabilization-complete-v0204 \
  -f verify_reproducibility=true
```

Una run è evidenza soltanto se GitHub assegna realmente il runner ed espone checkout, step di build, confronto finale e log. Uno stato `failure` senza step non dimostra né riproducibilità né non-riproducibilità.

Il verifier CI controlla che i quattro step restino limitati a una PR `stabilization/*` o a un dispatch esplicito e rifiuta `continue-on-error`, directory inattese, uno dei due rebuild mancante o reference non passati. Il verifier AAB richiede inoltre che i riferimenti telefono e Wear siano forniti come coppia e respinge un bundle usato come proprio riferimento.

## Verifica delle dipendenze Gradle

`gradle/verification-metadata.xml` fissa con SHA-256 gli artifact, i metadati Maven e i plugin risolti dall'intero gate Android/Wear. Gradle abilita automaticamente la dependency verification quando trova questo file; la CI passa inoltre `--dependency-verification=strict` in modo esplicito.[1] `tools/verify_gradle_dependency_metadata.py` rifiuta checksum assenti o duplicati, algoritmi deboli, trust bypass e una copertura troncata. Il lock corrente comprende 599 componenti e 1.041 artifact. Lo stesso verifier controlla `distributionUrl` e `distributionSha256Sum` dei wrapper root e Companion contro le versioni revisionate, coprendo anche il bootstrap della distribuzione Gradle prima che entri in funzione la verifica delle dipendenze.

Un aggiornamento Gradle o di una dipendenza deve rigenerare il metadata usando `--write-verification-metadata sha256` insieme a tutte le task CI. Il diff dei checksum va revisionato nella stessa pull request. Non si deve aggiungere un artifact a `trusted-artifacts` né disabilitare `verify-metadata` per far passare una build.

## Firma locale o CI

Per firmare entrambe le varianti `release`, impostare **tutte** le quattro proprietà seguenti tramite variabili d'ambiente oppure tramite un `gradle.properties` esterno/non versionato (per esempio `~/.gradle/gradle.properties`):

```text
BINARIO_RELEASE_STORE_FILE=/percorso/assoluto/al/keystore.jks
BINARIO_RELEASE_STORE_PASSWORD=...
BINARIO_RELEASE_KEY_ALIAS=...
BINARIO_RELEASE_KEY_PASSWORD=...
```

Le stesse chiavi possono essere esposte al processo Gradle come variabili d'ambiente con gli stessi nomi. Se manca anche una sola proprietà, Gradle emette un avviso e continua deliberatamente con una build unsigned: non esiste alcun fallback a file, password o segreti versionati. Il keystore e i relativi file sensibili restano esclusi da Git.

I rail ordinari non leggono alcun secret: un ambiente di distribuzione può iniettare le quattro variabili senza modificare il codice o la CI. L'unico percorso CI che usa secret è il job `release-sign` descritto sotto, attivo soltanto su richiesta esplicita.

## Artifact CI e firma su GitHub Actions

Il job `android` conserva per 14 giorni l'artifact `binario-play-bundles-unsigned` con `app-release.aab`, `wear-release.aab` e il log del verifier, ma soltanto per i push su `main`/`stabilization/**` e per i dispatch manuali: le pull request non caricano artifact, così lo spazio di archiviazione del repository non cresce a ogni revisione. I bundle unsigned servono come evidenza e per un confronto locale; non sono caricabili su Play.

Il job `release-sign` produce invece i bundle pronti per l'upload. Gira soltanto con `workflow_dispatch` e `sign_release=true`, dopo che il job `android` dello stesso commit è verde, e legge quattro secret del repository:

| Secret | Contenuto |
|---|---|
| `BINARIO_RELEASE_KEYSTORE_BASE64` | Il keystore di upload codificato con `base64 -w0 binario-upload.jks` |
| `BINARIO_RELEASE_STORE_PASSWORD` | Password del keystore |
| `BINARIO_RELEASE_KEY_ALIAS` | Alias della chiave di upload (per esempio `binario-upload`) |
| `BINARIO_RELEASE_KEY_PASSWORD` | Password della chiave |

Il job decodifica il keystore in `RUNNER_TEMP`, esporta le quattro variabili `BINARIO_RELEASE_*` che `app/build.gradle.kts` e `wear/build.gradle.kts` già riconoscono, esegue `:app:bundleRelease :wear:bundleRelease`, verifica i due AAB con `tools/verify_android_app_bundles.py --signed` (stessi controlli del gate unsigned, ma la firma JAR `META-INF/MANIFEST.MF`, `*.SF` e `*.RSA|DSA|EC` diventa obbligatoria) e pubblica l'artifact `binario-play-bundles-signed`, anch'esso con scadenza a 14 giorni; il keystore viene rimosso a fine job. `tools/verify_ci_pinning.py` blocca un job di firma senza `needs: android`, senza la condizione di dispatch, con secret interpolati nello script, con keystore fuori da `RUNNER_TEMP`, senza `--signed` o con artifact oltre i 14 giorni.

```sh
gh workflow run android-ci.yml \
  --repo fz1101hub/binario \
  --ref stabilization/v0.20.4 \
  -f verify_reproducibility=true \
  -f sign_release=true
gh run download --repo fz1101hub/binario --name binario-play-bundles-signed
```

Il keystore di upload va generato fuori dal repository, una sola volta, con validità superiore ai 25 anni richiesti da Play:

```sh
keytool -genkeypair -v -keystore binario-upload.jks -alias binario-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

## Google Play App Signing

Google Play firma gli APK consegnati agli utenti con una **chiave di firma dell'app** custodita da Google; il repository firma gli AAB con una **chiave di upload** distinta, che serve soltanto a Play per autenticare chi carica.[5] L'iscrizione a Play App Signing avviene al primo upload dell'app: in quella fase si può lasciare generare la chiave di firma a Google oppure caricare una chiave esistente con lo strumento PEPK. Dopo l'iscrizione la chiave di firma non è più sostituibile liberamente, mentre una chiave di upload smarrita può essere reimpostata dal supporto Play.

Regole che il rail e questa procedura danno per acquisite:

- `app-release.aab` e `wear-release.aab` sono lo stesso listing Play (`it.binario.app`) e vengono firmati con la **stessa chiave di upload**; Play li ri-firma con la stessa chiave di firma dell'app. Il companion Wear richiede la stessa identità di firma del telefono per il Data Layer, quindi non vanno mai usati keystore diversi per i due moduli.
- Una build già installata via sideload, come l'APK `v0.1.1` conservato in `artifacts/`, può essere aggiornata da Play soltanto se il certificato della chiave di firma dell'app coincide con quello usato per il sideload. Per mantenere la continuità, al momento dell'iscrizione va caricata come chiave di firma la chiave locale con cui quell'APK fu firmato; in caso contrario gli utenti con il sideload dovranno disinstallare e reinstallare da Play. Se quella chiave non è più disponibile, la seconda opzione è l'unica praticabile e va comunicata nelle note di rilascio.
- Il keystore di upload va conservato in almeno due copie offline cifrate, con password in un gestore di credenziali e mai nel repository; `.gitignore` esclude già `*.jks`, `*.keystore`, `*.p12` e `*.pem`. Prima dell'iscrizione a Play App Signing, perdere il keystore significherebbe perdere la possibilità di aggiornare l'app.

## Riferimenti

[1]: https://docs.gradle.org/8.14.3/userguide/dependency_verification.html "Gradle 8.14.3 — Verifying dependencies"
[2]: https://developer.android.com/guide/app-bundle "Android Developers — About Android App Bundles"
[3]: https://developer.android.com/training/wearables/packaging "Android Developers — Package and distribute Wear OS apps"
[4]: https://developer.android.com/guide/practices/page-sizes "Android Developers — Support 16 KB page sizes"
[5]: https://support.google.com/googleplay/android-developer/answer/9842756 "Google Play Console Help — Use Play App Signing"
