# Binario — consultazione immediata v0.14

## Linguaggio visivo

Il widget adotta un **tabellone ferroviario originale**, non una copia di altre applicazioni: testata compatta della tratta, stato di freschezza leggibile, intestazioni di colonna ad alto contrasto e una riga per corsa con ora, destinazione, servizio, binario e stato. I comandi pagina, direzione e refresh restano separati e riconoscibili; il limite resta di cinque corse per pagina.

Nella sezione **Consulta**, Treni e Stazioni sono due schede squadrate, con raggio ridotto, titolo esplicito e descrizione dell’azione. Il controllo rimane raggiungibile da tastiera e TalkBack.

## Sveglia alla fermata

La sveglia viene richiesta dalla riga della fermata e viene pianificata localmente a **cinque minuti** dall’orario previsto. In assenza di dati live, l’app dichiara che si tratta di una pianificazione basata sul catalogo locale e che l’orario può variare. La notifica non richiede account; la posizione, l’audio e i dati della fermata non sono inviati a servizi Binario.

## Condivisione

I dettagli di una corsa sono convertiti in un testo strutturato — operatore, categoria, numero, origine, destinazione, orari, binario, stato, fermate, fonte e freschezza — e passati al foglio di condivisione nativo Android. Il modello `TrainShareContent` resta privo di API Android e potrà essere usato da un futuro companion iOS.
