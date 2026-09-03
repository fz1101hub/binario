# Validazione su dispositivo — Binario v0.8.0

La build ha superato test unitari, compilazione e lint. Questa checklist controlla sul telefono i comportamenti percettivi e assistivi del redesign.

| Scenario | Risultato atteso |
|---|---|
| Home | La prima azione riconoscibile è pianificare un viaggio: partenza, arrivo, inversione e `Cerca treni` stanno nello stesso blocco. |
| Risultati | I risultati seguono il planner; le tratte fissate sono scorciatoie e non precedono la ricerca. |
| Tema | Il comando palette nella barra apre `Sistema`, `Chiaro`, `Scuro`; la scelta persiste dopo chiusura e riapertura dell’app. |
| Tema Sistema | Segue il tema del telefono al riavvio o al cambio tema di sistema. |
| Chiaro/Scuro | Verde, azzurro, ambra e rosso mantengono il ruolo semantico; nessuno stato si distingue solo per colore. |
| Font 200% | Campi, pulsante Cerca, risultati e opzioni tema restano leggibili e raggiungibili. |
| TalkBack | Il comando tema annuncia la modalità attiva; ogni corsa annuncia destinazione, orario, stato, binario e operatore. |
| Dati | Il catalogo locale continua a dichiarare “Catalogo dimostrativo locale — non live”. |

> Se l’app non apre, il test prioritario è la sola apertura a freddo prima di valutare il redesign. In caso di crash, occorre inviare il testo Android dell’errore o lo stack trace.
