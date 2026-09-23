# Binario — matrice funzionale completa

Questa matrice riporta le funzionalità emerse dai benchmark già svolti e le confronta con l’implementazione attuale. Lo scopo non è replicare le singole app, ma chiudere sistematicamente i gap del prodotto personale Binario.

| Area | Pattern dai benchmark | Stato in Binario | Prossima azione |
|---|---|---|---|
| Pianificazione tratta | Partenza, arrivo, inversione, andata/ritorno, data, ora, filtri, CTA. | **Parziale**: struttura presente; data/ora e filtri non sono ancora controlli completi. | v0.9: controlli interattivi e criteri ricerca espliciti. |
| Ricerca numero treno | Accesso immediato e risultato dettagliato. | **Parziale**: dominio disponibile, ma non è una modalità di apertura nel nuovo pianificatore. | v0.9: modalità `Treno` nella home. |
| Ricerca/tabellone stazione | Ricerca stazione, arrivi/partenze e stato fonte. | **Parziale**: superficie e stato non-live presenti. | v0.9: modalità `Stazione`, recenti e monitoraggio; live dopo provider. |
| Risultati viaggio | Lista soluzione, operatori/categorie, alternativa e filtri. | **Parziale**: lista corse demo e filtro operatore. | v0.9: filtri categoria/stato e ordinamento selezionabile. |
| Dettaglio treno | Stato, binario, ritardo, sequenza fermate, ultima osservazione. | **Presente** nel catalogo demo. | Arricchire con avvisi/variazioni dopo provider. |
| Tratte personali | Nome, salva, fissa, andata/ritorno, ordine. | **Presente** per nome, pin e direzione; ordine manuale assente. | v0.9: ordine e uso recente persistenti. |
| Recenti/preferite | Ricerca recente, tratta favorita e richiamo con un tocco. | **Parziale**: tratte salvate/pinnate, non cronologia ricerche. | v0.9: registro locale recenti con cancellazione. |
| Monitoraggio treno | Seguire uno specifico treno indipendentemente dalla tratta. | **Mancante**. | v0.9: dominio locale monitoraggi e UI; alert live dopo provider. |
| Monitoraggio stazione/direttrice | Seguire un tabellone o una linea. | **Mancante**. | v0.9: struttura monitoraggio, fonte non-live esplicita. |
| Avvisi personali | Ritardo oltre soglia, binario, cancellazione, quiet hours. | **Mancante**. | v1.0: regole opt-in e notifiche locali; trigger reali dopo provider. |
| Infomobilità personale | Perturbazioni filtrate per tratte/monitoraggi. | **Mancante**. | v1.0 con feed autorizzato. |
| Qualità/fonte dato | Provider, freshness, confidence, ultimo aggiornamento. | **Presente**, ma non sempre sintetizzato nei risultati. | v0.9: riga qualità uniforme in tutte le superfici. |
| Widget | Cinque corse, pagine, refresh, direzione e stato. | **Presente**. | Test fisico e aggiornamento da monitoraggi. |
| Wear OS / watchOS | Partenze rapide, complication, alert e voce. | **Predisposto**. | Collegare monitoraggi e backend autorizzato. |
| Voce Alexa/App Actions | Prossime corse, stato treno, tratte salvate. | **Backend predisposto**, Alexa non attiva senza credenziali. | Attivazione post-account e provider. |
| Accessibilità | TalkBack, contrasto, font 200%, non solo colore. | **Base presente**. | Test fisico e regressioni UI per ogni incremento. |
| Privacy e sicurezza | Nessun tracking, permessi minimi, HTTPS, backup escluso. | **Presente**. | Vincolo non negoziabile. |

## Backlog prioritario

| Release | Blocco | Funzioni incluse | Dipendenza |
|---|---|---|---|
| v0.9 | **Ricerca e memoria personale** | Modalità Viaggio/Treno/Stazione, data/ora e filtri interattivi, recenti persistenti, ordine tratte, monitoraggio locale treno/stazione. | Nessun feed live richiesto. |
| v1.0 | **Avvisi personali** | Regole opt-in, soglie, fasce orarie, quiet hours, notifica locale controllata. | Trigger reali subordinati a provider autorizzato. |
| v1.1 | **Informazione operativa** | Infomobilità, variazioni e tabelloni stazione per i miei monitoraggi. | Feed autorizzato per operatore/regione. |
| v1.2 | **Sincronizzazione personale** | Room, esportazione/importazione locale, collegamento sicuro con bridge vocale/watch. | Migrazione dati e backend già predisposto. |

> Il prossimo incremento è **v0.9 Ricerca e memoria personale**. Aggiunge funzioni percepibili senza presentare mai il catalogo dimostrativo come servizio live.
