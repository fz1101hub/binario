# Verifica — Binario v0.20.3

## Correzione conservativa della regressione UI

Il dispositivo ha segnalato che i controlli dell’app non rispondevano dopo la modifica del pianificatore v0.20.1. Non essendo disponibile un dispositivo o emulatore nel sandbox, la correzione è conservativa: ripristina il flusso UI del pianificatore precedente alla regressione e mantiene soltanto la correzione indipendente dell’asset catalogo JSON.

| Controllo | Esito |
|---|---|
| Stato selezione aggiuntivo | Rimosso: nessun riferimento a `selectedOrigin` o `selectedDestination`. |
| Stato catalogo aggiuntivo UI | Rimosso: nessun riferimento a `StationCatalogStatus` o `stationCatalogStatus`. |
| Campo partenza/arrivo | Torna a usare `OutlinedTextField` più pulsanti `TextButton` diretti. |
| Scambio tratta | Torna allo scambio diretto dei due valori testuali. |
| Catalogo nell’APK | Verificato dal tool di integrazione: 5.239 stazioni, Appiano Proba Petronia e Roma Ostiense presenti. |
| Test unitari e assemble | Superati prima del bump di versione; nuova APK da rigenerare e ispezionare prima della consegna. |

## Limite

La prova di tap reale non può essere effettuata senza un dispositivo Android o emulatore collegato. Questa release non deve essere dichiarata definitiva finché non viene verificato sul telefono che i tre selettori **Tratta / N. treno / Stazione**, il cambio partenza-arrivo e il campo testo rispondano al tocco.
