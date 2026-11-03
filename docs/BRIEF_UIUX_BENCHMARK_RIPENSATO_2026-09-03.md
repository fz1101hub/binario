# Binario — direzioni UI/UX dopo revisione completa del benchmark

## Principi estratti dal benchmark completo

Il benchmark converge su pochi principi che Binario deve mantenere: una tratta personale riconoscibile al primo sguardo; pianificazione con origine, destinazione, data e ora sempre esplicite; separazione fra tabellone di stazione, dettaglio e agenda; righe ferroviarie dense per gli orari; stati dati comprensibili senza affidarsi al colore; azioni principali raggiungibili con una mano; preferenze e metadati personali locali. Le fonti analizzate non giustificano invece duplicazione di servizi live, ticketing, geofencing o interfacce sovraccariche.

> Il benchmark definisce problemi ricorrenti da risolvere, non un layout da ricopiare.

## Cosa non riutilizzare

La nuova proposta non usa hero card bianche standard, cinque righe-card con chevron, segnaletica copiata, palette proprietarie di terzi, tabelloni finti con stato live o schermate che accumulano pannelli descrittivi. Il prodotto deve apparire come Binario, non come un collage delle altre app.

## Tre direzioni visuali originali

| Direzione | Metafora e ritmo | Home | Tabellone | Dettaglio |
| --- | --- | --- | --- | --- |
| **Nodo** | Un piccolo schema di deviatoi organizza il viaggio come percorso, non come lista. Palette pietra chiara, grafite, blu segnale e rame molto selettivo. | Un “tracciato” centrale collega piano, prossima tratta e azioni; i moduli sono innesti laterali, non card sovrapposte. | Colonne di orario su griglia modulare con direzioni raccolte per fascio. | Registro operativo scuro: nodi, tempi e checklist in una traccia leggibile. |
| **Foglio di corsa** | Il viaggio è una pagina tecnica personale, con numeri tabulari, righe e annotazioni ridotte. Palette avorio, inchiostro ferro e rosso mattone solo per attenzione. | La pianificazione è una striscia da compilare; il piano successivo occupa un blocco tipografico, non un contenitore. | La stazione diventa un foglio partenze a margini e indici, senza simulare un display live. | Dettaglio come foglio notturno, con progressione temporale e una sola barra azioni. |
| **Linea continua** | Un percorso verticale continuo attraversa le sezioni e permette l’uso con una mano; colori bassi, ampia luce e pochi accenti. | La prossima tratta guida la vista, mentre stazioni e funzioni emergono come biforcazioni nella linea. | Le partenze sono punti temporali collegati su un asse, con una source-line permanente. | Dettaglio scuro con sequenza della tratta e spaziatura da diario operativo. |

## Proposta prioritaria da visualizzare: Nodo

**Nodo** è la proposta più distante dagli screenshot precedenti mantenendo familiarità ferroviaria. Non usa una Home a card, né una barra inferiore derivativa: il tracciato diventa il sistema di orientamento. La parte superiore ospita solo il contesto locale; nella zona centrale il percorso pianificato è un diagramma interattivo semplice; in basso una dock di tre azioni è raggiungibile con il pollice. Il tabellone mantiene righe compatte da 52–56 dp, ma la fonte dati compare in una source-line sempre presente, non in un banner o in badge che competono con l’orario.

La tavolozza proposta è **calce #F5F1E9**, **grafite #17202A**, **blu segnale #1769E0**, **rame #B8652C** e **notte #09131F**. Gli orari usano cifre tabulari; titoli e controlli non sono mai interamente in maiuscolo; icone e testo accompagnano ogni stato. Nessun colore viene usato come unica informazione.

## Regole non negoziabili per i mockup

Ogni mockup deve dichiarare correttamente “programmato”, “dato locale” oppure “fonte live non collegata”; non può mostrare ritardi, binari o cancellazioni come dati reali. Le azioni devono avere testo oltre all’icona e dimensione adeguata al tocco. L’applicazione di qualsiasi direzione al codice Expo resta bloccata finché l’utente non seleziona una proposta.
