# PlannedJourney: dominio personale offline

`PlannedJourney` modella un viaggio personale pianificato senza riusare `SavedRoute` e senza incorporare stato operativo ferroviario. Il dominio contiene itinerari fino a quattro segmenti, data e ora con fuso IANA, reminder come preferenza, checklist limitata, ricorrenze finite, stato del ciclo di vita e agenda ordinata. Non contiene ritardi, binari, provider, credenziali, posizione, biglietti o identificativi di notifica.

## Persistenza e portabilità

`PlannedJourneyPersistenceSnapshot` è lo schema locale v1. La validazione impone un massimo di 40 viaggi, 4 segmenti, 24 elementi checklist e 12 occorrenze ricorrenti. Le date in gap DST sono rifiutate; gli overlap usano deterministicamente l’offset precedente.

`PlannedJourneyTransfer` è il confine JSON portabile. Accetta al massimo 256 KiB, rifiuta campi sconosciuti e versioni future, valida integralmente i viaggi prima dell’import e produce una preview distinta in:

- viaggi accettabili;
- duplicati per fingerprint canonico;
- conflitti di identificativo con il dato locale.

Il merge richiede una scelta esplicita fra mantenere il dato locale e sostituirlo. Il formato non è il backup completo Android e non include altre preferenze dell’app.

## Calendario ICS

`PlannedJourneyIcs` esporta al massimo 120 eventi e 256 KiB. Gli eventi usano istanti UTC, UID deterministici non reversibili, righe CRLF ripiegate a 75 ottetti, escaping RFC 5545 e `VALARM` relativo quando è configurato un reminder. Il parser di anteprima accetta soltanto calendari marcati con il `PRODID` Binario e verifica la coerenza dell’UID; non ricostruisce il dominio completo da file ICS.

## Stato dell’integrazione

Questa tranche fornisce il dominio, agenda, lifecycle, deduplica, formato JSON e ICS con test JVM. Non introduce ancora una schermata Android, una pianificazione notifiche o una sincronizzazione remota. Tali adapter devono dipendere da questi contratti e restare separati da Alexa Companion e dal Data Layer Wear.
