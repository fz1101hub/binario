# Validazione su dispositivo — Binario v0.7.1 ricerca-first

Questa build corregge la priorità della home: al lancio la prima azione deve essere la ricerca ferroviaria, non la visualizzazione di una tratta già attiva.

| Scenario | Risultato atteso |
|---|---|
| Apertura app | La prima schermata mostra titolo `Cerca treni`, modalità Tratta/N. treno/Stazione e campi di ricerca. |
| Cerca tratta | Inserendo Da e A, `Cerca treni` aggiorna i risultati senza creare automaticamente una nuova tratta salvata. |
| Cerca numero | Il campo è raggiungibile dalla home e il filtro operatore compare sotto la ricerca numero. |
| Cerca stazione | La UI esplicita correttamente che il tabellone richiede un provider autorizzato, senza inventare partenze. |
| Percorsi personali | Sono scorciatoie immediatamente sotto la ricerca, non l’elemento dominante della home. |
| Risultati | Il titolo `Treni in circolazione` arriva dopo i campi; ogni riga conserva ora, destinazione, stato, operatore e binario. |
| Dettaglio | Aprendo un risultato dalla ricerca, indietro riporta a Cerca treni; aprendo dalle Partenze, indietro riporta a Partenze. |
| Accessibilità | Con font 200% i campi Da/A, la CTA e le modalità restano visibili e usabili; TalkBack legge ogni riga corsa nell’ordine logico. |

> Il catalogo locale è intenzionalmente non-live. In assenza di una fonte autorizzata, il comportamento atteso è visualizzare **“Catalogo dimostrativo locale — non live”** o **“dati non disponibili in tempo reale”**.
