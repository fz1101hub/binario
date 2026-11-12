# Binario Companion Expo (congelato)

> **Congelato il 12 novembre 2026.** Questa app Expo/React Native non è distribuita e non riceve nuove funzioni né aggiornamenti di dipendenze. Decisione del titolare: «Importa tutto quello che puoi importare e poi congela». Le funzioni utili sono ora nell'app Android (`app/`) e nell'app iPhone (`ios/`); il codice di questa cartella resta com'è, come riferimento.

## Che cosa significa

- **Nessuna nuova funzione.** Agenda, dettatura e stazione più vicina si sviluppano soltanto in `app/` e `ios/`.
- **Nessun aggiornamento di dipendenze.** `.github/dependabot.yml` non ha una voce per `companion-expo/`; `package.json`, `pnpm-lock.yaml` e il lock Gradle del Companion restano fermi (versione `0.1.1`, identità `im.binario.companion`).
- **Non distribuita.** Nessuna scheda negli store la pubblica (`docs/store/` non la cita); il [README](../README.md) principale e [PRIVACY.md](../PRIVACY.md) la indicano come congelata.
- **CI solo per una modifica qui o a mano.** `Companion CI` (`.github/workflows/companion-ci.yml`) parte soltanto se cambiano `companion-expo/**` o il workflow stesso, oppure con un avvio manuale; i contratti e gli strumenti condivisi non la fanno partire (`tools/verify_ci_pinning.py` lo controlla). I gate di riproducibilità e degli App Bundle restano, così una modifica al codice congelato è ancora verificata per intero.
- **Restano perché altro li usa:**
  - `contracts/wear-snapshot/v1/companion-to-wear-v1.json`: lo leggono i test Wear dell'app principale (`WearSnapshotCodecTest`, `WearReliabilityTest`) e `tests/companion-wear-contract.test.ts` di questa cartella;
  - i profili `companion` di `tools/verify_android_app_bundles.py` e `tools/verify_gradle_dependency_metadata.py`, usati da `Companion CI`;
  - la sezione «Companion Expo» di `PRIVACY.md`, che `tools/verify_privacy_policy.py` richiede e controlla insieme ai testi di `app/(tabs)/settings.tsx` e `lib/i18n.ts`;
  - `assets/brand/binario-companion-icon.png`, usato da questa app; le icone degli store si generano dalla sua copia identica `store/source/binario-icon.png`.
- **Dati fermi.** `assets/data/stazioni-canoniche-v2.json` non segue il catalogo principale (`app/src/main/assets/stations/curated_it_rail_stations.json`): le correzioni del 12 novembre 2026 a dodici voci non sono riportate qui, che conserva i vecchi nomi di S00213, S02049 e NC001521 e le vecchie posizioni di S12075, S00822, S09022 e NC001070 (gli identificativi di località corretti non sono in questo file).
- **Rimuovere la cartella** è una decisione del titolare. Con essa andrebbero tolti `companion-ci.yml`, i profili `companion` dei due strumenti e la sezione di `PRIVACY.md` insieme ai controlli di `tools/verify_privacy_policy.py` e `tests/test_verify_privacy_policy.py`; il contratto Wear resta.

## Dove sono ora le funzioni

I file JSON e ICS dell'Agenda hanno lo stesso formato su Android e iPhone: un telefono legge i file dell'altro.

| Funzione del Companion | Android (`app/`) | iPhone (`ios/`) |
|---|---|---|
| Dettatura di tratta, giorno e ora, in 17 lingue | Riconoscitore vocale di sistema nel pianificatore e, per ogni tratta, nell'editor dell'Agenda | Campo «Scrivi o detta la tratta» con la dettatura della tastiera, nel pianificatore e nell'editor dell'Agenda; nessun permesso del microfono |
| Stazione più vicina come partenza | Pianificatore e partenza dell'editor dell'Agenda | Pianificatore, editor dell'Agenda e «Partenze»; una sola lettura della posizione, mai salvata |
| Agenda: fino a 40 viaggi, duplicati rifiutati, sola lettura se l'archivio non è leggibile | Agenda in Per te | Agenda, pagina della scheda «Tratte» |
| Prossimi e archivio, ricerca, filtro per mese | Sì | Sì |
| Checklist con preset (pendolare, coincidenza, bici, assistenza), «Ritorno», «Ripianifica», avviso di partenza già passata | Sì | Sì |
| Itinerari fino a 4 tratte, ripetizione settimanale (fino a 12 viaggi) | Sì | Sì |
| Modelli di viaggio (fino a 12), anticipo predefinito del promemoria | Sì | Sì |
| «Pronto per partire» con conto alla rovescia | Scheda in Per te | In cima all'Agenda |
| Importazione guidata da testo, con anteprima e conferma | Testo incollato o ricevuto da «Condividi» | Testo incollato |
| Promemoria locali | Sveglie esatte, ripristinate dopo il riavvio | Notifiche locali, nessun push |
| Calendario e trasferimento | Esporta l'Agenda in JSON e ICS, «Aggiungi al calendario» per un viaggio; importa JSON e, dal file ICS di un'altra app, i viaggi «A → B» | Esporta JSON e ICS (l'Agenda o un viaggio); importa JSON e, dal file ICS di un'altra app, i viaggi «A → B» |
| «Cancella archivio locale» | «Svuota l'Agenda» | «Svuota l'Agenda» |
| Stazioni preferite nel modulo | Preferiti nella partenza dell'editor dell'Agenda | No: l'iPhone non ha stazioni preferite |
| Collegamento rapido «Agenda» | Scorciatoia del launcher | Azione rapida dell'icona |

Dove il Companion e le app principali erano diversi vale la regola delle app principali (per esempio: anticipo predefinito «Nessuno», i preset aggiungono righe senza sostituirle, «oggi» è la data italiana). I dettagli sono nel [CHANGELOG](../CHANGELOG.md) e nelle sezioni Android e iPhone di [PRIVACY.md](../PRIVACY.md).

## Non portate, di proposito

| Funzione del Companion | Perché |
|---|---|
| Stazioni recenti | Mai compilate: le registrava solo il tabellone locale, che nessuna schermata usa |
| Tabellone locale «nessun dato live» | Codice mai importato da una schermata |
| Promemoria «all'ora della partenza» (0 minuti) | Cambia il formato dell'Agenda: una versione precedente rifiuterebbe l'intero archivio. Da decidere insieme per Android e iPhone |
| Statistiche e destinazione più frequente | Poco utili; la destinazione più frequente descrive le abitudini dell'utente |
| Fissare in alto e ordinare a mano | L'Agenda resta in ordine cronologico |
| Elenco dei prossimi tre viaggi sotto «Pronto per partire» | L'Agenda è subito sotto |
| Filtro «Settimana» e filtro per preparazione | Facoltativi; le app principali non salvano un'etichetta di preparazione |
| Importazione dell'archivio del Companion (`binario-tratte.json`) | Il Companion non è stato distribuito |
| Migrazione dei vecchi promemoria | Riguarda solo la storia interna del Companion |
| Tema, alto contrasto, «Riduci movimento» e «Modalità semplice» nell'app | Android ha già il tema; per contrasto e movimento valgono le impostazioni del sistema |
| Guida degli stati dei dati, JSON diagnostico, vibrazione di conferma | Poco valore; gli stati sono scritti accanto ai dati |
| Scheda «Companion» (stato di widget, Wear e Alexa) | Su Android c'è già Per te › Integrazioni |
| Widget e Tile Wear con il prossimo viaggio pianificato | Decisione del titolare, per ora no: widget e Wear mostrano la tratta attiva |
| Domanda vocale e checklist dall'orologio | La risposta vocale dava solo il titolo salvato; la checklist non era raggiungibile |
| Testo ricevuto da «Condividi» sull'iPhone | Servirebbe una nuova estensione con un App Group |
| Plugin, script, patch e shim di Expo | Servono solo alla build Expo |
| Protocollo di test TalkBack (`docs/PROTOCOLLO_TEST_TALKBACK_ANDROID.md`) | Facoltativo; non riscritto per l'app principale |
