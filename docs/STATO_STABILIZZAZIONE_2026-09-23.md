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
| Alexa Companion | Runtime e superfici WebDev generiche rimossi; OAuth con consenso e CSRF; allowlist dei redirect; revoca opponibile; migrazioni testate; handler built-in; health check; rate limit; CI e build verificabile. Il servizio resta separato dall'app e dagli smartwatch.[1] |
| Dati ferroviari | Download bounded, dispatcher corretti, indice GTFS compatto, regressione a heap limitato, semantica del giorno di servizio, festività, gzip, `stop_sequence=0`, operatori sconosciuti e policy provider applicate. La pipeline conserva 10 pack con copertura valida; AMT e Cotral restano catalogati ma disabilitati finché non esiste un feed affidabile.[2] [3] |
| Wear OS | Package telefonico e Wear coerenti per il Data Layer, sharing disattivato per default, refresh limitato, cancellazione del Data Item e della cache, chiavi UI univoche, manifest e policy `standalone=false` documentati.[4] |
| Contratto smartwatch | Schema v1 chiuso e bounded, TTL e provenienza espliciti, fixture golden Android–Wear, Companion–Wear e iOS–Apple Watch, decoder fail-closed e fallback `unknown`.[5] |
| Android UX | Deep link one-shot, lifecycle e Back corretti, shortcut, widget per istanza, refresh e freshness, allarmi ripristinabili, fascia silenziosa, permessi preservati alla rotazione, backup bounded, autocomplete preindicizzato e preferenze robuste.[6] |
| Accessibilità e voce | Stringhe a risorsa e semantiche migliorate; la dettatura distingue esiti confermati, ambigui e irrisolti e applica automaticamente soltanto stazioni confermate.[7] |
| PlannedJourney | Dominio personale offline distinto da `SavedRoute`; itinerari multi-tratta, agenda, reminder, checklist, ricorrenze finite, lifecycle, deduplica, import/export JSON con preview conflitti e calendario ICS bounded.[8] |
| Companion Expo | Bridge nativi, shared text, reminder e archivio locale corretti; sincronizzazione remota nascosta; branding e permessi verificati; prebuild Android riproducibile.[9] |
| Repository | Documenti storici spostati fuori dalla radice; todo di sessione rimossi; snapshot `surfaces/` preservato nel branch archivio e rimosso dalla linea attiva.[10] [11] |

## Evidenze di verifica

| Gate | Esito |
|---|---|
| Asset e freshness GTFS | **Verde:** 10 pack, nessun pack scaduto o in scadenza nel periodo minimo; manifest, checksum e registro runtime coerenti. |
| Regressione memoria GTFS | **Verde:** feed sintetico da 1,35 milioni di righe eseguito con heap JVM massimo di 256 MiB. |
| Android e Wear | **Verde:** 269 test app e 5 test Wear senza failure; lint debug, assemble debug, assemble release minificata e `lintVitalRelease`; 204 task Gradle nel gate combinato. La release è unsigned perché non sono state fornite credenziali di firma. |
| Alexa Companion | **Verde:** TypeScript, 57 test in 18 file e build di produzione con verifica dell'assenza dei residui runtime. |
| Companion Expo TypeScript | **Verde:** typecheck, 53 test in 11 file, branding, managed config, release inputs e due `expo prebuild --clean` consecutivi senza diff. |
| Companion Expo nativo | **Verde:** lint e assemble debug di app e Wear generati; 442 task Gradle eseguiti. Il primo build pulito ha installato NDK e CMake e ha richiesto circa 1 ora e 46 minuti. Gli avvisi residui provengono principalmente dalle dipendenze Expo/React Native deprecate, non da errori di compilazione. |
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

GitHub Actions ha restituito cinque fallimenti di startup con **zero step eseguiti**. Questi risultati non rappresentano test rossi del codice; per questo il ramo combinato è stato validato localmente. Il branch finale è pubblicato e la PR 26 è aperta e mergeable. Il bundle Git portabile resta disponibile come copia verificata della consegna.

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
