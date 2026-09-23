# Build release Android

I moduli Android canonici sono `app` e `wear`. Entrambi leggono il `versionName` dalla proprietà non sensibile canonica `BINARIO_VERSION_NAME` in `gradle.properties`. I `versionCode` restano distinti, come richiesto dai form factor di una futura distribuzione Play, ma provengono dalla stessa sorgente: `BINARIO_APP_VERSION_CODE` per `app` e `BINARIO_WEAR_VERSION_CODE` per `wear`. Per la release `v0.20.4` i valori sono `35`, `1` e `0.20.4`. Un override `-PBINARIO_VERSION_CODE=…` è disponibile per build coordinate controllate; sovrascrive entrambi i codici soltanto quando tale parità è davvero desiderata.

Le varianti `release` eseguono R8 e resource shrinking. Le regole locali preservano in modo prudente i tipi generati protobuf/GTFS-Realtime, jsoup e le callback del Data Layer Wear. Questa scelta privilegia l'affidabilità della prima build minificata; potrà essere ristretta soltanto dopo prove su dispositivi rappresentativi.

## Build CI unsigned

La build release non richiede né cerca chiavi nel repository. In assenza della configurazione di firma, i comandi seguenti producono APK release unsigned, adatti alla verifica CI:

```sh
./gradlew --no-daemon :app:assembleRelease :wear:assembleRelease \
  :app:lintVitalRelease :wear:lintVitalRelease
```

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
