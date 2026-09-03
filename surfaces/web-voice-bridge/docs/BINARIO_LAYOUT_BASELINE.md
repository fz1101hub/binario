# Baseline layout Binario Treni

## Evidenze visive verificate

I mockup del task `A7wgn2uY9sZNsGcfh3dMU3` definiscono un’app mobile orientata al viaggio e al monitoraggio, con barra di navigazione inferiore e una gerarchia informativa strettamente ferroviaria.

| Superficie | Elementi da preservare | Miglioramento da applicare nel progetto attuale |
|---|---|---|
| Home e ricerca | Form verticale con origine, destinazione, data, ora e inversione della tratta; CTA primaria ampia | Sostituire la sola creazione amministrativa di tratte con un flusso di pianificazione e monitoraggio leggibile anche su mobile |
| Riepilogo viaggio | Carta sintetica con tratta, numero treno, ritardo, binario e ultimo aggiornamento | Rendere sempre esplicite provenienza e freschezza, evitando di presentare dati programmati come realtime |
| Monitoraggi | Lista di stazioni e treni personali con stato e accesso al dettaglio | Collegare gradualmente i monitoraggi personali già persistiti alla nuova UI |
| Dettatura | Comandi vocali rapidi per comporre ricerca e pianificazione | Microfono nell’app accanto ai campi stazione, con testo rivedibile e matching successivo sul catalogo |
| Tabellone stazione | Tab Partenze/Arrivi, filtro e righe dense con ora, categoria, destinazione, ritardo, binario e stato | Usare stati testuali e iconografici accessibili: verificato, in orario, stimato, binario da confermare, indisponibile |
| Navigazione | Home, Stazioni, Viaggi e Avvisi in basso | L’interfaccia web può rappresentare le stesse aree mediante navigazione contestuale, senza riutilizzare il sidebar amministrativo come superficie primaria |

## Regole di non regressione

1. I campi Partenza e Arrivo restano distinti; lo scambio deve essere sempre esplicito e reversibile.
2. Colori, icone e testo devono comunicare lo stato insieme; nessun ritardo o livello di affidabilità può dipendere solo dal colore.
3. In assenza di una fonte live autorizzata, la UI deve dichiarare “orario programmato” o “dati realtime non disponibili”.
4. La dettatura nativa serve ricerca e pianificazione nell’app; Alexa e Google Assistant restano superfici companion complementari.
5. Il primo incremento deve mantenere la persistenza esistente delle tratte personali e non introdurre dati fittizi.

## Gerarchia di prodotto: app e companion

Binario è prima di tutto l’app ferroviaria: ricerca, pianificazione, stazioni, viaggi salvati, tabelloni e avvisi definiscono la sua navigazione e le sue priorità. La home deve rendere Stazioni e Avvisi superfici visibili anche quando non sono ancora collegati dati live; in tal caso comunica il limite senza insinuare l’assenza di ritardi o disservizi. Alexa, widget e smartwatch sono companion opzionali: utilizzano gli stessi monitoraggi e lo stesso stato dati, ma non devono sostituire la home, determinare il flusso principale né presentarsi come prodotto autonomo. Questa distinzione va mantenuta nella UI, nella copy e nei contratti dei dati.

## Stato della baseline sorgente

Il repository aperto è il progetto web **Binario**, con `main` al commit `2441cd1` e una storia dedicata a profilo remoto, tratte personali e companion vocali. I commit Android `10955e35` e `bd67cfa` citati nelle precedenti conversazioni non sono presenti in questo repository; il confronto Git conferma quindi che si tratta di una linea sorgente separata. La ripresa in questa codebase può riutilizzare layout, requisiti di prodotto e benchmark di Binario Treni, ma non deve presentare le funzionalità Android o i data pack GTFS come già implementati nel progetto web.
