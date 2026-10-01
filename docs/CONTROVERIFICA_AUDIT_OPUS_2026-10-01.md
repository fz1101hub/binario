# Controverifica dell’audit Claude Opus 5.5 — 1 ottobre 2026

**Perimetro:** audit indipendente `docs/audit-independent-2026-09-30-intero-progetto.md` sul branch documentale `docs/audit-integration-20260930`, relativo allo SHA `1d3cdee7b48d342dff5ec3713df6451cb4411e43` (cima della pila #33 → #34 → #32). Questa controverifica ha esaminato separatamente **i sette High nuovi**, non ha riclassificato individualmente i 30 Medium né i Low. La branch di correzione è stacked sopra la Draft PR #32; le PR precedenti non vengono riscritte, fuse o promosse.

## Giudizio sui finding prioritari

| ID | Verdetto indipendente | Modifica sulla branch di remediation | Evidenza e limite |
|---|---|---|---|
| N-H1 | Confermato, blocco launcher Android | `@JvmOverloads` sul costruttore `TrainViewModel` e test JVM sul costruttore `(Application)` | Factory Lifecycle e bytecode hanno riprodotto il `NoSuchMethodException` sull’HEAD auditato. Non è un avvio su dispositivo. |
| N-H2 | Confermato, input HTTP ostile | `Object.hasOwn` su entrambe le mappe locale; regressioni `constructor`, `constructor-foo`, `__proto__`, ecc. e GET HTTP | Test HTTP Alexa + typecheck locali verdi; nessun test pubblico contro un deployment reale. |
| N-H3 | Confermato, crash Express 4 per rejection OAuth | Wrapper Promise→`next` sulle tre route OAuth; middleware terminale `server_error` senza dettagli, `no-store`; test con tre guasti DB iniettati | Errori transitori ora producono 500 e l’istanza di test serve una richiesta successiva. Nessuna prova del deadlock MariaDB qui. |
| N-H4 | Confermato, ID GTFS duplicato per service day | Suffisso ISO della data di servizio per l’ID del servizio; regressione a due date; adattamento del fallback realtime | Un trip reale Trenord era duplicabile con stessa chiave Compose. La review della correzione ha scoperto un **secondo rischio**: update realtime attribuito a due occorrenze. Ora, senza run-date certa, il fallback lascia **entrambe statiche**; gate aggiuntivo su data/ora di partenza e test dedicato. |
| N-H5 | Bug confermato, **severità Medium**, non High | Il 41° salvataggio viene rifiutato senza riscrittura; messaggio di archivio pieno IT/EN/DE/FR/ES, anteprima conservata in import parziali; una coda unica serializza tutte le mutazioni del documento locale | Test prima/dopo la capienza e concorrenti fra import, aggiunta, ricorrenza e completamento; non è prova su dispositivo Expo. |
| N-H6 | Blocco di compilazione Watch confermato dalla firma API, ma prova solo statica | `AccessoryWidgetBackground()` nello `ZStack`; test statico | Serve typecheck Xcode/WidgetKit su macOS per il target effettivo. |
| N-H7 | Bundle ID Watch incorporato non prefissato, confermato dalla configurazione | Target Watch `it.binario.ios.watchkitapp`; WidgetKit annidato `it.binario.ios.watchkitapp.widgets`; test sui prefissi e sul grafo embed | Il progetto standalone mantiene ID separati `it.binario.watch*` e lo stesso App Group. La scelta finale degli identifier/provisioning va validata su Xcode/Apple Developer. |

La review autonoma successiva delle correzioni ha identificato anche una race fra mutatori diversi di `local-routes.ts`; la coda unica ne è la remediation. Non è corretta la conclusione “tutti i nuovi finding dell’audit risolti”: questa tranche riguarda sei High e il bug di capienza, mentre i Medium Apple/Android/Companion/Alexa restano in backlog.

## Evidenze locali sulla branch di lavoro

- Android `:app:testDebugUnitTest :app:lintDebug` con dependency verification `strict`: **BUILD SUCCESSFUL**, inclusi test GTFS notturni, matching realtime e constructor guard. Avvio reale della `MainActivity` non eseguito.
- Alexa: `pnpm check`, **141 test passati / 4 skipped**, build production + verifier: verdi. I 4 test MariaDB sono skipped nella suite standard; non sono qui conteggiati come eseguiti.
- Companion: `tsc --noEmit`, **107 test passati**, branding, manifest e managed config: verdi. La dependency audit segnalata dal rapporto per `brace-expansion` resta da eseguire/risolvere separatamente.
- Python: **102 test passati**; verifier CI pinning/privacy: verdi. BinarioKit Swift 6.4 su Linux: **34 test passati**. Il widget Watch ha un parse Swift e il grafo Apple ha invarianti statiche; **non** è stata eseguita una build Xcode iPhone/Watch in questa sessione.
- `git diff --check`: verde. Queste prove sono locali e non equivalgono a una CI remota verde o a un archivio firmato.

## Limiti e prossimo ordine di lavoro

L’audit Opus è utile e ha intercettato un blocker di avvio Android che i test precedenti non vedevano; tuttavia il report stesso segnala tre riavvii, copertura incompleta di due lotti, pochissimi candidati confutati e assenza di toolchain Apple nel suo ambiente. La controverifica locale ha confermato il blocco di avvio sul bytecode effettivamente compilato e declassato N-H5 per severità. I **30 Medium** richiedono una passata separata con prove mirate; priorità suggerite: regressione firma Companion N-M18 e gate dipendenze N-M19; calendario RFI N-M1; grafo Apple installabile (Package platforms, catalogo Watch, Info.plist `NSExtension`, Xcode build); data/provenance e notifiche notturne Android; copertura/freshness GTFS iOS. Nessun dato ferroviario inventato va introdotto per aumentare la copertura.

L’audit riporta un’annotazione GitHub Actions sui pagamenti/limite di spesa dell’account. **Questa annotazione non è stata verificabile con il token GitHub del task** (`check-runs` restituisce 403). Il fatto operativo comprovato resta che le run osservate non hanno step/log applicativi: il gate remoto non è verde. La licenza, la governance privacy, le credenziali/firme e l’hardware restano decisioni o verifiche del proprietario. Non effettuare merge, tag o release sulla base di questo report.
