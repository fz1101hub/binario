# Validazione su dispositivo — Binario v0.8.2

| Scenario | Risultato atteso |
|---|---|
| Avvio | L’app apre sulla tab `Partenze`, con comandi rapidi e intestazione del tabellone. |
| Comandi | `Cerca treno`, `Cerca tratta` e `Tabellone` aprono Cerca nella modalità relativa. |
| Intestazione app | Tratta, direzione, refresh e cambio tratta sono immediatamente disponibili. |
| Righe app | Le colonne ora, destinazione/treno, binario e stato restano allineate e toccabili. |
| Prima corsa | È percepibile come prossima, senza diventare una card separata. |
| Widget | Mostra esattamente cinque corse per pagina con quattro colonne: ora, destinazione/treno, binario, stato. |
| Widget controlli | Frecce pagina, direzione e aggiorna mantengono le stesse azioni della versione precedente. |
| App e widget | Cambio direzione nell’app aggiorna il widget; refresh widget non blocca l’app. |
| Accessibilità | TalkBack annuncia ogni riga con operatore, categoria, destinazione, previsto, ritardo e binario; intestazioni a 11sp o più. |
| Dato | Dati demo/non disponibili restano esplicitamente tali, senza ritardi o binari live inventati. |

## Verifica tecnica

`testDebugUnitTest` e `assembleDebug` sono completati con successo. `lintDebug` conclude con **No issues found**.
