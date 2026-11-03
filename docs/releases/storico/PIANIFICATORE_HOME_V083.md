# Binario v0.8.3 — home pianificatore

## Correzione di struttura

La home non è un tabellone. È il punto da cui una persona imposta il prossimo viaggio. Il tabellone compare **dopo** la ricerca o nella sezione Treni; il widget resta invece il tabellone veloce delle prossime corse.

| Sezione | Funzione principale | Contenuto |
|---|---|---|
| Cerca | Pianificare un viaggio | Partenza, arrivo, inversione, andata/ritorno, data, ora, filtri, CTA. |
| Treni | Consultare una tratta attiva | Tabellone con partenze, direzione, refresh e dettaglio. |
| Stazioni | Consultare arrivi/partenze | Ricerca stazione e stato esplicito della fonte. |
| Altro | Gestire personali e app | Tratte salvate, tema e informazioni sul dato. |

## Home Cerca

Il pianificatore è un unico blocco di campi con etichette persistenti: `Stazione partenza`, `Stazione arrivo`, `Sola andata` o `Andata e ritorno`, `Data`, `Ora`, `Filtri`. L’inversione è un controllo indipendente tra i due campi; il pulsante `Cerca soluzioni` è il comando principale. Le tratte salvate, fissate e usate di recente arrivano sotto come scorciatoie, non sopra il pianificatore.

Le parti decorative, i marchi, i logotipi e gli asset delle schermate di riferimento non vengono riprodotti. Binario usa testo, icone, componenti e palette propri.

## Risultati

Premere `Cerca soluzioni` apre risultati tabellari per la tratta, non lascia il planner mescolato alle righe. Da lì si apre il dettaglio corsa. Il cambio direzione e il refresh restano nella sezione Treni e nel widget, dove hanno un significato operativo.

## Widget

Il widget non segue la home pianificatore: conserva il tabellone a cinque corse per pagina perché serve per controllare rapidamente ciò che parte senza riaprire l’app.
