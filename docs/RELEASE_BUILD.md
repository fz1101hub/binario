# Build release Android

I moduli Android canonici sono `app` e `wear`. Entrambi leggono il `versionName` dalla proprietà non sensibile canonica `BINARIO_VERSION_NAME` in `gradle.properties`. I `versionCode` restano distinti, come richiesto dai form factor di una futura distribuzione Play, ma provengono dalla stessa sorgente: `BINARIO_APP_VERSION_CODE` per `app` e `BINARIO_WEAR_VERSION_CODE` per `wear`. Per la release `v0.20.4` i valori sono `35`, `1` e `0.20.4`. Un override `-PBINARIO_VERSION_CODE=…` è disponibile per build coordinate controllate; sovrascrive entrambi i codici soltanto quando tale parità è davvero desiderata.

Le varianti `release` eseguono R8 e resource shrinking. Le regole locali preservano in modo prudente i tipi generati protobuf/GTFS-Realtime, jsoup e le callback del Data Layer Wear. Questa scelta privilegia l'affidabilità della prima build minificata; potrà essere ristretta soltanto dopo prove su dispositivi rappresentativi.

## Build CI unsigned

La build release non richiede né cerca chiavi nel repository. In assenza della configurazione di firma, i comandi seguenti producono APK release unsigned, adatti alla verifica CI:

```sh
./gradlew --no-daemon :app:assembleRelease :wear:assembleRelease \
  :app:bundleRelease :wear:bundleRelease \
  :app:lintVitalRelease :wear:lintVitalRelease \
  --dependency-verification=strict
python3 tools/verify_android_app_bundles.py
```

## Artifact per Google Play

Google Play richiede Android App Bundle (AAB) per le nuove app. Un bundle è un formato di pubblicazione dal quale Play genera APK ottimizzati; non sostituisce un APK installabile direttamente.[2] Il gate produce `app-release.aab` e `wear-release.aab` separati, poi verifica ZIP, DEX, CRC, percorsi, duplicati, limiti dimensionali e SHA-256. Il manifest protobuf compilato viene letto direttamente: package, `versionName` e `versionCode` devono coincidere con la configurazione release canonica. Gli artifact restano unsigned finché non vengono fornite le credenziali di firma.

Il modulo Wear usa lo stesso package dell'app telefono, dichiara `android.hardware.type.watch` e `standalone=false`, ma conserva un proprio `versionCode`. Google indica che l'app Wear viene caricata e aggiornata separatamente e raccomanda codici versione indipendenti tra form factor.[3] La scelta del track Play, la firma e l'upload restano azioni proprietarie esterne a questo gate.

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

Il gate blocca una divergenza fra il file canonico, il progetto generato e gli AAB effettivi. Il prebuild fissa inoltre `org.gradle.parallel=false` e `org.gradle.workers.max=1`: senza questa serializzazione, il contenuto dell'AAB telefono era identico ma l'ordine dei member ZIP poteva cambiare. Con il packaging single-worker, due full rebuild hanno prodotto AAB byte-identici: 50.988.125 byte e SHA-256 `29ee0716464decf69cae7961c50f8e4b141a5079a58cd5149cb6f271a84104e0` per il telefono Companion; 4.309.499 byte e SHA-256 `ec5bb00303abf9b234d0c23696697807179efc35b95589673ade26df3edad305` per Wear Companion. Firma e distribuzione restano separate da quelle dell'app principale.

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

Sull'HEAD `93e592d` le due full rebuild locali sono risultate byte-identiche. Il telefono produce 18.490.402 byte con SHA-256 `1f75617758585baf1e6d212730ab2a8ae4e58e93c797040d6ea1b3db17bdd09a`; Wear produce 3.894.315 byte con SHA-256 `0a4d2fcba02914a33f79fade8d1467d9f0d34c82175a0120964f09ef18fbff9a`.

Questa è una **prova manuale riferita allo SHA indicato**, non una proprietà attualmente esercitata dai workflow ordinari: i rail CI costruiscono e verificano struttura/identità di un solo set di AAB, senza passare `--reference-phone` e `--reference-wear`. Dopo modifiche al codice o alla toolchain, la procedura a due full rebuild va quindi ripetuta sul commit candidato se la riproducibilità byte-per-byte è un criterio di rilascio.

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

Non sono configurati segreti nel workflow: un ambiente di distribuzione può iniettare le quattro variabili senza modificare il codice o la CI.

## Riferimenti

[1]: https://docs.gradle.org/8.14.3/userguide/dependency_verification.html "Gradle 8.14.3 — Verifying dependencies"
[2]: https://developer.android.com/guide/app-bundle "Android Developers — About Android App Bundles"
[3]: https://developer.android.com/training/wearables/packaging "Android Developers — Package and distribute Wear OS apps"
