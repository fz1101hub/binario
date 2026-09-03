# Sincronizzazione Binario opt-in

**Data:** 2 settembre 2026.  
**Perimetro:** tratte pianificate tra area personale Binario e companion Android/iOS; nessun dato ferroviario realtime.

La sincronizzazione non è necessaria per usare Binario. Ogni superficie conserva anzitutto il proprio archivio locale; non vengono avviati polling, backup, push o importazioni in background.

| Passaggio | Garanzia implementata |
| --- | --- |
| Browser → area personale | L’utente apre un’anteprima, seleziona individualmente le tratte nuove e conferma. Duplicate e conflitti di codice stazione non sono copiati. |
| Companion → area personale | Dopo collegamento facoltativo e consenso `syncEnabled`, l’utente esamina, seleziona e conferma le tratte locali da copiare. L’import server è idempotente. |
| Area personale → companion | Con lo stesso consenso, il companion mostra un’anteprima delle tratte pianificate e importa solo quelle selezionate, in modo additivo. |
| Conflitti | Stessi nomi/data/ora con codici stazione diversi restano bloccati e leggibili; non è applicata alcuna risoluzione automatica. |
| Revoca | La disattivazione blocca subito pull/push per quel device; la revoca elimina anche la credenziale locale e invalida il device server. Le copie già locali o nell’account non vengono cancellate automaticamente. |

L’area web dei dispositivi mostra inoltre lo stato `syncEnabled` di ogni device attivo. Il titolare può bloccare o ri-autorizzare le sole copie di tratte senza revocare il token usato per lo snapshot companion; il backend verifica che il device sia attivo e appartenga allo stesso account.

> Il collegamento account è separato dall’associazione di sistema telefono–smartwatch. Il codice di collegamento, quando scelto per la sola sincronizzazione opzionale, non è necessario al normale uso locale né al widget/watch locale.

La migrazione `0011_volatile_risque.sql` ha aggiunto `companion_devices.syncEnabled` con valore predefinito `false`. Web e companion hanno test di contratti/anteprima; la compilazione e la prova hardware iOS restano attività separate da svolgere in Xcode.

## Verifica dell’interfaccia web

La cattura della Home autenticata mostra la card **Sincronizzazione Binario facoltativa** accanto al planner: descrive il confronto prima della copia, non pre-seleziona alcuna tratta e rende disponibile una disattivazione esplicita. La cattura dell’Agenda mantiene visibili preferenze locali, statistiche e calendario senza introdurre stati realtime. Queste osservazioni visive non sostituiscono i test assistivi reali ancora aperti nella checklist di accessibilità.

## Copie locali e cancellazione

Il Privacy Center separa ora l’archivio account dal browser corrente. La cancellazione browser rimuove soltanto tratte ospite e preferenze viaggio locali; preserva tema, contrasto, testo grande, scorciatoie, account, sincronizzazione e token companion. Sul telefono, l’azione equivalente annulla prima i promemoria locali e poi rimuove le tratte AsyncStorage, senza modificare account, consenso di sincronizzazione o preferenze di accessibilità.
