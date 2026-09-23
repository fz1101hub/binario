# Policy di audit delle dipendenze e degli input CI

**Aggiornata:** 23 settembre 2026

I due progetti JavaScript del repository applicano un controllo riproducibile sulle vulnerabilità **high** e **critical** durante la CI.

## Alexa Companion

`alexa-companion/` esegue `pnpm audit --audit-level high`. Il lockfile usa pnpm 10.34.5 e contiene versioni corrette di tRPC, Axios, Drizzle ORM, MySQL2, Nano ID, PostCSS, Vite, Vitest e dei transitivi con patch disponibili. La rimozione dei componenti template `AIChatBox` e `chart`, non raggiungibili dalla dashboard, ha eliminato Recharts, Streamdown e 303 pacchetti transitivi. Anche il runtime, il collector e il plugin JSX specifici di Manus sono stati rimossi dalla configurazione Vite: il companion non dipende più da essi neppure in sviluppo.

Il gate è verde soltanto se non esistono advisory high o critical.

## Companion Expo

`companion-expo/` esegue `pnpm run verify:dependency-audit`. Il verificatore rifiuta ogni advisory high o critical eccetto i due seguenti:

| Advisory | Dipendenza | Motivazione temporanea |
|---|---|---|
| `GHSA-w3rx-r6r6-pgpr` | `image-size@1.2.1` via Expo/Metro | Nessuna versione corretta dichiarata dal registro audit. Il parser è usato dalla toolchain di build solo su asset PNG versionati o generati dal repository. |
| `GHSA-5p2g-fcmc-qvqq` | `image-size@1.2.1` via Expo/Metro | Stessa dipendenza e stesso confine; i formati vulnerabili ICNS, JXL e HEIF non sono input dell'app né della pipeline di branding. |

L'allowlist è **chiusa per ID, modulo, versione `1.2.1`, unico percorso transitivo Expo/Metro e assenza di versione corretta**. Undici test dedicati coprono entrambi gli ID ammessi e le variazioni di advisory, modulo, disponibilità patch, versione, percorso e cardinalità dei finding. Se uno di questi confini cambia, la CI fallisce. Vitest, Vite e PostCSS sono stati aggiornati alle linee corrette; pnpm è fissato alla versione 10.34.5. `expo-system-ui` è installato esplicitamente per rendere completa e priva di warning la configurazione `userInterfaceStyle`.

## Criteri di rimozione dell'eccezione

L'eccezione `image-size` va rimossa al primo aggiornamento Expo/Metro compatibile che non trascini la versione vulnerabile, oppure quando viene pubblicata una patch compatibile della dipendenza. Prima dell'integrazione sono obbligatori typecheck, test TypeScript, verifica branding, audit, due `expo prebuild --clean` identici e build/lint dei moduli Android generati.

## Monitoraggio continuo

Gli alert Dependabot sono attivi sul repository. `.github/dependabot.yml` controlla ogni settimana i due workspace pnpm mantenuti, Gradle, GitHub Actions e i requisiti Python root. Gli aggiornamenti minor e patch sono raggruppati per ridurre il rumore; gli aggiornamenti major restano separati, così una migrazione di piattaforma come Expo o React Native non viene confusa con una patch ordinaria. Non è configurato alcun auto-merge: ogni proposta deve superare i gate del componente interessato ed essere revisionata prima dell'integrazione.

Al momento dell'attivazione, GitHub ha segnalato sul **default branch** 155 alert aperti: 4 critical, 59 high, 81 moderate e 11 low. Questo numero descrive `main` prima dell'integrazione della PR 26 e non costituisce un risultato di scansione del relativo head. La baseline va quindi ricampionata nel [cruscotto Dependabot](https://github.com/fz1101hub/binario/security/dependabot) dopo la promozione controllata dell'integrazione; fino ad allora non viene dichiarata azzerata.

## Dipendenze Gradle verificate

Il progetto Android/Wear usa `gradle/verification-metadata.xml` in modalità strict. Il file contiene un solo SHA-256 per ciascuno dei 1.041 artifact appartenenti a 599 componenti, inclusi plugin Android/Kotlin, metadata Maven e dipendenze transitive. Il Companion usa un secondo lock sorgente in `companion-expo/native/android/verification-metadata.xml`: copre 2.043 artifact di 1.127 componenti, inclusi React Native, Expo, plugin Gradle e Wear. Il plugin Expo lo copia byte per byte in `android/gradle/verification-metadata.xml` durante ogni prebuild pulito; il verifier release richiede che le due copie coincidano.

Non sono presenti algoritmi deboli, `trusted-artifacts`, chiavi ignorate o altre aperture. Gradle verifica automaticamente il grafo quando trova il metadata; entrambi i workflow nativi richiedono inoltre esplicitamente `--dependency-verification=strict`.[1]

`tools/verify_gradle_dependency_metadata.py` controlla struttura, copertura minima, componenti critici e assenza di bypass per entrambi i profili. Sei test coprono i due lock e provano la disattivazione del metadata, l'aggiunta di trust globale, checksum mancanti o malformati e componenti duplicati. Ogni aggiornamento proposto da Dependabot deve rigenerare il metadata interessato con il gate completo e sottoporre a review il relativo diff.

## Input CI immutabili

I workflow non eseguono tag GitHub Actions mobili: ogni `uses:` esterno è fissato al commit SHA completo risolto dalla API GitHub, mentre il commento conserva il major leggibile e Dependabot propone gli aggiornamenti. I runner usano le famiglie versionate `ubuntu-24.04` e `macos-15`, non gli alias `*-latest`. Queste label GitHub-hosted ricevono comunque aggiornamenti periodici e **non sono immagini VM immutabili bit-for-bit**; Xcode viene quindi selezionato e verificato separatamente. Una garanzia sull'intera VM richiederebbe un runner proprietario da immagine fissata, scelta infrastrutturale fuori dal repository corrente. Il servizio MariaDB 11.4 usa invece il digest immutabile del manifest OCI `sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b`, verificato tramite l'header `Docker-Content-Digest` del registry ufficiale.

Apple Watch seleziona esplicitamente **Xcode 16.4 build 16F6**, disponibile sull'[immagine ufficiale macOS 15](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-Readme.md), e non installa più l'ultima versione Homebrew di XcodeGen. Il workflow scarica l'asset ufficiale `xcodegen.zip` della [release 2.46.0](https://github.com/yonaskolb/XcodeGen/releases/tag/2.46.0), ne verifica il digest pubblicato e ricontrollato localmente `sha256:4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806`, quindi esegue il binario dall'archivio estratto. I filtri `paths` attivano il rail macOS anche quando cambiano installer, verifier, test, requisito PyYAML o lock: una modifica della catena di fiducia non può evitare il job che usa realmente XcodeGen.

`.github/ci-inputs-lock.json` schema v2 conserva l'insieme esatto di Actions, immagini, runner, runtime patch (`Node 22.23.2`, `Temurin 17.0.20+101`, `Python 3.12.10`, `pnpm 10.34.5`), tool e hash SHA-256 di tutti gli step `run` Apple risolti manualmente alla data indicata. Il gate offline `tools/verify_ci_pinning.py` usa PyYAML 6.0.3 da wheel hashata, visita semanticamente job, step, servizi e job container anche in flow-style o tramite anchor, richiede runner nell'allowlist revisionata e rifiuta env/defaults/shell aggiuntivi nel rail Apple, compresi hook come `BASH_ENV`. I test negativi coprono tag mobili, runtime `latest`, SHA/digest formalmente validi ma non revisionati, runner self-hosted camuffati, job container, anchor, no-op shell, mutazioni degli step e trigger incompleti. Un aggiornamento del lock richiede una nuova risoluzione esterna e review; durante l'esecuzione, checkout, pull dell'immagine e download XcodeGen falliscono comunque se il riferimento fissato non esiste.

## Riferimenti

[1]: https://docs.gradle.org/8.14.3/userguide/dependency_verification.html "Gradle 8.14.3 — Verifying dependencies"
