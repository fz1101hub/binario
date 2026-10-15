# Matrice di parità — Homepage web e Home companion

**Data:** 3 settembre 2026  
**Perimetro:** funzioni della Homepage web con equivalente sensato sul companion Android/iOS. La matrice non certifica hardware, screen reader, provider ferroviari, dati realtime o distribuzione store.

| Homepage web: funzione o stato | Equivalente Home companion | Esito verificabile |
| --- | --- | --- |
| Pianificazione di una tratta con catalogo, data, ora e promemoria | Planner nativo con autocomplete offline, picker di sistema, promemoria locale e conferma del salvataggio | Presente. |
| Dittatura del percorso con revisione | Campo testo del planner che usa la dettatura della tastiera di sistema e richiede revisione | Presente; nessuna registrazione o upload automatico. |
| Stazione vicina nel campo Partenza | Pulsante contestuale “Vicino” nel planner, dopo azione e permesso, con calcolo sul catalogo offline | Presente. |
| Modelli di viaggio e preferiti | Modelli locali, preferiti della partenza e preferiti del tabellone | Presente. |
| Tratte personali / archivio | Riepilogo fino a tre tratte imminenti e accesso all’Agenda per archivio, filtri, checklist e statistiche | Presente; la lista completa resta correttamente in Agenda. |
| Ritorno e ripianificazione | Azioni dalla Home sul viaggio imminente; percorso precompilato e nuova data/ora obbligatorie | Presente. |
| Ricerca e apertura di un tabellone stazione | Card “Apri un tabellone” con ricerca offline, codice canonico e pannello stazione | Presente. |
| Stazioni preferite e recenti | Chip locali, limitati e deduplicati, per riaprire una stazione; nessuna coordinata o rete | Presente. |
| Stato dei dati ferroviari | Card “Stato del servizio”, con separazione fra pianificazione locale, futuro programmato e realtime | Presente; in assenza di provider lo stato è non disponibile. |
| Avvisi di servizio | Card “Avvisi del servizio” con stato vuoto che chiarisce di non dimostrare assenza di disservizi | Presente; nessun avviso viene inventato. |
| Agenda, checklist e importazione | Azioni rapide verso Agenda e importazione guidata, che conserva anteprima e conferma | Presente. |
| Privacy, trasferimento e sync facoltativo | Azione rapida verso le impostazioni locali; nessun trasferimento si avvia dalla Home | Presente. |
| Telefono, widget e smartwatch | Azione rapida verso il Companion hub, con superfici e limiti locali | Presente. |
| Accesso account e funzioni server della web autenticata | Non è richiesto dalla Home companion local-first; sync resta un’azione facoltativa delle Impostazioni | Confine intenzionale. |
| Tabellone live, ritardi, binari, cancellazioni e avvisi verificati | Non esposti come disponibili senza fonte autorizzata, timestamp e TTL | Confine intenzionale. |

## Verifiche svolte

Il contratto del catalogo aggiunge il lookup esclusivamente per codice canonico; la cronologia locale stazioni è limitata a sei elementi, deduplicata e si svuota in caso di archivio non valido. Il test contrattuale controlla anche la presenza di tabellone, stato del servizio, avvisi e messaggi di indisponibilità. La pipeline companion ha superato `pnpm check`, `pnpm test` (**35 file, 99 test**), `expo export --platform android` e `:app:assembleRelease :wear:assembleRelease`.

La verifica su telefono Android reale resta necessaria per interazioni touch, TalkBack, menu Condividi e resa visiva fisica. Nessun esito di tali prove viene dedotto dai test automatici o dalla compilazione.
