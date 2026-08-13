# Verifica Binario v0.10.0

## Esito tecnico

La release `0.10.0` è stata compilata con `versionCode 16` e `versionName 0.10.0`. La verifica automatizzata ha completato con esito positivo il 13 agosto 2026 mediante i seguenti comandi.

| Controllo | Comando | Esito |
|---|---|---|
| Test unitari e APK | `:app:testDebugUnitTest :app:assembleDebug` | Superato — 45 attività Gradle |
| Lint debug | `:app:lintDebug` | Superato — 30 attività Gradle |
| Test profilo unificato | `UnifiedProfileTest` incluso nel task unitario | Superato |

L’APK di debug generato è `app/build/outputs/apk/debug/app-debug.apk`.

## Funzioni verificate

Il pannello **Altro → Integrazioni** espone in modo accessibile le preferenze per Smartwatch Android, Comandi vocali e Alexa e Apple Watch. La preferenza Wear OS controlla l’invio dello snapshot ridotto; nessuna preferenza trasferisce credenziali o abilita servizi cloud automaticamente. Il profilo `UnifiedBinarioProfile` conserva esclusivamente identificativo tratta attiva, direzione, numeri monitorati e flag di superficie, con versione di schema `1`.

Il contratto condiviso è documentato in `ESPERIENZA_UNIFICATA_V10.md` e `voice-bridge/docs/ANDROID_COMPANION_CONTRACT.md`. Alexa e Voice Bridge restano inattivi fino a configurazione server-side e collegamento esplicito dell’account.

## Limiti dichiarati

I dati ferroviari della release restano locali dimostrativi e non in tempo reale. In assenza di un provider autorizzato, ogni superficie deve comunicare esattamente **“dati non disponibili in tempo reale”**. Il progetto Apple Watch è pianificato ma va rigenerato: nel worktree corrente non sono presenti sorgenti SwiftUI distribuibili. La verifica su dispositivo fisico, inclusi TalkBack e font al 200%, richiede ancora il collaudo dell’utente.
