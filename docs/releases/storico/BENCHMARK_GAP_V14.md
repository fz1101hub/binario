# Binario — revisione benchmark e gap v0.14.0

**Data:** 14 agosto 2026  
**Perimetro:** Orario Treni, On The Rails, ReteBK, Trenìt!, InfoTreni, Treninoo, Trainline, Trenitalia, TrenScan, Omio, Italo, Trenord, Île-de-France Mobilités e Treniamo.it.

## Esito sintetico

Con la v0.15.0 Binario copre anche il nucleo personale avanzato emerso dal benchmark: avvisi pendolare configurabili e ricorrenti, monitoraggio stazione locale, viaggio attivo, superfici widget personali, memoria portabile e preferenze di mobilità/accessibilità. Il gap residuo prioritario è esclusivamente l’**informazione operativa basata su dati live autorizzati**.

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
| Implementato v0.15 | **Sveglia configurabile**: anticipo 5–60 minuti, annullamento, riepilogo e quiet hours | Alta | Locale | Aggiornamento dinamico del ritardo solo con provider autorizzato. |
| Implementato v0.15 | **Sveglie ricorrenti per tratta**: una volta, feriali o ogni giorno | Alta | Locale | Eccezioni legate al servizio reale solo con provider. |
| Implementato v0.15 | **Monitoraggio stazione salvato** e superficie widget | Alta | Struttura locale | Arrivi/partenze reali restano bloccati dal provider. |
| Implementato v0.15 | **Stato di viaggio personale**: viaggio attivo e prossima fermata avanzata manualmente | Alta | Locale | Aggiornamento automatico solo con provider. |
| Implementato v0.15 | **Esportazione/importazione locale** di tratte, preferiti, monitoraggi e impostazioni | Media-alta | File scelto dall’utente | JSON versionato e validato, nessun cloud implicito. |
| Implementato v0.15 | **Widget configurabili** per tratta, viaggio o stazione | Alta | Snapshot locale | Le superfici personali dichiarano lo stato non-live. |
| Implementato v0.15 | **Preferenze di accessibilità del viaggio**: cambi, margine, senza barriere e tabellone compatto | Alta | Locale | I filtri su accessibilità reale richiedono dati stazione affidabili. |
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
| 1 | v0.15 — Incremento personale unificato | Implementato: avvisi, stazioni, viaggio attivo, widget, portabilità e accessibilità. |
| 2 | v1.0 — Provider autorizzato | Adapter con provenienza, freshness, TTL e gestione errore per dati Trenitalia/regionale autorizzati. |
| 3 | Dopo v1.0 | Avvisi di circolazione, storico affidabilità, ripianificazione e geofence opzionale. |

## Riferimenti

[1] [On The Rails — Google Play](https://play.google.com/store/apps/details?id=it.pixeldev.ontherails&hl=it)

[2] [App Trenitalia — pagina ufficiale](https://www.trenitalia.com/it/informazioni/app-trenitalia.html)

[3] [App Trenord — pagina ufficiale](https://www.trenord.it/biglietti/dove-acquistare/app-trenord/)

[4] [Île-de-France Mobilités — applicazione ufficiale](https://www.iledefrance-mobilites.fr/application)

[5] [Treniamo.it — treni in tempo reale e note legali](https://treniamo.it/it) · [stato treno](https://treniamo.it/it/treno) · [disclaimer](https://treniamo.it/it/legal)
