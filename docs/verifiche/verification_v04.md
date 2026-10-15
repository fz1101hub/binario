# Verifica release Binario v0.4.0

**Data:** 12 agosto 2026  
**Build verificata:** debug, `it.binario.app`, versione `0.4.0` (`versionCode 4`).

## Esiti tecnici

| Controllo | Esito | Evidenza |
|---|---|---|
| Compilazione APK debug | Superato | `:app:assembleDebug` completato con `BUILD SUCCESSFUL` in 49 secondi. |
| Analisi lint debug | Superato | `:app:lintDebug` completato con `BUILD SUCCESSFUL`; il report dichiara **No issues found**. |
| Metadati APK | Verificati | `minSdk 26`, `targetSdk 36`, `compileSdk 36`, application id `it.binario.app`. |
| Integrità artefatto | Verificata | SHA-256: `d58d2b44aefdd963d4405e09d64c743cedcfe62398dec4c279e882327b206c3e`. |
| Sicurezza di rete | Preservata | HTTPS obbligatorio e cleartext disabilitato dalla configurazione esistente. |
| Backup | Preservato | Backup e trasferimento dati esclusi dalla configurazione esistente. |

## Controlli funzionali implementati

| Ambito | Verifica statica e compilata |
|---|---|
| Copertura nazionale | `RailOperator` e `TrainCategory` normalizzano Trenitalia Regionale, Trenitalia, Frecce, Italo, Trenord e altri operatori. |
| Preferenza Lazio | La precedenza visiva per Trenitalia Regionale si applica alle tratte che toccano il Lazio, è persistente ed è disattivabile. |
| Trasparenza | Ogni snapshot porta provider, confidence, timestamp osservato/ricevuto, scadenza e stato di freschezza. |
| App e accessibilità | Lista, dettaglio e semantica descrivono operatore, categoria, destinazione, orario previsto, ritardo e binario. |
| Widget | Mantiene cinque corse per pagina e aggiunge operatore e categoria in testo leggibile e annunciabile. |
| Provider live | L’adapter Trenitalia è un confine senza richieste non autorizzate; richiede un gateway formalmente autorizzato. |

## Limite operativo essenziale

La release usa un **catalogo dimostrativo locale multi-operatore**. Non deve essere interpretata come servizio live: i ritardi, i binari, le fermate e le soppressioni visualizzati non sono dati di circolazione reale. Per l’uso ferroviario reale deve essere configurato un provider autorizzato tramite il repository dati.

## Riproducibilità

Il progetto ora include Gradle Wrapper 8.14.5. Con Android SDK API 36 e OpenJDK 21 disponibili, il controllo è riproducibile con:

```bash
./gradlew lintDebug assembleDebug
```
