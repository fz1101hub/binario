# Ultima rilevazione operativa — significato e provenienza

## Esito della verifica

L’app On The Rails contiene i campi `stazioneUltimoRilevamento` e `oraUltimoRilevamento`. L’analisi del codice mostra che derivano dai campi `lastTakeoverLocation` e `lastTakeoverTime` ottenuti dal canale `https://app.lefrecce.it/Channels.Mobile.BFF/api/info/mobility/status`.

Questa evidenza attribuisce l’**ultima rilevazione operativa** osservata nell’app di riferimento al canale mobile LeFrecce/Trenitalia, non al tracker pubblico Italo. Il tracker pubblico Italo dichiara stato dei treni e tabelloni in tempo reale, ma nelle pagine, form e bundle pubblici esaminati non espone un’etichetta o un campo documentabile come `boa`, `lastTakeoverLocation`, `ultima rilevazione` o posizione GPS.

## Modello corretto per Binario

| Concetto UI | Definizione | Quando può essere mostrato |
|---|---|---|
| Ultima rilevazione operativa | Ultima località/evento operativo confermato dal provider, con timestamp | Solo se la fonte espone luogo e ora con identità della corsa risolta |
| Ultima stazione confermata | Fermata con stato arrivato/partito confermato | Se presente nel tabellone o nel dettaglio ufficiale della corsa |
| Posizione GPS | Coordinata geografica fornita dal provider | Solo con `PositionKind.GPS`; non sostituibile con una stazione o una stima |
| Posizione stimata | Inferenza da orari e percorso | Non usare come rilevamento reale; eventuale futuro uso soltanto con etichetta esplicita |

## Regole di presentazione

1. Non chiamare mai una rilevazione operativa “boa” senza che il provider usi o documenti quel concetto.
2. Non mostrare una rilevazione LeFrecce/Trenitalia come dato Italo.
3. Non usare il canale mobile BFF di LeFrecce nell’APK: è un endpoint non documentato per integrazioni di terzi e richiede un percorso autorizzato.
4. Per Italo continuare a mostrare solo stato, ritardo e binario che compaiono nel tabellone pubblico. La “ultima rilevazione” resta assente finché Italo non la espone in un canale pubblico verificabile.

## Conseguenza implementativa

Non viene aggiunto un adapter Italo per la boa/rilevazione: i requisiti di provenienza e stabilità non sono soddisfatti. L’interfaccia può essere predisposta in futuro con un tipo `OperationalDetection`, ma il campo deve restare vuoto per Italo finché non esiste una fonte pubblica autorizzata.
