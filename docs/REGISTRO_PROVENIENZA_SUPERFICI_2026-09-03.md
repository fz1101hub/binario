# Binario — registro di provenienza delle superfici

## Regola di lettura

Questo registro separa il lavoro recuperato dalle sessioni recenti dalle versioni che erano già presenti nel repository privato `fz1101hub/binario`. Non è lecito attribuire a una linea capacità trovate nell’altra né fonderle senza un confronto tecnico puntuale.

| Superficie | Provenienza | Stato del salvataggio | Cosa è dimostrabile | Cosa non è dimostrabile |
| --- | --- | --- | --- | --- |
| Web Binario | Progetto WebDev corrente `/home/ubuntu/binario-voice-bridge`. | Versionato nel remoto WebDev fino al checkpoint `776bc526`. | Planner, Agenda, trasferimento locale, portabilità, contratti e test della linea web corrente. | Che ogni versione companion recente sia contenuta nello stesso repository. |
| Voice Bridge / Alexa del progetto web | Progetto WebDev corrente. | Codice e test sotto `server/voice` sono nel remoto WebDev. | Handler, OAuth, guardie fail-closed e messaggi di indisponibilità live. | Credenziali attive, console Amazon, account linking realmente configurato o Skill pubblicata. |
| Companion Expo Android/iOS | Sessioni recenti, catturato nella cartella `companion-expo/` il 3 settembre 2026. | Commit `b929618` sul ramo `recovery/expo-companion-session-20260903`. | Home, Agenda, impostazioni, Hub Companion, planner, tabellone offline, itinerari, import testuale, bridge Condividi Android, contratti e test presenti. | Compilazione della snapshot finché non sono recuperati moduli importati, lockfile e dipendenze assenti. |
| Android nativo e Wear OS | Versione Binario storica già esistente su Git. | Rami `main` e `feature/data-foundations`; recupero tecnico sul ramo `work/benchmark-local-improvements-20260903`. | App Kotlin, widget, catalogo, dettatura telefono; Tile, complication e snapshot Wear ricostruiti e compilati. | Che la richiesta vocale dal polso appartenga alla linea Expo recente o che sia presente nella sorgente Git recuperata. |
| Apple Watch | Versione Binario storica già esistente su Git, commit Apple `a5c204e`. | Sorgenti Swift, progetto XcodeGen `project.yml`, test, widget e PhoneConnectivity presenti sul repository storico. | Codice WatchOS e configurazione del progetto. | Build Xcode, firma Apple, prova hardware o origine nella linea Expo di sessione. |
| APK storico v0.20.0 | Artefatto della sessione di riferimento. | Archivio disponibile con APK e sorgenti del catalogo. | Esistenza della build e del catalogo. | Sorgente completa, moduli Wear o voce dal polso. |

## Regola di integrazione

La baseline Expo di sessione rimane la linea da preservare. Il Git storico può essere consultato per riconoscere soluzioni migliori, ma non viene copiato né usato come sostituto. Ogni integrazione deve avere: confronto del contratto, implementazione autonoma, test, build pertinente e commit su un ramo dedicato.

## Stato attuale

La sola baseline Expo appena recuperata risulta **parziale ma ora preservata su Git**. Le linee Android/Wear e Apple Watch del repository esistente sono **sorgenti storiche separate**. Il Voice Bridge web è **sorgente corrente WebDev**, mentre la disponibilità della Skill Alexa in servizi esterni resta un controllo distinto e non può essere dedotta dal codice.
