# Attivazione controllata dei servizi esterni Binario

Binario funziona localmente senza provider, account operatore, acquisto, Alexa o sincronizzazione cloud. Le integrazioni seguenti non si attivano tramite flag UI, URL pubblici, scraping o test fittizi: ogni gate deve essere soddisfatto e registrato dal titolare del prodotto.

## Provider ferroviari

| Gate | Evidenza richiesta | Comportamento fino al superamento |
| --- | --- | --- |
| Fonte e licenza | Contratto, licenza open verificabile o autorizzazione scritta; scope territoriale e di riuso. | `unconfigured` / nessun fetch. |
| Contratto tecnico | Identificativi stazione/treno, semantica ritardo/binario/cancellazione, limiti e SLA documentati. | Nessuna normalizzazione o previsione dedotta. |
| Privacy e sicurezza | Data map, DPA se applicabile, segreti server-side, rate-limit, timeout, circuit breaker, log minimizzati. | Nessun token provider nel client, watch, Alexa o URL. |
| Qualità | Fixture autorizzate, replay, conflitti, clock skew, TTL e kill switch testati. | Ogni dato ambiguo/scaduto degrada a indisponibile. |
| Operatività | Owner, dashboard, SLO, runbook incidente e rollback. | Nessun avviso realtime all’utente. |

Solo dopo i gate, il Rail Facts Gateway può ricevere una richiesta. Il gateway deve emettere uno snapshot con provider, natura del dato, confidence, timestamp di osservazione e TTL; le superfici Binario mostrano o notificano soltanto snapshot ancora validi.

## Venditori e biglietti

| Regola | Applicazione |
| --- | --- |
| Nessun checkout Binario | Non raccogliere carte, credenziali, password, OTP, PNR o sessioni del venditore. |
| Solo URL ufficiale verificato | HTTPS, host in allowlist, licenza/link ufficiale e governance provider attiva. |
| Nessun redirect dinamico | Rifiutare query, frammenti, credenziali e host non previsti. |
| Handoff esplicito | Etichetta unica: **«Continua sul sito ufficiale di …»**; apertura esterna. |

Il registro è vuoto nella build corrente, quindi non esiste né deve comparire un pulsante di acquisto.

## Alexa

Le richieste pubbliche possono usare il fallback del nucleo Binario senza collegare un account. Le tratte personali richiedono invece un collegamento **facoltativo** e separato, perché sono dati personali dell’area Binario. Amazon documenta espressamente che una Custom Skill può rimanere utilizzabile senza account linking per gli intent che non richiedono dati personali e richiedere il link soltanto quando serve. [1]

| Gate | Evidenza richiesta | Stato attuale |
| --- | --- | --- |
| Console Amazon | Skill ID, interaction model, endpoint HTTPS e configurazione locale/mercato. | Non configurata. |
| Sicurezza webhook | Verifica ASK SDK di firma, certificato, timestamp e Skill ID; test con richieste valide/non valide. | Codice predisposto, non test console. |
| Account personale | OAuth Authorization Code Grant, access token scoped/revocabile e link card solo all’intent personale. | Predisposto nel backend, non attivo. |
| Dati ferroviari | Stesso snapshot provider/freschezza di web, mobile e watch. | Nessuna fonte autorizzata: fallback obbligatorio. |
| Certificazione | Test console/dispositivo, privacy notice e review Amazon superate. | Non eseguita. |

> Non configurare un flusso voice-forward con OTP per aggirare l’accesso: per Binario l’account non è richiesto all’uso base e il linking personale deve restare un’azione esplicita nell’app Alexa.

## Registro decisionale minimo

Prima di accendere un’integrazione, archiviare nel registro di release: responsabile, data, ambiente, evidenza dei gate, scadenza di licenza/segreto, piano di rollback, kill switch e prova dell’esperienza di indisponibilità. Il superamento di un singolo test tecnico non autorizza da solo un servizio esterno.

## Riferimenti

[1]: https://developer.amazon.com/docs/alexaplus/account-linking/account-linking-for-custom-skills.html "Amazon Developer — Account Linking for Custom Skills"
