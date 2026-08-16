# Changelog

## 0.20.1 — 16 agosto 2026

Corretto il flusso bloccante del pianificatore. I campi **Partenza** e **Arrivo** tengono ora separatamente testo e identità della stazione selezionata; mentre l’utente digita, mostrano subito una lista di scelte cliccabili sotto il rispettivo campo, con nome e codice ferroviario. Il catalogo espone inoltre uno stato esplicito di caricamento o indisponibilità invece di far apparire il solo fallback di ricerca libera.

Lo scambio di stazioni trasferisce ora in modo atomico sia i testi sia le rispettive identità canoniche. La ricerca normalizza gli estremi, resetta la direzione su **andata** e costruisce un identificativo ordinato `origine → destinazione`, impedendo che una tratta richiesta venga presentata al contrario. I test includono il suggerimento `Appiano` → `Appiano Proba Petronia` e i due ordini distinti `Appiano → Roma Ostiense` e `Roma Ostiense → Appiano`.

## 0.20.0 — 16 agosto 2026

Il database **`stazioni_ferroviarie_curato_v4`** fornito dall’utente sostituisce integralmente il precedente catalogo geografico: le 5.239 stazioni curate sono ora l’unica lista canonica per autocompletamento, ricerca, tratte non salvate, preferiti, widget e ordinamento per vicinanza. Ogni record conserva identificativo stabile, codice ferroviario, alias e coordinate quando disponibili; nessun catalogo GTFS o dataset OSM concorre più ai suggerimenti della UI.

Il catalogo include 3.430 codici ferroviari e 3.086 identificativi Trenitalia univoci. Il modello `Station` conserva questi campi anche nelle configurazioni widget. ViaggiaTreno mantiene la query pubblica per **nome stazione**, perché il probe ha verificato che il relativo form non accetta il `trenitalia_location_id`; il codice univoco viene comunque usato come identità cache e per riconciliare in sicurezza nome, alias e snapshot. Quaranta location ID ambigui sono esclusi dal mapping automatico: nessun ritardo viene attribuito su una chiave non univoca.

## 0.19.0 — 15 agosto 2026

L’autocompletamento include ora un catalogo geografico offline nazionale di **3.749** stazioni e fermate ferroviarie italiane, derivato da una singola interrogazione read-only OpenStreetMap con filtro `railway=station|halt`, esclusione di elementi dismessi, abbandonati o merci e attribuzione ODbL mantenuta. Il caricamento avviene in background e non rallenta la digitazione; Roma Termini, Roma Tiburtina, Roma Trastevere, Fiumicino Aeroporto, Viterbo Porta Fiorentina, Cassino e Latina sono coperti dal test dell’asset.

Questo catalogo è deliberatamente **solo di scoperta**: suggerisce nomi di stazioni ma non abilita un provider, non modifica la catena realtime → GTFS programmato → indisponibilità e non conferma servizio, orario, binario, ritardo, soppressione o circolazione. L’attribuzione `© OpenStreetMap contributors, ODbL 1.0` è disponibile nelle Impostazioni, nel manifest dell’asset e nella documentazione della fonte.

## 0.18.0 — 15 agosto 2026

Eliminate le preselezioni Roma–Torino, il catalogo seed visualizzato all’avvio e l’ultimo datasource dimostrativo compilato. L’app parte senza una tratta attiva: ogni ricerca può usare qualunque nome digitato, un suggerimento GTFS oppure una stazione personale già scelta, senza richiedere un salvataggio preventivo. Le vecchie tratte personali restano leggibili esclusivamente per migrazione locale e non tornano nel catalogo iniziale.

Ogni widget conserva ora il proprio target e il proprio snapshot. La configurazione consente di digitare liberamente i due estremi di una tratta, una stazione o un numero treno; tratte e stazioni salvate sono soltanto scorciatoie facoltative. Il refresh legge la tratta persistita nella singola istanza e non la tratta attiva dell’app; paginazione, direzione, errore e snapshot sono isolati per widget. Se non è disponibile una fonte qualificata, il widget espone l’indisponibilità senza costruire orari, ritardi, binari o soppressioni artificiali.

## 0.17.2 — 15 agosto 2026

Corretto il catalogo UI limitato alle stazioni di test. Il pianificatore interroga ora in background un indice lazy dei `stops.txt` inclusi nei data pack GTFS autorizzati, con normalizzazione di accenti, punteggiatura e spazi. Le stazioni selezionate fuori dal catalogo iniziale vengono salvate solo in locale, così tratte, preferiti e monitoraggi restano ripristinabili. Quando un nome non è ancora nel catalogo disponibile, l’utente può inviarlo alle fonti abilitate come ricerca libera: il risultato è un tabellone/orario di fonte, un GTFS programmato oppure un’indisponibilità motivata; non viene creato alcun orario fittizio. Aggiunti test sia su fixture normalizzate sia sul pack Lombardia incluso, che trova Brescia.

## 0.17.1 — 15 agosto 2026

Corretto il percorso che poteva lasciare in vista dati dimostrativi ereditati. Il repository di produzione non usa più `DemoTrainDataSource` come fallback implicito: prova gli adapter realtime abilitati, quindi gli orari GTFS programmati, e se nessuna fonte restituisce una tratta utile mostra un errore esplicito con azione di aggiornamento. Il widget elimina gli snapshot demo salvati da versioni precedenti e, in caso di errore provider, mostra il motivo dell’indisponibilità invece di conservare righe fittizie. Aggiunti test per il fallback di produzione e per la migrazione della cache widget.

## 0.17.0 — 15 agosto 2026

Riallineata l’app alla baseline dati `feature/data-foundations`. Il graph unico usa tabelloni best-effort Italo, ViaggiaTreno e RFI, avvisi RSS RFI, alert/posizioni STA Alto Adige, feed ufficiali SNCF GTFS-Realtime e dodici data pack GTFS embedded come fallback programmato. App, worker e widget condividono `BinarioDataGraph`. Il widget conserva e mostra provider, confidenza, freshness, punteggio di qualità e warning; gli stati gialli sono limitati a variazioni contenute in snapshot operativi qualificabili. Un orario GTFS statico resta sempre `PROGRAMMATO` e non viene mai presentato come ritardo, binario, soppressione o posizione reale.

## 0.16.3 — 14 agosto 2026

Il widget può ora ricevere un **nome personalizzato per istanza**, mostrato in testata al posto del nome della tratta; il nome è locale, opzionale e retrocompatibile con le superfici già create. La configurazione è una superficie Material completa: testo, sfondo e pulsanti ereditano il tema persistito, mentre riepilogo e azioni seguono le categorie in un unico flusso scorrevole e visivamente centrato. Il formato più piccolo riusa il layout RemoteViews standard e stabile, limita il tabellone a due righe complete e nasconde soltanto testata colonne e comandi non essenziali: non impiega più il resource compatto che causava il caricamento fallito nel launcher.

## 0.16.2 — 14 agosto 2026

Correzione definitiva del flusso widget su dispositivo: la configurazione rispetta gli inset di status bar e navigation bar, applica il colore di superficie del tema persistito anche fuori dai componenti Material e sincronizza il contrasto delle icone di sistema. Il layout compatto include ora tutte le `TextView` richieste dalle azioni `RemoteViews`; elimina quindi il caricamento fallito causato da riferimenti a viste non compatibili durante il resize.

## 0.16.1 — 14 agosto 2026

Corretto il flusso di configurazione widget: ora applica il tema persistito dell’app, resta interamente scorrevole e presenta **Tratta**, **Stazione** e **Treno** una sotto l’altra, con spiegazione del comportamento e selettore verticale sotto la categoria scelta. Il widget compatto usa un layout RemoteViews dedicato con righe a due livelli e metadati affiancati: nessuna colonna viene compressa o tagliata; le informazioni disponibili vengono riorganizzate con priorità leggibile.

## 0.16.0 — 14 agosto 2026

Il widget Android diventa un tabellone Binario **configurabile per istanza**: al momento dell’aggiunta si può scegliere una tratta, una stazione specifica oppure un treno specifico, senza influire sugli altri widget. Il layout risponde al ridimensionamento del launcher con tre densità: compatta, standard ed espansa. Il tabellone mantiene una gerarchia originale ad alto contrasto con testata verde, orari a larghezza fissa, righe separate e stati allineati. Il giallo compare esclusivamente per un ritardo o una soppressione presenti in uno snapshot non dimostrativo e disponibile; il catalogo locale continua a dichiarare dati non disponibili in tempo reale e a etichettare le variazioni simulate come demo.

## 0.15.0 — 14 agosto 2026

Questa release concentra tutte le funzioni benchmark realizzabili senza dati live. **Avvisi pendolare** offre anticipo da 5 a 60 minuti, una volta/feriali/quotidiano, quiet hours, riepilogo e annullamento locale. Le stazioni possono essere monitorate come arrivi, partenze o entrambi; un dettaglio treno può avviare un **viaggio attivo** con prossima fermata avanzata manualmente. Il widget può mostrare la tratta, il viaggio attivo o una stazione monitorata, dichiarando sempre lo stato locale/non-live quando necessario. Impostazioni offre preferenze per cambi, margine di interscambio, percorsi senza barriere e tabellone compatto, oltre a esportazione/importazione JSON scelta esplicitamente dall’utente. Il confine per dati live resta fail-closed: nessun endpoint, segreto o richiesta non autorizzata nel client.

## 0.14.0 — 14 agosto 2026

Il widget è stato ridisegnato come tabellone ferroviario originale a contrasto elevato: cinque righe per pagina, ora, destinazione, servizio, binario e stato separati, con comandi squadrati per pagina, direzione e refresh. Ogni fermata futura nel dettaglio corsa può impostare una sveglia locale cinque minuti prima dell’orario previsto, previa sola autorizzazione alle notifiche. I dettagli treno si condividono ora con il foglio di sistema Android attraverso un contratto testuale riutilizzabile da un futuro iOS. Le sezioni Treni e Stazioni di Consulta sono due schede squadrate e descrittive.

## 0.13.0 — 14 agosto 2026

La dettatura è ora dentro la scheda di ricerca attiva: tratta, numero treno o stazione, con prompt e messaggi contestuali. Il pianificatore include il filtro **Qualsiasi**, **Diretto**, **Max 1 cambio** e **Max 2 cambi**; nel catalogo dimostrativo il numero di cambi è esplicitamente indicativo. Le precedenti tab Treni e Stazioni confluiscono in **Consulta**, con sezioni interne accessibili. **Altro** diventa **Impostazioni**, organizzata in Aspetto, Viaggi personali, Integrazioni, monitoraggi e Privacy e dati.

## 0.12.0 — 13 agosto 2026

Il pianificatore usa ora selettori nativi accessibili per data e ora. Le stazioni possono essere salvate tra i preferiti solo sul dispositivo; la sezione Stazioni può ordinarne fino a otto per vicinanza dopo una richiesta esplicita di posizione approssimata, senza salvataggio o uso in background. Il nuovo comando **Detta viaggio** usa il riconoscitore vocale di sistema e interpreta localmente partenza, arrivo, stazione, numero treno, oggi/domani, data espressa, ora e andata/ritorno. Nessun audio, testo dettato o posizione viene inviato a Binario o Voice Bridge.

## 0.11.0 — 13 agosto 2026

Ricostruito il companion nativo Apple Watch per watchOS 10 con SwiftUI, cache-first, Widget/complication, App Intent e test sorgenti pronti per Xcode. Il Voice Bridge ora conserva un profilo personale remoto minimo, autenticato, versionato, idempotente e con controllo di concorrenza; nessun dato live, token o segreto entra nel payload. Android prepara il body contrattuale senza effettuare richieste di rete e supporta deep link `binario://` validati per tratta, prossime corse e ricerca treno.

## 0.10.0 — 13 agosto 2026

L’app principale diventa il centro di controllo delle integrazioni. In Altro è disponibile il pannello Smartwatch Android, Comandi vocali e Alexa, Apple Watch: gestisce preferenze locali e stato senza memorizzare credenziali. Il profilo unificato versionato raccoglie tratta attiva, direzione, numeri treno monitorati e flag di superficie; Wear OS riceve snapshot solo quando abilitato. Il Voice Bridge viene documentato come una superficie Binario, con sincronizzazione autenticata prevista e mai con segreti nel client.

## 0.9.0 — 13 agosto 2026

Primo blocco funzionale dalla matrice benchmark: il pianificatore offre ora le modalità Viaggio, Treno e Stazione; le ricerche di tratta riuscite restano tra le recenti locali; ogni dettaglio treno può essere aggiunto o rimosso dai monitoraggi personali. La sezione Altro consente di gestire i treni monitorati. Gli avvisi reali restano disabilitati finché non esiste un feed autorizzato.

## 0.8.3 — 13 agosto 2026

La home torna a essere un pianificatore di viaggio: campi partenza/arrivo, inversione, sola andata o andata e ritorno, data, ora, filtri e ricerca dominante. Recenti e preferite sono scorciatoie sotto al pianificatore. Le soluzioni sono ora una schermata separata; il tabellone resta nella sezione Treni e il widget mantiene cinque prossime corse per pagina.

## 0.8.2 — 13 agosto 2026

Home e widget adottano un modello comune a tabellone: comandi rapidi di consultazione, intestazione tratta/direzione/refresh, colonne ora-destinazione/treno-binario-stato e righe ferroviarie dense. Il widget conserva cinque corse per pagina, pagina, direzione e aggiornamento, ma ora presenta campi allineati invece di testo multilinea.

## 0.8.1 — 13 agosto 2026

La home diventa una console operativa ferroviaria. All’avvio mostra azioni rapide per treno, tratta e stazione; la tratta attiva con refresh/direzione; la prossima corsa e le successive; infine le tratte monitorate e lo stato dati. Il planner completo rimane nella scheda Cerca e le preferenze visive v0.8 restano invariate.

## 0.8.0 — 13 agosto 2026

Questa release sostituisce il precedente linguaggio visivo con una home di pianificazione più diretta: il planner tratta è una superficie compatta, con partenza, arrivo, inversione e CTA di ricerca; i risultati e le scorciatoie personali seguono il planner, senza mescolarsi al suo flusso.

| Area | Aggiornamento |
|---|---|
| Tema | Nuovo selettore persistente `Sistema`, `Chiaro`, `Scuro`, disponibile dalla barra superiore e mantenuto soltanto sul dispositivo. |
| Palette | Verde ferroviario, antracite, superfici neutre, azzurro percorso e colori di segnale; widget coerente. |
| Home | Titolo Binario, pianificazione come azione primaria, stazioni in card, inversione visiva e CTA esplicita. |
| Qualità | Aggiunti test di parsing della preferenza tema; nessun account, tracking o nuova raccolta dati. |

## 0.7.3 — 12 agosto 2026

Aggiornamento della base dimostrativa e dell’identità visiva. La tratta iniziale è ora **Roma Termini – Torino Porta Nuova**; eventuali installazioni con la sola tratta predefinita Roma–Napoli vengono migrate automaticamente. La lista delle corse usa un ordinamento neutrale per orario previsto e numero treno: non esiste più la preferenza Regionale Lazio.

La palette è stata ridisegnata in modo originale verso un linguaggio ferroviario più sobrio: **petrolio**, **avorio carta**, **verde segnale** e **rame**. App chiara, dark mode e widget mantengono superfici e testi ad alto contrasto.

| Area | Aggiornamento |
|---|---|
| Dati demo | Tratta iniziale Roma–Torino, con migrazione dell’ID predefinito Roma–Napoli. |
| Ordinamento | Corse per orario previsto, poi numero treno, in app, widget e Wear OS. |
| Preferenze | Rimossi controllo, persistenza e messaggi relativi alla priorità Regionale Lazio. |
| Palette | Nuovi token cromatici petrolio/avorio/segnale/rame e widget coerente. |

## 0.5.0 — 12 agosto 2026

Questa release inaugura la schermata personale di **partenze rapide** e incorpora i primi miglioramenti selezionati dalla matrice di benchmark multi-app, senza aggiungere fonti live non autorizzate, account o permessi.

| Area | Novità |
|---|---|
| Partenze rapide | La schermata corse mostra una sintesi delle due corse più utili della tratta attiva, con operatore, categoria, numero, destinazione, orario previsto, stato e binario. Ogni riga apre il dettaglio e ha semantica TalkBack completa. |
| Tratte fissate | Le tratte personali possono essere fissate o rimosse dai preferiti; le fissate precedono le altre e lo stato è persistente in locale. Il decoder resta compatibile con le tratte v0.4. |
| Ordinamento storico | La precedente priorità Lazio è stata sostituita in v0.7.3 da un ordinamento neutrale per orario previsto e numero treno. |
| Trasparenza demo | Quando è attivo il catalogo locale, la UI dichiara esplicitamente “catalogo dimostrativo locale — non live” e non lo presenta come aggiornamento operativo. |
| Test | Aggiunti tre test unitari per l’ordinamento su tratta laziale, tratta non laziale e preferenza disattivata. |
| Riproducibilità | Aggiunti `settings.gradle.kts`, `gradle.properties` e Gradle Wrapper 8.14.5 al progetto consegnato. |

> La build 0.5.0 mantiene un catalogo dimostrativo locale multi-operatore: non contiene dati ferroviari reali. Ritardi, binari, soppressioni, tabelloni e avvisi live richiedono un provider autorizzato.

## 0.4.0 — 12 agosto 2026

Questa release rende esplicita la vocazione nazionale di Binario e aggiunge una preferenza personale per le tratte Trenitalia Regionale del Lazio, senza sottrarre visibilità agli altri operatori.

| Area | Novità |
|---|---|
| Operatori | Modelli normalizzati per Trenitalia Regionale, Trenitalia lunga percorrenza e Frecce, Italo, Trenord e altri operatori. |
| Corse | Operatore e categoria sono mostrati in elenco, dettaglio, semantica TalkBack e widget; è disponibile il filtro per operatore e la ricerca per numero. |
| Lazio/Roma | Le tratte che toccano il Lazio possono privilegiare visivamente Trenitalia Regionale; la preferenza è persistente e disattivabile. La ricerca stazioni include nodi ferroviari Roma/Lazio e città nazionali. |
| Qualità del dato | Gli snapshot portano fonte, confidence, istante di osservazione/ricezione, scadenza e stati `FRESH`, `AGING`, `STALE` e `UNAVAILABLE`. |
| Provider | Aggiunti repository multi-provider, confine per gateway autorizzato e adapter Trenitalia riservato a un canale formalmente autorizzato. Il client non interpella endpoint operativi non documentati. |
| Widget | Il widget mantiene cinque corse per pagina e ora mostra operatore, categoria, destinazione e stato in due righe accessibili. |
| Sicurezza | Nessun segreto nel client; le fonti live sono sostituibili tramite gateway autorizzato. |

> La build 0.4.0 usa ancora un catalogo dimostrativo locale multi-operatore: non contiene dati ferroviari reali. Ritardi, binari, soppressioni e orari live richiedono un provider autorizzato configurato lato server o nel contesto personale dell’utente.

## 0.3.0 — 12 agosto 2026

Questa release trasforma il prototipo iniziale in una base personale completa per il monitoraggio di tratte.

| Area | Novità |
|---|---|
| Tratte | Salvataggio locale di relazioni personali, selezione e commutazione andata/ritorno. |
| Corse | Elenco completo delle prossime corse, refresh manuale, stati di freschezza e dettaglio fermate. |
| Widget | Widget nativo con 5 corse per pagina, pagina precedente/successiva, direzione e refresh. |
| Accessibilità | Tema chiaro/scuro, contrasto elevato, testi espliciti, semantica, target tattili e font scalabile. |
| Sicurezza | HTTPS obbligatorio, cleartext disabilitato, backup esclusi, componenti non esportati e PendingIntent immutabili. |
| Architettura | Provider isolato, snapshot cache, contratti notifiche e core predisposto a condivisione futura. |
| Toolchain | `compileSdk`/`targetSdk` 36, AGP 8.13.0, Gradle 8.14.5, JDK 17. |

> I dati della 0.3.0 restano dimostrativi locali. Un provider autorizzato deve sostituire `DemoTrainDataSource` prima dell’uso ferroviario reale o della distribuzione.
