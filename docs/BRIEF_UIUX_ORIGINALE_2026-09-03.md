# Binario — brief UI/UX originale

## Materiale osservato

I riferimenti utente della Home chiara e del tabellone chiaro mostrano una gerarchia operativa: brand compatto, stato dei dati subito leggibile, una sola azione primaria, accessi rapidi alle stazioni e un vuoto informativo dichiarato senza fingere realtime. Questi principi sono pertinenti a Binario, ma la composizione, il linguaggio dei controlli e le proporzioni non devono essere replicati.

## Direzione proposta: **Segnale di banchina**

La direzione da sottoporre all’utente combina la calma editoriale di una banchina con una lettura rapida da tabellone: sfondo avorio/grigio-ferro nelle superfici chiare, blu notte esclusivamente come fascia di orientamento, blu elettrico per l’azione primaria e arancio segnale riservato agli avvisi locali. La Home deve essere più corta e più visiva: la tratta successiva o il planner dominano la parte alta, mentre stazioni, tabellone e avvisi diventano moduli compatti ad alta leggibilità.

| Superficie | Gerarchia proposta | Vincolo dati |
| --- | --- | --- |
| Home chiara | Saluto minimo, planner/tratta dominante, tre scorciatoie ferroviarie, una riga di stato dati. | Nessuna card può far sembrare live un dato locale o programmato. |
| Tabellone chiaro | Nome stazione, partenza/arrivo, filtro compatto, stato source-first, righe operative o vuoto utile. | Fonte, orario di aggiornamento e indisponibilità restano leggibili. |
| Dettaglio scuro | Cronologia della tratta, orario programmato, checklist, margine personale e azioni secondarie. | Dati personali e stato ferroviario restano semanticamente distinti. |

Nel riferimento scuro l’itinerario è letto come una sequenza verticale, mentre lo stato del servizio è visivamente separato dal piano personale. Per Binario si riutilizza questo principio, non la composizione: nel mockup originale la sequenza sarà resa come traccia a segmenti con tempi programmati e il pannello dati userà una tonalità separata, non allarmistica, quando la fonte non è collegata.

## Decisioni UX

La navigazione non deve diventare una copia degli esempi: le sezioni restano quelle funzionali di Binario e gli elementi informativi sono riuniti per intenzione utente, non per moltiplicare card. Gli stati vuoti diventano istruzioni brevi e specifiche, con una sola azione utile. Etichette, pin e ordine manuale della tratta devono apparire come preferenze personali e non come stati ferroviari.

## Confini prima dell’approvazione

Questo documento è un brief per mockup. Non autorizza modifiche a densità, formato orario, microcopy, navigazione, colori o layout della baseline Expo. Il restyling inizierà soltanto dopo una scelta esplicita dell’utente su mockup originali.
