# Verifica release Binario v0.5.0

**Data:** 12 agosto 2026  
**Artefatto verificato:** APK debug `it.binario.app`, versione `0.5.0` (`versionCode 5`).

## Esiti tecnici

| Controllo | Esito | Evidenza |
|---|---|---|
| Test unitari debug | Superato | 3 test eseguiti; 0 failure, 0 error, 0 skipped. |
| Lint debug | Superato | Report: **No issues found**. |
| APK debug | Generato | `app/build/outputs/apk/debug/app-debug.apk`. |
| Metadati | Verificati | `minSdk 26`, `targetSdk 36`, `compileSdk 36`, application id `it.binario.app`. |
| Integrità | Verificata | SHA-256: `f8dde99c3f132123db95a98d487d64f253db3617b78477a581754ba900bbdc3f`. |
| Toolchain | Verificata | OpenJDK 21 per build, target Java 17, AGP 8.13.0, Gradle 8.14.5, Android SDK API 36. |

## Incremento v0.5 verificato

| Ambito | Comportamento verificato |
|---|---|
| Partenze rapide | La UI prevede una sintesi delle prime due corse della tratta attiva, con semantica completa e apertura del dettaglio. |
| Tratte fissate | Il modello `SavedRoute` gestisce `isPinned`; la persistenza locale legge sia il precedente formato di quattro campi sia il nuovo formato di cinque campi. |
| Ordinamento Lazio | I test coprono: precedenza Trenitalia Regionale su tratta laziale con preferenza attiva; ordine orario fuori Lazio; ordine orario con preferenza disattivata. |
| Trasparenza demo | Quando `DataConfidence.DEMONSTRATION` è attiva, il banner dichiara espressamente che il catalogo è locale e non-live. |
| Sicurezza esistente | HTTPS obbligatorio, cleartext disabilitato, backup esclusi e assenza di credenziali nel client restano invariati. |

## Comandi eseguiti

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Il progetto include il Gradle Wrapper 8.14.5. Per la verifica nel sandbox sono stati inoltre ripristinati `settings.gradle.kts` con repository Google/Maven Central e `gradle.properties` con AndroidX abilitato.

## Limite operativo vincolante

La release usa ancora un **catalogo dimostrativo locale multi-operatore**. Le corse, gli orari, i ritardi, i binari e le soppressioni non sono informazioni operative reali. Nessuna schermata o widget deve essere usato per prendere decisioni di viaggio finché non sarà configurato un provider autorizzato attraverso `RailDataRepository`.
