# Validazione su dispositivo — Binario v0.8.1

| Scenario | Risultato atteso |
|---|---|
| Apertura a freddo | L’app apre direttamente su `Partenze`, senza dipendere dalla presenza di Wear OS. |
| Azioni rapide | `Treno`, `Tratta` e `Stazione` portano alla scheda Cerca nella modalità corretta. |
| Tratta in evidenza | Mostra tratta/direzione, ha refresh e cambio direzione nello stesso pannello. |
| Prossima corsa | Mostra orario, destinazione, stato e binario; tocco apre il dettaglio. |
| Successive | Le corse restanti sono leggibili come righe dense e ordinate per orario. |
| Tratte monitorate | Una tratta selezionata aggiorna la home senza aprire un’altra schermata. |
| Tabellone | La scorciatoia esplicita indisponibilità senza feed autorizzato e non inventa partenze. |
| Tema | Il selettore v0.8 resta disponibile dalla barra superiore in tutte le sezioni. |
| Accessibilità | Font 200%, TalkBack e dark mode mantengono azioni, stati e testi comprensibili. |

> Il catalogo dimostrativo resta non-live. Il corretto comportamento è visualizzare **“Catalogo dimostrativo locale — non live”**, non dati di ritardo o binario falsamente in tempo reale.
