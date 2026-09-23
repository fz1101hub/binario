# Verifica Binario v0.11.0

## Esito tecnico

La release `0.11.0` usa `versionCode 17` e `versionName 0.11.0`. I controlli indicati sono stati eseguiti il 13 agosto 2026.

| Area | Controllo | Esito |
|---|---|---|
| Android | `:app:testDebugUnitTest :app:assembleDebug` | Superato — 45 attività Gradle |
| Android | `:app:lintDebug` | Superato — 30 attività Gradle |
| Voice Bridge | `pnpm check` | Superato |
| Voice Bridge | `pnpm test -- --run` | Superato — 11 file, 29 test |
| Database Voice Bridge | Migrazione `0002_sweet_rachel_grey.sql` | Applicata e verificata: tabella `binario_profiles` con utente univoco e chiave esterna |
| Apple Watch | Verifica strutturale file e target XcodeGen | Superata nel sandbox |

L’APK di debug generato è `app/build/outputs/apk/debug/app-debug.apk`.

## Funzioni incluse

Il companion Apple Watch contiene sorgenti SwiftUI per la tratta e fino a due corse, dettaglio accessibile, cache-first, Widget/complication e App Intent che rilegge solo la cache. Non afferma disponibilità live e non riceve dati direttamente da Android; il canale Watch Connectivity è riservato a una futura app iOS, mentre Android potrà passare dal profilo remoto autorizzato.

Il Voice Bridge espone `profile.get` e `profile.upsert` esclusivamente in sessione autenticata. Il profilo usa schema `1`, UUID di mutazione, revisione ottimistica e DTO pubblico: non restituisce `lastMutationId` o `monitoredTrainNumbersJson`. Il replay dello stesso UUID mantiene la revisione invariata e restituisce `idempotent: true`.

Android prepara un payload locale compatibile e presenta lo stato **“Collegamento account Voice Bridge richiesto; nessun dato inviato”**. Le scorciatoie e i deep link `binario://` validano solo tratta attiva, prossime corse e numero treno; non aprono rete né memorizzano token.

## Limiti dichiarati

Il sandbox non dispone di Xcode/XcodeGen: generazione, firma e test del companion Apple Watch richiedono macOS. La sincronizzazione Android → Voice Bridge non è ancora abilitata perché richiede un’autenticazione utente esplicita e un trasporto HTTPS dedicato. Alexa resta fail-closed fino alla configurazione server-side. I dati ferroviari restano dimostrativi e, in assenza di feed autorizzato, l’app deve comunicare **“dati non disponibili in tempo reale”**.
