# Binario — predisposizione Alexa Custom Skill

## Posizione nel prodotto Binario

L’app Android per smartphone è il prodotto principale e la fonte di verità. Alexa è un companion dello stesso livello di Wear OS e Apple Watch: espone una vista vocale ridotta e non possiede dati ferroviari o preferenze indipendenti. L’utente abilita nell’app **Altro → Integrazioni → Comandi vocali e Alexa** la preferenza di collegamento; solo dopo un’autenticazione effettiva nel companion Alexa tratte, monitoraggi e snapshot freschi potranno essere sincronizzati. L’interruttore Android non crea né conserva credenziali Alexa e non equivale a un account collegato.

## Stato della presente build

Il companion è predisposto, ma intenzionalmente **non attivo** quando `ALEXA_ENABLED=false` e tutte le variabili Alexa restano vuote. Per attivarlo impostare `ALEXA_ENABLED=true` e configurare insieme `ALEXA_SKILL_ID`, `ALEXA_OAUTH_CLIENT_ID`, `ALEXA_OAUTH_CLIENT_SECRET`, `ALEXA_OAUTH_REDIRECT_URIS` e `VOICE_TOKEN_PEPPER`. Il boot di produzione rifiuta sia una modalità non esplicita sia configurazioni parziali o contraddittorie. In stato disabilitato `POST /api/alexa/webhook` restituisce `503 alexa_not_configured`; non accetta richieste parziali o non firmate.

## Configurazione da eseguire dopo la creazione dell’account sviluppatore

| Console Alexa     | Valore Binario                                        |
| ----------------- | ----------------------------------------------------- |
| Tipo skill        | Custom Skill, locale principale italiano (`it-IT`); `skill-package/` dichiara anche `en-US`, `en-GB`, `de-DE`, `fr-FR`, `es-ES`, `ar-SA`, `hi-IN`, `ja-JP`, `pt-BR` (vedi `docs/l10n/LOCALES.md`) |
| Invocation name   | `it-IT` «binario treni» (due parole, come «binario trains», «binario züge», «binario trenes» negli altri locale); vedi sotto |
| Endpoint HTTPS    | `https://{dominio-binario}/api/alexa/webhook`         |
| Account linking   | OAuth 2.0, authorization code grant                   |
| Authorization URI | `https://{dominio-binario}/api/alexa/oauth/authorize` |
| Access token URI  | `https://{dominio-binario}/api/alexa/oauth/token`     |
| Your Client ID    | Lo stesso valore di `ALEXA_OAUTH_CLIENT_ID`           |
| Your Secret       | Lo stesso valore di `ALEXA_OAUTH_CLIENT_SECRET`       |
| Your Authentication Scheme | **HTTP Basic (Recommended)**, il default della console; anche *Credentials in request body* funziona |
| Scope             | `routes.read`                                         |
| Skill ID          | Da inserire come `ALEXA_SKILL_ID` nei segreti server  |

Il token endpoint accetta le credenziali del client in uno solo dei due modi previsti da RFC 6749 §2.3.1: nell'header `Authorization: Basic` (id e secret form-URL-encoded, poi base64) oppure come `client_id` e `client_secret` nel corpo. Una richiesta che li usa entrambi, o con credenziali errate, riceve `401 invalid_client` con `WWW-Authenticate: Basic`. Ogni risposta del token endpoint porta `Cache-Control: no-store` e `Pragma: no-cache`.

Amazon chiama webhook e token endpoint da pochi indirizzi condivisi: per IP si contano soltanto le richieste che falliscono la verifica della firma (webhook, 60 al minuto) o l'autenticazione del client (token, 30 al minuto). Il traffico verificato ha budget propri: 60 richieste al minuto per utente Alexa firmato (chiave: digest di skill ID e user ID) e 1.200 richieste token al minuto per il client OAuth autenticato. Le pagine aperte dal browser (consenso Alexa, login dashboard) restano limitate a 30 richieste al minuto per IP.

L’endpoint webhook mantiene l’ASK SDK ufficiale per gli intent. Prima del dispatch, `server/voice/alexaWebhook.ts` verifica il corpo originale con `Signature-256` (RSA-SHA256 tramite Node/OpenSSL), la catena X.509 con PKIjs/WebCrypto e le CA pubbliche incorporate in Node, il SAN esatto `echo-api.amazon.com`, la validità dei certificati, il timestamp entro 150 secondi e lo Skill ID. Sono rifiutati anche timestamp futuri oltre la stessa tolleranza e ID discordanti fra sessione e contesto. Il download ammette soltanto HTTPS da `s3.amazonaws.com/echo.api/`, senza redirect, con timeout e dimensione limitati; la cache è limitata a 64 catene e cinque minuti, senza superare la scadenza dei certificati. Come l’adapter precedente, non viene effettuato un controllo online CRL/OCSP.

L’adapter Express precedente è stato rimosso perché trascinava `node-forge` vulnerabile senza patch disponibile (GHSA-86w9-cpqp-85rv). Nessuna eccezione è stata aggiunta al gate di audit Alexa. I test includono firme e certificati con DigestInfo PKCS#1 alterato, oltre a certificati non attendibili, scaduti e con SAN errato. Amazon richiede HTTPS e la convalida delle richieste per una Custom Skill ospitata come web service.[1] Il certificato della pubblicazione deve essere firmato da una CA attendibile; un certificato autofirmato è ammesso solo nei test.[1]

## Modello di interazione da creare

**Nome di invocazione italiano — decisione del titolare.** Amazon non ammette un nome di una sola parola comune senza prova di marchio, e «binario» è una parola d'uso quotidiano («binario 3»). Il modello `it-IT` usa quindi «binario treni», coerente con gli altri locale; nome della skill, frasi d'esempio di `skill.json` e suggerimenti Echo Show sono allineati. Se il titolare dispone di un marchio registrato «Binario» e preferisce l'invocazione di una parola, deve ripristinarla in `it-IT.json`, `skill.json` e `shared/i18n.ts` e allegare la prova del marchio alla certificazione. `server/voice/alexaSkill.test.ts` verifica per tutti i locale che il nome abbia almeno due parole minuscole e che ogni frase d'esempio e ogni suggerimento Echo Show, tolti invocazione e connettore («chiedi a … », «ask … for»), corrisponda a un sample dell'intent.

| Intent              | Esempi di utterance italiane                                         | Slot                      |
| ------------------- | -------------------------------------------------------------------- | ------------------------- |
| `NextTrainsIntent`  | “quali sono i prossimi treni”, “prossimi treni per {routeName}”      | `routeName` facoltativo   |
| `TrainStatusIntent` | “stato del treno {trainNumber}”, “il treno {trainNumber} è puntuale” | `trainNumber` facoltativo |
| `MyRoutesIntent`    | “le mie tratte”, “elenca le tratte salvate”                          | nessuno                   |

Il nome detto per `routeName` (`AMAZON.SearchQuery`, trascritto in minuscolo e spesso senza accenti né punteggiatura) è confrontato con le tratte salvate dopo una normalizzazione (NFKD senza diacritici, punteggiatura come spazio, spazi compattati), poi per parole o prefissi. Se corrisponde a più tratte la skill le elenca e chiede quale; se non corrisponde a nessuna dice di non averla trovata ed elenca le tratte salvate, a sessione aperta, invece di rispondere che non ci sono tratte. Gli elenchi parlati usano `Intl.ListFormat` e il terminatore della lingua («.», «।», «。»).

Tutti gli intent che restituiscono dati personali richiedono un access token valido. Senza account collegato la skill pronuncia le istruzioni di collegamento e restituisce una LinkAccount card. Quando manca una fonte ferroviaria live autorizzata, la skill usa esattamente: **“dati non disponibili in tempo reale”**.

Se Amazon apre l'autorizzazione prima che l'utente abbia una sessione dashboard, il companion conserva **solo** la richiesta già verificata in un cookie firmato `__Host-`, `Secure`, `HttpOnly`, `SameSite=Lax`, con `Path=/` e TTL di dieci minuti. La dashboard mostra allora la schermata di accesso: il login parte solo dal pulsante **Accedi**, mai da solo (un login automatico annullava il logout e, dopo una cancellazione, ricreava l'account passando dal callback OAuth). Dopo il callback dashboard la richiesta è verificata di nuovo contro client e callback URI consentiti, quindi viene cancellata e viene mostrata la stessa pagina di consenso CSRF-protetta. Il cookie non contiene credenziali.

## Schermate Echo Show (APL) — azione del titolare

Il codice della skill proietta su Echo Show le risposte che già pronuncia: **Partenze** (`NextTrainsIntent`), **Stato treno** (`TrainStatusIntent`), **Le mie tratte** (`MyRoutesIntent`, stesse cinque tratte lette a voce) e una home non personale per avvio, aiuto e richieste non riconosciute. L’implementazione è in `server/voice/presentation.ts`, la specifica in [`ECHO_SHOW_APL_UI_SPEC.md`](ECHO_SHOW_APL_UI_SPEC.md).

**Azione del titolare, obbligatoria perché le schermate compaiano:** nella console Alexa, *Build → Interfaces*, attivare **Alexa Presentation Language** e selezionare i profili Hub rotondo e Hub orizzontale (piccolo, medio, grande, extra-large), poi salvare e ricompilare il modello. In alternativa distribuire con ASK CLI `skill-package/skill.json`, che dichiara già l’interfaccia `ALEXA_PRESENTATION_APL` con viewport `HUB` rettangolari e rotondi: importare un manifest con `"interfaces": []` la disattiverebbe di nuovo. Finché l’interfaccia non è attiva, Alexa non segnala `Alexa.Presentation.APL` in `context.System.device.supportedInterfaces` e la skill resta solo vocale, identica a prima su ogni dispositivo.

Comportamento verificabile nei test (`server/voice/presentation.test.ts`, `server/voice/alexaSkill.test.ts`):

- il documento `Alexa.Presentation.APL.RenderDocument` viene aggiunto solo se il dispositivo dichiara APL con runtime ≥ 1.4; altoparlanti, Echo Spot 2024 e dispositivi senza APL ricevono la stessa voce e la stessa card;
- nessuna schermata personale prima dell’account linking verificato, dopo Stop/Annulla, su `SessionEndedRequest` o in caso di errore; il documento non contiene token, identificativi Alexa o Binario, origini o destinazioni;
- ogni schermata riporta lo stato del dato in testo («Dati non disponibili in tempo reale», o l’etichetta del catalogo dimostrativo), la fonte e l’ora della verifica (ora italiana), mai un orario, ritardo o binario inventato;
- layout per classi di viewport lette dal dispositivo (piccolo, medio, grande, extra-large, rotondo), token Binario 2.0 chiari e scuri, nessun pacchetto, font o immagine remota, target touch ≥ 64 dp;
- sui dispositivi con schermo la sessione resta aperta a microfono chiuso (`shouldEndSession` assente) perché il documento resti leggibile e i pulsanti funzionino; ogni tocco (`Alexa.Presentation.APL.UserEvent`) è accettato solo per due azioni in allowlist e riverifica l’account linking come una richiesta vocale.

Da verificare dal titolare con il simulatore della console e su hardware reale prima della pubblicazione: rendering per ciascun profilo, lettura VoiceView, testi lunghi, timeout dello schermo e tocchi dopo la fine della sessione.

## Privacy, sicurezza e pubblicazione

Il companion conserva tratte e metadati minimi del collegamento; token, authorization code e identificativi Alexa sono memorizzati soltanto come hash HMAC server-side. Non vengono registrati nei log origine, destinazione, nome della tratta, numero treno, token o richiesta vocale. I codici OAuth sono monouso e scadono dopo cinque minuti; gli access token scadono dopo un’ora. I refresh token ruotano a ogni uso, durano un anno dall’ultima rotazione e restano revocabili server-side (unlink, nuovo collegamento, cancellazione account). Se Amazon ripresenta entro 60 secondi un refresh token appena ruotato (risposta persa, due refresh concorrenti) riceve un nuovo token della stessa famiglia senza revoche; il riuso fuori da questa finestra revoca l’intera famiglia e invalida la generazione delle credenziali vocali. I token emessi a un retry e mai usati scadono alla rotazione successiva.

Prima della pubblicazione, predisporre pagine HTTPS di privacy policy e termini, indicare con chiarezza quali informazioni usa la skill e fare una beta privata. Amazon richiede tali pagine e account linking conforme a OAuth 2.0 per le skill con dati personalizzati.[2]

## Riferimenti

[1]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html "Amazon Alexa — Host a Custom Skill as a Web Service"
[2]: https://developer.amazon.com/en-US/docs/alexa/custom-skills/security-testing-for-an-alexa-skill.html "Amazon Alexa — Security Requirements for Alexa Skills"
