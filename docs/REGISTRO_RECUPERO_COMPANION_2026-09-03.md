# Binario — registro di recupero del companion

## Esito dell’audit

Il repository Git `fz1101hub/binario` contiene una linea nativa Android/Wear precedente e un ramo di recupero creato il 3 settembre 2026. Non contiene una cronologia completa e lineare della successiva codebase Expo/companion descritta nelle sessioni storiche. In particolare, nello storico Git consultabile non è presente un modulo Wear che esegua l’acquisizione vocale dal polso.

> **Conseguenza operativa.** Non considero più una capacità “sviluppata” se non è presente in una sorgente Git recuperabile, accompagnata da test/build, oppure se non viene ricostruita e consolidata con queste evidenze.

| Sorgente controllata | Stato | Capacità verificabili | Limiti |
| --- | --- | --- | --- |
| `main` del repository | Recuperabile, commit `8de7305` | Base Android/Wear e widget fino a `v0.16.3`. | Non include la serie successiva del companion. |
| `feature/data-foundations` | Recuperabile, commit `bd67cfa` | Catalogo, planner, voce Android e contratti dati fino alla serie `v0.20.x`. | Il modulo Wear era incompleto: manifest e activity referenziavano file assenti. |
| Commit voce `805c01e`, `88744bc` | Recuperabili | Dittatura **Android telefono** con `RecognizerIntent` e parser contestuale del planner. | Nessuna modifica sotto `wear/`; non prova una richiesta vocale dal polso. |
| Archivio `Binario-v0.20.0-consegna.zip` | Recuperabile come artefatto | APK debug, catalogo e note di consegna. | Include solo sorgenti del catalogo; non contiene la codebase completa né voce Wear. |
| Copia Expo sandbox precedente | Parziale, senza `.git` e senza dipendenze coerenti | Può offrire indizi di prodotto, non una base verificabile. | Non è usata per recuperare, sovrascrivere o dichiarare funzionalità. |
| Ramo `work/benchmark-local-improvements-20260903` | Consolidato su Git | Policy fonti locale, riordino tratte, recupero Tile/complication/snapshot Wear e protocolli di test. | Non include ancora input vocale Wear. |

## Capacità smarrite o non dimostrabili

La funzione “parla dal polso” con domande sulla tratta non è attualmente dimostrabile dal codice recuperato. È pertanto classificata come **da ricostruire**, non come completata. Lo stesso vale per qualsiasi affermazione sui ritardi: senza un provider ferroviario autorizzato, l’unica risposta corretta è “dati live non disponibili”, con eventuale riepilogo dell’ultimo orario programmato locale.

## Regola di consolidamento

Ogni incremento companion deve seguire questa sequenza: modifica piccola, test unitari, build Android e Wear, commit isolato, push del ramo e verifica dello stato Git pulito. Gli APK sono artefatti di verifica e non sostituiscono la sorgente; il codice deve sempre precedere la build nel repository.

## Prossimo recupero prioritario

Il prossimo incremento ricostruirà il companion vocale Wear in modo limitato e verificabile: l’orologio apre il riconoscitore di sistema, invia al telefono solo testo breve validato, il telefono consulta il suo stato locale e restituisce un riepilogo coerente. La domanda resta limitata a tratta, stazione, numero treno, orario programmato e stato dati. Ritardi, binari e soppressioni richiedono una fonte autorizzata e non verranno sintetizzati.
