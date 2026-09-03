# Parità web → companion

## Principio di riferimento

La web app Binario è la superficie funzionale e UX/UI di riferimento. Android e iOS adottano gli stessi concetti, testi operativi, disclosure e stati; la differenza è ammessa solo quando dipende da controlli nativi, permessi di sistema, assenza di Xcode o hardware reale.

> La parità non richiede di replicare meccanicamente il web. Su Android e iOS Binario deve preferire il controllo di sistema più chiaro e accessibile: picker data/ora, permesso posizione richiesto al gesto, condivisione e selezione file nativi, notifiche locali, azioni di sistema e feedback haptic solo per conferme significative.

| Capacità web di riferimento | Companion corrente | Azione di parità |
| --- | --- | --- |
| Planner locale con autocomplete, data/ora, promemoria e stazione vicina inline | Presente | Completata; il companion usa il catalogo offline e il consenso posizione di sistema. |
| Dittatura guidata, testo rivedibile e consenso | Presente con dettatura tastiera OS | Parità funzionale; non registra né invia audio dal companion. |
| Template completi origine/destinazione/orario/preferenze | Presente | Archivio AsyncStorage limitato, applicazione esplicita e data sempre da scegliere. |
| Ricerca nelle tratte locali | Presente | Ricerca locale per stazione, data o ora nella schermata Agenda/Promemoria. |
| Agenda con ricorrenze, completamento, archivio e statistiche | Filtri, archivio temporale e statistiche pianificazione presenti; ricorrenze/completamento esplicito assenti | Estendere solo se necessario il contratto locale, senza scheduler nascosto né telemetria. |
| Export/import ICS e riepilogo condivisibile | Presente | Selettore file e condivisione nativi espliciti, con file minimizzato privo di checklist, token, coordinate e dati realtime. |
| Privacy Center, cancellazione dati viaggio e diagnostica | Presente in forma nativa | Allineare testo, mappa dati ed etichette a web senza cancellare account o preferenze. |
| Tema, contrasto, movimento, modalità semplificata e testo leggibile | Tema, contrasto, movimento e modalità semplificata presenti | Rispettare font scale OS e validarlo su dispositivi reali; non duplicare artificialmente il controllo di sistema. |
| Azioni rapide e navigazione ai flussi locali | Scorciatoie Android, hub e feedback haptic discreto presenti | Validare le azioni di sistema su device reale. |
| Widget e Tile | Presente con adattamenti nativi | Mantenere only-local/non-realtime; test hardware rimane esterno. |
| Sync selettiva e revocabile | Presente | Conservare parità contrattuale; nessuna sync in background. |

## Limiti non sostituibili

I target iOS/WidgetKit/Apple Watch sono allineati a livello di contratto e handoff, ma richiedono Xcode/macOS, firma e hardware per una parità eseguibile. I dati ferroviari realtime, la vendita, account operatori e Alexa richiedono provider o console autorizzati e restano inattivi.
