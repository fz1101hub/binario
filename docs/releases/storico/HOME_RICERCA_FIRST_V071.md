# Home ricerca-first — Binario v0.7.1

## Evidenze adottate

Le fonti ufficiali confermano che la consultazione ferroviaria parte normalmente dall’azione di ricerca: l’app Trenitalia pone le informazioni di viaggio e le ricerche salvate in primo piano; Italo in Viaggio espone ricerca per tratta, numero treno e stazione, quindi restituisce un tabellone con numero, partenza, destinazione e stato.[1] [2] [3]

Binario adotta lo stesso **ordine di compito**, non le interfacce proprietarie: prima si cerca una tratta o un treno, poi si leggono risultati e informazioni personali. Nessun flusso commerciale, vendita o autenticazione viene introdotto.

## Nuova home

| Ordine | Blocco | Azione |
|---|---|---|
| 1 | Ricerca principale | Passare fra `Tratta`, `N. treno` e `Stazione`; inserire il criterio e avviare la ricerca. |
| 2 | Selezioni rapide | Riprendere una tratta fissata o l’ultima tratta usata con un tocco. |
| 3 | Risultato attivo | Vedere tratta corrente, stato dati e le prossime corse dopo aver cercato o selezionato una tratta. |
| 4 | Funzioni personali | Gestire tratte, direzione, preferenza Lazio e widget in superfici secondarie. |

## Comportamento del catalogo dimostrativo

Il campo `N. treno` filtra il catalogo correntemente disponibile e la ricerca per tratta usa le stazioni locali supportate. La UI deve dichiararlo apertamente fino a quando un adapter autorizzato non fornirà ricerca live nazionale. La ricerca non crea automaticamente una tratta permanente: il salvataggio resta un’azione esplicita nella sezione Tratte.

## Criteri di accettazione

1. La prima azione visibile al lancio è `Cerca treni`.
2. Origine e destinazione sono distinguibili, scambiabili e dotate di suggerimenti stazione.
3. Il numero treno non è nascosto in una tab secondaria.
4. Le tratte fissate e recenti sono scorciatoie, non sostituti della ricerca.
5. Risultato, qualità dati e prossime corse arrivano solo dopo il blocco ricerca.
6. Font 200% non rende inutilizzabili i campi o il pulsante di ricerca.

## Riferimenti

[1]: https://www.trenitalia.com/it/informazioni/app-trenitalia.html "Trenitalia — App Trenitalia"
[2]: https://italoinviaggio.italotreno.com/ "Italo — Italo in viaggio"
[3]: https://italoinviaggio.italotreno.com/it/tratta "Italo — Treni in circolazione"
