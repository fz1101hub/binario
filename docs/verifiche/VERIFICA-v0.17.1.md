# Verifica Binario v0.17.1

**Data:** 15 agosto 2026  
**Branch:** `feature/data-foundations`  
**Oggetto:** eliminazione del fallback dimostrativo implicito e trasparenza del degrado dati.

| Controllo | Esito |
|---|---|
| `:app:testDebugUnitTest` | Superato, inclusi `ProductionFallbackTest` e `WidgetLegacySnapshotPolicyTest`. |
| `:app:assembleDebug` | Superato; APK debug generato. |
| `:app:lintDebug` | Superato. |
| Runtime data source | `BinarioDataGraph` usa provider best-effort → GTFS embedded → errore esplicito; non istanzia `DemoTrainDataSource`. |
| Cache widget | Uno snapshot con confidence `DEMONSTRATION` viene rimosso prima del rendering. |
| UI e widget | Messaggio provider visibile nella riga dati e nello stato vuoto; widget senza snapshot mostra il motivo e apre l’app per riprovare. |

> Una fonte realtime può non restituire una corsa per una tratta o stazione non coperta. In quel caso Binario mostra indisponibilità o orario programmato, mai ritardi, binari, soppressioni o posizioni inventate.
