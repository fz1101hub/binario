# Binario — revisione benchmark e gap v0.14.0

**Data:** 14 agosto 2026  
**Perimetro:** Orario Treni, On The Rails, ReteBK, Trenìt!, InfoTreni, Treninoo, Trainline, Trenitalia, TrenScan, Omio, Italo, Trenord, Île-de-France Mobilités e Treniamo.it.

## Esito sintetico

Binario ha ormai coperto il nucleo di consultazione personale emerso dal benchmark: pianificatore tratta/treno/stazione, data e ora, filtri operatore e cambi, recenti, tratte fissate, monitoraggio treno locale, fermate, widget paginato, stazioni preferite e vicine, dettatura, tema, sveglia locale, condivisione, Wear OS, base watchOS e preferenze privacy-first. Non è però ancora al livello delle app più mature in tre aree: **personalizzazione degli avvisi**, **memoria personale portabile** e **informazione operativa basata su dati live autorizzati**.

| Famiglia benchmark | Pattern verificato | Stato Binario v0.14.0 | Lettura utile |
|---|---|---|---|
| Orario Treni, ReteBK, Trenìt!, Treninoo, InfoTreni | ricerca, dettaglio, fermate, binari, widget e preferiti | In gran parte presente | Il vantaggio residuo dipende soprattutto dalla qualità del dato live. |
| On The Rails | allarmi configurabili, ricorrenze pendolari, geofencing, widget specializzati, storico puntualità | Parziale | Gli allarmi locali sono presenti ma fissi; ricorrenze, configurazione e geofence mancano. [1] |
| Treniamo.it | mappa treno stimata, stato treno, partenze/arrivi, statistiche puntualità e notizie traffico | Parziale / bloccato | Rafforza la priorità di storico, infomobilità e visualizzazione del percorso; non adottare la sua fonte, dichiarata non ufficiale. [5] |
| Trenitalia, Trenord, Italo | ricerca salvata, notifiche personali, spostamento viaggio, biglietteria e programmi proprietari | Parziale / fuori perimetro | Salvataggio ricerche e suggerimenti di ripianificazione sono adottabili; biglietteria e loyalty richiedono accordi e non sono priorità. [2] [3] |
| Trainline, Omio, Île-de-France Mobilités | multimodalità, titoli digitali, profilo di mobilità e preferenze accessibilità | Selettivo | Preferenze accessibilità e memoria personale sono adottabili; pagamenti e titoli digitali non lo sono senza un ecosistema contrattuale. [4] |

## Gap effettivi

| Priorità | Funzione non ancora completa | Valore per l’uso personale | Dipendenza | Decisione |
|---|---|---|---|---|
| P0 | **Sveglia configurabile**: anticipo 1–60 minuti, suono/vibrazione, annullamento e riepilogo | Alta, soprattutto per viaggi lunghi e pendolari | Nessuna fonte live per la versione iniziale | Adottare subito; aggiornamento dinamico del ritardo solo con provider autorizzato. |
| P0 | **Sveglie ricorrenti per tratta**: giorni della settimana, fascia oraria e eccezioni | Alta | Nessuna per la pianificazione locale | Adottare subito. |
| P0 | **Monitoraggio stazione salvato**: preferiti, arrivi/partenze, filtro partenza/arrivo e scorciatoia widget | Alta | Struttura locale subito; contenuto reale richiede provider | Adottare la struttura e dichiarare lo stato non-live. |
| P1 | **Stato di viaggio personale**: viaggio attivo, prossima fermata, azioni “scendi qui” e riprendi ricerca | Alta | Nessuna per stato locale; live per aggiornamenti automatici | Adottare subito in forma locale. |
| P1 | **Esportazione/importazione locale** di tratte, preferiti, monitoraggi e impostazioni | Media-alta | Nessuna, con scelta file esplicita | Adottare subito, preferibilmente con file JSON validato e senza cloud implicito. |
| P1 | **Widget configurabili** per una stazione o un treno monitorato | Alta | Snapshot locale subito; live per dato utile | Adottare dopo il monitoraggio stazione. |
| P1 | **Preferenze di accessibilità del viaggio**: riduzione cambi, margine interscambio, percorsi senza barriere e carattere ad alta densità | Alta | Persistenza locale subito; algoritmo e dati stazione per risultati reali | Adottare modello preferenze e UI; filtraggio reale dopo provider. |
| P2 | **Storico puntualità e affidabilità** per treno/tratta | Media | Serie storica autorizzata e conservazione dati | Rimandare al provider autorizzato. |
| P2 | **Infomobilità, scioperi, lavori, binari variati, ripianificazione** | Alta | Feed operativo autorizzato, tempi di aggiornamento e responsabilità informativa | Bloccato fino a fonte autorizzata. |
| P2 | **Geofence alla fermata** | Media | Posizione continua o in background, consenso più invasivo e test batteria | Opzionale; non prioritario rispetto alla sveglia configurabile. |
| Fuori perimetro | **Biglietteria, QR, abbonamenti, carnet, loyalty, pagamenti, Phone Pass** | Basso per il prodotto personale di consultazione | Contratti, pagamenti, account e compliance commerciale | Non adottare ora. |

## Cosa è già stato chiuso rispetto alla matrice v0.9

Le voci precedentemente indicate come parziali per data/ora, modalità Treno/Stazione, monitoraggio treno, cronologia recente, qualità del dato, widget e dettaglio corsa sono state implementate nelle release successive. Anche il gap “sveglia alla fermata” è stato chiuso in forma iniziale nella v0.14.0; resta la sua configurabilità.

> **Decisione di prodotto:** non simulare ritardi, perturbazioni, binari o arrivi in stazione. Le funzionalità che dipendono da tali segnali devono restare esplicitamente non-live finché un provider è documentato e autorizzato.

Treniamo.it conferma inoltre il vincolo: il servizio dichiara esplicitamente l’uso di fonti non ufficiali, precisione non garantita e una posizione treno stimata per interpolazione tra fermate. Binario può adottare il pattern di prodotto — mappa del percorso, statistiche e notizie — solo dietro dati autorizzati e con la relativa qualità/freshness esposta all’utente. [5]

## Roadmap proposta

| Ordine | Incremento | Obiettivo misurabile |
|---|---|---|
| 1 | v0.15 — Avvisi pendolare | Anticipo configurabile, ricorrenze, annullamento, quiet hours e pagina riepilogo avvisi locali. |
| 2 | v0.16 — Stazioni e viaggio attivo | Monitoraggio stazione salvato, stato viaggio locale, prossima fermata e widget configurabile per superficie. |
| 3 | v0.17 — Portabilità e accessibilità | Esporta/importa dati personali, preferenze di mobilità/accessibilità e reset privacy controllato. |
| 4 | v1.0 — Provider autorizzato | Adapter con provenienza, freshness, TTL e gestione errore per dati Trenitalia/regionale autorizzati. |
| 5 | Dopo v1.0 | Avvisi di circolazione, storico affidabilità, ripianificazione e geofence opzionale. |

## Riferimenti

[1] [On The Rails — Google Play](https://play.google.com/store/apps/details?id=it.pixeldev.ontherails&hl=it)

[2] [App Trenitalia — pagina ufficiale](https://www.trenitalia.com/it/informazioni/app-trenitalia.html)

[3] [App Trenord — pagina ufficiale](https://www.trenord.it/biglietti/dove-acquistare/app-trenord/)

[4] [Île-de-France Mobilités — applicazione ufficiale](https://www.iledefrance-mobilites.fr/application)

[5] [Treniamo.it — treni in tempo reale e note legali](https://treniamo.it/it) · [stato treno](https://treniamo.it/it/treno) · [disclaimer](https://treniamo.it/it/legal)
