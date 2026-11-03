# Binario v1.0 — esperienza unificata

## Stato rilevato

| Superficie | Dato locale attuale | Limite attuale | Decisione unificata |
|---|---|---|---|
| App Android | Tratte, direzione, recenti, monitoraggi e snapshot in SharedPreferences/cache. | È l’unica sorgente locale completa. | È il punto di gestione principale e il proprietario della configurazione dispositivo. |
| Widget Android | Snapshot e direzione della tratta attiva. | Non espone preferenze, monitoraggi o stato integrazioni. | Resta una vista derivata read-only del contratto locale. |
| Wear OS | Snapshot della tratta attiva e due corse via Data Layer. | Non riceve tratte salvate, monitoraggi o impostazioni. | Riceverà un envelope di preferenze minime, privo di segreti. |
| Apple Watch | Nel repository resta soltanto `project.yml`; i sorgenti SwiftUI/Watch Connectivity non sono presenti nel worktree corrente. | Nessun contratto eseguibile da allineare. | Il progetto watchOS verrà ricreato dal medesimo schema di envelope; non viene dichiarato pronto finché non ricompaiono o vengono rigenerati i sorgenti. |
| Voice Bridge | Database server con tratte per utente e collegamento Alexa hash-based. | Non sincronizzato con app locale e non contiene monitoraggi. | Diventa il profilo remoto opzionale dell’utente, non un prodotto distinto. |

## Contratto Binario condiviso

Il contratto segue il principio **app principale come centro di controllo, backend come fonte remota opzionale, companion come viste limitate**. Non trasferisce credenziali Alexa, token OAuth, posizione, biglietti, audio o storico non necessario.

| Campo | Android | Widget/Wear/watchOS | Voice Bridge |
|---|---|---|---|
| `schemaVersion` | Produce e verifica. | Verifica. | Verifica. |
| `routes` | Fonte locale e modifica. | Solo tratta attiva/nomi minimi. | Sincronizzazione opzionale per utente collegato. |
| `activeRouteId` e `direction` | Fonte locale. | Riceve. | Usa solo per risposte vocali quando disponibile. |
| `monitoredTrainNumbers` | Fonte locale e modifica. | Riceve lista minima. | Sincronizzazione opzionale; nessuna risposta personale senza account collegato. |
| `snapshot` | Fonte del repository ferroviario. | Riceve una versione ridotta/cache-first. | Riceve o recupera solo tramite provider autorizzato. |
| `quality` | Provider, freshness, confidence e notice. | Mostrati integralmente. | Pronunciati senza eufemismi. |
| `integrationSettings` | Gestiti nell’app. | Ricevono solo flag applicabili. | Pubblica stato link, mai segreti. |

## Impostazioni nell’app principale

La schermata **Altro → Integrazioni** conterrà quattro blocchi: dati e privacy; smartwatch Android; Apple Watch; comandi vocali. Ogni blocco mostra stato, dati condivisi e azione consentita. Le credenziali e il collegamento Alexa restano nel backend e nella console Amazon; l’app mostra solo “non configurato”, “pronto per collegamento”, “collegato” o “non disponibile”.

## Regola di sicurezza e trasparenza

> L’app, il widget, gli orologi e la voce devono mostrare lo stesso stato di qualità. In assenza di feed autorizzato il testo è **“dati non disponibili in tempo reale”**; nessuna superficie può trasformare un catalogo locale in informazione live.
