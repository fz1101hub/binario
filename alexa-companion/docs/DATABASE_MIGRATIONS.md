# Migrazioni database Alexa Companion

## Discendenza canonica

La catena Drizzle canonica di questo companion è **0000–0012**. Le migrazioni **0000–0011** mantengono i tag, gli snapshot e i timestamp del journal WebDev storico; `0007` contiene soltanto una correzione del separatore tra due statement che il test MariaDB esegue separatamente. Non devono essere rinumerate o ricreate: un database già provisionato da WebDev contiene dati e oggetti introdotti da quella storia, inclusi tratte personali, preferenze stazione e record di pairing.

La migrazione **0012_alexa_credential_revocation_adoption** è intenzionalmente solo additiva. Usa `IF NOT EXISTS` per introdurre `credentialVersion`, `revokedAt` e gli oggetti storici mancanti senza eliminare o riscrivere dati. Questo consente di adottare sia un database WebDev arrivato a `0011`, sia la breve catena Alexa `0000–0003` distribuita prima dell’unificazione.

## Procedura operativa

Usare sempre il normale comando `pnpm db:migrate` contro un backup verificato del database target. Non inserire manualmente righe in `__drizzle_migrations` e non eseguire selettivamente i file SQL. Su un database proveniente dalla catena 0000–0011 o dalla breve 0000–0003, Drizzle applica soltanto 0012; su un database vuoto applica tutta la catena nell’ordine del journal. Il test MariaDB `pnpm test:db-migrations` esercita tutti e tre i percorsi e verifica la conservazione di righe storiche.

Questa procedura non esprime alcuna scelta di hosting o credenziale: il `DATABASE_URL` resta un input dell'ambiente di deploy.
