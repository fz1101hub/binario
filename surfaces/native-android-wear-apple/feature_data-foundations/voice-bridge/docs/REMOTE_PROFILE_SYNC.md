# Contratto di sincronizzazione del profilo Binario

## Scopo e limiti

Il profilo remoto consente di salvare, per un utente già autenticato nel Voice Bridge, lo stato personale minimo usato dalle superfici Binario. Non trasferisce snapshot ferroviari, posizione, biglietti, cronologia ricerche, audio, token, segreti OAuth o credenziali Alexa. Non attiva dati ferroviari live e non modifica la risposta obbligatoria **“dati non disponibili in tempo reale”** quando manca una fonte autorizzata.

| Campo | Tipo | Vincolo | Uso |
|---|---|---|---|
| `schemaVersion` | intero | Valore attuale: `1` | Evoluzione compatibile del payload |
| `activeRouteId` | stringa | 1–120 caratteri | Identificativo locale della tratta attiva |
| `direction` | enum | `OUTBOUND` o `RETURN` | Direzione selezionata |
| `monitoredTrainNumbers` | elenco | Massimo 32 identificativi unici | Monitoraggi personali, senza storico |
| `wearSnapshotSharingEnabled` | booleano | — | Preferenza di invio verso Wear OS |
| `voiceProfileSyncRequested` | booleano | — | Preferenza esplicita di collegamento Voice Bridge |
| `appleWatchSetupRequested` | booleano | — | Preferenza di configurazione Apple Watch |
| `expectedRevision` | intero | Facoltativo, minimo `0` | Concorrenza ottimistica |
| `clientMutationId` | UUID | Obbligatorio | Idempotenza del salvataggio |
| `revision` | intero | Solo risposta server | Versione corrente da inviare come `expectedRevision` nel salvataggio seguente |

## Operazioni protette

Il router tRPC espone `profile.get` e `profile.upsert` soltanto tramite sessione autenticata. Il salvataggio è limitato al proprietario della sessione, è idempotente per `clientMutationId` e usa `expectedRevision` per impedire che un dispositivo sovrascriva silenziosamente una versione più recente. Se si verifica un conflitto, il server non applica alcuna modifica e risponde con il codice applicativo `CONFLICT`.

## Stato di integrazione Android

Nella presente fase Android espone il payload come `UnifiedBinarioProfile`, ma non apre connessioni di rete e non conserva credenziali. Il collegamento effettivo richiederà una sessione utente esplicita e un client trasporto dedicato in un incremento successivo; fino a quel momento la preferenza in app resta locale e descrittiva.
