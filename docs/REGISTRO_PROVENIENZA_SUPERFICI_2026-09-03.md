# Binario — registro di provenienza delle superfici

> **Documento storico.** La decisione corrente è in `docs/architecture/ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`: `app/` è il prodotto principale, `alexa-companion/` è il companion Alexa canonico e il vecchio progetto WebDev resta uno snapshot non canonico da triagiare.

## Regola di lettura

Questo registro separa la linea locale **Binario v0.1.1** dalle versioni che erano già presenti nel repository privato `fz1101hub/binario`. Non è lecito attribuire a una linea capacità trovate nell’altra né fonderle senza un confronto tecnico puntuale.

> **Perimetro operativo dal 3 settembre 2026:** il recupero riguarda esclusivamente i file locali v0.1.1, copiati e verificati nel ramo `recovery/expo-companion-session-20260903`. Le righe sulle altre superfici restano un registro storico; non sono una fonte di recupero né una descrizione della nostra linea recente.

| Superficie | Provenienza | Stato del salvataggio | Cosa è dimostrabile | Cosa non è dimostrabile |
| --- | --- | --- | --- | --- |
| Web Binario | Progetto WebDev corrente `/home/ubuntu/binario-voice-bridge`. | Versionato nel remoto WebDev fino al checkpoint `776bc526`. | Planner, Agenda, trasferimento locale, portabilità, contratti e test della linea web corrente. | Che ogni versione companion recente sia contenuta nello stesso repository. |
| Voice Bridge / Alexa del progetto web | Progetto WebDev corrente. | Codice e test sotto `server/voice` sono nel remoto WebDev. | Handler, OAuth, guardie fail-closed e messaggi di indisponibilità live. | Credenziali attive, console Amazon, account linking realmente configurato o Skill pubblicata. |
| Companion Expo Android/iOS | Linea locale **v0.1.1** presente nel progetto e catturata in `companion-expo/`. | Commit iniziale `b929618`, verifica e APK preservato fino a `ea5848e` sul ramo `recovery/expo-companion-session-20260903`. | Home, Agenda, impostazioni, Hub Companion, planner, tabellone offline, itinerari, import testuale, bridge Condividi Android, contratti, test e APK locale presenti. | Compilazione della snapshot finché non sono recuperati moduli importati, lockfile e dipendenze assenti. |
| Android nativo e Wear OS | Versione Binario storica già esistente su Git. | Rami `main` e `feature/data-foundations`; recupero tecnico sul ramo `work/benchmark-local-improvements-20260903`. | App Kotlin, widget, catalogo, dettatura telefono; Tile, complication e snapshot Wear ricostruiti e compilati. | Che la richiesta vocale dal polso appartenga alla linea Expo recente o che sia presente nella sorgente Git recuperata. |
| Apple Watch | Versione Binario storica già esistente su Git, commit Apple `a5c204e`. | Sorgenti Swift, progetto XcodeGen `project.yml`, test, widget e PhoneConnectivity presenti sul repository storico. | Codice WatchOS e configurazione del progetto. | Build Xcode, firma Apple, prova hardware o origine nella linea Expo di sessione. |
| APK v0.1.1 locale | Build già presente sotto la cartella progetto locale. | Versionato nel ramo laterale, commit `bd2a6ce`. | APK di 72.592.327 byte e SHA-256 `e17c48223e22c3c65da999ac23dcaaf467cf80e824a892781140f8c1fac2358b`. | Che l’APK sostituisca la sorgente o attesti test hardware non eseguiti. |

## Regola di integrazione

La baseline Expo locale v0.1.1 rimane la linea da preservare. Nessuna versione storica viene consultata, copiata o integrata durante il recupero. Eventuali confronti futuri richiederanno una richiesta espressa, un confronto del contratto, implementazione autonoma, test, build pertinente e commit su un ramo dedicato.

## Stato attuale

La baseline Expo locale **v0.1.1** risulta **parziale ma preservata su Git**, con tutti i 35 file sorgente/documentazione locali confrontati tramite SHA-256 e l’APK locale versionato. Le linee Android/Wear e Apple Watch del repository esistente sono **sorgenti storiche separate** e non partecipano al recupero. Il Voice Bridge web è **sorgente corrente WebDev**, mentre la disponibilità della Skill Alexa in servizi esterni resta un controllo distinto e non può essere dedotta dal codice.
