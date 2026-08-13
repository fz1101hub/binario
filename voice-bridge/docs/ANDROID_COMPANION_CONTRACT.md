# Contratto Binario tra Android, smartwatch e Voice Bridge

## Un solo profilo personale

Android, widget, Wear OS, watchOS e Voice Bridge rappresentano la stessa persona e le stesse preferenze Binario. La fonte di verità locale è l’app Android finché non è attivato un profilo remoto autenticato; il Voice Bridge diventa la fonte remota opzionale per tratte e monitoraggi quando l’utente esegue un collegamento esplicito.

| Risorsa | App Android | Companion | Voice Bridge |
|---|---|---|---|
| Tratte e fissaggi | Gestione completa | Solo tratta attiva e nomi minimi | Copia sincronizzabile dopo autenticazione |
| Direzione attiva | Gestione completa | Riceve snapshot | Utilizzabile per richiesta vocale collegata |
| Monitoraggi treno | Gestione completa | Riceve stato minimo | Copia sincronizzabile dopo autenticazione |
| Snapshot ferroviario | Dal repository dati Binario | Cache-first ridotta | Solo da provider autorizzato o snapshot sincronizzato con freshness |
| Integrazioni | Toggle e stato nell’app | Solo flag applicabili | Stato del collegamento, mai segreti |

## Regole non negoziabili

1. L’app Android non invia mai token Alexa, OAuth secret, posizione, biglietti, audio o storico al companion.
2. Il Voice Bridge non restituisce una risposta personale senza un access token collegato e verificato.
3. Ogni superficie conserva provider, freshness, confidence e notice; in assenza di feed autorizzato comunica **“dati non disponibili in tempo reale”**.
4. Prima del collegamento account, i toggle in Android indicano solo la preferenza dell’utente: non attestano che Alexa o Apple Watch siano configurati.

## Attivazione futura

L’app Android dovrà richiedere un’autenticazione esplicita verso il Voice Bridge prima di inviare una copia delle tratte e dei monitoraggi. La sincronizzazione usa un endpoint autenticato, idempotente e con versione di schema; non usa mai SharedPreferences come canale verso Internet e non risolve le credenziali lato client.
