# Alexa come companion di Binario

Alexa non è un prodotto né una fonte dati separata: è una superficie vocale del nucleo Binario. Le richieste generiche di stato ferroviario possono essere poste senza account Binario, ma ricevono un dato solo se il nucleo dispone di una fonte autorizzata con provider, timestamp, TTL e confidence validi. Nello stato attuale la risposta è quindi esplicitamente **«dati non disponibili in tempo reale»**.

| Interazione | Account Binario | Comportamento corrente |
| --- | --- | --- |
| Stato di un treno o domanda su una tratta nominata | Non richiesto | Fallback realtime fail-closed; nessun treno, ritardo o binario inventato. |
| Elenco o selezione delle tratte personali | Facoltativo, necessario per il dato personale | Account linking OAuth protetto, solo se la Skill verrà configurata. |
| Invocazione pubblica della Skill | Richiede configurazione Amazon esterna, non un account Binario | Endpoint `503 alexa_not_configured` finché skill ID, client OAuth e segreto non sono configurati. |

La console Amazon Developer, l’identificativo della Skill, l’account linking e i test di certificazione non sono inclusi nel codice e non sono stati simulati. L’handler web verifica la configurazione prima di elaborare il webhook; quando attivo, l’adapter Alexa resta responsabile della verifica di firma, certificato, timestamp e Skill ID prima dei handler.
