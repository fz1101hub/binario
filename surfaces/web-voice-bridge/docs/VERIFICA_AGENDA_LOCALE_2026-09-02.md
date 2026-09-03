# Verifica Agenda locale — Binario

**Data:** 2 settembre 2026.  
**Perimetro:** rotta web `/agenda`, agenda e ricorrenze delle tratte conservate nel browser.

L’Agenda è alimentata unicamente dal registro `binario-local-routes-v1` del browser. Non esegue richieste a provider ferroviari, non invia dati a servizi terzi e distingue esplicitamente il contesto programmato dallo stato ferroviario realtime.

| Controllo | Esito | Limite della verifica |
| --- | --- | --- |
| Contratto ricorrenze, statistiche, condivisione e ICS | Superato in 6 test unitari mirati. | Non sostituisce il test end-to-end di tutte le future integrazioni. |
| Agenda con filtro, promemoria e archiviazione | Superato in 3 test UI, incluso controllo automatico di violazioni a11y critical/serious. | Il contrasto è escluso dal controllo jsdom. |
| Visualizzazione desktop | Empty state, filtri e invito a pianificare risultano leggibili senza dati inseriti. | Non sono state create tratte fittizie nella UI per la cattura. |
| Reflow mobile a 375 × 812 px | Hero, filtri, stato vuoto e CTA rifluiscono verticalmente senza overflow orizzontale osservabile. | Non equivale a zoom 200–400%, screen reader o test con utenti. |

> L’Agenda crea soltanto **occorrenze visuali** da una scelta esplicita di giorni, orario e numero massimo di ripetizioni. Non iscrive calendari, non pianifica notifiche di sistema e non afferma la disponibilità di dati realtime.
