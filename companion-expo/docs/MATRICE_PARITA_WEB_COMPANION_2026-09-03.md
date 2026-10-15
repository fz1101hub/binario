# Matrice di parità locale — Binario

**Data:** 3 settembre 2026  
**Perimetro:** sole funzioni ferroviarie e di preparazione viaggio locali. Questa matrice non è una dichiarazione di conformità, una certificazione hardware o un’attestazione di disponibilità realtime.

| Superficie web di riferimento | Equivalente companion | Evidenza nel repository |
| --- | --- | --- |
| Planner con catalogo, preferiti, template, stazione vicina consensuale e data/ora | Planner nativo con autocomplete offline, preferiti, template, picker di sistema e permission posizione richiesta nel solo campo Partenza | Contratti catalogo/planner, test offline e superfici native |
| Agenda locale, ricerca, filtri, checklist, statistiche, archivio, ritorno e ripianificazione | Agenda nativa con gli stessi dati locali, filtri, ricerca, checklist, statistiche, ritorno/ripianifica, completamento/ripristino manuale e ricorrenze deliberate | `local-route-contract`, persistenza AsyncStorage e test Agenda |
| Viaggi con più treni | Itinerari locali da 2 a 4 tappe con modalità cambio informativa e checklist della prossima tappa | `local-journey-itineraries`, `local-itineraries` e test dedicati |
| Portabilità calendario/archivio | ICS selettivo, trasferimento manuale con preview/dedup e importazione guidata da testo | Contratti ICS/archivio/testo e relativi test |
| Importazione da testo incollato | Android: target esplicito `ACTION_SEND text/plain`; iOS/web: incolla deliberato | Plugin rigenerabile, bridge Android, contratto del testo e test statico del perimetro |
| Privacy, modalità semplificata, tema e preferenze accessibili | Preferenze e cancellazione archivio locale nelle impostazioni; haptic e azioni OS dove disponibili | Theme, privacy, feedback e test contrattuali |

## Confini intenzionali

La ricezione Android accetta soltanto testo che l’utente sceglie di condividere a Binario. Il bridge conserva un singolo valore di massimo 4.000 caratteri solo per consegnarlo alla stessa anteprima dell’Agenda, poi lo cancella; non accetta file, immagini, URL da risolvere o MIME generici. Android richiede un filtro `ACTION_SEND` con il MIME type dichiarato e raccomanda che l’utente possa rivedere il testo prima dell’uso.[1]

Nell’SDK Expo 54, `expo-sharing` supporta l’invio di file dall’app ma non la registrazione come destinataria di contenuti nel menu Condividi; perciò non viene dichiarato un receiver iOS/Web non implementato.[2] Il receiver Android è compilato e coperto da contratti statici; il collaudo effettivo nel menu Condividi di un telefono Android resta un test hardware aperto.

Non vengono implementati dati ferroviari realtime, calcolo di coincidenze, tracking, automatismi di promemoria per ricorrenze, apertura di link condivisi, login di operatori o vendita biglietti.

## Verifiche automatizzate dell’incremento

Il companion ha superato `pnpm check`, `pnpm test` (**34 file, 95 test**), `expo export --platform android` e la build interna `:app:assembleRelease :wear:assembleRelease`. Le build confermano la compilazione dei target ma non sostituiscono test Android/iOS/Wear reali, screen reader o distribuzione store.

## Riferimenti

[1]: https://developer.android.com/training/sharing/receive "Android Developers — Receive simple data from other apps"
[2]: https://docs.expo.dev/versions/v54.0.0/sdk/sharing/ "Expo SDK 54 — Sharing"
