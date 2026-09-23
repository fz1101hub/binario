# Contratto Binario tra app Android e companion

## Un solo profilo personale

L’app Android per smartphone è il prodotto principale e la fonte di verità del profilo Binario. Widget, Wear OS, watchOS e Alexa sono companion: ricevono soltanto il sottoinsieme necessario attraverso contratti versionati e non possono diventare sorgenti concorrenti per tratte, preferenze o stato ferroviario. Il companion Alexa conserva una copia remota minima solo dopo un collegamento esplicito e autenticato.

| Risorsa | App Android principale | Smartwatch e widget | Companion Alexa |
|---|---|---|---|
| Tratte e fissaggi | Gestione completa | Solo tratta attiva e nomi minimi | Copia sincronizzabile dopo autenticazione |
| Direzione attiva | Gestione completa | Riceve snapshot | Utilizzabile per richiesta vocale collegata |
| Monitoraggi treno | Gestione completa | Riceve stato minimo | Copia sincronizzabile dopo autenticazione |
| Snapshot ferroviario | Dal repository dati Binario | Cache-first ridotta | Solo da provider autorizzato o snapshot sincronizzato con freshness |
| Integrazioni | Toggle e stato nell’app | Configurazione locale della propria superficie | Stato del collegamento Alexa, mai segreti né flag degli altri companion |

## Regole non negoziabili

1. L’app Android non invia mai token Alexa, OAuth secret, posizione, biglietti, audio o storico ai companion.
2. Il companion Alexa non restituisce una risposta personale senza un access token collegato e verificato.
3. Ogni snapshot condiviso conserva provider, istante di generazione, TTL, freshness, confidence e notice; un companion non può trasformare un dato programmato in dato live.
4. Se lo snapshot è assente o scaduto, Alexa comunica **“dati non disponibili in tempo reale”** e gli smartwatch mostrano uno stato equivalente.
5. I flag Wear OS e Apple Watch restano locali all’app e non fanno parte dell’API pubblica Alexa.
6. Prima del collegamento account, i toggle in Android indicano solo la preferenza dell’utente: non attestano che Alexa o Apple Watch siano configurati.

## Attivazione futura

L’app Android dovrà richiedere un’autenticazione esplicita verso il companion Alexa prima di inviare una copia minima delle tratte, dei monitoraggi e degli snapshot necessari alla voce. La sincronizzazione usa un endpoint autenticato, idempotente e con versione di schema; non usa mai SharedPreferences come canale verso Internet e non risolve le credenziali lato client.
