# Binario v0.8.2 — tabellone app e widget

## Principio

La home e il widget sono due superfici della stessa consultazione: **tratta selezionata, direzione, aggiornamento, partenze ordinate e stato del dato**. Il widget non è una versione miniaturizzata della home; presenta cinque righe e controlli rapidi. L’app estende lo stesso tabellone con ricerca, cambio tratta, dettaglio e tratte salvate.

| Strato | App | Widget |
|---|---|---|
| Intestazione | Tratta, direzione, refresh, ricerca | Tratta, freshness, pagina |
| Colonne | Ora, destinazione, treno, binario, stato | Ora, destinazione/treno, binario, stato |
| Azioni | Ricerca treno/tratta/stazione, direzione, refresh | Pagina precedente/successiva, direzione, refresh |
| Densità | Tutte le corse disponibili | Esattamente cinque per pagina |
| Dettaglio | Apribile dalla riga | Apre Binario sulla tratta attiva |

## Home

La schermata `Partenze` apre direttamente su un **tabellone**. L’intestazione non usa una card promozionale: tratta e controlli sono una fascia compatta. Seguono le intestazioni di colonna e righe ferroviarie regolari. La prima corsa è leggermente evidenziata, ma conserva la stessa geometria delle altre.

Le azioni Treno, Tratta e Stazione sono comandi brevi nella fascia superiore. Il tabellone stazione continua a dichiarare l’assenza di un provider autorizzato, senza valori inventati. Le tratte monitorate rimangono al termine del tabellone per cambiare contesto senza sostituire la consultazione corrente.

## Widget

Ogni riga widget usa quattro campi remoti: `ORA`, `DESTINAZIONE + TRENO`, `BINARIO`, `STATO`. Le cinque righe non incorporano newline o testo preformattato, così l’allineamento e l’accessibilità non dipendono dalla dimensione del widget. I controlli non cambiano: pagina, direzione e refresh restano sempre disponibili.

> Il tabellone Binario mantiene sempre fonte e freshness. Quando la sorgente è locale/dimostrativa o indisponibile, non presenta binari o ritardi come live.
