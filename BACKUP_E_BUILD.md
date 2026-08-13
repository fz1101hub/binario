# Backup e build di Binario

Questo repository contiene sorgenti Android, modulo Wear OS, progetto sorgente watchOS, documentazione di architettura, benchmark e checklist. Non contiene APK/AAB, cache Gradle, SDK Android, configurazioni locali, certificati o segreti.

## Prerequisiti Android

| Componente | Versione/profilo |
|---|---|
| JDK | 21 |
| Android SDK | Platform 36 e Build Tools 36 |
| Gradle Wrapper | incluso nel repository |
| Target SDK | 36 |
| Min SDK app | 26 |

Impostare `sdk.dir` nel file locale `local.properties`, che resta escluso da Git, quindi eseguire:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :wear:testDebugUnitTest :wear:lintDebug :wear:assembleDebug
```

L’APK debug dell’app è prodotto in `app/build/outputs/apk/debug/app-debug.apk`; l’APK Wear OS in `wear/build/outputs/apk/debug/wear-debug.apk`.

## Voice Bridge Alexa e App Actions

La cartella `voice-bridge/` contiene il backend separato per Alexa Custom Skill, dashboard protetta, OAuth 2.0 authorization-code, tratte personali e documentazione App Actions Android. Per la verifica locale:

```bash
cd voice-bridge
pnpm install --frozen-lockfile
pnpm test
pnpm check
pnpm build
```

Il bridge è deliberatamente inattivo finché non saranno impostati nel servizio di deployment i segreti `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET` e `VOICE_TOKEN_PEPPER`. Questi valori non devono essere inseriti nel repository. Senza configurazione Alexa il webhook rifiuta la richiesta; senza una fonte ferroviaria autorizzata la risposta vocale resta **“dati non disponibili in tempo reale”**.

## Apple Watch

La cartella `apple-watch/` contiene un progetto sorgente SwiftUI/watchOS dichiarato con XcodeGen. Aprirlo su macOS con Xcode/XcodeGen, configurare team e bundle identifier, quindi eseguire la build su simulatore e dispositivo. Il repository non include profili di provisioning, certificati o chiavi Apple.

## Politica dati e fonti

Il catalogo ferroviario incluso è dimostrativo e non-live. Qualsiasi provider reale deve essere inserito attraverso l’architettura adapter e rispettare autorizzazioni, provider metadata, freshness e confidence. Non caricare mai token, endpoint privati, dump di dati personali o file di analisi contenenti credenziali.

## Backup operativo

Il repository GitHub privato è il backup del codice. Prima di ogni push, eseguire `git status --ignored` e controllare che non compaiano artefatti, file `local.properties`, chiavi o cartelle build. Le APK destinate al test vanno gestite separatamente come release allegata, non come sorgenti Git.
