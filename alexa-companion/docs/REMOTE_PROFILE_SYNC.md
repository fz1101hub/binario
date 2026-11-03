# Contratto di sincronizzazione del profilo Binario

## Scopo e limiti

Il profilo remoto consente di salvare, per un utente già autenticato nel companion Alexa, lo stato personale minimo necessario alle richieste vocali. L’app Android resta la fonte di verità. Il profilo non trasferisce preferenze Wear OS o Apple Watch, posizione, biglietti, cronologia ricerche, audio, token, segreti OAuth o credenziali Alexa. Finché non viene aggiunto uno snapshot ferroviario versionato e fresco, non attiva dati live e non modifica la risposta obbligatoria **“dati non disponibili in tempo reale”** quando manca una fonte autorizzata.

| Campo | Tipo | Vincolo | Uso |
|---|---|---|---|
| `schemaVersion` | intero | Valore attuale: `1` | Evoluzione compatibile del payload |
| `activeRouteId` | stringa | 1–120 caratteri | Identificativo locale della tratta attiva |
| `direction` | enum | `OUTBOUND` o `RETURN` | Direzione selezionata |
| `monitoredTrainNumbers` | elenco | Massimo 32 identificativi unici | Monitoraggi personali, senza storico |
| `expectedRevision` | intero | Facoltativo, minimo `0` | Concorrenza ottimistica |
| `clientMutationId` | UUID | Obbligatorio | Idempotenza del salvataggio |
| `revision` | intero | Solo risposta server | Versione corrente da inviare come `expectedRevision` nel salvataggio seguente |

## Operazioni protette

Il router tRPC espone `profile.get` e `profile.upsert` soltanto tramite sessione autenticata. Il salvataggio è limitato al proprietario della sessione, è idempotente per `clientMutationId` e usa `expectedRevision` per impedire che un dispositivo sovrascriva silenziosamente una versione più recente. Se si verifica un conflitto, il server non applica alcuna modifica e risponde con il codice applicativo `CONFLICT`.

## Stato di integrazione Android

Nella presente fase Android prepara un `AlexaCompanionProfileRequest`, ma non apre connessioni di rete e non conserva credenziali. Il collegamento effettivo richiederà una sessione utente esplicita e un client trasporto dedicato in un incremento successivo; fino a quel momento la preferenza in app resta locale e descrittiva. Le preferenze Wear OS e Apple Watch restano nei rispettivi contratti locali e non vengono serializzate verso Alexa.
