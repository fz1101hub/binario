# Policy di audit delle dipendenze e degli input CI

**Aggiornata:** 15 ottobre 2026

I due progetti JavaScript del repository applicano un controllo riproducibile sulle vulnerabilità **high** e **critical** durante la CI.

## Alexa Companion

`alexa-companion/` esegue `pnpm audit --audit-level high`. Il lockfile usa pnpm 10.34.5 e contiene versioni corrette di tRPC, Axios, Drizzle ORM, MySQL2, Nano ID, PostCSS, Vite, Vitest e dei transitivi con patch disponibili. La rimozione dei componenti template `AIChatBox` e `chart`, non raggiungibili dalla dashboard, ha eliminato Recharts, Streamdown e 303 pacchetti transitivi. Anche il runtime, il collector e il plugin JSX specifici di Manus sono stati rimossi dalla configurazione Vite: il companion non dipende più da essi neppure in sviluppo.

Il gate è verde soltanto se non esistono advisory high o critical. L’aggiornamento del 15 ottobre risolve anche le advisory low/moderate rilevate: Vitest 4.1.11, esbuild 0.25.12, Babel 7.29.6, body-parser 1.20.6, qs 6.16.0 e postcss-selector-parser 7.1.6. Sono verificati typecheck, suite, assenza di drift Drizzle, build e avvio con sole dipendenze di produzione.

L’adapter `ask-sdk-express-adapter` è stato rimosso per eliminare `node-forge@1.4.0` dal grafo Alexa di produzione: GHSA-86w9-cpqp-85rv non dispone di una release corretta. Il webhook usa ora Node/OpenSSL per RSA-SHA256 e PKIjs 3.4.1 con WebCrypto per la catena X.509; l’ASK SDK rimane per gli intent. Il verifier mantiene i controlli Amazon su URL del certificato, CA, SAN, scadenza, corpo originale, timestamp e Skill ID, con test di regressione sulle firme PKCS#1 malformate di richieste e certificati. Non sono previste deroghe all’audit Alexa.

## Companion Expo

`companion-expo/` esegue `pnpm run verify:dependency-audit`. Il verificatore rifiuta ogni advisory high o critical. Le advisory con una versione corretta pubblicata vengono risolte con override pnpm in `companion-expo/pnpm-workspace.yaml` (oggi: `brace-expansion` per le tre major, `compression` 1.8.2, `image-size` 2.0.3, `postcss`, `source-map-js` 1.2.2, `tinypool` 2.1.2, `undici`, `vite`).

Restano ammesse soltanto le advisory per cui il registro **non dichiara alcuna versione corretta** e che arrivano esclusivamente dalla toolchain Expo di build o sviluppo, non dal bundle React Native installato sui dispositivi:

| Advisory | Dipendenza e unico percorso | Motivazione temporanea |
|---|---|---|
| `GHSA-86w9-cpqp-85rv` | `node-forge@1.4.0` via `expo>@expo/cli` | Usato dalla CLI Expo per i certificati del dev server; non è incluso nell'app. Nessuna release corretta di `node-forge` esiste (1.4.0 è l'ultima). |
| `GHSA-vfj7-8cjw-p6xm` | `braces@3.0.3` via `expo>@expo/metro>metro-file-map>micromatch` | Usato da Metro durante il build su glob controllati dal progetto; non è incluso nell'app. Nessuna release corretta di `braces` esiste (3.0.3 è l'ultima). |

L'eccezione è **chiusa per ID, modulo, versione installata, unico percorso transitivo e assenza di versione corretta** (`scripts/dependency-audit-policy.mjs`). I test di `tests/dependency-audit-policy.test.ts` coprono ciascuna eccezione e le variazioni di ID, modulo, disponibilità della patch, versione, percorso e numero di finding; un altro test blinda l'elenco chiuso e gli override. Se uno di questi confini cambia, la CI fallisce. Il verificatore stampa comunque le eccezioni applicate. pnpm è fissato alla versione 10.34.5.

## Criteri di rimozione dell'eccezione

Ogni eccezione va rimossa appena il registro pubblica una versione corretta compatibile (in quel caso la policy la rende di nuovo bloccante e serve un override), oppure al primo aggiornamento Expo/Metro che non trascini più il pacchetto. Prima dell'integrazione sono obbligatori typecheck, test TypeScript, verifica branding, audit, due `expo prebuild --clean` identici e build/lint dei moduli Android generati.

## Monitoraggio continuo

`.github/dependabot.yml` diventa operativo soltanto quando viene promosso sul default branch `main`: GitHub valuta la configurazione Dependabot del default branch, quindi la presenza del file in un branch feature, `integration/**` o `stabilization/**` non abilita Dependabot. Questa modifica non dichiara né simula un'attivazione da un feature branch. Dopo la promozione su `main`, il file controlla ogni settimana i due workspace pnpm mantenuti, Gradle, GitHub Actions e i requisiti Python root. Gli aggiornamenti minor e patch sono raggruppati per ridurre il rumore; gli aggiornamenti major restano separati, così una migrazione di piattaforma come Expo o React Native non viene confusa con una patch ordinaria. Non è configurato alcun auto-merge: ogni proposta deve superare i gate del componente interessato ed essere revisionata prima dell'integrazione.

La baseline storica registrata sul **default branch** è di 155 alert aperti: 4 critical, 59 high, 81 moderate e 11 low. Descrive `main` prima dell'integrazione della PR 26 e non costituisce né un risultato di scansione del relativo head né prova dell'attivazione di questo file. La baseline va ricampionata nel [cruscotto Dependabot](https://github.com/fz1101hub/binario/security/dependabot) soltanto dopo la promozione controllata dell'integrazione su `main`; fino ad allora non viene dichiarata né aggiornata né azzerata.

## Dipendenze Gradle verificate

Il progetto Android/Wear usa `gradle/verification-metadata.xml` in modalità strict. Il file contiene un solo SHA-256 per ciascuno dei 1.044 artifact appartenenti a 601 componenti, inclusi plugin Android/Kotlin, metadata Maven e dipendenze transitive. Il Companion usa un secondo lock sorgente in `companion-expo/native/android/verification-metadata.xml`: copre 2.058 artifact di 1.133 componenti, inclusi React Native, Expo, plugin Gradle e Wear. Il plugin Expo lo copia byte per byte in `android/gradle/verification-metadata.xml` durante ogni prebuild pulito; il verifier release richiede che le due copie coincidano.

Non sono presenti algoritmi deboli, `trusted-artifacts`, chiavi ignorate o altre aperture. Gradle verifica automaticamente il grafo quando trova il metadata; entrambi i workflow nativi richiedono inoltre esplicitamente `--dependency-verification=strict`.[1]

`tools/verify_gradle_dependency_metadata.py` controlla struttura, copertura minima, componenti critici, checksum delle distribuzioni wrapper e assenza di bypass per entrambi i profili. Dieci test coprono i due lock e provano la disattivazione del metadata, l'aggiunta di trust globale, checksum mancanti o malformati e componenti duplicati. Il wrapper root è aggiornato a Gradle 8.14.6 con checksum ufficiale; il gate lint mantiene `warningsAsErrors` attivo. Ogni aggiornamento proposto da Dependabot deve rigenerare il metadata interessato con il gate completo e sottoporre a review il relativo diff.

## Input CI immutabili

I workflow non eseguono tag GitHub Actions mobili: ogni `uses:` esterno è fissato al commit SHA completo risolto dalla API GitHub, mentre il commento conserva il major leggibile e Dependabot propone gli aggiornamenti. I runner usano le famiglie versionate `ubuntu-24.04` e `macos-15`, non gli alias `*-latest`. Queste label GitHub-hosted ricevono comunque aggiornamenti periodici e **non sono immagini VM immutabili bit-for-bit**; Xcode viene quindi selezionato e verificato separatamente. Una garanzia sull'intera VM richiederebbe un runner proprietario da immagine fissata, scelta infrastrutturale fuori dal repository corrente. Il servizio MariaDB 11.4 usa invece il digest immutabile del manifest OCI `sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b`, verificato tramite l'header `Docker-Content-Digest` del registry ufficiale.

Apple Watch seleziona esplicitamente **Xcode 16.4 build 16F6**, disponibile sull'[immagine ufficiale macOS 15](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md), e non installa più l'ultima versione Homebrew di XcodeGen. Il workflow scarica l'asset ufficiale `xcodegen.zip` della [release 2.46.0](https://github.com/yonaskolb/XcodeGen/releases/tag/2.46.0), ne verifica il digest pubblicato e ricontrollato localmente `sha256:4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806`, quindi esegue il binario dall'archivio estratto. I filtri `paths` attivano il rail macOS anche quando cambiano installer, verifier, test del progetto, requisito PyYAML o lock; lo stesso step esegue `tests/test_verify_apple_watch_project.py`, che blocca drift di scheme, target watch-only, embedding WidgetKit e refresh timeline. Una modifica della catena di fiducia o del test che la controlla non può quindi evitare il job che usa realmente XcodeGen.

`.github/ci-inputs-lock.json` schema v3 conserva l'insieme esatto di Actions, immagini, runner, runtime patch (`Node 22.23.2`, `Temurin 17.0.20+101`, `Python 3.12.10`, `pnpm 10.34.5`), tool, hash SHA-256 di tutti gli step `run` Apple risolti manualmente alla data indicata e la policy semantica dei trigger `push`. Il gate offline `tools/verify_ci_pinning.py` usa PyYAML 6.0.3 da wheel hashata, visita semanticamente job, step, servizi e job container anche in flow-style o tramite anchor, richiede runner nell'allowlist revisionata e rifiuta env/defaults/shell aggiuntivi nel rail Apple, compresi hook come `BASH_ENV`. Verifica inoltre che i workflow costosi usino pull request path-filtered per i branch di lavoro e consentano `push` soltanto su `main` e `stabilization/**`: il primo riceve l'integrazione ordinaria, il secondo i gate post-merge della release; `integration/**` resta escluso per non duplicare la run della pull request. I filtri includono gli input condivisi: il contratto Wear attiva Android/Wear, mentre ogni modifica Companion esegue anche il verifier privacy. I test negativi coprono tag mobili, runtime `latest`, SHA/digest formalmente validi ma non revisionati, runner self-hosted camuffati, job container, anchor, no-op shell, mutazioni degli step e trigger incompleti. Un aggiornamento del lock richiede una nuova risoluzione esterna e review; durante l'esecuzione, checkout, pull dell'immagine e download XcodeGen falliscono comunque se il riferimento fissato non esiste.

## Riferimenti

[1]: https://docs.gradle.org/8.14.3/userguide/dependency_verification.html "Gradle 8.14.3 — Verifying dependencies"

## Aggiornamento del 26 ottobre 2026 — PR pendolari

L’audit CI del companion Alexa ha rilevato cinque advisory high in quattro pacchetti. Sono state selezionate versioni corrette compatibili con le major già usate:

| Pacchetto | Versione minima fissata | Advisory |
|---|---|---|
| follow-redirects | 1.16.1 | GHSA-q3jh-wrc6-jgq8, GHSA-8r9p-f939-6h3c |
| vite | 7.3.7 | GHSA-587h-7wmj-h5pm |
| zod | 4.6.6 | GHSA-mrhx-c7xc-m3w5 |
| body-parser 1.x | 1.20.9 | GHSA-27r3-jxmh-p828 |

Il lockfile è stato risolto con pnpm 10.34.5, senza eseguire script delle dipendenze, nel [job isolato 40388390939](https://github.com/fz1101hub/binario/actions/runs/40388390939). L’audit del grafo risolto termina con **zero high/critical e tre moderate**. Il controllo standard `pnpm install --frozen-lockfile` e il gate `pnpm audit --audit-level high` restano obbligatori nella CI applicativa; il job di risoluzione non li sostituisce e non è incluso nel branch dell’app.
