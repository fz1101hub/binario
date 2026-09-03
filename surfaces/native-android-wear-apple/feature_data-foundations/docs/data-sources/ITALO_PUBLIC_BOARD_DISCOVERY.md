# Italo in Viaggio — verifica tabellone pubblico

Il 15 agosto 2026 è stata aperta la pagina pubblica:

`https://italoinviaggio.italotreno.com/it/stazione/roma-termini`

La pagina dichiara tabelloni di partenze e arrivi aggiornati in tempo reale e visualizza l’ultimo aggiornamento. Nell’osservazione effettuata non erano presenti treni in circolazione per Roma Termini. Le risorse browser mostravano la pagina, gli asset React e contenuti statici; non è emerso un endpoint JSON semplice e stabile per il tabellone nelle risorse iniziali.

Il provider Binario può quindi usare esclusivamente la pagina pubblica come sorgente best-effort, parsando HTML soltanto quando contiene righe esplicite di treno. Non deve impiegare BIG, token, API interne o endpoint di vendita.

Riferimento: https://italoinviaggio.italotreno.com/it/stazione/roma-termini

L’ispezione successiva ha confermato che la pagina espone un form `POST` sulla stessa URL della stazione, ma i controlli React non esportano campi nativi in `FormData`. Il client Android non può quindi ricostruire in modo affidabile un POST dal solo markup. L’adapter deve usare l’HTML della pagina stazione già indirizzabile tramite slug pubblico e considerare l’assenza di righe come un risultato senza corse, non come un errore.

Il prossimo test deve usare una stazione o fascia oraria con corse visibili; il parser deve lavorare solamente sulle righe HTML direttamente presenti nella risposta pubblica, senza emulare le funzioni di vendita o sessione.

## Verifica della possibile boa di rilevamento — 15 agosto 2026

Il tracker pubblico Italo presenta una ricerca per stazione e numero treno, con campi `station-selector-from` e `train-number-filter`. Nella pagina iniziale non compaiono testi pubblici riferiti a `boa`, `ultima rilevazione`, `ultimo passaggio` o `posizione`. I dettagli della corsa sono caricati da moduli JavaScript pubblici e richiedono una corsa in circolazione per essere osservati. Questa evidenza non esclude che il dettaglio di una corsa mostri una rilevazione operativa, ma impedisce per ora di attribuire tale dato a una fonte pubblica stabile o di equipararlo a GPS.
