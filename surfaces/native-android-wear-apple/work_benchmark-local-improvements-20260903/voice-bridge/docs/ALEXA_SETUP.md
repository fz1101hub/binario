# Binario — predisposizione Alexa Custom Skill

## Posizione nel prodotto Binario

Alexa non è un prodotto separato: è una superficie del profilo Binario gestito dall’app Android. L’utente abilita nell’app **Altro → Integrazioni → Comandi vocali e Alexa** la preferenza di collegamento; solo dopo un’autenticazione effettiva nel Voice Bridge tratte e monitoraggi potranno essere sincronizzati. L’interruttore Android non crea né conserva credenziali Alexa e non equivale a un account collegato.

## Stato della presente build

Il bridge è pronto, ma intenzionalmente **non attivo** finché non sono configurati `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET` e `VOICE_TOKEN_PEPPER`. In questo stato `POST /api/alexa/webhook` restituisce `503 alexa_not_configured`; non accetta richieste parziali o non firmate.

## Configurazione da eseguire dopo la creazione dell’account sviluppatore

| Console Alexa | Valore Binario |
|---|---|
| Tipo skill | Custom Skill, locale italiano (`it-IT`) |
| Endpoint HTTPS | `https://{dominio-binario}/api/alexa/webhook` |
| Account linking | OAuth 2.0, authorization code grant |
| Authorization URI | `https://{dominio-binario}/api/alexa/oauth/authorize` |
| Access token URI | `https://{dominio-binario}/api/alexa/oauth/token` |
| Scope | `routes.read` |
| Skill ID | Da inserire come `ALEXA_SKILL_ID` nei segreti server |

L’endpoint webhook usa l’ASK SDK ufficiale con verifica firma, catena certificati, timestamp e Skill ID. Amazon richiede HTTPS e la convalida delle richieste per una Custom Skill ospitata come web service.[1] Il certificato della pubblicazione deve essere firmato da una CA attendibile; un certificato autofirmato è ammesso solo nei test.[1]

## Modello di interazione da creare

| Intent | Esempi di utterance italiane | Slot |
|---|---|---|
| `NextTrainsIntent` | “quali sono i prossimi treni”, “prossimi treni per {routeName}” | `routeName` facoltativo |
| `TrainStatusIntent` | “stato del treno {trainNumber}”, “il treno {trainNumber} è puntuale” | `trainNumber` facoltativo |
| `MyRoutesIntent` | “le mie tratte”, “elenca le tratte salvate” | nessuno |

Tutti gli intent che restituiscono dati personali richiedono un access token valido. Senza account collegato la skill pronuncia le istruzioni di collegamento e restituisce una LinkAccount card. Quando manca una fonte ferroviaria live autorizzata, la skill usa esattamente: **“dati non disponibili in tempo reale”**.

## Privacy, sicurezza e pubblicazione

Il bridge conserva tratte e metadati minimi del collegamento; token, authorization code e identificativi Alexa sono memorizzati soltanto come hash HMAC server-side. Non vengono registrati nei log origine, destinazione, nome della tratta, numero treno, token o richiesta vocale. I codici OAuth sono monouso e scadono dopo cinque minuti; gli access token scadono dopo un’ora e i refresh token ruotano e sono revocabili.

Prima della pubblicazione, predisporre pagine HTTPS di privacy policy e termini, indicare con chiarezza quali informazioni usa la skill e fare una beta privata. Amazon richiede tali pagine e account linking conforme a OAuth 2.0 per le skill con dati personalizzati.[2]

## Riferimenti

[1]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html "Amazon Alexa — Host a Custom Skill as a Web Service"
[2]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/security-testing-for-an-alexa-skill.html "Amazon Alexa — Security Requirements for Alexa Skills"
