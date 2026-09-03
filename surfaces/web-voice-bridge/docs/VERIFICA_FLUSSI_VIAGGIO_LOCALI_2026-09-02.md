# Verifica flussi viaggio locali

**Data:** 2 settembre 2026.  
**Perimetro:** Home e Agenda web dopo andata/ritorno, ripianifica, riepilogo pre-partenza, filtri archivio e importazione calendario selettiva.

| Controllo | Esito osservato | Limite |
| --- | --- | --- |
| Home in sessione autenticata | Planner, azioni rapide, disclosure sulle fonti e controlli privacy restano ordinati e leggibili nella griglia desktop. | La cattura autenticata non verifica la card di trasferimento manuale riservata al percorso ospite. |
| Agenda senza tratte | Filtri, preferenze viaggio, statistiche locali e importazione ICS presentano gerarchia leggibile e stato vuoto chiaro. | Non sostituisce la prova di interazione con dati personali reali. |
| Reflow osservabile | Nessun overflow orizzontale è stato osservato nelle catture desktop complete. | Non equivale a zoom 200–400%, font scale o lettore di schermo. |
| Flussi di importazione | Contratti e test UI verificano anteprima, selezione, deduplicazione e conferma prima di ogni modifica al browser. | Serve ancora un test umano con file rappresentativi scelti dall’utente prima di qualsiasi rilascio pubblico. |

> Tutte le informazioni di orario nei flussi verificati sono **programmate localmente**. Nessuna card attesta dati ferroviari realtime, binari, ritardi, cancellazioni o spostamenti effettivamente compiuti.
