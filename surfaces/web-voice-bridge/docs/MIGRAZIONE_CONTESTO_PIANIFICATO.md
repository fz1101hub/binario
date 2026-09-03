# Evidenza migrazione contesto pianificato

## Scopo

Le tratte personali possono salvare un riferimento **programmato** di data e ora, distinto da qualsiasi dato realtime. I campi sono opzionali per mantenere la compatibilità con tutte le tratte create prima dell’introduzione della pianificazione.

## Migrazione revisionata

Il file `drizzle/0005_abandoned_kate_bishop.sql` contiene entrambe le istruzioni non distruttive:

```sql
ALTER TABLE `railway_routes` ADD `travelDate` varchar(10);
ALTER TABLE `railway_routes` ADD `travelTime` varchar(5);
```

La precedente lettura parziale aveva mostrato solo la seconda riga; non si trattava di una migrazione con un solo campo. Le due istruzioni sono state applicate separatamente sul database gestito, senza aggiornare né eliminare righe esistenti.

## Verifica database reale

La query eseguita su `information_schema.COLUMNS` per `railway_routes` ha restituito due righe:

| Colonna | Tipo | Nullabile | Significato |
|---|---|---|---|
| `travelDate` | `varchar(10)` | Sì | Data scelta per la pianificazione, in formato `YYYY-MM-DD` |
| `travelTime` | `varchar(5)` | Sì | Orario scelto per la pianificazione, in formato `HH:MM` |

Il contratto server valida entrambi i formati e converte i valori assenti in `null`. Le procedure protette create/list propagano i campi e i test coprono sia la validazione sia la retrocompatibilità dei valori opzionali. Non sono stati inseriti dati di prova nel database reale.

## Vincolo semantico

Data e ora salvate descrivono il **contesto programmato richiesto dall’utente**. Non costituiscono un orario operativo, un ritardo, un binario o una previsione. Qualunque dato live richiederà una fonte autorizzata e dovrà riportare provenienza, timestamp e freschezza.
