# Binario — predisposizione Alexa Custom Skill

## Posizione nel prodotto Binario

L’app Android per smartphone è il prodotto principale e la fonte di verità. Alexa è un companion dello stesso livello di Wear OS e Apple Watch: espone una vista vocale ridotta e non possiede dati ferroviari o preferenze indipendenti. L’utente abilita nell’app **Altro → Integrazioni → Comandi vocali e Alexa** la preferenza di collegamento; solo dopo un’autenticazione effettiva nel companion Alexa tratte, monitoraggi e snapshot freschi potranno essere sincronizzati. L’interruttore Android non crea né conserva credenziali Alexa e non equivale a un account collegato.

## Stato della presente build

Il companion è predisposto, ma intenzionalmente **non attivo** quando `ALEXA_ENABLED=false` e tutte le variabili Alexa restano vuote. Per attivarlo impostare `ALEXA_ENABLED=true` e configurare insieme `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET`, `ALEXA_OAUTH_REDIRECT_URIS` e `VOICE_TOKEN_PEPPER`. Il boot di produzione rifiuta sia una modalità non esplicita sia configurazioni parziali o contraddittorie. In stato disabilitato `POST /api/alexa/webhook` restituisce `503 alexa_not_configured`; non accetta richieste parziali o non firmate.

## Configurazione da eseguire dopo la creazione dell’account sviluppatore

| Console Alexa     | Valore Binario                                        |
| ----------------- | ----------------------------------------------------- |
| Tipo skill        | Custom Skill, locale italiano (`it-IT`)               |
| Endpoint HTTPS    | `https://{dominio-binario}/api/alexa/webhook`         |
| Account linking   | OAuth 2.0, authorization code grant                   |
| Authorization URI | `https://{dominio-binario}/api/alexa/oauth/authorize` |
| Access token URI  | `https://{dominio-binario}/api/alexa/oauth/token`     |
| Scope             | `routes.read`                                         |
| Skill ID          | Da inserire come `ALEXA_SKILL_ID` nei segreti server  |

L’endpoint webhook mantiene l’ASK SDK ufficiale per gli intent. Prima del dispatch, `server/voice/alexaWebhook.ts` verifica il corpo originale con `Signature-256` (RSA-SHA256 tramite Node/OpenSSL), la catena X.509 con PKIjs/WebCrypto e le CA pubbliche incorporate in Node, il SAN esatto `echo-api.amazon.com`, la validità dei certificati, il timestamp entro 150 secondi e lo Skill ID. Sono rifiutati anche timestamp futuri oltre la stessa tolleranza e ID discordanti fra sessione e contesto. Il download ammette soltanto HTTPS da `s3.amazonaws.com/echo.api/`, senza redirect, con timeout e dimensione limitati; la cache è limitata a 64 catene e cinque minuti, senza superare la scadenza dei certificati. Come l’adapter precedente, non viene effettuato un controllo online CRL/OCSP.

L’adapter Express precedente è stato rimosso perché trascinava `node-forge` vulnerabile senza patch disponibile (GHSA-86w9-cpqp-85rv). Nessuna eccezione è stata aggiunta al gate di audit Alexa. I test includono firme e certificati con DigestInfo PKCS#1 alterato, oltre a certificati non attendibili, scaduti e con SAN errato. Amazon richiede HTTPS e la convalida delle richieste per una Custom Skill ospitata come web service.[1] Il certificato della pubblicazione deve essere firmato da una CA attendibile; un certificato autofirmato è ammesso solo nei test.[1]

## Modello di interazione da creare

| Intent              | Esempi di utterance italiane                                         | Slot                      |
| ------------------- | -------------------------------------------------------------------- | ------------------------- |
| `NextTrainsIntent`  | “quali sono i prossimi treni”, “prossimi treni per {routeName}”      | `routeName` facoltativo   |
| `TrainStatusIntent` | “stato del treno {trainNumber}”, “il treno {trainNumber} è puntuale” | `trainNumber` facoltativo |
| `MyRoutesIntent`    | “le mie tratte”, “elenca le tratte salvate”                          | nessuno                   |

Tutti gli intent che restituiscono dati personali richiedono un access token valido. Senza account collegato la skill pronuncia le istruzioni di collegamento e restituisce una LinkAccount card. Quando manca una fonte ferroviaria live autorizzata, la skill usa esattamente: **“dati non disponibili in tempo reale”**.

Se Amazon apre l'autorizzazione prima che l'utente abbia una sessione dashboard, il companion conserva **solo** la richiesta già verificata in un cookie firmato `__Host-`, `Secure`, `HttpOnly`, `SameSite=Lax`, con `Path=/` e TTL di dieci minuti. Dopo il callback dashboard la richiesta è verificata di nuovo contro client e callback URI consentiti, quindi viene cancellata e viene mostrata la stessa pagina di consenso CSRF-protetta. Il cookie non contiene credenziali.

## Privacy, sicurezza e pubblicazione

Il companion conserva tratte e metadati minimi del collegamento; token, authorization code e identificativi Alexa sono memorizzati soltanto come hash HMAC server-side. Non vengono registrati nei log origine, destinazione, nome della tratta, numero treno, token o richiesta vocale. I codici OAuth sono monouso e scadono dopo cinque minuti; gli access token scadono dopo un’ora e i refresh token ruotano e sono revocabili.

Prima della pubblicazione, predisporre pagine HTTPS di privacy policy e termini, indicare con chiarezza quali informazioni usa la skill e fare una beta privata. Amazon richiede tali pagine e account linking conforme a OAuth 2.0 per le skill con dati personalizzati.[2]

## Riferimenti

[1]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html "Amazon Alexa — Host a Custom Skill as a Web Service"
[2]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/security-testing-for-an-alexa-skill.html "Amazon Alexa — Security Requirements for Alexa Skills"
