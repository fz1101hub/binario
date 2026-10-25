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

La schermata Android Agenda integra dominio, lifecycle, checklist, import/export e selettori nativi di data e ora. La ricerca delle stazioni avviene fuori dal thread UI, supporta accenti e codici, ordina risultati deterministici e richiede una scelta canonica per nomi ambigui. La modifica di un itinerario importato conserva le tratte successive e il fuso originale.

`PlannedJourneyReminderEngine` e `AndroidPlannedJourneyReminders` pianificano notifiche locali soltanto dopo consenso esplicito per dispositivo, inizialmente disabilitato. Tale consenso e il registro di consegna sono separati dal formato di trasferimento v1. La pianificazione usa il fuso IANA di ciascuna occorrenza e al massimo 64 allarmi contemporanei, riconciliati dopo mutazioni, riapertura, riavvio, aggiornamento dell’app e modifiche dell’ora/fuso. Senza accesso agli allarmi esatti Android può differire la consegna.

Il registro viene persistito prima di chiamare AlarmManager/NotificationManager. Ogni consegna rilegge il viaggio corrente, verifica consenso e permesso, scarta allarmi obsoleti e deduplica le occorrenze anche attraverso cambi dell’orologio. La consegna è **al più una volta**: un arresto fra registrazione e pubblicazione può perdere un avviso; Android non garantisce puntualità o consegna. Al riavvio non vengono riprodotti avvisi con anticipo già trascorso; quelli già in coda possono arrivare soltanto prima della partenza.

Completamento, archiviazione, cancellazione e disattivazione annullano gli allarmi interessati e ritirano le notifiche attive. Il payload degli intent contiene solo un identificatore opaco; il tocco apre `binario://agenda`. Nessuna sincronizzazione remota viene introdotta. Questi adapter restano separati da Alexa Companion e dal Data Layer Wear.

## Test Android e APK per prove manuali

La CI Android esegue `tools/run_android_ui_tests.sh` su emulatore API 35 con KVM, con permesso notifiche accordato e poi negato. Include Agenda a caratteri grandi, selezione di stazioni e data/ora, creazione/cancellazione di allarmi Android e notifica privata. La macchina cloud senza KVM verifica unit test, lint e compilazione dei test strumentali; l’esecuzione strumentale è verificata sul runner GitHub.

Dopo i test, il workflow pubblica l’artifact `binario-agenda-test-apk`, contenente APK debug firmato, SHA-256, informazioni sulla firma e commit sorgente. L’APK richiede Android 8.0 o successivo ed è destinato a prove manuali.
