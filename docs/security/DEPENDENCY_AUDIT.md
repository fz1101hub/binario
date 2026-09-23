# Policy di audit delle dipendenze JavaScript

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

L'allowlist è **chiusa per ID, modulo, versione `1.2.1`, percorso transitivo Expo/Metro e assenza di versione corretta**. Se compare un advisory diverso, se uno dei due advisory ottiene una versione corretta o se cambiano modulo, versione o percorso coinvolti, la CI fallisce. Vitest, Vite e PostCSS sono stati aggiornati alle linee corrette; pnpm è fissato alla versione 10.34.5. `expo-system-ui` è installato esplicitamente per rendere completa e priva di warning la configurazione `userInterfaceStyle`.

## Criteri di rimozione dell'eccezione

L'eccezione `image-size` va rimossa al primo aggiornamento Expo/Metro compatibile che non trascini la versione vulnerabile, oppure quando viene pubblicata una patch compatibile della dipendenza. Prima dell'integrazione sono obbligatori typecheck, test TypeScript, verifica branding, audit, due `expo prebuild --clean` identici e build/lint dei moduli Android generati.

## Monitoraggio continuo

Gli alert Dependabot sono attivi sul repository. `.github/dependabot.yml` controlla ogni settimana i due workspace pnpm mantenuti, Gradle e GitHub Actions. Gli aggiornamenti minor e patch sono raggruppati per ridurre il rumore; gli aggiornamenti major restano separati, così una migrazione di piattaforma come Expo o React Native non viene confusa con una patch ordinaria. Non è configurato alcun auto-merge: ogni proposta deve superare i gate del componente interessato ed essere revisionata prima dell'integrazione.
