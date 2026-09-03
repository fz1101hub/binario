# Resilienza offline e controllo dei dati

## Regola di prodotto

Binario non conserva né sintetizza informazioni ferroviarie realtime per garantirsi una vista offline. In mancanza di una fonte autorizzata, di una connessione disponibile, di una risposta valida o di freschezza entro scadenza, il prodotto comunica **indisponibilità**. Non deduce assenza di ritardi, binari, cancellazioni, posizioni o avvisi.

| Superficie | Dato locale ammesso | Comportamento senza connettività o alla scadenza |
|---|---|---|
| App web | Tratte personali, preferiti e recenti salvati nel profilo | Le tratte restano consultabili; lo stato ferroviario resta indisponibile. |
| Companion Expo | Solo `CompanionSnapshot` valido fino a `expiresAtUtcMs` e token dispositivo in SecureStore | Non invia richieste se offline; allo scadere sostituisce lo snapshot con indisponibilità e sincronizza tale stato a widget/watch. |
| Widget, Wear OS, Apple Watch | Solo `WidgetPayload`/`WatchPayload` derivato dal medesimo snapshot | Rifiuta payload senza timestamp/scadenza validi o fuori TTL; nessun polling autonomo. |
| Alexa e notifiche | Decisione server derivata dal contratto condiviso | Gli avvisi vengono soppressi per fonte non verificata, conflitto, assenza o scadenza. |

## Controllo utente dei dati

La Home autenticata offre un centro privacy che consente di esportare in JSON i dati Binario e di eliminarli dopo conferma esplicita della parola `ELIMINA`. L’esportazione comprende tratte (attive e archiviate), preferiti/recenti, profilo opzionale, metadati dei device e metadati dei collegamenti Alexa. Esclude intenzionalmente password, cookie, token, codici di pairing, hash, audio, coordinate, biglietti e dati ferroviari realtime.

La cancellazione è una transazione unica sul perimetro Binario: elimina tratte, preferenze stazione, profilo, codici di pairing, dispositivi, codici/refresh token vocali e metadati Alexa. L’account tecnico resta perché è gestito dal sistema di autenticazione e consente, se l’utente lo desidera, un utilizzo nuovo dell’app. La rimozione dei device rende immediatamente non utilizzabili i relativi bearer.

## Limiti espliciti

Questo documento descrive i controlli tecnici oggi implementati; non sostituisce informativa privacy, registro del trattamento, DPA, DPIA o valutazione legale. Prima di un’offerta pubblica occorre definire tempi di retention, titolarità/responsabilità dei fornitori e un recapito security realmente presidiato.
