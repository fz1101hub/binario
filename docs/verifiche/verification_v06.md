# Verifica — Binario v0.6.0, companion smartwatch

**Data:** 12 agosto 2026  
**Ambito:** app Android, companion Wear OS e sorgenti nativi watchOS.

## Esiti verificati

| Area | Esito | Evidenza |
|---|---|---|
| App Android | Superato | `:app:testDebugUnitTest`, `:app:lintDebug` e `:app:assembleDebug` completati con successo. |
| Companion Wear OS | Superato | `:wear:testDebugUnitTest`, `:wear:lintDebug` e `:wear:assembleDebug` completati con successo. |
| Trasparenza dato | Superato | I test Wear verificano che il catalogo demo non venga chiamato live e che lo stato indisponibile contenga “dati non disponibili in tempo reale”. |
| Privacy trasferimento | Superato per revisione del contratto | Il Data Layer trasferisce solo snapshot della tratta attiva, massimo due corse, quality/freshness e avviso; non include token, posizione, biglietti, audio o storico. |
| Apple Watch | Sorgenti predisposti | Il progetto SwiftUI/watchOS richiede Xcode su macOS per generazione `.xcodeproj`, firma, simulatore e test su Apple Watch; questi strumenti non sono disponibili nel sandbox Linux. |

## Controlli di progettazione

| Requisito | Implementazione |
|---|---|
| Compatto al polso | App Wear e watchOS mostrano al massimo due corse; la Tile è intenzionalmente sintetica. |
| Azione di refresh | L’orologio chiede un refresh al telefono senza inviare parametro personale; il telefono esegue la propria pipeline dati/cache. |
| Accessibilità | Ogni corsa presenta una descrizione semantica con operatore, categoria, numero, destinazione, orario previsto, stato, ritardo e binario. |
| Batterie e aggiornamenti | La complication Wear richiede aggiornamenti non più frequenti di cinque minuti; le superfici al polso sono cache-first. |
| Nessun dato inventato | Senza provider autorizzato il companion dichiara lo stato non-live e non genera ritardi, binari o soppressioni reali. |

> Il companion Android/Wear OS è un prodotto buildabile. Il companion Apple Watch è un progetto sorgente nativo pronto per Xcode, non un artefatto firmato o pubblicabile da questo ambiente.

## Riferimenti

[1]: https://developer.android.com/training/wearables/data/overview "Android Developers — Wearable Data Layer API overview"
[2]: https://developer.android.com/training/wearables/tiles/get_started "Android Developers — Get started with Tiles"
[3]: https://developer.android.com/training/wearables/complications/exposing-data "Android Developers — Expose data to complications"
[4]: https://developer.apple.com/documentation/appintents "Apple Developer — App Intents"
[5]: https://developer.apple.com/watchos/ "Apple Developer — Build for watchOS"
