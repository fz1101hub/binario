# Binario — matrice di recupero di tutte le versioni disponibili

## Regola di conservazione

Il ramo `recovery/expo-companion-session-20260903` conserva le superfici Binario disponibili **senza fondere** i rispettivi sorgenti. Ogni directory è una snapshot: non rappresenta un merge, una migrazione o una dichiarazione di compatibilità fra versioni.

| Superficie preservata | Posizione nel ramo | Contenuto conservato | Stato tecnico |
| --- | --- | --- | --- |
| Companion Expo locale v0.1.1 | `companion-expo/` | Schermate Android/iOS, contratti, planner, Agenda, widget/Wear plugin, Android bridge, test e documentazione. | Copia identica ai file locali; dipendenze, lockfile e alcuni moduli importati erano già assenti dalla cartella origine. |
| APK release locale v0.1.1 | `artifacts/v0.1.1/` | Build installabile locale e SHA-256. | Preservato; non equivale a un test hardware nuovo. |
| Web e Voice Bridge/Alexa | `surfaces/web-voice-bridge/` | 294 file: client, server, Voice Bridge, test, contratti, schema, lockfile e documentazione. | Copia sorgente locale senza `.git`, `node_modules`, log, build o file ambiente. |
| Android, Wear OS e Apple Watch nativi | `surfaces/native-android-wear-apple/` | Tre snapshot sorgente locali: `main`, `feature_data-foundations` e `work_benchmark-local-improvements-20260903`, con commit registrato in `RECOVERED_COMMIT.txt`. | Preservati separatamente; nessun file è stato riversato nel companion Expo. |
| Archivi versioni Binario | `archives/session-versions/` | 22 ZIP da v0.1.0 a v0.20.1, con manifest del contenuto, validazione ZIP e SHA-256. | Tutti validi; 22 oggetti Git LFS. |
| APK debug versioni Binario | `archives/session-apks/` | 31 APK debug disponibili da v0.1.0 a v0.20.3 con SHA-256. | Conservati come artefatti immutabili; 31 oggetti Git LFS. |

## Integrità e dimensioni

| Categoria | Quantità | Dimensione rilevata | Metodo di conservazione |
| --- | ---: | ---: | --- |
| Archivi ZIP | 22 | 826 MB | Git LFS |
| APK debug | 31 | 1.023 MB | Git LFS |
| Oggetti LFS totali | 53 | circa 1,85 GB | Git LFS |
| Snapshot native | 3 | 117 MB | Git ordinario; nessun file singolo oltre 20 MB. |
| Copia web/Voice Bridge | 294 file | 2,4 MB | Git ordinario. |

I manifest ZIP sono in `archives/session-versions/manifests/`; le convalide in `archives/session-versions/ZIP_VALIDATION.tsv`; i checksum in ciascun `SHA256SUMS.txt`.

## Commit di recupero

| Commit | Finalità |
| --- | --- |
| `b929618` | Baseline Expo locale v0.1.1. |
| `bd2a6ce` | APK release locale v0.1.1. |
| `4b7b83f` | Superficie web/Voice Bridge, snapshot native e archivi ZIP multiversione. |
| `d78e4f0` | APK debug multiversione. |

> Questa matrice attesta la presenza e l’integrità degli elementi salvati. Non dichiara che ogni release sia ricompilabile sul sandbox o che Apple Watch, Alexa, widget e Wear siano stati provati su hardware fisico.
