# Contratto per rinvii ai venditori ufficiali

Binario **non** gestisce checkout, pagamenti, credenziali, password, OTP, carte né sessioni degli operatori ferroviari. In questa versione non è configurato alcun venditore e quindi l’interfaccia non presenta pulsanti di acquisto.

| Condizione necessaria | Effetto se assente o non valida |
| --- | --- |
| Governance provider attiva e kill switch disattivato | Nessun rinvio |
| Licenza e fonte autorizzate, registrate come verificate | Nessun rinvio |
| URL HTTPS ufficiale verificato | Nessun rinvio |
| Host dell’URL incluso nell’allowlist del venditore | Nessun rinvio |
| Nessuna credenziale, porta non standard, query, frammento o parametro di redirect | Nessun rinvio |

Quando tutte le condizioni saranno soddisfatte, Binario potrà esporre esclusivamente il testo **«Continua sul sito ufficiale di …»** e aprire l’URL già convalidato dal contratto. Il venditore completerà la transazione nel proprio sito; Binario non riceve né conserva le credenziali del venditore. I test del codice convalidano la logica di sicurezza, ma non costituiscono autorizzazione commerciale, tecnica o contrattuale.
