# Contratto di pairing dei companion Binario

## Finalità e confini

Il pairing collega **una singola installazione** Android o iOS a un account Binario già autenticato. Non trasferisce la sessione web, non espone cookie, non introduce un archivio ferroviario parallelo e non abilita alcun provider realtime. Il device può leggere esclusivamente lo snapshot unificato del core; anche attraverso il token dispositivo, il backend applica il medesimo contratto `CompanionSnapshot` e il medesimo fallback fail-closed.

## Protocollo

| Passaggio | Procedura | Garanzia |
|---|---|---|
| Emissione | Utente autenticato → `companion.issuePairingCode` | Codice opaco di 256 bit, valido 10 minuti e persistito solo come HMAC. |
| Riscatto | Companion → `companion.redeemPairingCode` | Codice atomico, monouso e associato a nome/piattaforma dell’installazione. |
| Credenziale | Backend → companion | Token opaco di 256 bit, valido 90 giorni, mostrato una volta, persistito sul server solo come HMAC. |
| Consultazione | Companion → `companion.deviceSnapshot` con `Authorization: Bearer` | Il bearer identifica il solo device attivo; la risposta è l’unico `CompanionSnapshot` normalizzato. |
| Revoca | Dashboard → `companion.revokeDevice` oppure device → `companion.revokeCurrentDevice` | Revoca immediata; il client rimuove token SecureStore e cache snapshot. |

Il token non compare in URL, dashboard, log applicativi intenzionali, notifiche, widget, watch, Alexa o cache AsyncStorage. La dashboard mostra soltanto metadati minimizzati dell’installazione e un prefisso non autentificante per riconoscerla.

## Comportamento fail-closed

Una credenziale assente, scaduta, revocata o malformata genera `UNAUTHORIZED`; il companion cancella l’eventuale accesso locale per errori 401/403 e mostra il fallback **non disponibile**. Un errore rete o una risposta incompatibile produce lo stesso fallback. Gli snapshot realtime restano soppressi se scaduti, conflittuali o non verificati; dati `scheduled` possono alimentare soltanto promemoria esplicitamente etichettati come non realtime.

## Operatività e rotazione

Non esiste refresh automatico: alla scadenza di 90 giorni, l’utente genera un nuovo codice dalla dashboard. Ciò limita la persistenza delle credenziali su un device. Il server deve mantenere i timestamp `createdAt`, `lastUsedAt`, `expiresAt` e `revokedAt` per supportare controllo, revoca e pulizia; la routine di cancellazione dei record già scaduti potrà essere introdotta solo con una policy retention approvata e un’esecuzione schedulata esplicita.
