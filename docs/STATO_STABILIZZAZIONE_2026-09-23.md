# Stato consolidato della stabilizzazione Binario

**Data:** 23 settembre 2026  
**Autore:** Manus AI  
**Base:** `stabilization/v0.20.4`  
**Branch di integrazione:** `integration/stabilization-complete-v0204`

## Conclusione

Il piano di stabilizzazione è stato sviluppato fino al limite delle decisioni proprietarie e delle prove su dispositivi reali. Il branch di integrazione unisce le correzioni Android, Wear OS, Companion Expo e Alexa; include inoltre il contratto smartwatch v1, il dominio `PlannedJourney`, la pipeline GTFS riproducibile e la pulizia del repository. Le correzioni non sono una riscrittura: mantengono l'app Android come prodotto principale e trattano Wear, Companion Expo, Apple Watch e Alexa come superfici subordinate.

I gate locali del codice sono verdi. Restano fuori dalla conclusione tecnica la scelta della licenza, le credenziali di firma, il canale distributivo Wear, il provider OAuth di produzione e la promozione del branch in `main`. Il test telefono-orologio reale e la certificazione Alexa richiedono inoltre hardware o account esterni.

## Perimetro integrato

| Area | Risultato |
|---|---|
| Alexa Companion | Runtime e superfici WebDev generiche rimossi; OAuth con consenso e CSRF; allowlist dei redirect; richiesta `authorize` conservata e rivalidata attraverso il login; revoca opponibile; migrazioni provate su MariaDB vuoto e in upgrade; handler built-in; health check; limiter dedicato al webhook; configurazione fail-closed all'avvio. Il servizio resta separato dall'app e dagli smartwatch.[1] |
| Dati ferroviari | Download bounded e single-flight, dispatcher IO/CPU espliciti, indice GTFS compatto, regressione a heap limitato, fuso ferroviario coerente, semantica del giorno di servizio, festività, gzip, `stop_sequence=0`, operatori sconosciuti e policy provider applicate. La CI impone ora integrità e almeno 30 giorni di validità; AMT e Cotral restano catalogati ma disabilitati finché non esiste un feed affidabile.[2] [3] |
| Wear OS | Package telefonico e Wear coerenti per il Data Layer, sharing disattivato per default, refresh limitato, cancellazione del Data Item e della cache, invalidazione runtime al TTL, chiavi UI univoche, manifest e policy `standalone=false` documentati. Manifest, anteprime Tile, backup policy e copy UI sono verificati dal lint.[4] |
| Contratto smartwatch | Schema v1 chiuso e bounded, TTL e provenienza espliciti, fixture golden Android–Wear, Companion–Wear e iOS–Apple Watch, decoder fail-closed e fallback `unknown`. Il canale Companion→Wear trasporta e valida ora il contratto effettivo, e il prebuild rigenera anche i test Kotlin golden.[5] |
| Android UX | Deep link one-shot, lifecycle e Back corretti, shortcut, widget per istanza, refresh e freshness, allarmi ripristinabili, fascia silenziosa, permessi preservati alla rotazione, backup bounded, autocomplete calcolato fuori dalla composizione e preferenze robuste. Il copy UI della schermata principale, del configuratore widget, delle notifiche e del selettore tema è centralizzato nelle risorse Android.[6] |
| Accessibilità e voce | Stringhe a risorsa e semantiche migliorate; label dinamiche, stati, filtri e annunci delle superfici Android/Wear sono localizzabili; la dettatura distingue esiti confermati, ambigui e irrisolti e applica automaticamente soltanto stazioni confermate.[7] |
| PlannedJourney | Dominio personale offline distinto da `SavedRoute`; persistenza locale versionata e bounded; schermata Agenda raggiungibile; itinerari multi-tratta, reminder, checklist, ricorrenze finite, lifecycle, deduplica, import/export JSON con preview conflitti e calendario ICS bounded.[8] |
| Companion Expo | Bridge nativi, shared text, reminder e archivio locale corretti; sincronizzazione remota nascosta; semantiche accessibili; branding e permessi verificati; prebuild Android riproducibile comprensivo dei test nativi.[9] |
| Repository | Documenti storici spostati fuori dalla radice; todo di sessione rimossi; snapshot `surfaces/` preservato nel branch archivio e rimosso dalla linea attiva.[10] [11] |

## Evidenze di verifica

| Gate | Esito |
|---|---|
| Asset e freshness GTFS | **Verde:** 10 pack, nessun pack scaduto o in scadenza nel periodo minimo; manifest, checksum e registro runtime coerenti. |
| Regressione memoria GTFS | **Verde:** feed sintetico da 1,35 milioni di righe eseguito con heap JVM massimo di 256 MiB. |
| Android e Wear | **Verde:** 277 test app e 9 test Wear senza failure; compilazione dei test UI; lint debug a **zero issue** per entrambi i moduli, assemble debug, assemble release minificata e `lintVitalRelease`; 194 task nel gate release finale. La release è unsigned perché non sono state fornite credenziali di firma. |
| Alexa Companion | **Verde:** TypeScript, 64 test ordinari e 2 test di migrazione su MariaDB reale; build di produzione e smoke test fail-closed/`healthz`. |
| Companion Expo TypeScript | **Verde:** typecheck, 60 test in 13 file, branding, managed config e release inputs. Due `expo prebuild --clean` consecutivi hanno prodotto output identico, inclusi i test Kotlin golden. |
| Companion Expo nativo | **Verde:** 3 test app e 3 test Wear; lint e assemble debug di app e Wear generati; 452 task nel gate nativo completo del contratto. Gli avvisi residui provengono principalmente dalle dipendenze Expo/React Native deprecate, non da errori di compilazione. |
| Privacy e supply chain | **Verde:** verificatore privacy, integrità asset e policy lockfile. |
| Apple Watch | Il rail macOS isolato ha compilato e testato il contratto; manca intenzionalmente un produttore iOS reale e quindi non esiste una prova end-to-end iPhone–Watch. |

## Decisioni lasciate in sospeso

| Decisione | Stato tecnico | Azione proprietaria necessaria |
|---|---|---|
| Licenza | `LICENSE` non viene inventata. Alexa dichiara MIT, ma ciò non autorizza ad applicarla all'intero repository. | Scegliere MIT o un'altra licenza. |
| Firma release Android/Wear | R8, shrinking e build release sono verificati; il materiale di firma non deve entrare nel repository. | Configurare i quattro segreti `BINARIO_RELEASE_*` in un ambiente di release. |
| Distribuzione Wear | Il prodotto è coerente come companion non standalone e usa lo stesso application ID del telefono. | Scegliere track Wear in Google Play o distribuzione di collaudo e svolgere la prova telefono–watch. |
| OAuth dashboard Alexa | Il codice è operabile, ma il portale OAuth dipende dalla scelta di hosting. | Confermare hosting Manus oppure configurare un provider OAuth sostitutivo. |
| Apple Watch | Il prototipo è watch-only, bounded e testabile, ma non dispone di app iOS produttrice. | Congelarlo fuori dalla release oppure autorizzare lo sviluppo dell'app iOS. |
| Promozione Git | Il branch integrato è pronto per revisione e merge. Protezione di `main`, tag e policy di merge modificano l'accesso al repository. | Approvare separatamente le impostazioni di protezione e i tag. |

## Limiti della consegna corrente

GitHub Actions ha restituito fallimenti di startup sia per l'evento `push` sia per `pull_request`, sempre con **zero step eseguiti**. Questi risultati non rappresentano test rossi del codice; per questo il ramo combinato è stato validato localmente. I workflow coprono ora anche i push su `integration/**`, ma la prova remota resta subordinata allo sblocco dei runner. Il branch finale è pubblicato e la PR 26 è aperta e mergeable. Il bundle Git portabile resta disponibile come copia verificata della consegna.

Le scelte proprietarie elencate sopra non bloccano ulteriore sviluppo interno, ma impediscono di dichiarare una release pubblica completa. In particolare, una build unsigned non sostituisce una release firmata e una fixture smartwatch non sostituisce una prova su hardware reale.

## Riferimenti

[1]: https://github.com/fz1101hub/binario/pull/22 "PR 22 — Alexa companion cleanup"
[2]: https://github.com/fz1101hub/binario/pull/19 "PR 19 — Rail data correctness"
[3]: https://github.com/fz1101hub/binario/pull/12 "PR 12 — Reproducible GTFS pipeline"
[4]: https://github.com/fz1101hub/binario/pull/17 "PR 17 — Wear Data Layer"
[5]: https://github.com/fz1101hub/binario/pull/21 "PR 21 — Shared smartwatch snapshot contract"
[6]: https://github.com/fz1101hub/binario/pull/26 "PR 26 — Complete stabilization integration"
[7]: https://github.com/fz1101hub/binario/pull/25 "PR 25 — Android voice review"
[8]: https://github.com/fz1101hub/binario/pull/5 "PR 5 — PlannedJourney domain"
[9]: https://github.com/fz1101hub/binario/pull/9 "PR 9 — Companion sync visibility"
[10]: https://github.com/fz1101hub/binario/pull/24 "PR 24 — Archive and remove historical surfaces"
[11]: https://github.com/fz1101hub/binario/pull/23 "PR 23 — Repository documentation hygiene"
