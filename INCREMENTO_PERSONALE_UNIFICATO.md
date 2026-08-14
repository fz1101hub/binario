# Binario — incremento personale unificato

## Perimetro implementabile ora

L’incremento concentra funzionalità personali che funzionano senza provider ferroviario: avvisi pendolare, monitoraggi stazione salvati, viaggio attivo, configurazione della superficie widget, portabilità locale e preferenze di mobilità/accessibilità. Tutti questi dati restano sul dispositivo, sono modificabili dall’utente e non richiedono un account.

| Funzione | Dato locale | Vincolo esplicito |
|---|---|---|
| Avviso pendolare | Fermata, orario, anticipo, giorni, quiet hours | Non si aggiorna automaticamente con i ritardi senza provider autorizzato. |
| Monitoraggio stazione | Stazione e filtro arrivi/partenze | Non inventa un tabellone; mostra sempre stato non-live. |
| Viaggio attivo | Treno, prossima fermata, orario visibile | L’avanzamento è manuale salvo dati autorizzati. |
| Widget configurabile | Superficie scelta e titolo informativo | Un solo snapshot locale; stazione e viaggio attivo mostrano il limite non-live quando necessario. |
| Portabilità | File JSON di esportazione/importazione scelto dall’utente | Nessun cloud automatico, nessun backup implicito, convalida della versione. |
| Preferenze mobilità | Cambi massimi, margine interscambio, accessibilità | Sono preferenze per UI e filtri futuri; non promettono percorsi accessibili senza dati stazione. |

## Avvisi locali

Gli avvisi ricorrenti sono pianificati dal sistema Android sul dispositivo. L’app chiede l’autorizzazione alle notifiche soltanto all’atto della creazione; se viene negata, non pianifica né tenta invii alternativi. Le quiet hours sopprimono l’avviso e la pianificazione successiva rimane disponibile.

## Confine dati live

L’adapter ferroviario è un’interfaccia senza credenziali e senza endpoint preconfigurato. Finché un operatore non concede un canale documentato, ogni superficie deve mostrare il testo **“dati non disponibili in tempo reale”** e non presentare dati dimostrativi come operativi.

Il client contiene inoltre un guardrail di readiness: il canale Trenitalia è inizialmente **non configurato**, non consente richieste di rete e non conserva credenziali. Un eventuale gateway sarà solo server-side, dopo autorizzazione documentata e con provenienza, freshness e TTL esposti in ogni snapshot.
