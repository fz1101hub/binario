# Verifica del riallineamento dell’app Binario

## Perimetro di prova

La verifica non richiede alcun accesso al profilo dell’utente, né l’inserimento di password, codici OTP o dati personali. I test riguardano esclusivamente dati di catalogo e contesti di prova non personali.

| Flusso | Evidenza | Esito |
|---|---|---|
| Catalogo stazioni | Parser e ricerca verificati su nome, alias e assenza di risultati inventati | Superato |
| Tratta personale | Procedura tRPC autenticata verificata con un contesto di test isolato | Superato |
| Codici stazione | `originStationCode` e `destinationStationCode` normalizzati e passati alla persistenza | Superato |
| Home–tabellone | Percorso, recupero query e rendering statico di nome/codice verificati | Superato |
| Dettatura nativa | Procedura protetta, invio temporaneo al servizio di trascrizione italiana e revisione nel campo verificati con test | Superato |
| Layout | Home e tabellone mobile verificati nell’anteprima | Superato |
| Realtime | Stati di indisponibilità dichiarati esplicitamente senza generare ritardi, binari o avvisi fittizi | Superato |

## Limite intenzionale

L’app non interroga ancora una fonte ferroviaria live. Di conseguenza il tabellone rende visibile la stazione scelta, i controlli Partenze/Arrivi e il relativo stato, ma non mostra orari, ritardi, binari, cancellazioni o disservizi inventati. Il prossimo incremento potrà collegare una fonte autorizzata mantenendo questa distinzione.

La dettatura chiede l’accesso al microfono soltanto al tocco del relativo controllo. La registrazione è caricata unicamente per la richiesta di trascrizione italiana, non viene collegata al profilo o ai monitoraggi personali e il testo trascritto può essere modificato o ignorato prima di ogni ricerca o salvataggio. Il buffer binario viene azzerato in memoria al termine della trascrizione o in caso di errore. Lo storage sottostante non espone una cancellazione diretta: la chiave non viene registrata nel database né esposta all’interfaccia, quindi l’oggetto resta non referenziato dal prodotto. Prima della pubblicazione commerciale occorre comunque definire contrattualmente la retention effettiva del fornitore storage e del servizio di trascrizione.

## Dettatura guidata di tratta

La dettatura della tratta completa riusa lo stesso consenso esplicito e la stessa trascrizione temporanea dei microfoni dei singoli campi. La frase viene interpretata localmente per isolare Partenza, Arrivo, data e orario, ma viene applicata alla pianificazione solo dopo la revisione dell’utente e la conferma delle due stazioni nel catalogo canonico. In caso di catalogo non disponibile, stazione ambigua o stazione non riconosciuta, l’app non completa né invia automaticamente la tratta.

Il prodotto non dichiara una cancellazione dell’audio presso il fornitore oltre i termini effettivamente previsti dall’accordo di trascrizione. Prima della pubblicazione commerciale restano necessari: registro dei trattamenti, definizione della base giuridica e dei ruoli privacy, retention contrattuale del fornitore e valutazione DPIA se il trattamento o la scala lo rendono necessario.

## Confini di prodotto

Ricerca, pianificazione, dettatura, viaggi, stazioni, tabelloni e avvisi restano il prodotto principale. Alexa, widget e smartwatch restano companion opzionali dei monitoraggi configurati nell’app.
