# Proposte di stile grafico — Home companion

Le immagini sono **concept visuali**, non schermate funzionali né dichiarazioni sulla disponibilità di dati ferroviari.

| Direzione | Caratteristiche osservate | Uso consigliato |
| --- | --- | --- |
| Segnaletica ferroviaria contemporanea | Sfondo antracite, giallo segnale, tipografia condensata, griglia/tabellone e pannelli scuri a bordo sottile. | Identità ferroviaria netta e operativa. |
| Editoriale di viaggio italiano | Carta avorio, blu inchiostro, rosso mattone, titoli serif, moduli ispirati a biglietti e segnaletica storica. | Identità più calda e distintiva. |
| Banchina notturna | Navy profondo, superfici acciaio, dettagli ciano e accento giallo contenuto. | Esperienza scura, sobria e tecnica. |

La scelta grafica successiva deve preservare contrasto, dimensioni minime dei target touch, font scale e la separazione fra pianificazione locale e dati live non disponibili.

## Pattern pubblici da usare come ispirazione, non come copia

L’analisi degli screenshot pubblici di OnTheRails e Orario Treni evidenzia una lezione comune: la ricerca/azione primaria resta nella parte superiore, mentre preferiti o recenti diventano memoria compatta a consultazione rapida. OnTheRails usa righe ad alta densità per lo stato dei treni e la navigazione scura, mentre Orario Treni usa una superficie chiara e una form di ricerca diretta con recenti leggibili. Per Binario la traduzione originale proposta è una Home con: ricerca e tabellone in una superficie unificata, memoria stazioni in chip/righe brevi, indicatori di stato come capsule o righe, e testo esplicativo disponibile soltanto da un gesto di approfondimento.

## Direzione Binario: Rail Control

La nuova proposta unisce la praticità della ricerca diretta con una superficie informativa più matura. Il blu indaco di Binario non replica il teal o il nero dei benchmark; l’ambra diventa l’unico richiamo per l’azione primaria. La Home non è una collezione di card: usa campi allineati, divisori sottili, moduli a bassa altezza e una memoria stazioni visibile ma non dominante. La condizione dei dati rimane leggibile come stato breve e non come paragrafo: l’utente vede immediatamente se il tabellone è disponibile, senza che l’interfaccia simuli arrivi o avvisi live.

I mockup Rail Control e Station First saranno consegnati solo dopo la generazione completa e la verifica che non siano segnaposto di elaborazione.

## Riferimenti forniti dall’utente

Le tre schermate di riferimento definiscono una direzione più concreta per Binario: **Home chiara** con campo percorso dominante, CTA blu piena, stato presentato come badge compatto e lista monitoraggi a righe; **tabellone chiaro** con header tipografico ampio, toggle Arrivi/Partenze, filtro essenziale e righe cronologiche con orario, codice, destinazione e stato; **dettaglio scuro** con blu profondo, tipografia ad alta gerarchia, ritardo ambra, binario in contorno blu e linea del percorso verticale. La proposta Binario dovrà prendere questi principi — non testi, marchi, dati live o composizioni identiche — come riferimento per densità, contrasto e comportamento chiaro/scuro.

## Verifica dei mockup coordinati

Il master Home e il tabellone prodotti usano correttamente una gerarchia ferroviaria più compatta: ricerca o selezione principale in alto, chip dati locali, controlli blu a contorno, righe informative scansionabili e stato di indisponibilità esplicito. Entrambi includono però un bordo rosso tratteggiato tecnico generato insieme alla composizione; tale artefatto non appartiene alla direzione grafica e non dovrà essere presente nelle immagini finali sottoposte alla scelta dell’utente.

Il dettaglio scuro mantiene lo stesso sistema: superficie navy, chip dati locali, contenitore data/ora compatto, timeline verticale delle tappe, stato del servizio non disponibile e azioni Checklist/Ripianifica. La gerarchia e il contrasto sono adatti alla direzione Rail Control; anche qui il bordo rosso tratteggiato è un artefatto tecnico da escludere dalla proposta finale.

I tre mockup coordinati — Home chiara, tabellone chiaro e dettaglio scuro — sono stati recuperati come file immagine locali da 1080×1920, pronti per essere sottoposti all’utente. La scelta sarà guidata dai pattern utili (densità, controlli, chiaroscuro, timeline), non dal bordo tecnico rosso tratteggiato presente nella generazione.

### Mockup verificati

**Rail Control** usa un’intestazione indaco scura, campi di percorso raccolti in una sola superficie, una CTA ambra e moduli in formato riga per tabellone, viaggio e stato del servizio. **Station First** usa un’intestazione blu più netta, ricerca stazione come gesto iniziale, tabellone come azione dominante e griglia ridotta per nuova tratta e agenda. Entrambi usano contenuti dimostrativi e non raffigurano informazioni ferroviarie live.

## Sintesi ibrida originale

La successiva direzione Binario rifiuta le riproduzioni ravvicinate: adotta una fascia verticale di percorso, superfici inclinante a bassa altezza, una dock lineare flottante e una palette navy/cobalto/nebbia/ambra diversa dai benchmark. Dal panorama delle app ferroviarie conserva soltanto i principi utili: gesto di ricerca immediato, memoria stazioni compatta, controlli da tabellone, stati scansionabili e dettaglio ad alto contrasto. Il tabellone non conterrà partenze, ritardi, binari o avvisi inventati: in assenza di una fonte autorizzata presenterà sempre una disponibilità esplicita e non ambigua.

## Criteri dal benchmark completo

L’output finale deve conservare quattro idee operative emerse dal benchmark: la Home privilegia una tratta quotidiana o una ricerca immediata; stazioni, tratte e corse hanno memorie separate e non sono un unico elenco indistinto; il tabellone è una superficie dedicata, non un blocco sovraccarico; il dettaglio usa una timeline verticale e distingue sempre pianificato, previsto e stato. Questi principi informano la gerarchia, non autorizzano l’inserimento di dati live: ogni valore futuro ottenuto da una fonte consentita dovrà esporre fonte, timestamp e freschezza.

Visivamente, il concept ibrido eviterà tanto le card bianche ripetute quanto la dashboard tecnica troppo densa. I moduli verranno trattati come segnali di una stessa infrastruttura — bordo, tipo, icona e colore coerenti — con nav inferiore discreta, spazio negativo sufficiente e una progressione Home → Stazione → Viaggio → Avvisi leggibile a colpo d’occhio.

## Sintesi del benchmark completo fornito

Il benchmark completo individua come modello quotidiano una Home con ricerca o tratta personale immediatamente raggiungibile, memorie separate per tratte, stazioni e corse, una pagina tabellone dedicata, un dettaglio corsa con timeline verticale e widget essenziali. Per Binario diventano requisiti di linguaggio: azioni rapide senza sovraffollamento, stati di dato brevi e leggibili, preferiti nominabili, controlli di aggiornamento espliciti e indicatori che non si basano mai sul solo colore.

Le proposte visive potranno rappresentare questa gerarchia, ma non rappresenteranno partenze, ritardi, binari, cancellazioni, alert operativi o fonti live come se fossero disponibili. Tali capacità restano condizionate a una fonte autorizzata con provenienza, timestamp e freschezza mostrabili.
