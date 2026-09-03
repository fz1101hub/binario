# Widget Binario configurabile e ridimensionabile

## Obiettivo

Il widget diventa un **tabellone ferroviario Binario originale**, progettato per lettura rapida: testata verde, righe bianche separate, orari a larghezza fissa, destinazione dominante, identificativo di servizio e stato allineato a destra. La struttura è ispirata alla densità informativa dei riferimenti forniti, senza riprodurne grafica, testi o composizione.

## Configurazione per istanza

Ogni widget conserva una scelta indipendente, anche quando sono presenti più widget nella home.

| Destinazione | Dato mostrato senza provider live | Dato che resta bloccato |
|---|---|---|
| Tratta | Le cinque corse del relativo snapshot locale paginato | Aggiornamento operativo reale senza provider autorizzato |
| Stazione specifica | Nome e modalità di monitoraggio salvata | Arrivi e partenze reali |
| Treno specifico | Riga del treno selezionato nello snapshot locale | Posizione e variazioni reali |

## Ridimensionamento

Il provider legge le opzioni del launcher a ogni resize e adotta una densità locale.

| Modalità | Criterio | Presentazione |
|---|---|---|
| Compatta | larghezza o altezza ridotta | Due righe, senza metadati secondari né intestazioni colonne |
| Standard | dimensione normale | Cinque righe, colonne ora/destinazione/treno/binario/stato |
| Espansa | larghezza e altezza ampie | Cinque righe, testata completa e comandi leggibili |

## Stati ed evidenza

> Il giallo non è un indicatore decorativo: appare solo per una corsa con ritardo, variazione o soppressione contenuta in uno **snapshot non dimostrativo** e non dichiarato non disponibile. TalkBack annuncia sempre lo stato completo; il colore non è l’unico segnale.

Con il catalogo locale Binario mantiene la dicitura **“dati non disponibili in tempo reale”** e non applica il giallo come se derivasse da un evento operativo.
