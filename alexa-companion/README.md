# Binario Alexa Companion

`alexa-companion/` contiene la **companion server-side per la Custom Skill Alexa di Binario**. L'app Android in `app/` resta il prodotto principale e la fonte di verità del dominio ferroviario e delle preferenze utente.

## Responsabilità

Il companion gestisce:

- webhook HTTPS della Custom Skill Alexa;
- account linking OAuth 2.0 con consenso esplicito e protezione CSRF;
- revoca e nuovo collegamento dell'account;
- profilo vocale minimo, versionato e autenticato;
- dashboard tecnica per collegamento, revoca e diagnostica;
- modello di interazione Alexa conservato in `skill-package/`.

Non è una seconda app Binario, non sostituisce l'app smartphone e non mantiene una fonte ferroviaria indipendente.

## Fonte dei dati

Le risposte Alexa devono derivare dallo stesso contratto dati dell'app principale o da snapshot sincronizzati che includano provider, timestamp, TTL e stato operativo. In assenza di una fonte autorizzata e fresca, la risposta resta fail-closed: **“dati non disponibili in tempo reale”**.

Wear OS e Apple Watch sono companion separati. Le loro preferenze locali non fanno parte dell'API pubblica Alexa.

## Stato

Il servizio dispone di hardening per build di produzione, cookie di sessione `HttpOnly`, consenso OAuth, allowlist dei redirect, CSRF, revoca effettiva, migrazioni versionate, limiti di richiesta e health check. La skill resta non pubblicabile finché non sono completati configurazione Amazon, test end-to-end, privacy policy e collegamento dati autenticato con l'app principale.

## Sviluppo

```bash
pnpm install --frozen-lockfile
pnpm run check
pnpm test
pnpm run build
```

Il comando di build verifica anche che il bundle di produzione non contenga runtime o token di anteprima Manus.

## Documentazione

- [`docs/ALEXA_SETUP.md`](docs/ALEXA_SETUP.md): configurazione e pubblicazione Alexa.
- [`docs/ANDROID_COMPANION_CONTRACT.md`](docs/ANDROID_COMPANION_CONTRACT.md): relazione tra app principale e companion.
- [`docs/REMOTE_PROFILE_SYNC.md`](docs/REMOTE_PROFILE_SYNC.md): contratto del profilo vocale remoto.
- [`../docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`](../docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md): decisione architetturale.
- [`../docs/architecture/TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md`](../docs/architecture/TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md): destinazione delle funzioni più avanzate che non appartengono ad Alexa.
