# Verifica — Binario v0.20.1

## Correzione richiesta

Il pianificatore mostrava il fallback “Cerca nelle fonti disponibili” al posto dei suggerimenti anche per stazioni esistenti, e una ricerca poteva essere percepita come invertita. La correzione distingue il testo digitato dalla stazione effettivamente selezionata, rende esplicito lo stato di caricamento del catalogo e conserva l’ordine degli estremi durante selezione, scambio e ricerca.

## Comportamento verificato

| Caso | Esito |
|---|---|
| Digitazione `Appiano` | Il catalogo curato restituisce `Appiano Proba Petronia` tramite alias `APPIANO`, con codice `S08328`. |
| Digitazione `Roma Osti` | Il pianificatore presenta Roma Ostiense come scelta selezionabile non appena il catalogo è pronto. |
| Selezione | Testo e oggetto `Station` sono aggiornati insieme per il solo campo interessato. |
| Scambio | Partenza e arrivo scambiano in un’unica azione testo e identità canonica. |
| Ricerca Appiano → Roma Ostiense | L’ID è `search-curated:S08328-curated:S08406`; la direzione viene riportata a `OUTBOUND`. |
| Ricerca inversa | L’ID è distinto: `search-curated:S08406-curated:S08328`. |
| Catalogo non pronto | Il campo comunica “Caricamento elenco stazioni…” invece di simulare un risultato. |

## Validazione tecnica

| Controllo | Esito |
|---|---|
| `:app:compileDebugKotlin` | Superato. |
| `:app:testDebugUnitTest` | Superato. |
| Test aggiunti | Alias Appiano e ordine dei due estremi. |

## Limite esplicito

Un suggerimento identifica una stazione: non garantisce l’esistenza di una corsa in quel momento. Orari e ritardi continuano a provenire solo dalla catena realtime qualificata, GTFS programmato o indisponibilità esplicita.
