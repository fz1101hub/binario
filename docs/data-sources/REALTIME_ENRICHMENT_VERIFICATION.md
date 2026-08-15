# Verifica dell’arricchimento realtime

## Fonti raggiungibili

Il controllo read-only del 15 agosto 2026 ha confermato la raggiungibilità dei feed che alimentano l’arricchimento tipizzato.

| Fonte | HTTP | Formato | Payload acquisito | Uso nell’app |
|---|---:|---|---:|---|
| SNCF Trip Updates | 200 | Protocol Buffers | 138.430 byte | Ritardi e orari previsti per fermata, dopo matching GTFS |
| SNCF Service Alerts | 200 | Protocol Buffers | 749.998 byte | Avvisi ufficiali |
| STA Trip Updates | 200 | JSON GTFS-Realtime | 25.837 byte | Ritardi e orari previsti per fermata, dopo matching GTFS |

## Raccordo statico–realtime

Il resolver richiede la stessa identità di corsa e fermata nel feed realtime e nel GTFS embedded. Questa scelta evita di unire erroneamente corse di giorni, linee o operatori diversi.

Durante il controllo STA sono stati osservati 87 aggiornamenti di fermata realtime. Nessuno usava un `trip_id` presente nel data pack altoatesino incluso nell’APK: il realtime era già valido, ma il GTFS embedded non è allineato alla sua versione di esercizio corrente. L’app conserva quindi i Trip Updates tipizzati, ma non presenta un orario statico come corrispondente finché non viene importato un GTFS STA della medesima versione.

> Un aggiornamento con `delay` o `time` senza un evento esplicitamente effettivo resta una **previsione**. Non genera una rilevazione operativa e non alimenta il ritardo alla rilevazione.

## Garanzie applicate

Il livello dati conserva il ritardo pubblicato dalla fonte, espone tempi previsti con provenienza e calcola il ritardo alla rilevazione soltanto per `actualAt` confrontabile con lo stesso stop GTFS. Gli errori o i mismatch di identità rimangono separati dal risultato e non causano fallback verso dati fittizi.
