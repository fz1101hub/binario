# Verifica — Binario Voice Bridge

**Data:** 12 agosto 2026  
**Stato:** build predisposta per Alexa, volutamente non attiva senza credenziali sviluppatore.

## Esiti di verifica

| Controllo | Esito | Evidenza |
|---|---|---|
| Migrazione database | Superato | Presenti `railway_routes`, `alexa_account_links`, `oauth_authorization_codes` e `oauth_refresh_tokens`. |
| Test unitari | Superato | 7 file, 18 test; 0 failure, 0 error. Coprono guardie token, contratto tratte, tutte le procedure tRPC delle tratte con dinieghi/errori, OAuth fail-closed e webhook senza credenziali. |
| Controllo TypeScript | Superato | `pnpm check` completato senza errori. |
| Build produzione | Superato | `pnpm build` ha generato client e server bundle. |
| Webhook senza credenziali | Superato | `POST /api/alexa/webhook` restituisce HTTP `503`; il bridge resta fail-closed. |
| Deep link Android | Superato | `/app/train-search?number=1234` restituisce `302`, `Cache-Control: no-store` e URI `binario://train/search?number=1234`. |
| Verifica visiva | Superato | Dashboard desktop verificata con schermate tratte, Alexa, stato del dato e App Actions. |

## Checklist privacy e sicurezza

| Requisito | Implementazione |
|---|---|
| Risposte personali Alexa | Gli intent personali esigono un token di account linking verificato; senza token restituiscono una LinkAccount card. |
| Token e codici | Authorization code e refresh token sono salvati soltanto come hash HMAC; gli access token sono JWT con scadenza di un’ora. |
| Revoca | La dashboard revoca collegamento e refresh token del solo utente autenticato. |
| Webhook | Il route handler usa l’ASK SDK con verifica di firma, catena certificati, timestamp e Skill ID, una volta configurati. |
| Modalità non attiva | L’endpoint non processa richieste finché non esistono `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET` e `VOICE_TOKEN_PEPPER`. |
| Dati ferroviari | Senza provider autorizzato la voce usa esattamente: **“dati non disponibili in tempo reale”**. |
| Minimizzazione | Sono persistite solo tratte nominate e metadati di collegamento; non sono modellati biglietti, cronologia viaggi, posizione o audio. |
| HTTPS e backup | Il servizio gestito espone endpoint HTTPS; le app Android restano responsabili dei propri divieti di backup e dei rispettivi intent/deep link. |

## Attivazione dopo l’account sviluppatore Alexa

| Passaggio | Azione |
|---|---|
| 1 | Creare una **Custom Skill** italiana nella console Amazon e registrarne l’endpoint HTTPS `https://{dominio}/api/alexa/webhook`. |
| 2 | Configurare account linking OAuth 2.0 con authorization code grant: `/api/alexa/oauth/authorize` e `/api/alexa/oauth/token`. |
| 3 | Inserire `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET` e `VOICE_TOKEN_PEPPER` tramite la gestione segreti del progetto. |
| 4 | Eseguire test della console Alexa e beta privata; testare i casi senza collegamento, token scaduto, firma non valida e assenza feed live. |
| 5 | Pubblicare privacy policy e termini HTTPS prima della certificazione. |

> Il bridge non deve essere dichiarato operativo né usato per informazioni ferroviarie reali finché non è presente una fonte autorizzata e non è conclusa la configurazione in console Alexa.

## Riferimenti

[1]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html "Amazon Alexa — Host a Custom Skill as a Web Service"
[2]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/account-linking-for-custom-skills.html "Amazon Alexa — Account Linking for Custom Skills"
[3]: https://developer.android.com/develop/devices/assistant/overview "Android Developers — Google Assistant and App Actions"
