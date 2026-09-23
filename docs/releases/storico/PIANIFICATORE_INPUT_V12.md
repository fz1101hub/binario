# Binario — ingressi pianificatore v0.12

## Decisioni di interazione

| Funzione | Interazione | Persistenza | Limite di privacy |
|---|---|---|---|
| Data | Selettore nativo Android, aperto dal pulsante `DATA` | Stato della ricerca corrente | Nessun dato esterno inviato |
| Ora | Selettore nativo Android, aperto dal pulsante `ORA` | Stato della ricerca corrente | Nessun dato esterno inviato |
| Stazioni preferite | Stella su una stazione e lista preferiti locale | Solo identificativi stazione in `SharedPreferences` | Nessun account o sincronizzazione |
| Stazioni vicine | Azione esplicita nella sezione Stazioni | Nessuna posizione conservata | Solo `ACCESS_COARSE_LOCATION`, richiesta puntuale e senza background |
| Dettatura | Intent del riconoscitore vocale di sistema per singolo campo | Testo nel campo scelto | Nessun permesso microfono Binario; l’eventuale trascrizione è gestita dal servizio di sistema scelto dall’utente |

## Vincoli funzionali

La posizione serve esclusivamente a calcolare la distanza in linea d’aria tra la posizione appena richiesta e le coordinate statiche del catalogo locale di stazioni. Non viene scritta su disco, sincronizzata a companion o Voice Bridge, né usata per creare un profilo di mobilità.

La dettatura espone un pulsante nominato per ciascun campo. Il risultato viene ripulito, limitato e poi sottoposto alla stessa ricerca stazioni o numero treno dell’inserimento manuale. Se il riconoscitore non è disponibile o l’utente nega l’azione, l’app mantiene il campo invariato e fornisce un messaggio comprensibile a TalkBack.

Le funzioni di pianificazione restano dichiaratamente indipendenti dallo stato dei dati ferroviari: un risultato di ricerca non trasforma il catalogo dimostrativo in un feed live. In assenza di una fonte autorizzata, l’interfaccia mantiene la formulazione **“dati non disponibili in tempo reale”**.
