# Deep link Binario

Le sole destinazioni web interpretate sono il dettaglio di una tratta `#/viaggi/{id}` e il tabellone `/stazioni?name={nome}&code={codice}`. Il parser accetta soltanto percorsi interni, identificativi numerici positivi per una tratta e parametri `name`/`code` della stazione canonica. Query ulteriori, fragment, schemi esterni, codici non validi e URL malformati vengono rifiutati.

L’identificativo tratta non contiene nome, nota, origine, destinazione, token, coordinate, audio o snapshot. L’accesso al dettaglio resta comunque vincolato al profilo autenticato: un ID non posseduto restituisce indisponibilità.
