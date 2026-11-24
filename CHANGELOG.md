# Changelog

## In sviluppo — iPhone: «Per te», CO₂, condivisione, orari salvati, puntualità personale, mappe, collegamenti diretti e impostazioni come su Android

- **Confronto completo Android ↔ iPhone e Wear OS ↔ Apple Watch** in [docs/PARITA_ANDROID_IPHONE.md](docs/PARITA_ANDROID_IPHONE.md): una riga per funzione, con lo stato e l'area di lavoro; in fondo ciò che resta.
- **Navigazione come su Android:** tre schede, **Pianifica**, **Partenze** e **Per te**; Per te raccoglie le tratte salvate, l'Agenda, **Impostazioni**, **Orari salvati**, **Stazioni preferite** e **I miei treni**, dove ogni treno si può rinominare.
- **Stima CO₂** in ogni riga dei risultati e nel dettaglio del treno («CO₂ stimata: X kg (auto ~Y kg, aereo ~Z kg)»), calcolata sull'iPhone con i fattori EEA; crediti in Impostazioni › Informazioni.
- **Condivisione:** il treno (stesso testo di Android), una fermata del treno (tieni premuta la fermata) e l'intero elenco dei risultati, dal foglio di condivisione di iOS.
- **Orari salvati:** «Salva orario» sotto i risultati; in Per te l'elenco in sola lettura, segnato «Salvato il … · non aggiornato», da rinominare, condividere ed eliminare.
- **Puntualità personale** nel dettaglio del treno: ritardo medio, quota in orario e oltre 15 minuti, fermata dove il ritardo cresce di più, distribuzione e storico per data, dalle osservazioni registrate sull'iPhone quando l'app legge lo stato del treno (al più 365 giorni).
- **Partenze:** stella delle stazioni preferite, **Tipi di treno** per stazione con il conteggio dei treni nascosti e «Mostra tutti», **Stazioni più vicine** da una sola posizione su tocco, **Assistenza Sala Blu** di RFI.
- **Dettaglio del treno:** «Apri sul sito ufficiale» per Trenitalia e Italo e «Bici a bordo: condizioni» quando l'orario non lo dice.
- **Impostazioni:** tema Sistema/Chiaro/Scuro, lingua dell'app (nelle Impostazioni di iOS), **Biglietti e assistenza**, **Backup locale** nello stesso file JSON di Android, **Suggerisci una stazione**.
- **Base comune:** nuove tabelle `co2-estimate-v1.json`, `official-links-v1.json`, `saved-timetables-v1.json`, `personal-punctuality-v1.json`, `train-type-groups-v1.json` e `local-backup-v1.json`, lette da Android e iPhone; nuove regole con i casi comuni `co2-estimate-v1.json`, `personal-punctuality-v1.json` e `train-type-groups-v1.json`.
- **Pianifica:** modalità **Treno** (le corse di oggi di un numero dalle fonti in tempo reale) e **Stazione**, **Andata e ritorno** con data e ora del ritorno («RITORNO» anche in «Salva orario» e «Condividi»), **Recenti e preferite** sotto il modulo.
- **Partenze:** **Le tue partenze**, il tabellone della tratta attiva con «Direzione» e «Cambia tratta»; **Collegamenti diretti** di una stazione per sette giorni, con «Cerca i treni» e «Cerca le corse»; **Monitora questa stazione**; il binario cambiato «Bin. 7 (era 5)» anche in partenze, risultati e dettaglio.
- **Mappe come su Android, aperte solo su tocco:** **Mappa del percorso** nel dettaglio del treno, **Mappa della stazione** con le sei stazioni vicine e **Mappa delle stazioni vicine**, sulla mappa di Apple (MapKit).
- **Dettaglio del treno:** **Aggiungi al calendario** con l'editor di eventi di iOS, senza permesso del calendario.
- **Per te:** **Recenti**, **Nuova tratta** («Salva e attiva tratta»), tratte da fissare e spostare, **Stazioni monitorate**.
- **Treni preferiti in background:** **Puntualità dei treni preferiti** (attiva di serie, una volta al giorno dopo l'arrivo previsto, con «Dati in tempo reale») e **Riepilogo settimanale dei treni preferiti** (spento di serie, la domenica sera, senza richieste), nello stesso compito serale in background dei promemoria.
- **Azioni rapide e collegamenti:** Tratta attiva, Prossime partenze e Ricerca treno accanto ad Agenda; i collegamenti `binario://` di Android.
- **Impostazioni:** **Integrazioni Binario** con il profilo vocale per Alexa; il backup locale porta ora anche i treni preferiti (con destinazione e operatore, in entrambe le app) e le stazioni monitorate.
- **Base comune:** nuova tabella `favourite-punctuality-v1.json` e casi comuni `favourite-punctuality-v1.json` (registro, andamento, riepilogo e registrazione dei treni preferiti), letti da Android e iPhone.
- **Treno** cerca il numero anche nell'orario incluso (e negli orari completi dei paesi confinanti scaricati), come Android; senza dati in tempo reale restano le corse dell'orario.
- **Affidabilità dei miei treni** come su Android: per i primi otto treni preferiti la rete (con il proprio limite), «Registrato su questo telefono» negli ultimi 30 giorni, «In miglioramento» / «In peggioramento» o «Ancora nessun dato».
- **Salva orario** e **Condividi** prendono l'elenco come è mostrato, viaggi con cambi compresi, con arrivo, «giorno dopo» e durata in ogni riga.
- **Backup locale:** anche avvisi pendolare, preferenze di viaggio, tratte fissate e il loro ordine, nelle chiavi di Android.
- Il confronto Android ↔ iPhone conta ora anche viaggi con cambi, notifiche e sveglie del viaggio, widget, European Sleeper e quadro orario RFI: nell'area di questo lavoro non resta nulla di aperto.
- Testi di Android in 17 lingue. Aggiornati [l'informativa privacy](PRIVACY.md) (nuovi dati locali, file di backup, collegamenti ufficiali, mappe, calendario, lavori in background dei treni preferiti), le note per App Review, il README, l'ADR della base comune e i README dei contratti.

## In sviluppo — iPhone: European Sleeper e quadro orario programmato RFI come su Android

- **Quadro orario programmato RFI sull'iPhone.** Quando gli orari dell'iPhone non hanno treni per una ricerca o per le **Partenze** (per esempio un orario incluso scaduto), l'app legge, come Android, la pagina pubblica delle partenze o degli arrivi di uno degli hub con identificativo verificato su `prm.rfi.it`: stesse righe, fermate, validità e periodicità («Lun-Ven», «Feriale», «Escl. sab. e fest.», festività nazionali e Pasqua). La pagina è chiesta una volta per 30 minuti e letta per 6 ore; al primo rifiuto l'app si ferma.
- **European Sleeper sull'iPhone.** Se anche RFI non ha treni, l'app scarica come Android l'archivio GTFS community di European Sleeper/JBB (`jbb.ghsq.de`, al più 8 MB, valido 6 ore, pausa di 5 minuti dopo un errore), ne tiene solo i treni di European Sleeper, collega le fermate al catalogo con la regola di Android e li mostra in risultati e **Partenze** con l'avviso «Orario da una fonte di copertura non ufficiale: verifica con il vettore prima di partire.» Con **Dati in tempo reale** attivo i Trip Updates dello stesso host si uniscono ai treni per `trip_id` e fermata.
- **Senza codice di terzi.** Lettura degli archivi ZIP (directory centrale, limiti di dimensione, rapporto di compressione e nomi come il builder Python, DEFLATE con il framework Compression di Apple, CRC-32), dell'HTML delle pagine RFI e dei nomi delle fermate (la regola `RailStopNameMatcher` di Android) scritte in BinarioKit; l'archivio diventa un piccolo indice con lo schema di quello incluso, letto dal pianificatore e dalle **Partenze** esistenti.
- **Base comune:** nuove tabelle `contracts/shared-data/v1/remote-gtfs-v1.json` (archivio, limiti, cache e pause) e `rfi-programmed-v1.json` (indirizzo, limiti, hub verificati), la riga dei Trip Updates di European Sleeper in `realtime-feeds-v1.json`; Android le legge al posto delle sue costanti. Nuovi casi comuni `contracts/shared-rules/v1/remote-gtfs-v1.json` e `rfi-programmed-v1.json`, letti dai test Android e iPhone (e, per gli archivi, dal test del builder); l'iPhone legge anche `calendar-v1.json`.
- Testo dell'avviso di Android nelle 17 lingue. Aggiornati [l'informativa privacy verificabile](PRIVACY.md) (sezione *Orari di copertura sull'iPhone*, host `prm.rfi.it` e `jbb.ghsq.de`), la politica delle fonti e la risposta privacy della checklist App Store.

## In sviluppo — iPhone: notifiche e sveglie del viaggio come su Android

- **Viaggio seguito come attività in tempo reale.** Nel dettaglio di un treno con i dati in tempo reale, **Notifica con lo stato del viaggio** mostra sulla schermata di blocco e nella Dynamic Island ciò che Android mette nella notifica in corso: ritardo, prossima fermata e binario, coincidenze, «Scendi tra N fermate» e «Scendi alla prossima fermata», con **Aggiorna** e **Interrompi**, da 30 minuti prima della partenza a 30 dopo l'arrivo. Soppressione, cambio di binario e coincidenza a rischio arrivano anche come notifica con suono (senza suono tra le 22 e le 7). Nuova estensione `BinarioLiveActivity`, separata dai widget della schermata Home; nessuna notifica push.
- **Sveglia alla fermata:** da una fermata futura del treno, notifica locale cinque minuti prima dell'orario previsto, spostata dagli aggiornamenti del viaggio seguito; le sveglie attive sono elencate in Impostazioni.
- **Avvisami quando sta arrivando** per un treno preferito, alla stazione scelta 5, 10 o 15 minuti prima dell'orario previsto ricalcolato con il ritardo, mai tra le 22 e le 7; **la campanella** per i nuovi avvisi ViaggiaTreno dei treni preferiti o seguiti, nel giorno del viaggio, mai tra le 22 e le 7; **avvisi pendolare** (tratta salvata, stazione, orario, anticipo, ripetizione, fascia silenziosa) con lo stato del treno.
- **Stima dalla tua posizione:** con il viaggio seguito, la posizione precisa «mentre usi l'app» proiettata sulla linea fra le fermate dà «Scendi tra N fermate» e la stima del ritardo («circa +6 min, arrivo ~18:47»), sempre come stima accanto al dato ufficiale; nulla viene salvato o inviato e si ferma 10 minuti dopo l'arrivo.
- **Impostazioni › Notifiche e sveglie** con l'interruttore degli avvisi sui treni, le sveglie attive, gli avvisi pendolare e ciò che iOS consente.
- **Cosa iOS non consente come Android:** i controlli girano con l'app aperta e nel solo aggiornamento in background già esistente (quando iOS lo avvia), non a orari garantiti come WorkManager; la posizione non si usa in background (niente «sempre», niente modalità `location`), quindi stima e «Scendi tra N fermate» dalla posizione si fermano quando l'app si chiude; l'attività in tempo reale si avvia solo con l'app aperta e si segue solo un treno con i dati in tempo reale del dettaglio; lo stato negli avvisi pendolare è quello letto all'ultimo controllo prima dell'avviso.
- **Base comune:** nuova tabella `contracts/shared-data/v1/trip-alerts-v1.json` (anticipi, finestre, cadenze, tolleranze, limiti, posizione; le ore di quiete restano in `evening-alerts-v1.json`), letta come codice generato da Android al posto delle sue costanti e dall'iPhone, e nuovi casi comuni in `contracts/shared-rules/v1/trip-alerts-v1.json` per quando scatta ogni avviso, «Scendi tra N fermate» e la stima, letti dai test Android e iPhone.
- Testi di Android nelle 17 lingue, più le poche frasi proprie dell'iPhone. Aggiornati [l'informativa privacy](PRIVACY.md), il testo del permesso di posizione, il listing, le note per App Review e la checklist App Store.

## In sviluppo — I widget della schermata Home anche su iPhone

- **Tre widget come su Android:** **Tratta** (prossime corse di una tratta, pulsante per la direzione, «Mostra il ritorno dalle HH:mm»), **Tabellone stazione** (prossime partenze, «📍 Più vicina» facoltativo) e **I miei treni** (ritardo, binario, soppressione, prossima stazione o ultimo rilevamento). Tre dimensioni, come le forme compatta, standard ed estesa di Android; «Aggiornato alle HH:mm» in ogni widget.
- **Impostazioni di ogni widget** nel foglio di modifica di iOS: aggiornamento automatico spento, in una o due finestre (ogni 15, 30 o 60 minuti; lun–ven, tutti i giorni o giorni scelti, anche oltre la mezzanotte) o sempre ogni 15 minuti; tipi di treno da nascondere; treni «In viaggio» fino all'arrivo; corse mostrate (prossime, 3 ore, 6 ore, tutto il giorno); colonna del binario, nome del treno, titolo; prossima stazione o ultimo rilevamento, solo treni in viaggio, ordinamento. ↻, pagine e direzione sono pulsanti nel widget.
- **Dati:** l'orario incluso, che l'app installa nell'App Group `group.it.binario.ios` per i widget, e con **Dati in tempo reale** attivo le stesse fonti dell'app, solo quando iOS aggiorna un widget; l'aggiornamento automatico è una richiesta a iOS, che decide quando ridisegnare.
- **«📍 Più vicina»:** il widget non legge la posizione; il pulsante apre l'app, che legge una sola posizione come «Stazione più vicina» e salva per il widget soltanto la stazione. Un tocco sul widget apre il pianificatore sulla tratta, **Partenze** sulla stazione o **I miei treni**.
- **Base comune:** la tabella `contracts/shared-data/v1/widgets-v1.json`, letta come codice generato da Android al posto delle costanti dei widget e dall'iPhone, e i casi comuni `contracts/shared-rules/v1/widgets-v1.json`, letti dai test Android e iPhone.
- **Diverso da Android:** niente posizione nel widget, niente anteprima né «Prova ora» nel foglio di modifica, tre righe nella dimensione media; nessuna Live Activity per ora (la stessa estensione la accoglierà).
- Testi di Android in 17 lingue (`tools/ios_widget_strings.py`). Aggiornati [l'informativa privacy](PRIVACY.md) (App Group, richieste dei widget, nessuna posizione), la checklist App Store con le note per App Review, i manifesti privacy e il controllo dell'archivio Release.

## In sviluppo — iPhone: viaggi con cambi come su Android

- **Viaggi con cambi** nei risultati del pianificatore: fino a due cambi, calcolati sull'iPhone dall'indice incluso (e dagli orari completi dei paesi confinanti già scaricati) con la ricerca di Android (Connection Scan): stessi tempi di cambio (10 minuti nelle grandi stazioni, 3 tra mezzi urbani), percorsi a piedi tra stazioni vicine e fermate urbane, finestra, orizzonte, scelta dei pack, attese lunghe e «meno camminate». I treni diretti e i viaggi con cambi stanno in un solo elenco con i chip di Android («Tutti», «Diretti», «Meno cambi», «Più veloce», «Meno a piedi», «Più affidabili») e il filtro dei cambi del pianificatore («Qualsiasi», «Diretto», «Max 1 cambio», «Max 2 cambi»).
- **Resta a bordo:** una corsa che prosegue con un altro numero (stesso `block_id`, o lo stesso treno pubblicato da due pack) non è un cambio, come su Android.
- **Ultimo di oggi**, «Non ci sono più treni oggi: primo domani alle HH:mm» e «Nessun treno fino alle HH:mm» per una ricerca di oggi; con le statistiche, «Più affidabili», il treno più debole del viaggio e «Coincidenza a … riuscita N volte su M».
- **Dettaglio del viaggio:** ogni tratta con orari, mezzo, operatore, accessibilità e bici, i cambi con l'attesa, i percorsi a piedi, la fonte e l'avviso sugli orari programmati.
- **Preferenze di viaggio** (Impostazioni): velocità a piedi, tempo massimo a piedi per tratto, «Preferisci», tipi di servizio da preferire o evitare e «Solo percorsi accessibili in sedia a rotelle». Restano sull'iPhone (`UserDefaults`); i treni diretti nascosti dalle preferenze sono contati sotto i risultati, come su Android.
- **Indice orari:** due tabelle facoltative, `trip_detail` (`block_id` e `wheelchair_accessible` delle corse) e `stop_detail` (punto di cambio delle fermate e `wheelchair_boarding`), nell'indice pubblico e in quello personale; lo schema resta alla versione 2, così gli indici esteri già fissati restano validi (senza blocchi né accessibilità quando è installato l'orario completo di un paese). Indici ricostruiti in modo riproducibile; l'orario di Alexa accetta le tabelle nuove.
- **Base comune:** i parametri della ricerca sono la nuova tabella `contracts/shared-data/v1/journey-planner-v1.json`, letta come codice generato anche da Android al posto delle sue costanti; nuovi casi comuni `contracts/shared-rules/v1/connection-scan-v1.json`, letti dai test Kotlin e Swift.
- **Coincidenza a rischio / persa** con i dati in tempo reale: con **Dati in tempo reale** attivo i treni dei cambi in corsa entro tre ore (al massimo otto) sono chiesti per numero a ViaggiaTreno e Italo quando arrivano i viaggi, poi al massimo una volta al minuto mentre i risultati sono sullo schermo e ogni 30 secondi per il viaggio aperto, che passa per primo. Schede e dettaglio mostrano «Coincidenza a rischio», «Coincidenza persa», «Coincidenza confermata» e gli orari previsti; una risposta non confermata scade dopo cinque minuti. Stesse regole di Android per scegliere la corsa della ricerca per numero (mai quella di ieri di un treno notturno).
- **Più presto / Più tardi** sopra e sotto i risultati: treni diretti e viaggi con cambi, ognuno dalla propria prima o ultima partenza, finestre di un'ora, tre, sei e dodici, mai prima del giorno cercato, con «Nessun'altra soluzione più presto / più tardi».
- **Base comune:** nuove tabelle `journey-live-v1.json` e `results-paging-v1.json` in `contracts/shared-data/v1/`, lette come codice generato anche da Android, e nuovi casi `contracts/shared-rules/v1/journey-live-v1.json` e `results-paging-v1.json`, letti dai test Kotlin e Swift.
- **Percorso in tempo reale dei treni diretti**, come su Android, per una ricerca di oggi con **Dati in tempo reale** attivo: il percorso ViaggiaTreno (tabellone della stazione di partenza, corse di al massimo 12 treni verificate fino alla destinazione, prima i numeri dell'orario su un tabellone affollato) e il percorso RFI (partenze della stazione di partenza e arrivi di quella di arrivo, la stessa corsa sui due), con la precedenza di Android. Le corse live prendono il posto dello stesso treno dell'orario (numero, famiglia dell'operatore, entro tre ore; una corsa non verificata non copre il tabellone) e quelle che l'orario non ha si aggiungono, anche quando l'orario non ha treni diretti. Le schede mostrano partenza e arrivo programmati e l'arrivo previsto verificato; i treni di «Più presto» e «Più tardi» hanno il tabellone della loro ora e il percorso. Regole nella tabella comune `live-route-v1.json` e nei casi `contracts/shared-rules/v1/live-route-v1.json`, letti dai test Kotlin e Swift; Android legge la tabella come codice generato. «Più presto» conta ora i minuti dell'orologio italiano anche nella notte del cambio d'ora, come Android. L'Apple Watch riceve ancora i treni dell'orario, con i valori del tabellone di partenza.
- **Percorso in tempo reale anche sulle pagine e sul ritorno:** ogni finestra di «Più presto» / «Più tardi» chiede il percorso dalla propria ora, come Android; il ritorno di «Andata e ritorno» ha il tabellone della sua stazione di partenza, il proprio percorso e le corse che l'orario non ha. Un'azienda dell'orario che non è Trenitalia, Trenord o Italo vale per ogni famiglia, come l'`OTHER` di Android. Sotto i risultati la fonte del percorso («Trenitalia — ViaggiaTreno realtime», «RFI — Monitor Arrivi/Partenze live») con l'avviso di Android, tradotto nelle 17 lingue.
- **Dettaglio del viaggio:** **Condividi** (stesso testo di Android: stazioni, giorno, orari, durata e cambi, treni, cambi di treno, fonte) e **CO₂ stimata** delle tratte in treno o bus, senza i percorsi a piedi. Le schede dei treni diretti mostrano anche la durata.
- **«Ultimo di oggi» nei tabelloni:** il tabellone della stazione e «Le tue partenze» segnano l'ultima partenza di oggi verso ogni capolinea (o verso la destinazione della tratta) e dicono «Non ci sono più treni oggi: primo domani alle …», dall'orario incluso letto sull'iPhone, mai dai dati in tempo reale. Casi comuni `contracts/shared-rules/v1/last-of-day-v1.json`, letti dai test Kotlin e Swift.
- **Mobilità e accessibilità** (Impostazioni, sotto la lingua): cambi preferiti, margine di interscambio, percorsi senza barriere e tabelloni compatti, con il riepilogo di Android, salvati sull'iPhone e nel backup locale (chiave `mobility` di Android). Come su Android nessuna ricerca le legge ancora. Nuova tabella `mobility-preferences-v1.json`, letta come codice generato anche da Android, e casi `mobility-preferences-v1.json` per il backup.
- Testi di Android nelle 17 lingue; i testi «Ordine» e «Per orario» del vecchio selettore dei risultati lasciano il posto ai chip. Aggiornati [l'informativa privacy](PRIVACY.md) (nuova preferenza locale, nessuna richiesta di rete) e le note per App Review.

## In sviluppo — Metro, tram e funicolari anche su iPhone

- **Indice orari dell'iPhone con le reti urbane pubbliche di Android:** metropolitana e tram di Milano (ATM), metropolitana, funicolari e ascensori di Genova (AMT), tramvia di Firenze (GEST), metrotranvie di Cagliari e Sassari (ARST) e Marconi Express di Bologna. Stessi tipi di linea di Android (metro 1, 12, 400–499; tram 0, 900–999; funicolare 5, 6, 7, 1300–1499). L'indice passa a 22 pack: 16,2 MB compressi e 59,4 MB di SQLite (prima 7,3 e 27,8), sotto i limiti di 32 e 256 MiB.
- **Viaggi:** dalla stazione si raggiunge a piedi una fermata entro 600 m, con gli stessi minuti di Android, e la tratta si mostra come su Android: «Metro M1», «Tram 14», «Funicolare», con la fermata dove salire. Una corsa urbana non è mai la copia di un treno, non entra nelle statistiche di puntualità e non chiede dati in tempo reale alle fonti ferroviarie.
- **Ricerca stazioni e tabelloni:** con **Includi fermate di metro e tram nella ricerca** (Impostazioni) le fermate urbane compaiono con il badge «M», «T» o «F»; ogni fermata ha il proprio tabellone in **Partenze**. Nella scheda dell'aeroporto di Bologna, **Cerca le corse** cerca il Marconi Express, come su Android.
- **Base comune:** l'elenco delle reti urbane è in `contracts/shared-data/v1/pack-roles-v1.json` (`urbanPacks`) e i valori del cammino verso una fermata urbana in `urban-stops-v1.json`; Android li legge ora dal codice generato. Nuovi casi di prova comuni per nomi delle fermate, badge e minuti a piedi (`contracts/shared-rules/v1/urban-stops-v1.json`) e per linee ed etichette delle corse urbane (`journey-leg-vehicles-v1.json`), letti dai test Kotlin, Swift e Python.
- **Configurazione personale dell'iPhone:** lo schema XcodeGen «Binario Personal» (configurazione `Personal`, condizione `BINARIO_PERSONAL_SOURCES`) include un indice proprio con le reti urbane delle build personali Android: Roma, GTT Torino, ACTV Venezia e ANM Napoli (23,1 MB compressi). Cotral resta solo su Android. Debug e Release restano le configurazioni pubbliche, con l'indice pubblico.
- **Tempo reale delle reti urbane:** nella configurazione personale il tabellone di una fermata di Roma o di Mestre mostra orari previsti e soppressioni dal feed aperto della rete (Roma Servizi per la Mobilità, ACTV), come su Android, con «Dati in tempo reale» attivo.
- **Non ancora sull'iPhone:** il foglio dei collegamenti diretti da una fermata di Android e il tempo reale nelle tratte urbane dei risultati.
- Testi di Android in 17 lingue. Aggiornati [l'informativa privacy](PRIVACY.md) (nuova preferenza locale, nessuna richiesta di rete), i testi del listing, le note per App Review e la checklist App Store.

## In sviluppo — iPhone: meteo, bici in condivisione, indennizzo, treni in movimento e promemoria serali come su Android

- **Meteo a destinazione** nel dettaglio del treno: la previsione Open-Meteo di oggi o domani per l'ultima fermata mostrata, con le coordinate del catalogo arrotondate a due decimali (mai la posizione del telefono), gli stessi testi per ogni codice WMO e l'attribuzione CC BY 4.0. Interruttore in Impostazioni, attivo di serie come su Android.
- **Bici in condivisione** in fondo a **Partenze**: dopo il tocco su «Mostra bici e monopattini entro 300 m», stazioni con bici, e-bike e posti liberi e mezzi a flusso libero con la batteria, dai feed GBFS di BikeMi, Verona Bike, nextbike Bergamo e Fa.Mo.Se; Bird (Roma) solo nelle build personali compilate con `BINARIO_PERSONAL_SOURCES`.
- **Indennizzo per ritardo** (regolamento UE 2021/782) nel dettaglio del treno: 25 % da 60 minuti, 50 % da 120, solo su un arrivo effettivo verificato dalla fonte all'ultima fermata.
- **Treni in movimento** da **Partenze** (attorno alla stazione) e sotto i risultati del pianificatore (sulla tratta): posizioni stimate dall'orario incluso, spostate dal ritardo che lo schermo già mostra, con le stesse regole di Android, sulla mappa di Apple (MapKit), con legenda ed elenco per VoiceOver.
- **Promemoria serali**: «Scioperi del giorno dopo» alle 18 (attivo di serie) e «Lavori di domani sulle tue tratte» alle 19 (spento di serie), con un solo aggiornamento in background (`fetch`, un identificativo). Leggono solo il calendario del MIT e il feed di RFI e mostrano notifiche locali: nessuna notifica push. Con un promemoria attivo, Impostazioni non dice più che quei feed si leggono solo aprendo gli avvisi.
- **Base comune:** cinque tabelle nuove in `contracts/shared-data/v1/` (meteo, bici in condivisione, indennizzo, mappa dei treni, promemoria serali), lette come codice generato anche da Android al posto delle sue costanti, e cinque file di casi in `contracts/shared-rules/v1/`, letti dai test Android (con le fixture Android) e iPhone. L'indice orari dell'iPhone sa ora dare le corse in movimento di un'area o di una tratta.
- Testi di Android nelle 17 lingue. La riga «… e altri avvisi in Per te» della notifica dei lavori non c'è sull'iPhone, che non ha la scheda Per te; i treni della mappa non si aprono ancora con un tocco. Aggiornati [l'informativa privacy verificabile](PRIVACY.md) (Open-Meteo, GBFS, mappa Apple, promemoria serali), le note per App Review e la checklist App Store.

## In sviluppo — Alexa con l'orario programmato; Android come l'iPhone sui giorni della settimana

- **Prossimi treni con Alexa.** «Prossimi treni per casa lavoro» e il nuovo «prossimi treni da Milano Centrale a Bologna Centrale» (senza collegamento dell'account) dicono le prossime tre partenze dell'orario programmato, con categoria e numero o l'etichetta del bus: «Secondo l'orario programmato le prossime partenze sono il REG 2161 delle 9:12, …». La risposta comincia sempre con «dati non disponibili in tempo reale» e finisce dicendo che il binario non è indicato e che lo stato in tempo reale è nell'app. Senza partenze nelle 24 ore, o con l'orario scaduto, la skill lo dice; se l'orario non può rispondere resta la risposta di prima (i treni più puntuali delle prossime tre ore).
- **Stato di un treno.** Resta senza dati live: la skill non interroga ViaggiaTreno dal server. Dopo l'avviso dice la corsa di oggi nell'orario programmato («oggi il treno 9512 parte da Roma Termini alle 7:10 e arriva a Milano Centrale alle 10:50»), che lo stato in tempo reale è nell'app, e la puntualità delle statistiche quando c'è.
- **Stesso orario delle app.** La skill legge l'indice che l'iPhone include (`ios/Resources/binario_gtfs_schedule_v1.sqlite.zlib`, costruito dai pack di Android), copiato in `dist/data/timetable/` dalla build e verificato come l'installer dell'iPhone (dimensioni, SHA-256, schema, integrità, impronta). Lo legge con `node:sqlite` di Node, senza nuove dipendenze: serve Node 22.13 o successivo. `GET /readyz` controlla anche l'orario (`timetable`).
- **Stesse regole dell'iPhone.** La ricerca è quella del planner dell'iPhone, portata in TypeScript: giorni di servizio, fusi delle aziende, corse oltre le 24:00 e cambio d'ora, un treno pubblicato da due pack detto una volta sola, autobus e bus sostitutivi con la loro etichetta. Nuovi casi comuni `contracts/shared-rules/v1/next-trains-v1.json` su un piccolo indice sintetico, letti dai test della skill e dell'iPhone. La skill legge ora anche le tabelle `pack-roles-v1.json` e `train-number-prefixes-v1.json` come codice generato, e i casi dei numeri (`rules-v1.json`) e dei mezzi (`journey-leg-vehicles-v1.json`).
- **Echo Show.** La schermata Partenze elenca le stesse partenze (orario, treno, «Binario —») sotto «Orari programmati, non in tempo reale», la frase dell'Apple Watch; Stato treno mostra la corsa programmata. Un nuovo suggerimento propone la domanda fra due stazioni.
- **Android, osservazioni sui giorni della settimana come sull'iPhone.** Il dettaglio di un treno mostra fino a due giorni della settimana in cui il treno è chiaramente più o meno puntuale della media, la differenza maggiore prima, come l'iPhone (prima Android ne mostrava uno, preferendo il giorno della corsa). Il massimo è nella tabella comune `punctuality-v1.json` (`maximumWeekdayRemarks`), la regola ha i casi comuni `contracts/shared-rules/v1/weekday-remarks-v1.json`, letti dai test Android e iPhone.
- **Modello di interazione.** Nuovo intent `StationTrainsIntent` in tutti i 12 locale, con il tipo di slot `BINARIO_STATION` (nomi del catalogo stazioni); l'aiuto cita le due stazioni. Testi nuovi nelle 17 lingue del companion, tradotti automaticamente fuori dall'italiano.

## In sviluppo — iPhone: arrivi, dettagli della corsa e avvisi per treno da ViaggiaTreno e RFI

- **Arrivi in «Partenze».** Accanto alle partenze l'iPhone mostra ora gli arrivi di una stazione, come Android: il tabellone in tempo reale del monitor RFI (`arrivals=True`) e di ViaggiaTreno (`arrivi/`), uniti campo per campo con le stesse regole delle partenze. Italo pubblica solo le partenze e non viene chiesto. Gli arrivi esistono solo con **Dati in tempo reale** attivo, come su Android, dove il tabellone non ha arrivi programmati.
- **Dettagli della corsa.** Toccando un arrivo si apre la corsa con tutte le fermate (`andamentoTreno` di ViaggiaTreno, come `getStationBoardRun` di Android): la riga del tabellone, origine e destinazione, la fonte e l'ora del rilevamento, ogni fermata con orari previsti ed effettivi. Una partenza del tabellone apre il dettaglio del treno di sempre, che ora chiede direttamente la corsa che ViaggiaTreno ha indicato nella riga, senza la ricerca per numero: una richiesta in meno.
- **Avvisi per questo treno** nel dettaglio del treno, come la sezione di Android: gli avvisi che ViaggiaTreno pubblica per il numero del treno («smart caring», solo quelli validi nel giorno della corsa e per la sua origine), le notizie Trenitalia che lo citano e, per un treno Trenord o vicino alla rete Trenord, gli avvisi delle sue linee. Solo con **Dati in tempo reale** attivo; con l'etichetta «Sciopero», la fonte, «Riprova» e la copia dell'ultimo aggiornamento riuscito.
- **Operatore da `codiceCliente`.** Sull'iPhone, come su Android, una riga o una corsa ViaggiaTreno senza `compImpresa` prende l'operatore dal codice cliente (63 Trenord; 1, 2, 4 e 18 Trenitalia): un treno Trenord non viene più presentato come Trenitalia.
- **Tabelle comuni nuove**, lette come codice generato da Android e iPhone: `viaggiatreno-v1.json` (indirizzo e risorse dell'API, limiti di una richiesta, intervallo, durata dei valori, richieste di dettaglio in parallelo e loro tempo massimo, limiti di lettura, tipi di fermata, codici cliente) e `rfi-monitor-v1.json` (selettore e monitor, limiti, intervallo, durata dei valori, durata del selettore). La tabella degli avvisi ha in più il feed degli avvisi per treno e i suoi limiti di lettura. I valori erano già identici sui due telefoni: Android non cambia comportamento.
- **Casi comuni** in `contracts/shared-rules/v1/live-sources-v1.json` (operatore di una riga ViaggiaTreno, riga del tabellone arrivi, avvisi per treno di un giorno sulle fixture Android), letti da `LiveSourcesContractTest` (Android) e `LiveSourcesContractTests` (iPhone).
- **Rete e privacy.** Nessun host nuovo: gli arrivi e gli avvisi per treno vanno a `www.viaggiatreno.it` e `iechub.rfi.it`, con gli stessi intervalli, limiti e pause di Android; gli avvisi per treno passano dalla stessa pausa di ViaggiaTreno, quindi un rifiuto ferma tutte le richieste a quell'host. Aggiornati [l'informativa privacy](PRIVACY.md) e il questionario privacy della checklist App Store.
- **Testi** di Android in 17 lingue (`station_board_*`, `notices_*`).
- **Non ancora sull'iPhone:** la tratta live (ViaggiaTreno con le fermate di ogni corsa, RFI con il treno sui tabelloni di partenza e di arrivo) e la sua unione con le corse programmate (`LiveRunMerge`), perché i risultati dell'iPhone non hanno ancora l'orario di arrivo né righe solo live; i quadri orari programmati RFI (`prm.rfi.it`), perché sull'iPhone l'orario programmato di tabellone e pianificatore è l'indice GTFS incluso e quei quadri porterebbero un host e un lettore nuovi. I loro valori restano nel codice Android finché l'iPhone non li usa.

## In sviluppo — Aggiornamenti delle corse e tabelloni svizzeri anche su iPhone

- **iPhone, aggiornamenti delle corse come su Android:** i feed aperti GTFS-Realtime di STA Alto Adige, SNCF e Trenitalia France danno ritardo e orario previsto alla partenza dei treni dell'orario incluso di quell'operatore nei risultati del pianificatore, come su Android, e nel dettaglio del treno aperto da lì finché ViaggiaTreno o Italo non danno la corsa intera; sull'iPhone anche in **Partenze**, sulle righe dell'orario incluso. Stesse regole di Android: solo la fermata elencata, solo il giorno di servizio della corsa, osservazioni di al più cinque minuti prima, ritardo pubblicato o differenza tra orario previsto e programmato. Un feed si scarica intero, senza parametri, solo quando una schermata elenca un treno di oggi di quel pack; il confronto avviene sul dispositivo. Il tabellone live della stazione, quando risponde, resta prima.
- **Lettore protobuf scritto a mano** in BinarioKit, solo per la parte che legge Android (intestazione, TripUpdate, StopTimeUpdate, ritardi, orari, stato) e con limiti di dimensione: un feed malformato non dà nessun dato. Nessuna dipendenza nuova.
- **Tabelloni svizzeri** di `transport.opendata.ch` in **Partenze** e nei risultati, per le stazioni svizzere e di confine: il numero UIC viene dal catalogo o dal pack svizzero, altrimenti da una ricerca per nome tenuta una settimana. Su Android sono una fonte personale; sull'iPhone, come ViaggiaTreno, RFI e Italo, dipendono da **Dati in tempo reale**, e così i feed delle corse.
- **Buone maniere come Android:** User-Agent di Binario, HTTPS, stessi limiti di risposta e durate di cache (90 secondi STA, due minuti SNCF e Trenitalia France, un minuto i tabelloni svizzeri), un errore ricordato 30 secondi, pausa al primo HTTP 403 o 429. Sull'iPhone SNCF e Trenitalia France, sullo stesso host, condividono anche l'interruttore dopo errori ripetuti; nessun nuovo tentativo automatico.
- **Reti urbane:** i feed di Roma Mobilità e ACTV sono letti e coperti dai test, ma esistono solo nelle build personali (`BINARIO_PERSONAL_SOURCES`) e con l'orario della rete incluso, come `UrbanRealtimeFeeds.forBuild` su Android; i tabelloni urbani dell'iPhone non li usano ancora.
- **Tabella comune** `contracts/shared-data/v1/realtime-feeds-v1.json` (indirizzi, formati, limiti, cache, pause, finestra di osservazione, tabelloni svizzeri), letta come codice generato da Android e iPhone; gli adapter Android ora prendono i valori da lì, senza cambiare comportamento.
- **Casi comuni** `contracts/shared-rules/v1/gtfs-realtime-v1.json` (lettura del protobuf, feed, raccordo con l'orario, reti urbane) e `swiss-station-board-v1.json` (numero UIC, ricerca per nome, tabellone), sulle fixture dei test Android, letti dai test Android con le binding protobuf e dagli adapter esistenti e dai test Swift.
- **Non portati sull'iPhone:** il feed e l'orario di European Sleeper (Android scarica il GTFS dell'operatore a runtime; leggerlo sul dispositivo richiede un lettore di zip e di GTFS e l'abbinamento delle stazioni), gli aggiornamenti per **I miei treni**, per l'arrivo alla destinazione (le righe dell'iPhone hanno solo la partenza live) e per le copie di un treno tenute da un altro pack (per esempio la copia Trenitalia di un Frecciarossa Trenitalia France).
- Testi: la riga di `transport.opendata.ch` nelle Impostazioni viene da Android in 17 lingue; la riga dei feed delle corse è solo iPhone, con traduzioni automatiche da rivedere. Aggiornati [l'informativa privacy verificabile](PRIVACY.md) e le note per App Review con i nuovi host.

## In sviluppo — Wear OS e Apple Watch con gli stessi dati

- **Apple Watch con i dati in tempo reale.** L'iPhone mette sui treni inviati al Watch i valori live che ha già letto in primo piano, da meno di cinque minuti, per il pianificatore o per «Partenze» (RFI, Italo, ViaggiaTreno), come fa Android con Wear OS:
  - orario previsto, ritardo, «In orario» o «In ritardo» e binario della fonte; le corse sono in ordine di partenza prevista, e un treno in ritardo non ancora partito resta fra le prossime;
  - un treno soppresso non è inviato e diventa l'avviso «Soppresso: R 2345 delle 08:15»; con fonti in conflitto l'avviso lo dice;
  - lo snapshot è operativo, con la fiducia di Android (fonte primaria dell'operatore per ViaggiaTreno e Italo, del gestore dell'infrastruttura per RFI), e i valori live valgono 15 minuti dalla lettura; il Watch mostra «Dati aggiornati alle 08:12»;
  - l'iPhone non chiede nulla alle fonti per il Watch: senza valori recenti lo snapshot resta quello dell'orario programmato, come prima.
- **Alla fine dei valori live, l'orario programmato su entrambi gli orologi.** Dopo i 15 minuti dei valori live Apple Watch e Wear OS non mostrano più «Dati non disponibili» o «Dati scaduti» fino alla richiesta successiva: mostrano l'orario programmato delle stesse corse, senza ritardo né binario e nell'ordine programmato, fino all'ultima corsa (al più 12 ore), e app, Tile, complication e widget cambiano da soli a quell'istante. Il telefono invia lo snapshot più lungo solo a un orologio che lo dichiara (capability `binario_wear_programmed_fallback_v1`); un orologio precedente riceve i 15 minuti, come prima. Su Wear OS anche lo snapshot dell'orario programmato dura ora fino all'ultima corsa, come su Apple Watch, e l'app toglie dall'elenco le corse partite, come Tile e complication.
- **Affidabilità anche su Apple Watch.** «In orario 22 volte su 27» sotto la corsa nell'app Watch, come su Wear OS, dalle statistiche di rete incluse nell'iPhone e con la stessa regola di Android (mai per un bus), mai nei widget.
- **Bus su entrambi gli orologi.** Nuovo campo facoltativo `service{n}Vehicle` nel contratto v1 (`contracts/wear-snapshot/v1`, nuove fixture `android-to-wear-v1-vehicle.json` e `ios-to-apple-watch-v1-optional.json`):
  - Android lo ricava dai pullman dell'operatore (Bus Trenitalia, FrecciaLink, ItaloBus), l'iPhone dal mezzo dell'orario;
  - Wear OS mostra l'icona del bus nell'app e l'etichetta nel Tile e nella complication («08:10 · Bus Trenitalia 3601»); Apple Watch il simbolo del bus con l'etichetta nelle righe, nel dettaglio e nei tre widget; l'accessibilità non lo chiama treno;
  - la categoria di un bus è sempre la sua etichetta, anche per un orologio che non conosce il campo: Wear OS non dice più «Treno» e Apple Watch non mostra più «BUS 1234».
- **Compatibilità.** Un orologio riceve affidabilità e mezzo solo se dichiara di saperli leggere: Wear OS con le capability `binario_wear_reliability_v1` e `binario_wear_vehicle_v1`, Apple Watch con un application context verso l'iPhone che contiene soltanto quell'elenco. Un payload senza i campi resta valido, e una corsa senza mezzo è un treno.
- **Apple Watch:** un treno è chiamato come nelle righe dell'iPhone («FR 9505», «RE_11 - 2153» senza ripetere la linea).
- Le differenze che restano fra i due orologi (superfici, azioni, aggiornamento, durata) sono elencate nel README del contratto. Aggiornati l'informativa privacy e le note di Apple Watch e iPhone. Testi nuovi in 17 lingue, da Android e Wear OS.

## In sviluppo — Base comune tra i dispositivi

- Le app restano native, ma su una base comune ([ADR-0002](docs/architecture/ADR-0002-BASE-COMUNE.md)). Le tabelle che più piattaforme usano stanno ora in `contracts/shared-data/v1/`, e `tools/generate_shared_code.py` ne scrive il codice Kotlin, Swift e, per la skill, TypeScript. Il generatore controlla le tabelle prima di scrivere, e la CI con `--check` verifica che il codice sia aggiornato.
- Otto tabelle, finora ricopiate a mano, ora sono scritte una volta sola: codici Italo di riserva e pagine per nome, place ID RFI, punteggi delle fonti, soglie della puntualità (anche per Alexa), categorie ammesse davanti al numero di un treno, collegamenti con gli aeroporti, festività (Android e Alexa), limiti dell'Agenda e dei modelli. Su ogni piattaforma i valori erano già identici: non cambia nessun comportamento.
- Nuovi casi di prova comuni in `contracts/shared-rules/v1/` per le regole rimaste nel codice: soppressione esplicita e operatore delle righe RFI, modelli di «Preparazione del viaggio», festività con Pasqua e Pasquetta.
- iPhone: i collegamenti con gli aeroporti sanno quali pack sono linee urbane (`AirportRoutes.byCityLine`). Oggi non cambia nulla, perché nessun pack urbano arriva sull'iPhone.
- Dopo l'unione dei lavori iPhone su orari e avvisi, quattro tabelle in più passano dal generatore:
  - i codici delle stazioni Italo del pack orario (`italo-gtfs-station-codes.json`): Android legge il codice generato, il builder dell'indice iPhone il JSON;
  - le fonti degli avvisi (`notices-sources-v1.json`): Android e iPhone leggono il codice generato. Sull'iPhone la tabella non è più un file incluso nell'app, letto all'avvio, e il test Kotlin a riflessione non serve più;
  - come si chiede a ogni fonte (`source-manners-v1.json`: User-Agent, rifiuti 403 e 429, pausa, interruttore), separata dagli avvisi perché vale per tutte le fonti live dei due telefoni;
  - i ruoli dei pack orari (`pack-roles-v1.json`: nazionali, esteri, che ripubblicano i treni dei partner, con autobus dell'operatore), letti anche dal builder e dal verificatore dell'indice iPhone. Android continua a riconoscere i pack esteri dal paese del catalogo, e per ogni pack ferroviario il risultato coincide con la tabella.
  - Anche qui i valori erano identici: non cambia nessun comportamento.
- I workflow Android, Apple e Alexa partono anche quando cambiano le tabelle o il generatore.

## In sviluppo — Avvisi ferroviari anche su iPhone; tabella comune delle fonti degli avvisi

- **iPhone, avvisi come su Android** (stesse fonti, stesse regole, stessi testi):
  - in fondo a **Partenze**, l'area **Avvisi e infomobilità**: «Scioperi in programma» dal calendario del MIT (solo settore ferroviario), avvisi RFI e Trenitalia senza doppioni, avvisi Trenord per direttrice, etichetta «Sciopero» con la pagina dei treni garantiti, collegamenti al calendario scioperi e a FS News. Non scarica nulla finché non si tocca «Mostra avvisi»;
  - **Avvisi Trenord per questa stazione** nel tabellone di una stazione dell'area Trenord;
  - **Lavori e interruzioni sulle tue tratte** nella scheda **Tratte** e **… su questa tratta** sotto i risultati del pianificatore di una tratta salvata. Sull'iPhone l'avviso si abbina ai due estremi della tratta e alle linee che il testo cita: l'indice degli orari non ha ancora le fermate intermedie dei treni, che Android usa in più.
- **Fonti e regole.** RSS del MIT e di RFI sempre; infomobilità Trenitalia (ViaggiaTreno, con il solo salto in chiaro già ammesso) e avvisi Trenord solo con **Dati in tempo reale** attivo, come le fonti personali di Android. Impostazioni elenca le due nuove fonti e dice che RFI e MIT si leggono anche con l'interruttore spento; la nota dell'interruttore ora dice «non chiede nulla a queste fonti».
- **Buone maniere come Android:** User-Agent di Binario, HTTPS, stessi intervalli, stesse durate di cache e di copia non aggiornata, stessi limiti di risposta; al primo HTTP 403 o 429 la fonte va in pausa. Nessun nuovo tentativo automatico, come per le altre fonti dell'iPhone.
- **Non portati sull'iPhone:** il promemoria degli scioperi delle 18 e l'avviso serale dei lavori di domani, che richiedono un controllo in background che l'app iPhone non ha; gli avvisi nel dettaglio del treno.
- **Tabella comune** `contracts/shared-data/v1/notices-sources-v1.json`: indirizzi dei feed, accesso, intervalli, cache, limiti, pause e tabelle di lettura (parole degli scioperi e dei lavori, mesi, radici operative e area Trenord, domini ufficiali), con i valori di Android. Android e iPhone la leggono come codice generato (vedi «Base comune tra i dispositivi»).
- **Casi comuni** `contracts/shared-rules/v1/notices-v1.json` sulle fixture dei test Android, letti da `NoticesContractTest` (Android) e `NoticesContractTests` (iPhone). I workflow Android e Apple partono anche quando cambia `contracts/shared-data/`.
- Testi: quelli di Android in 17 lingue; le tre righe nuove delle Impostazioni e la nota modificata sono solo iPhone, con traduzioni automatiche da rivedere. Aggiornati [l'informativa privacy verificabile](PRIVACY.md), le note per App Review e la checklist App Store; il controllo della privacy legge ora anche gli host della tabella comune.

## In sviluppo — iPhone e Apple Watch con gli stessi orari di Android

- **iPhone:** l'indice degli orari include ora tutti i pack ferroviari di Android, 17 invece di 10:
  - Trenitalia e Italo, con i loro bus (Bus Trenitalia, FrecciaLink, ItaloBus);
  - le ferrovie regionali di `gtfs-transit/`: FAL, Ferrotramviaria, Ferrovie del Gargano, Mi.Co.Tra. Udine – Villach e Genova – Casella.
  - Metropolitane, tram e people mover restano solo su Android, perché l'iPhone non ha tratte di città. Le stazioni con orari passano da 804 a 2.563.
- **Stesse stazioni di Android:** le fermate Trenitalia e Italo sono riconosciute dal codice di stazione con le regole dell'app Android (1.829 fermate Trenitalia e 63 Italo), poi dal nome.
  - La tabella dei codici Italo è in `contracts/shared-data/v1/italo-gtfs-station-codes.json`.
  - I casi di prova comuni sono in `contracts/shared-rules/v1/gtfs-station-codes-v1.json`, letti dai test Python e Kotlin.
- **Bus:** nei risultati, nel dettaglio e in «Partenze» un bus compare con l'icona del bus e la stessa etichetta di Android («Bus Trenitalia», «ItaloBus», «Bus sostitutivo»), in 17 lingue.
  - VoiceOver non lo chiama «treno»; i bus non hanno la riga di puntualità.
  - I casi comuni ad Android e iPhone sono in `contracts/shared-rules/v1/journey-leg-vehicles-v1.json`.
- **Nomi e copie come su Android:**
  - categoria e operatore sono scritti come su Android («FR 9241» di «Trenitalia»);
  - un treno pubblicato da due pack (per esempio Trenitalia e Trenitalia France) compare una volta sola, con la copia del pack nazionale;
  - le corse Italo duplicate dello stesso itinerario sono lasciate fuori.
- **Fonti:** Impostazioni › Info mostra licenza, pagina e attribuzione dei 17 pack, con gli stessi testi del manifest Android. La licenza TFT è ora «Creative Commons Attribution (CC BY)».
  - Genova – Casella ha un'origine HTTP: è accettata solo come fonte importata offline e confermata da una copia HTTPS identica.
- **Dimensioni:** l'indice passa da 2,3 a 7,3 MB compressi (27,8 MB decompresso), entro i limiti esistenti.
- **Aggiornamento settimanale:** l'indice iPhone viene ricostruito e verificato anche quando cambia un pack di `gtfs-transit/`. I filtri della CI Android, Apple e GTFS coprono `contracts/shared-rules/` e `contracts/shared-data/`.
- **Apple Watch:** riceve gli stessi treni e bus dall'iPhone. Il formato inviato al Watch non ha un campo per il tipo di mezzo, quindi un bus compare con la sua sigla e il numero («BUS 1234»), senza icona.
- Il testo del pianificatore e le schede App Store dicono ora quali reti sono incluse (traduzioni automatiche, da rivedere).

## In sviluppo — «Cerca le corse» per il Marconi Express e per le linee urbane

- Android, collegamenti con gli aeroporti: i collegamenti in treno (Fiumicino, Malpensa, Bari, Palermo, Cagliari, Trieste, Caselle) continuano a dire «Cerca i treni da … a …»; solo Bologna, servita dal Marconi Express (una monorotaia), dice «Cerca le corse da … a …».
- Android, collegamenti diretti di una stazione: le destinazioni raggiunte da metro, tram o funicolare dicono «Cerca le corse» invece di «Cerca i treni»; quelle in treno restano invariate.
- Nuova stringa `planner_airport_search_runs` in 17 lingue (traduzioni automatiche, da rivedere). Su iPhone non cambia nulla: il collegamento di Bologna lì non compare.

## In sviluppo — Aggiornamento settimanale del 23 novembre

- Orari aggiornati: STA, SNCF (nuovi pin, pre-release verificate online) e Trenitalia France, ricostruito con le regole sulle categorie e sulle corse fuori ordine (righe «FR», 4 corse del 9281 lasciate fuori). Statistiche di puntualità: 10.863 treni, finestra 24 ottobre – 22 novembre.
- Svizzera e Trenord non aggiornati, restano i pack precedenti (validi fino al 12 dicembre). Per la Svizzera, l'archivio del 21 novembre si decomprime a 4,6 GB, oltre il limite di 1 GB. L'errore elenca ora i membri più grandi, così il prossimo aggiornamento mostra cosa è cresciuto senza scaricare di nuovo la fonte.

## In sviluppo — Trenitalia France anche su iPhone; Frecciarossa francesi con la loro categoria

- **iPhone:** l'indice degli orari include il pack di Trenitalia France (10 pack). I Frecciarossa Parigi–Lione–Chambéry–Oulx–Torino–Milano compaiono da Oulx, Torino Porta Susa e Milano Centrale; restano anche le corse che fermano a Lione Part-Dieu, così il suo tabellone mostra tutti i treni dell'operatore.
  - Parigi, Chambéry e Marsiglia non hanno codici nel catalogo e non si possono scegliere come estremi del viaggio, come per i treni SNCF.
  - Il testo del pianificatore dice ora «Trenitalia (tranne Trenitalia France) e Italo non sono inclusi», in 17 lingue (le traduzioni aggiunte sono automatiche e da rivedere). Stesse modifiche nelle schede App Store.
- **Costruzione del pack Trenitalia France** (vale per Android e iPhone dalla prossima ricostruzione, cioè l'aggiornamento settimanale):
  - le linee prendono la categoria del pack nazionale («FR», «Frecciarossa») invece della coppia origine/destinazione. Su Android quindi le righe diventano «FR 9281» e i treni entrano nel filtro Frecce; prima erano classificati come regionali;
  - le corse con le fermate numerate fuori dall'ordine degli orari vengono lasciate fuori e contate (oggi 4 corse del 9281 di fine novembre e inizio dicembre, che su Android comparivano come un treno fantasma Milano → Parigi). Oltre il 5% delle corse il pack non viene costruito;
  - la costruzione si ferma se compaiono una linea non ferroviaria o un'agenzia non rivista;
  - l'aggiornamento settimanale ricostruisce un pack pubblicato prima di queste regole anche se l'archivio di origine non è cambiato.

## In sviluppo — setup-java 6.3.0 (Dependabot #103)

- `actions/setup-java` passa da 6.2.0 a 6.3.0 (commit `d1374f8`, tag verificato con `git ls-remote`) nei tre job Android e nel controllo delle fonti live; aggiornato anche `.github/ci-inputs-lock.json`.
  - Novità della versione: le annotazioni del compilatore Java nelle PR diventano opzionali e sono spente per impostazione predefinita. Gli errori di compilazione restano nei log e fanno comunque fallire la build.
  - Il JDK resta Temurin 17.0.20+101, fissato come prima.
- **Correzione di un test Android:** l'annullamento di un download dei pack esteri ora viene provato solo dopo che il download è partito davvero. Prima il test poteva fallire a caso.

## In sviluppo — Ritardi Trenitalia France e orari francesi in ora italiana

- **Correzione:** il pack Trenitalia France aggiunto con la PR #111 aveva gli orari in UTC, mentre l'app li legge in ora italiana. I suoi treni comparivano un'ora prima, accanto alle copie del pack nazionale.
  - La costruzione del pack porta ora ogni corsa in ora italiana (+60 minuti d'inverno, +120 d'estate) e riempie il numero del treno dal codice della linea.
  - `--check` rifiuta qualsiasi pack con orari non in ora italiana.
  - Ogni Frecciarossa compare una volta sola, abbinato alla sua copia francese.
- **Ritardi in tempo reale di Trenitalia France su Android** (GTFS-RT di transport.data.gouv.fr, Etalab 2.0, stesso host dei ritardi SNCF):
  - i Frecciarossa Parigi–Lione, Parigi–Marsiglia e Parigi–Milano ricevono i ritardi anche quando l'app li mostra dal pack nazionale, grazie alla «identità alternativa» del treno tenuto nella deduplica;
  - il feed del treno ha la precedenza e due feed non si mescolano mai sullo stesso treno;
  - un pack con orari non locali non riceve ritardi.
- **Fonti in tempo reale SNCF, STA e Trenitalia France:** si fermano al primo rifiuto (403 o 429) anche senza `Retry-After`; prima potevano partire altre due richieste.
- PRIVACY.md e la scheda Play sulla sicurezza dei dati citano il nuovo feed. Nessun host nuovo, nessun dato dell'utente inviato.
- **Fonti valutate e scartate** con la sonda GTFS-RT:
  - TER ZOU Région Sud: Ventimiglia–Nizza è già coperta dal feed SNCF;
  - Trentino Trasporti via BusOne: alle 6:15 il feed aveva 83 corse su 58 linee di autobus e nessun treno della Trento–Malè.

## In sviluppo — Marconi Express e Trenitalia France

- **Marconi Express** (Bologna Centrale – Lazzaretto – Aeroporto, TPER, CC BY 3.0 IT dichiarata dalla pagina open data di TPER): il pack entra fra quelli pubblici (39 KB, 2.326 corse fino al 31 dicembre).
  - Bologna Centrale ↔ Aeroporto si aggiunge ai collegamenti con gli aeroporti, con la linea «MEX».
  - Su Android le linee monorotaia (route_type 12) si trattano come metropolitana: prima le loro corse venivano scartate.
  - La versione del file si legge ogni volta dalla pagina di TPER.
- **Trenitalia France** (Frecciarossa Parigi–Lione, Parigi–Marsiglia, Parigi–Milano; Etalab 2.0): il pack entra fra quelli principali (502 corse).
  - I treni già presenti nel pack nazionale Trenitalia compaiono una volta sola.
  - Serve come base per i ritardi in tempo reale di Trenitalia France, in arrivo.
- **Slovenia:** il candidato legge ora il file pubblico `dujpp.si`, che contiene 766 linee ferroviarie SŽ fino al 2028; `b2b.nap.si` chiede un'autenticazione. Resta solo nelle build personali finché l'editore non chiarisce la licenza.
- **CI:** nuova sonda GTFS-RT, che confronta un feed in tempo reale con un orario incluso o pubblicato. Sui dati di oggi:
  - il GTFS-RT SNCF già usato dall'app copre i TER Ventimiglia–Nizza (C3, C13, P1) sul nostro pack, quindi il feed ZOU della Région Sud non serve;
  - Trenitalia France combacia al 100% con il suo orario.

## In sviluppo — Stazioni regionali nel catalogo e nuove fonti candidate

- **Catalogo stazioni: 5.249 → 5.290.** 41 fermate ferroviarie dei pack inclusi non avevano una stazione: ora ce l'hanno, con nome e posizione presi dal pack.
  - Genova–Casella: tutte le 20 fermate.
  - ARST: 10 fermate sulle linee Sassari–Alghero, Sassari–Sorso e Macomer–Nuoro.
  - Ferrovie del Gargano: 9 fermate.
  - EAV: S. Marco.
  - Trentino: Mezzana, capolinea della Trento–Malè–Mezzana.
  - Napoli Montesanto ora si trova anche come «Montesanto», il nome usato da EAV.
  - Le stazioni Roma–Lido e Roma Nord restano fuori: il loro orario Cotral c'è solo nelle build personali.
- **Due voci del catalogo corrette, senza cambiare gli identificativi:**
  - S00239 era Tronzano Vercellese con il nome sbagliato «Pino-Tronzano»;
  - S09526 «Borgo Lombardo» stava in Umbria, ora è a Borgolombardo.
- **Android, nomi ambigui:** un'omonima a più di 10 km non rende più ambiguo il nome di una fermata. Tre fermate trovano così la loro stazione e nessuna la perde.
- **Nuove fonti candidate,** costruite dalla CI solo su richiesta:
  - Marconi Express (Bologna Centrale–aeroporto);
  - tram di Palermo (AMAT);
  - ferrovie della Calabria;
  - orario nazionale sloveno, per Trieste–Lubiana, Nova Gorica e Sežana.
  - Restano nelle build personali finché la CI non legge la licenza dichiarata dall'editore.
  - La CI ora stampa anche le licenze dichiarate dai portali (Socrata, CKAN) per Trenord, Trentino ed EAV.
- **Licenze di Trenord e Trentino confermate dagli editori:** CC BY 4.0 per entrambe, lette dai portali dati.lombardia.it e dati.trentino.it in una run della CI. Sono registrate nel manifest, nella policy iOS e nei crediti di Impostazioni › Info. Tutti i pack principali hanno ora una licenza registrata.
- **iPhone:** i tre indici completi dei paesi confinanti hanno un'impronta nuova, perché registrano il catalogo stazioni. Dopo il merge vengono pubblicati come nuove pre-release; le precedenti restano scaricabili per le versioni già installate.

## In sviluppo — Svizzera dall'indirizzo stabile, orario 2027 dal 13 dicembre

- **Svizzera**: il pack si aggiorna ora dall'indirizzo stabile (permalink) dell'orario 2026 invece che da un file datato agosto 2025, e dal 13 dicembre 2026 passa da solo all'orario 2027.
  - Il cambio è scritto nel manifest (`nextTimetable`). L'aggiornamento accetta il nuovo indirizzo solo se è quello registrato, poi aggiorna anche la policy iOS e il catalogo delle fonti.
  - Ogni altro cambio di indirizzo viene rifiutato.
- **Aggiornamento settimanale**: corretto un controllo della CI che avrebbe fatto fallire ogni aggiornamento della Svizzera e della Francia, ora che sono pack di zona di confine. Il controllo cercava il pack completo nella copia del repository invece che in quella appena scaricata.
- **Trenitalia France** (Frecciarossa Milano–Parigi e Parigi–Lione–Marsiglia, licenza Etalab 2.0) è registrato come pack candidato: la CI lo costruisce solo su richiesta.
  - Per ora non entra nell'app, perché i suoi treni sono già nel pack nazionale Trenitalia.
  - Servirà come base per i ritardi in tempo reale di Trenitalia France.
  - I test garantiscono che, quando entrerà, ogni Frecciarossa compaia una volta sola.

## In sviluppo — Fermate abbinate per nome solo entro 10 km

- **Android, abbinamento delle fermate alle stazioni**: un abbinamento per nome vale ora solo entro 10 km. Prima una fermata poteva finire su una stazione omonima a centinaia di km. Per esempio:
  - la Guarda dell'Engadina finiva sulla «Guarda» vicino a Ferrara (266 km) e Steinach in Svizzera su Steinach in Tirolo (159 km), così una ricerca Tirano → Guarda (FE) mostrava treni svizzeri;
  - stesso problema per Bellavista (Svizzera → Puglia), Lison e Villeneuve (Francia → Italia), Montesanto e S. Marco (EAV), Acqua Acetosa e Montebello (Roma Nord), San Giorgio (ARST), Sant'Antonino (Genova–Casella).
- **Come è stata scelta la soglia**: dai dati di tutti i pack. L'abbinamento giusto più lontano è a 8,4 km (Rocchetta S. Antonio-Lacedonia), quello sbagliato più vicino a 18,7 km. È anche la soglia già usata su iPhone e per i codici Trenitalia.
- **Cosa cambia**: 20 fermate perdono un abbinamento sbagliato. Nessuna fermata cambia stazione e nessuna stazione italiana perde i suoi treni.
- Le stazioni servite dai pack esteri si aggiornano di conseguenza (Svizzera −3, Francia −2); i file pubblicati non cambiano.
- Le stazioni senza coordinate nel catalogo restano abbinate per nome: per 9 su 12 il codice ferroviario conferma che sono proprio quelle.

## In sviluppo — Corridoi dei frontalieri; Svizzera e Francia a richiesta

Decisioni del titolare del 22 novembre: «più che un raggio in km, un limite ragionato sulle tratte estere più usate, specie dai frontalieri», e «procedi per tutte le nazioni».
- **Corridoi invece del raggio di 35 km.** Nell'app restano, intere, le corse che passano per uno di questi corridoi o che fermano in Italia. Ogni corridoio ha il suo motivo scritto in `tools/foreign_pack_rules.py` e in SOURCE_POLICY.md:
  - **Svizzera**:
    - Como – Chiasso – Lugano – Bellinzona – Biasca/Locarno;
    - Varese – Stabio – Mendrisio;
    - Luino – Cadenazzo;
    - Tirano – Poschiavo – St. Moritz (Bernina);
    - Domodossola – Re – Locarno (Vigezzina-Centovalli);
    - Domodossola – Iselle – Briga – Visp (Sempione).
  - **Austria**: Brennero – Innsbruck; San Candido – Lienz; Tarvisio – Villach – Klagenfurt.
  - **Francia**: Ventimiglia – Mentone – Monaco – Nizza; Oulx – Modane (Fréjus).
  - Nei pack ogni coppia di un corridoio conserva tutte le corse del pack completo, e `--check` lo verifica a ogni aggiornamento.
- **Svizzera e Francia diventano come l'Austria**: zona di confine nell'app e orario completo scaricabile su richiesta.
  - Le licenze sono registrate con le evidenze dei cataloghi e la conferma del titolare: per la Svizzera, uso libero di opendata.swiss con citazione della fonte; per SNCF Open Data, licenza con attribuzione.
  - Pack esteri nell'app: da 9,3 MB a 0,73 MB in tutto. Svizzera 2,66 → 0,23 MB, Francia 5,26 → 0,27 MB, Austria 0,23 MB.
  - Indice orario dell'iPhone: 4,42 → 2,23 MB. Indici completi scaricabili: Svizzera 2,64 MB, Francia 2,51 MB, Austria 1,16 MB.
- **iPhone**: Ventimiglia–Nizza è ora cercabile (484 corse).
- **Android, stazioni con lo stesso nome**: le stazioni della zona ora tengono conto di come Android abbina le fermate. Per esempio i due Maccagno del catalogo trovano entrambi i treni TILO da Cadenazzo.
  - Due controlli severi garantiscono che una stazione con corse mancanti non sembri mai completa e chieda lo scaricamento.

## In sviluppo — Orari dei paesi confinanti: zona di confine inclusa, orario completo a richiesta

Decisione del titolare del 22 novembre («proposta ibrida»), su Android e iPhone.
- **Nell'app resta la zona di confine**:
  - le corse che fermano almeno una volta entro 35 km da una stazione italiana, tenute intere, con la loro vera destinazione (poi sostituito dai corridoi dei frontalieri, voce sopra);
  - su Android Brennero–Innsbruck, Domodossola–Briga, Como–Lugano e Ventimiglia–Nizza funzionano senza rete, come prima (tutte le corse del pack completo restano).
- **L'orario completo di un paese si scarica su richiesta**:
  - l'app lo propone solo quando una ricerca non trova nulla perché un capolinea è fuori dalla zona (per esempio Milano → Vienna), oppure con una riga nei risultati e in «Partenze»;
  - parte solo dopo il tocco su «Scarica»: una sola richiesta HTTPS a GitHub, che vede l'indirizzo IP e quale paese viene scaricato, e nient'altro;
  - il file è un allegato di una release del repository, la sua impronta SHA-256 è fissata nella versione dell'app e controllata prima dell'uso;
  - resta sul telefono, non entra nei backup, si elimina da Impostazioni › «Orari dei paesi confinanti»;
  - nessun aggiornamento automatico: dopo un aggiornamento dell'app che cambia il file, Impostazioni mostra «Aggiornamento disponibile».
- **All'inizio solo l'Austria (ÖBB, CC BY 4.0)**: Svizzera e Francia sono rimaste intere finché il titolare non ha confermato le licenze (voce sopra); il meccanismo vale per qualsiasi combinazione dei tre paesi.
  - Pack ÖBB nell'app: 1,41 MB → 0,21 MB (1.180 corse su 8.731).
  - iPhone: indice orario 4,47 → 4,42 MB; indice completo dell'Austria scaricabile (1,16 MB).
- **iPhone, ricerca più precisa**: l'indice riconosce ora Innsbruck Hbf, Brennero (ÖBB) e «Domodossola (I)», con le stesse regole sui nomi delle fermate di Android; Brennero–Innsbruck e Domodossola–Briga diventano cercabili su iPhone. Ventimiglia–Nizza resta non cercabile su iPhone finché il pack SNCF non passa alla zona di confine (Nizza non ha codici ferroviari nel catalogo).
- **Pubblicazione**:
  - un nuovo workflow pubblica i file fissati come pre-release immutabili con tag basati sull'impronta (`gtfs-full-…`, `gtfs-ios-…`), mai modificate né cancellate;
  - un registro (`data/gtfs-foreign/published.json`) che si può solo allungare;
  - la firma Android e l'archivio iPhone si bloccano se un file fissato non si scarica con l'impronta esatta.
- **Privacy**: PRIVACY.md, la scheda Play sulla sicurezza dei dati e la nota per l'App Store descrivono il nuovo scaricamento; le risposte restano «nessun dato raccolto».
- I testi nuovi nelle 17 lingue sono traduzioni automatiche da far rileggere.

## In sviluppo — Aggiornamenti delle dipendenze (Dependabot #91–#93)

Ogni aggiornamento proposto da Dependabot è stato provato da solo; entrano quelli che passano tutti i controlli.
- **Controlli automatici (#91)**: le cinque azioni di configurazione (pnpm, Node, Java, Python, verifica del wrapper Gradle) alle versioni nuove, fissate per commit e verificate contro i tag ufficiali.
- **Android (#92)**:
  - entrano jsoup 1.24.1 e gtfs-realtime-bindings 0.2.0 (con protobuf-java 4.34.0), con le impronte prese da Maven Central;
  - restano indietro Kotlin 2.4, maplibre 13.7, kotlinx-coroutines-test 1.11 e WorkManager 2.12, che richiedono il passaggio a Kotlin ≥ 2.2 previsto con l'aggiornamento degli strumenti, e AGP 8.13.2, le cui impronte vanno generate dalla CI.
- **Alexa (#93)**:
  - entrano Express 5, TypeScript 7, Vite 8, Vitest 5, cookie 2, dotenv 18, nanoid 6, superjson 2, asn1js 4 / PKIjs 4, lucide-react 1, react-day-picker 10, framer-motion 14, @hookform/resolvers 7 e @types/node 26;
  - piccoli adattamenti: `parseCookie` al posto di `parse`, i percorsi «tutto il resto» senza `*` per Express 5, `baseUrl` tolto per TypeScript 7;
  - resta indietro react-resizable-panels 4: cambia l'intera API di un componente che non usiamo.

## In sviluppo — Ferrovia del Renon

- **Ferrovia del Renon** (STA, linea 160): le sue 10 fermate, da L'Assunta e Soprabolzano a Collalbo, entrano nel catalogo delle stazioni (5.249 stazioni).
  - Nomi e posizioni vengono dal pack STA incluso; come le altre stazioni senza codice ferroviario, non hanno dati in tempo reale.
  - Si trovano nella ricerca stazioni di Android e iPhone e nel pianificatore (per esempio Soprabolzano → Collalbo).
  - L'indice orario dell'iPhone tiene ora anche le 86 corse della linea (+13 KB).
  - Nessun'altra fermata cambia stazione.
  - I nomi tedeschi (Oberbozen, Klobenstein…) non ci sono: il pack STA pubblica solo i nomi italiani.

## In sviluppo — EAV con l'orario 2027 e dipendenze Alexa

- **EAV** (Circumvesuviana e le altre linee EAV): l'orario incluso arriva ora al 31 dicembre 2027 invece del 31 dicembre 2026 (718 corse).
  - Il nuovo file dell'editore teneva viva una corsa al giorno fino al 2030, e il controllo del refresh lo rifiutava.
  - Per EAV soltanto, la costruzione del pack taglia ora quella coda all'ultimo giorno con l'orario completo e lo dichiara nel pack (`serviceClippedAfter`, verificato da `--check`).
  - La regola del taglio è una sola, condivisa dalla costruzione e dal refresh; per tutti gli altri pack nulla cambia.
- **Alexa**: `qs` 6.16.1, `ip-address` 10.7.4 e Vitest 4.1.12 chiudono le 4 advisory moderate del backend (#99).

## In sviluppo — Aggiornamento del 21 novembre

- **Orari**:
  - Trenitalia (orario completo ora fino al 22 dicembre) e Italo aggiornati, con licenza e attribuzione invariate.
  - Nuove versioni per STA, ARST, SNCF (fino al 30 aprile 2027), Genova AMT e Genova–Casella (fino all'8 gennaio 2027) e la metro di Cagliari; indice iPhone ricostruito.
  - EAV resta quello di prima: il nuovo feed tiene viva una corsa al giorno fino al 2030 e il controllo lo rifiuta.
  - Trenord resta quello di prima: il portale della Lombardia non ha risposto.
- **Statistiche di puntualità**:
  - 10.856 treni sulla finestra 22 ottobre – 20 novembre (erano 6.641); bastano per una release (17 giorni coperti).
  - Dal 15 novembre la costruzione del file falliva: il controllo confrontava la media con i ritardi in virgola mobile, e una media arrotondata esattamente al limite (0,75 → 0,8 su 12 giorni) risultava fuori di un miliardesimo. Il confronto è ora in decimi interi; una media davvero incompatibile è ancora rifiutata.
  - Le osservazioni raccolte in quei giorni non sono andate perse.

## In sviluppo — Secondo audit dell'11 novembre: correzioni

Dodici revisori indipendenti, ognuno con un punto di vista diverso, hanno trovato 115 problemi e proposto circa 80 miglioramenti. Ogni rilievo è prima verificato o confutato, poi riprodotto con un test e corretto con un commit a parte. Le correzioni arrivano per area.

- **Android, treni preferiti**: un preferito salvato da una riga dell'orario con numero composto («RE_11 - 2153», «S5 - 811A») viene cercato con il numero della corsa. Tornano così lo stato in tempo reale nel widget e in «I miei treni», l'avviso di avvicinamento e la registrazione della puntualità, che ora resta sotto il numero del preferito.
- **Android, dati in tempo reale**:
  - un treno con l'orario spostato (lavori, pack vecchio) è una riga sola: la corsa in tempo reale prende il posto del treno programmato con lo stesso numero entro tre ore, non più entro venti minuti;
  - il caricamento di una tratta avvia l'orario insieme alle fonti in tempo reale, non dopo;
  - il tabellone RFI della stazione di partenza è chiesto una volta per aggiornamento, non due, e ViaggiaTreno non riceve più una terza finestra di partenze.
- **Fonti pubbliche, comportamento più prudente**:
  - una risposta arrivata in ritardo a una richiesta precedente non annulla più la pausa dopo un 403 o 429;
  - la pausa vale per il sito, non per il singolo adattatore: le due richieste a Italo la condividono, e così gli avvisi di ViaggiaTreno e le notizie di Trenitalia, che ora si fermano al primo 403;
  - lo scaricamento di riserva degli orari segue i reindirizzamenti solo sullo stesso sito e si ferma a un rifiuto, per il Retry-After o 15 minuti;
  - un solo User-Agent per tutte le richieste dell'app Android, con la versione della build: «Binario/0.20.4 (Android; rail timetable app)».
- **Android, pianificatore e orari offline**:
  - dove quattro reti servono la stessa coppia di stazioni (Milano Centrale – Verona Porta Nuova) il pianificatore le legge tutte: prima ne leggeva tre e lasciava fuori Trenitalia, che ha la maggior parte dei treni. Lo stesso vale per la mappa dei treni in movimento della tratta;
  - Italo: lo stesso treno non compare più con due, tre o quattro numeri. Il pack pubblica anche gli itinerari combinati come corse a sé; ora si tiene una corsa per treno reale, con il suo numero (Milano Centrale → Verona Porta Nuova: 8 treni invece di 21 righe);
  - un orario scaduto non viene più tolto in silenzio quando un'altra rete serve ancora la tratta: i risultati dicono quali reti mancano;
  - la ricerca per numero apre solo i pack che contengono quel numero, e le ricerche lanciate insieme non rileggono tutte gli stessi pack;
  - i treni notturni partiti ieri restano sulla mappa fino all'arrivo, anche dopo mezzogiorno;
  - il controllo live di un cambio usa lo stesso tempo minimo di cambio del calcolo (10 minuti nelle grandi stazioni, non 5);
  - fermate con lo stesso nome ma a più di 150 metri di distanza sono due luoghi diversi;
  - una ricerca annullata smette di leggere il pack.
- **iPhone e Apple Watch**:
  - «Partenze» mette prima i dati in tempo reale (decisione del titolare): con una fonte live che risponde, il tabellone sono i treni live, ognuno con numero, destinazione e orario dell'orario incluso nell'app; dopo l'ultimo treno live seguono i treni programmati. Senza dati live resta l'orario incluso nell'app, con l'avviso;
  - di sera «Partenze» mostra anche i treni subito dopo mezzanotte (Trenord, Svizzera), e dice quando l'orario di una rete della stazione è scaduto;
  - «Più puntuale» esclude i treni soppressi e conta il ritardo pubblicato dalla fonte live;
  - una fermata straordinaria si legge «Fermata straordinaria», non più come fermata saltata;
  - dopo un rifiuto 403 o 429 la fonte resta ferma almeno 15 minuti, come su Android; una connessione persa conta come errore di rete;
  - una richiesta lasciata da una schermata continua e la sua risposta viene riusata: la stessa pagina non viene chiesta due volte;
  - un treno con l'orario spostato si abbina all'orario entro tre ore, come su Android; «S5 - 811A» ha lo stesso numero che su Android;
  - un solo User-Agent con la versione dell'app: «Binario/<versione> (iOS; rail timetable app)»;
  - «Salva tratta» conferma il salvataggio, l'errore compare nella scheda giusta; «Elimina tutte» chiede conferma;
  - le stazioni con un doppio spazio nel nome sono di nuovo utilizzabili;
  - un errore di lettura non spegne più il pianificatore fino alla chiusura dell'app;
  - il widget del Watch smette di aggiornarsi ogni 15 minuti quando non ha nulla da mostrare.
- **«Partenze» su Android con i dati in tempo reale per primi** (decisione del titolare, anche nel widget della stazione): decidono i tabelloni live (monitor RFI, ViaggiaTreno, Svizzera); l'orario programmato RFI si mostra solo quando nessuno di loro risponde con treni, con l'avviso, e dopo l'ultimo treno live per arrivare a sei ore. Il tabellone di Italo, che elenca solo i treni Italo, si unisce sempre ma da solo non sostituisce quello della stazione. Nessuna richiesta in più; ordine delle fonti live e nome «Partenze» invariati.
- **Android, stato dell'app**:
  - i viaggi con cambio aggiornano i dati in tempo reale delle tratte e l'avviso «Coincidenza a rischio» (prima restavano quelli del momento della ricerca);
  - tornando all'app i dati si aggiornano subito se hanno più di 30 secondi, invece di aspettare il ciclo successivo; mai più di una richiesta ogni 30 secondi per tipo di dato;
  - le pagine «Più presto» e «Più tardi» scadute tornano «Programmato» invece di restare live per sempre;
  - i dettagli delle corse del tabellone non spariscono più a ogni aggiornamento;
  - un backup senza tratte salvate si importa se contiene altro; l'esportazione controlla il file prima di scriverlo e non crea più un file che l'importazione rifiuterebbe; il file esportato viene troncato se è più corto del precedente;
  - dopo un'importazione all'avvio si apre la tratta giusta; l'importazione azzera data e ora pianificate come una nuova tratta;
  - gli indici delle stazioni e lo storico della puntualità non lavorano più sul thread principale all'avvio;
  - fissare una tratta non cancella più la ricerca non salvata; la direzione salvata non viene sovrascritta da una ricerca;
  - la copia salvata mostrata dopo un aggiornamento fallito non elenca più treni partiti; la ricerca per numero dopo mezzanotte resta sul giorno cercato;
  - un caricamento superato da una nuova ricerca viene annullato; i promemoria del pendolare si rileggono quando si torna all'app; il tabellone della stazione resta aperto anche se Android chiude l'app in background.
- **Android, schermate**:
  - sul tabellone di stazione TalkBack dice per primo che un treno è soppresso e legge le variazioni di percorso; «Soppresso», «Parzialmente soppresso», «Deviato» e «Bus sostitutivo» sono etichette in grassetto nel colore di errore;
  - «In orario» non compare più accanto a «+0 min» o a un treno che una fonte dà in ritardo: una sola regola per righe, schede, distintivi e TalkBack;
  - la dettatura non indovina più date e ore nelle lingue di cui non conosce le parole: lì il microfono della tratta è nascosto, quello del numero di treno resta, e le cifre di ogni alfabeto vengono lette;
  - con caratteri grandi la destinazione va su due righe e il binario sotto; il tabellone dice quanti treni non sono in elenco oltre i 60;
  - «Ultimo di oggi» e «primo domani» solo sul tabellone di oggi;
  - «Smetti di monitorare» spegne davvero il monitoraggio della stazione (prima, su una stazione monitorata solo per le partenze, lo estendeva agli arrivi);
  - lo spagnolo dice sempre «cancelado», come su iPhone;
  - un controllo nella CI verifica chiavi, segnaposto e plurali delle 17 lingue.
- **Alexa**:
  - i festivi contano come domeniche in «il più puntuale»; un giorno della settimana senza dati non fa più proporre un treno;
  - le frasi d'esempio e i messaggi di benvenuto propongono le domande sulla puntualità; le due domande in tempo reale aggiungono quello che la skill sa della puntualità;
  - con più treni con lo stesso numero lo dice, invece di sceglierne uno;
  - al massimo 32 tratte per account, nomi di stazione controllati al salvataggio, conferma prima di rimuovere una tratta; il cruscotto distingue caricamento, errore e dati;
  - l'accesso è legato all'account Alexa che ha fatto il collegamento; codici e token scaduti o revocati si cancellano dopo 30 giorni;
  - HSTS e Content-Security-Policy in produzione, endpoint di prontezza, spegnimento ordinato, fuso orario della sessione del database fissato; un errore di lettura delle statistiche si riprova dopo 60 secondi;
  - tolto il codice inutilizzato del modello iniziale.
- **Raccolta della puntualità**:
  - dopo un blocco «morbido» (troppe risposte senza dati) la raccolta si ferma come dopo un 403/429: da 2 ore fino a 7 giorni, e riparte solo con una risposta su un treno riconosciuto;
  - un'esecuzione a cavallo della mezzanotte di Roma rimanda il resto della coda invece di chiedere un giorno ormai chiuso;
  - i codici d'origine Trenord che non sono codici ViaggiaTreno (per esempio «Brescia» S09999) sono confermati con il catalogo delle stazioni, oppure la corsa è saltata;
  - un treno in ritardo osservato solo in viaggio conserva le fermate già passate; i ritardi oltre 720 minuti contano come 720;
  - un file di statistiche con oltre il 30% di treni o campioni in meno viene rifiutato;
  - ogni treno porta i giorni dall'ultima osservazione (`l`): Alexa non propone più un treno non visto da oltre una settimana;
  - le statistiche bloccano una release finché non coprono almeno 14 giorni e non hanno più di 14 giorni.
- **CI, build e documenti**:
  - il bundle Play contiene tutte le 17 lingue (decisione del titolare) e un controllo lo verifica;
  - i file condivisi (statistiche, catalogo, icone, risorse iOS) fanno partire la CI dei prodotti che li usano;
  - una firma di release avviata a mano non viene più annullata da un merge su main;
  - il controllo delle fonti live dice quanti controlli ha verificato e quali ha saltato; i test saltati dopo un aggiornamento dei dati diventano avvisi;
  - Dependabot apre una PR raggruppata al mese per ecosistema e ignora gli aggiornamenti legati all'SDK Expo e le versioni maggiori della toolchain Android;
  - l'archivio iPhone usa il numero dell'esecuzione come numero di build;
  - 41 documenti storici hanno l'avviso «Documento storico»; un controllo verifica la riga di stato dei documenti datati;
  - `AZIONI_TITOLARE.md` elenca i passi su GitHub che spettano al titolare (protezione dei rami, PR vecchie, licenza, archivio di APK).
- **Android, ordine degli orari offline** (decisione del titolare): nel pianificatore e nella ricerca dei treni diretti, quando due reti pubblicano lo stesso treno vince prima Trenitalia o Italo, poi le reti regionali italiane, poi quelle estere. Dove una stazione non è servita né da Trenitalia né da Italo (Innsbruck, Lugano, Merano) vengono prima le reti che la servono, quelle estere prima delle regionali. Il pianificatore prova per prime metro e tram quando un capo della ricerca è in una città con la rete urbana inclusa: Roma Termini → Lido Centro torna alle 8:55 con metro B e Roma–Lido. Ordine delle fonti in tempo reale invariato.
- **Android, notifiche, widget e orologio**:
  - il widget «I miei treni» in modalità «Sempre» chiede solo i treni vicini alla loro corsa (da un'ora prima della partenza a mezz'ora dopo l'arrivo), una volta per tutti i widget, e non chiede più a Italo i treni di altre imprese: sparisce la gran parte delle circa 2.300 richieste al giorno;
  - un preferito con numero composto («RE_11 - 2153») non resta più «In attesa del primo aggiornamento» nel widget; una corsa di un giorno precedente non viene più spostata a oggi né contata come in viaggio;
  - la sveglia di fermata a orario non spegne più la sveglia GPS quando il GPS ha una posizione recente; se la posizione viene spenta durante il viaggio la sveglia GPS lo nota; un canale di notifica bloccato viene riconosciuto;
  - l'avviso di avvicinamento arriva al momento giusto anche in risparmio energetico (sveglia esatta quando è permessa); se cade tra le 22 e le 7 l'app lo dice al salvataggio, e nei giorni in cui non potrebbe suonare non fa la richiesta;
  - le righe di affidabilità dicono la soglia («entro 5 min», «entro 15 min»);
  - il riepilogo settimanale non riprova più ogni minuto con le notifiche spente; il registratore della puntualità non riprova nei giorni in cui il treno non circola e conta il giorno di servizio di ieri;
  - le notifiche a tempo scadono da sole; la pausa dopo un rifiuto 403/429 resta anche se Android chiude l'app (al massimo un'ora);
  - sull'orologio un treno già partito non resta «Adesso» e la tile dice «Nessuna corsa da mostrare»; il widget della tratta con dati vecchi non mostra più un arrivo o un binario superati.
- **Android, dettatura in tutte le 17 lingue** (importata dall'app compagna Expo, con gli errori corretti): giorno e ora si leggono in tutte le lingue, comprese le cifre di ogni alfabeto e le forme come «alle 7.45», «8 e mezza», «20 Uhr 15», 午後8時, 오후 8시, 下午8点, «ngày 12 tháng 11»; «oggi» è il giorno italiano; un'ora seguita da parole che non capisce resta vuota invece di diventare «:00». Il microfono della tratta torna in tutte le lingue; le stazioni si riconoscono con il nome del catalogo, e quando non si trovano l'app inserisce solo data e ora e lo dice.
- **iPhone, Agenda dei viaggi** (importata dall'app compagna Expo, nel formato di Android): una pagina in cima a «Tratte» con i viaggi in programma e l'archivio; creazione, modifica, completamento ed eliminazione (con conferma), stazioni dal catalogo, data e ora sempre in ora italiana, lista di controllo con le voci pronte, «ritorno» e «ripianifica», avviso per una partenza già passata, annunci VoiceOver. Al massimo 40 viaggi; un doppione è rifiutato; se un viaggio salvato non si legge più l'Agenda resta in sola lettura con quelli leggibili, senza perdere nulla. Tutto resta sul telefono (PRIVACY.md). Poi:
  - **Promemoria**: notifiche locali da 5 a 60 minuti prima, chieste solo al salvataggio di un viaggio con promemoria; se il permesso manca o l'ora è passata il viaggio resta salvato e un avviso lo dice. La pagina del viaggio mostra il promemoria davvero programmato. Al massimo i 64 più vicini, come permette iOS.
  - **File**: esportazione nel calendario (.ics) e trasferimento dell'Agenda (.json) nello stesso formato di Android, dal foglio di condivisione; l'importazione mostra un'anteprima con viaggi nuovi, doppioni, in conflitto e non leggibili, e non supera mai i 40 viaggi. Binario non crea file propri.
  - **Importazione da testo**: un viaggio per riga, nelle 17 lingue; si aggiunge solo dopo «Ho verificato».
  - **Come su Android**:
    - Viaggi fino a 4 tratte e viaggi ripetuti (2, 4, 8 o 12 volte nei giorni scelti), con un file di trasferimento identico byte per byte a quello di Android, quindi un'Agenda passa fra i due telefoni senza perdite.
    - Un promemoria per ogni partenza.
    - Modelli di viaggio, promemoria predefinito, «Svuota l'Agenda», ricerca e filtro per mese, la scheda «Pronto per partire», l'importazione dal calendario di un'altra app e l'azione rapida «Agenda» sull'icona.
- **Orari inclusi nell'app** (Android e iPhone):
  - **Italo**: spariscono i doppioni. Gli itinerari combinati e le copie di treni già presenti non compaiono più come corse in più: Milano Centrale → Verona Porta Nuova passa da 25 righe a 8 treni, Roma Termini → Caserta da 10 a 3. Dove una copia conservava una sosta, il treno ne prende l'orario (il 9939 parte da Roma Termini alle 17:30).
  - **Croazia tolta** (decisione del titolare): il pack HŽPP non serviva nessuna stazione italiana; l'app scende di circa 2 MB. I pack svizzero, austriaco e francese restano.
  - **Ferrovie toscane TFT**: le due linee ferroviarie pubblicate come autobus ora sono treni.
  - **Genova**: le corse degli ascensori e delle funicolari pubblicate «a frequenza» diventano corse vere e compaiono nel pianificatore.
  - **Corse scadute**: le corse che non circoleranno più non vengono più incluse. I pack pubblici scendono di 3,1 MB e l'indice iPhone da 6,5 a 4,5 MB, senza cambiare le date di validità.
  - **Fine dell'orario completo**: ogni pack dichiara anche l'ultimo giorno con l'orario completo (`fullServiceUntil`). Il controllo dei 30 giorni per le release ora usa quella data: Trenitalia pubblica l'orario completo fino al 12 dicembre e dopo solo una parte, quindi le release restano bloccate dal 13 novembre finché il refresh settimanale non porta l'orario di dicembre, come già per i pack che finiscono il 12 dicembre.
  - **Catalogo delle stazioni**: 12 stazioni corrette dal pack Trenitalia (fra cui Ala, S. Ambrogio e posizioni sbagliate), più nuovi controlli su area servita, codici e conteggi.
  - **iPhone**: l'indice degli orari usa anche le stazioni senza codice del catalogo, quindi più corse EAV e Trentino; un indice doppio è stato tolto.
- **Android, Agenda dei viaggi** (importata dall'app compagna Expo):
  - **Dati illeggibili**: se un viaggio salvato non si legge più, l'Agenda resta in sola lettura con quelli leggibili invece di sovrascriverli al salvataggio successivo.
  - **Messaggi distinti** per Agenda piena, doppione, sola lettura e dati non validi; un avviso prima di salvare una partenza già passata.
  - **Viaggi partiti** nell'archivio; «Ritorno» e «Ripianifica» sui viaggi di una sola tratta; voci pronte per la lista di controllo; promemoria predefinito; viaggi ripetuti (2, 4, 8 o 12); modelli di viaggio; stazione più vicina e preferite come partenza; «Aggiungi al calendario» per ogni viaggio; «Svuota l'Agenda»; ricerca e filtro per mese.
  - **Importazione**: da testo incollato o condiviso da un'altra app («Condividi»), e dai file di calendario di altre app.
  - **Altro**: la scheda «Pronto per partire» in Per te e una scorciatoia «Agenda» sull'icona dell'app.
  - **Viaggi con più tratte**: l'editor ne gestisce fino a 4 («Aggiungi tratta», «Rimuovi tratta»; la partenza della tratta successiva è l'arrivo della precedente), modificarne uno non perde più le tratte dopo la prima, e «Ritorno» e «Ripianifica» valgono anche per loro. L'iPhone per ora importa solo i viaggi di una tratta senza ripetizioni.
  - **Dettatura** di una tratta nell'editor, con le stesse regole e lingue del pianificatore.
  - **Privacy**: tutto resta sul telefono (PRIVACY.md).
- **iPhone, «Scrivi o detta la tratta» e «Stazione più vicina»** (importate dall'app compagna Expo):
  - **Dettatura**: nel pianificatore e nell'editor dell'Agenda si scrive o si detta con la tastiera la tratta («da Roma Termini a Torino Porta Nuova domani alle 18»). L'iPhone la legge con le stesse regole di Android nelle 17 lingue; i 264 casi sono in un file comune (`contracts/voice-input/v1`) controllato da entrambe le app. Binario non apre il microfono e non chiede permessi.
  - **Stazione più vicina**: per la partenza nel pianificatore e nell'Agenda, e in «Partenze». Il permesso di posizione viene chiesto solo al tocco, la posizione è letta una volta, usata sul telefono e mai salvata né inviata; nessuna stazione oltre 50 km.
- **Companion Expo congelato** (decisione del titolare: «Importa tutto quello che puoi importare e poi congela»):
  - **Funzioni portate**: le funzioni utili dell'app Expo sono ora nelle app Android e iPhone.
    - L'Agenda: voci pronte della checklist, «Ritorno» e «Ripianifica», viaggi fino a 4 tratte, ripetizioni, modelli, promemoria predefinito, «Pronto per partire», ricerca e mese, «Svuota l'Agenda».
    - L'importazione guidata da testo, i promemoria locali, i file ICS e JSON nello stesso formato sui due telefoni.
    - La dettatura in 17 lingue e la stazione più vicina.
  - **Stato di `companion-expo/`**: non è distribuito e non riceve nuove funzioni né aggiornamenti di dipendenze. La sua CI parte solo quando cambia il suo codice, o a mano.
  - **Icone degli store**: si generano da `store/source/binario-icon.png`, copia identica del marchio; le icone generate sono identiche byte per byte.
  - **Documentazione**: `companion-expo/README.md` dice dove si trova ogni funzione e che cosa non è stato portato, e perché. README, PRIVACY.md e la documentazione di release lo indicano come congelato.
- **iPhone e Apple Watch, Xcode 26.3**: la CI compila e prova con Xcode 26.3 (SDK iOS e watchOS 26.2), richiesto da App Store Connect da aprile 2026 per caricare una build; prima era fissata a Xcode 16.4. Versioni minime invariate (iOS 17, watchOS 10).
- **Ultime correzioni dell'audit**:
  - **iPhone, ricerca delle stazioni** più veloce: i nomi del catalogo si preparano una volta all'avvio invece che a ogni lettera; risultati identici, verificati su 30 ricerche.
  - **Android, «Partenze»**: quando nessun tabellone live risponde e resta l'orario programmato, l'avviso dice «Sono disponibili solo orari programmati»; «Realtime parzialmente non disponibile» resta per i casi davvero parziali.
  - **Alexa, puntualità di un treno**:
    - Senza stazione di partenza, Alexa risponde solo se circola un solo treno con quel numero, come l'app; prima ne sceglieva uno.
    - Un treno registrato solo alla prima fermata non viene più detto «in orario al capolinea».
  - **Regole comuni**: un solo file di casi (`contracts/shared-rules/v1`) controlla che Android, iPhone e Alexa calcolino allo stesso modo il numero del treno e le statistiche di puntualità.
- **Avvisi da fonti non cifrate**: un indirizzo costruito ad arte (schema annidato, barre codificate più volte) non diventa più un link toccabile. Il controllo sui siti ammessi vale nel punto in cui il link viene creato.
- **CI iPhone** (#84): l'età degli orari blocca solo le release (run avviata a mano, PR verso un ramo di stabilizzazione). Nelle altre run è un avviso: dal 13 novembre il job sarebbe diventato rosso su ogni modifica.

Corrette subito con la PR #83:

- **Android**: un treno con dati in tempo reale di ViaggiaTreno non diventa più «Altro operatore». La correzione del 10 novembre leggeva l'operatore solo da un campo che le risposte reali non hanno; ora si legge anche `codiceCliente` (Trenitalia, Trenord), e quando la fonte non dice chi gestisce il treno restano operatore, categoria e bici dell'orario. Tornano così il filtro per operatore, il link di acquisto di Frecce e Intercity e le etichette giuste su widget, orologio e storico.
- **iPhone**: il tempo trascorso per la seconda richiesta a ViaggiaTreno non usa più `systemUptime`, un'API che Apple accetta solo se dichiarata nel manifest della privacy: il caricamento su App Store Connect sarebbe stato rifiutato. Un nuovo controllo ferma la CI se i sorgenti usano un'API di quel tipo non dichiarata.

## In sviluppo — Audit indipendente del 10 novembre: correzioni

Otto revisori indipendenti, uno per area, hanno trovato 87 difetti. Ognuno è stato prima verificato o confutato, poi riprodotto con un test e corretto con un commit a parte. Le correzioni sono arrivate in quattro PR: #79 iPhone e Apple Watch, #80 companion Expo, #81 Wear OS, raccolta della puntualità, CI e documenti, e questa per dati Android, notifiche, widget e Alexa.

- **Android, risultati e dati in tempo reale**:
  - alla stazione di arrivo vale l'orario di arrivo, non quello in cui il treno riparte (prima STA 1839 Fortezza → Brunico durava 54 minuti invece di 37);
  - una corsa in tempo reale verificata non sostituisce più l'intero elenco: i treni Italo e quelli successivi restano, con l'orario programmato e i dati dei tabelloni;
  - una fermata intermedia aggiunta o soppressa non fa più sparire la corsa;
  - le righe di ViaggiaTreno prendono l'operatore che dichiarano (Trenord compreso), mai Trenitalia per ipotesi;
  - due treni di pacchetti diversi alla stessa ora restano due treni se numero e operatore non coincidono (REG 16348 e Italo 8984 Venezia S. Lucia → Mestre);
  - dopo mezzanotte si vedono ancora i treni della sera prima in ritardo.
- **Fonti pubbliche**:
  - un 403 o 429 mette subito in pausa ViaggiaTreno, il monitor RFI e Italo, per il Retry-After o 15 minuti;
  - ViaggiaTreno ha un solo stato condiviso in tutta l'app;
  - i redirect sono ammessi solo sullo stesso host.
- **Avviso di arrivo dei treni preferiti**: non suona più nei giorni in cui il treno non circola né quando la corsa salta la fermata, e funziona anche per le fermate dopo mezzanotte. Un avviso di lavori non viene più notificato di nuovo ogni 7 giorni.
- **Widget**:
  - «I miei treni» non mostra più come attuale lo stato di un giorno precedente;
  - il widget della tratta con dati vecchi mostra l'orario;
  - nomi delle fonti e degli operatori nella lingua del telefono;
  - TalkBack legge l'azione giusta sui pulsanti.
- **Altre correzioni Android**: un'app riaperta da Recenti non ripete il link di avvio; un backup troppo grande è spiegato nella lingua dell'app; il permesso di notifica concesso dopo una rotazione accende davvero la notifica.
- **Wear OS** (#81):
  - i dati restano 15 minuti con l'ora della rilevazione;
  - un treno soppresso non è più la prossima partenza;
  - Tile e complicazione passano al treno successivo un minuto dopo la partenza;
  - i treni del mattino dopo non risultano già partiti.
- **iPhone e Apple Watch** (#79):
  - i treni Trenord letti da RFI e ViaggiaTreno hanno i dati in tempo reale;
  - una fonte in pausa non viene sbloccata da una risposta arrivata in ritardo, e la mancanza di rete non la mette in pausa;
  - «In orario» non compare più accanto a un orario che la fonte ha spostato;
  - i nomi di linea (SNCF, TFT, Trentino, ARST) non sono più trattati come numeri di treno;
  - nessuna richiesta parte in background.
- **Companion Expo** (#80):
  - i promemoria funzionano e si annullano in tutte le 17 lingue;
  - il promemoria scelto nel pianificatore viene programmato;
  - la dettatura legge le ore dalle 20 alle 23;
  - date e orari sono sempre ora italiana;
  - il calendario ICS importa ed esporta i fusi orari.
- **Alexa**:
  - «qual è il treno più puntuale» non propone più i treni del fine settimana nei giorni feriali;
  - «il treno N è puntuale» va alla risposta sulla puntualità;
  - lo stato di un treno non chiede più il collegamento dell'account;
  - fuori dall'italiano gli orari sono indicati come ora italiana;
  - la dashboard non riceve più i vecchi campi personali del database;
  - il server non parte con un MariaDB che trasformerebbe le date vuote nella data corrente.
- **Raccolta della puntualità** (#81):
  - una cache non ripristinata non può più cancellare 35 giorni di osservazioni;
  - un rifiuto mette in pausa le run successive (da 2 ore fino a 7 giorni);
  - il User-Agent descrive con precisione il raccoglitore.
- **CI**: la firma della release solo da `main` o `stabilization/*`, nell'ambiente `release`; controlli più severi su trigger, checkout e segreti dei workflow.
- **Documenti**: PRIVACY.md, scheda Data safety di Play, note per la revisione Apple (eccezione ATS) e AZIONI_TITOLARE allineati al codice.

## In sviluppo — Dati in tempo reale su iPhone: le schermate

- **Partenze** su iPhone mostra quello che le fonti pubbliche vedono dei treni della stazione, come su Android: orario previsto e ritardo («prev. 08:17 · +5 min»), «Soppresso», il binario effettivo, la fonte di ogni valore («Fonte: ViaggiaTreno · Binario da RFI») e l'ora del rilevamento. I treni senza dati in tempo reale restano con «Orari programmati statici». Il tabellone si aggiorna quando compare, quando l'app torna in primo piano, cambiando stazione e trascinando in basso o con «Aggiorna», mai da solo.
- **Risultati del pianificatore**: per una ricerca da tre ore prima a tre ore dopo adesso, ritardo, soppressione e binario di ogni treno elencato, con una sola richiesta per la stazione di partenza. Se le fonti non rispondono i risultati restano quelli dell'orario incluso.
- **Dettaglio del treno**: lo stato della corsa («In ritardo di 7 minuti», «In orario», «Soppresso», «Parzialmente soppresso»), partenza prevista e binario alla tua stazione, «Ultimo rilevamento», e tutte le fermate con orario programmato, previsto o effettivo, ritardo, binario, fermate superate e saltate. Conta lo stato del treno stesso, non quello della riga da cui è stato aperto; la corsa di ieri con lo stesso numero non viene mai mostrata. La stella di «I miei treni» resta com'è.
- **I miei treni**: lo stato di oggi dei primi 8 treni salvati che partono in quella finestra, chiesto un treno alla volta e solo mentre la scheda è visibile.
- **Impostazioni**: nuovo interruttore «Dati in tempo reale», attivo per impostazione predefinita; spento, l'app non fa alcuna richiesta di rete e torna ai soli orari inclusi. Sotto, l'elenco delle fonti (ViaggiaTreno, RFI, Italo) con quello che riceve ciascuna.
- Solo in primo piano, nessuna richiesta in background o a intervalli: le richieste in corso si annullano quando l'app va in background o la schermata si chiude. Valgono le regole del nucleo (una richiesta per risorsa ogni 30 secondi, pausa al primo rifiuto, indicata con «ViaggiaTreno in pausa fino alle …»). I valori restano visibili 5 minuti, poi la riga torna all'orario incluso fino al prossimo aggiornamento. Il Watch continua a ricevere solo l'orario programmato.
- VoiceOver legge lo stato in tempo reale dentro la frase di ogni riga e di ogni fermata. Testi in tutte le 17 lingue, quelli di Android dove esistono.
- [PRIVACY.md](PRIVACY.md): nuova sezione «Dati in tempo reale» dell'iPhone, con fonti, momenti delle richieste, contenuto di ciascuna (numero del treno, codice della stazione, identificativo RFI, codice Italo), nessun identificativo, account o posizione, indirizzo IP visto dalle fonti, dati solo in memoria e la chiave `it.binario.ios.live-data-enabled.v1`. Le note di App Store dicono che l'app contatta fonti ferroviarie pubbliche senza dati dell'utente; la risposta App Privacy resta «Dati non raccolti».

## In sviluppo — Dati in tempo reale su iPhone: il nucleo

- **Dati in tempo reale su iPhone, prima parte**: l'iPhone sa chiedere lo stato di un treno per numero e le partenze di una stazione alle stesse fonti pubbliche di Android e nello stesso ordine: ViaggiaTreno e Italo per il numero, monitor RFI, Italo e ViaggiaTreno per le partenze. Ogni dato prende la fonte migliore, come su Android: RFI per il binario, l'operatore del treno per ritardo e soppressione. L'orario incluso nell'app resta la riserva.
  - Valgono le regole di Android: un ritardo conta solo con una rilevazione degli ultimi 5 minuti, «RITARDO» senza minuti resta in ritardo senza orario previsto, una fermata soppressa rende il treno parzialmente soppresso.
  - Solo HTTPS, con un User-Agent onesto di Binario per iPhone, senza cookie né cache; l'unico passaggio in chiaro è quello che ViaggiaTreno impone sul proprio dominio, lo stesso permesso su Android.
  - Una richiesta per risorsa ogni 30 secondi (20 per RFI); al primo rifiuto la fonte va in pausa almeno un minuto, o per il tempo che chiede, e non viene richiesta prima.
- **Ancora nessuna richiesta di rete**: le schermate useranno questi dati nel passo successivo, con l'informativa privacy aggiornata.

## In sviluppo — «Partenze» su iPhone

- **Partenze** su iPhone, nella seconda scheda come su Android: scegli una stazione e vedi i prossimi treni in partenza dagli orari programmati inclusi nell'app, senza rete.
  - Oggi da adesso, più le corse di ieri che partono ancora dopo mezzanotte (orari oltre le 24:00); i treni del giorno dopo no.
  - Ogni riga: orario, treno come nei risultati del pianificatore («REG 2153»), destinazione (l'ultima fermata del treno), operatore e bici a bordo quando l'orario le pubblica. Il binario non c'è, perché gli orari inclusi non lo riportano.
  - 20 treni alla volta, «Più tardi» per i successivi; un treno partito da un minuto sparisce.
  - Quando oggi non parte più nulla: «Non ci sono più treni oggi: primo domani alle …», come su Android.
  - Un tocco apre il dettaglio del treno; la stella lo salva in «I miei treni» con questa stazione come partenza.
  - Con le righe dell'orario programmato compare «Orari programmati statici. Non sono dati in tempo reale.» e VoiceOver legge ogni riga come una frase: orario, treno, destinazione, binario.
  - Pullman e bus sostitutivi seguono la regola del pianificatore. Nessuna affidabilità per riga, perché il tabellone Android non la mostra.
- **Pronto per i dati live**: il tabellone passa da un fornitore che oggi è solo l'orario incluso e che resterà la riserva di una fonte live; ogni riga può già portare ritardo, soppressione, binario effettivo e fonte. Nessuna richiesta di rete in questa versione.
- **Statistiche di rete**: la documentazione iPhone ora dice che dal 9 novembre la raccolta comprende anche i treni Trenord, che entrano nel file con gli aggiornamenti settimanali.
- [PRIVACY.md](PRIVACY.md): l'ultima stazione di Partenze resta nelle `UserDefaults` dell'iPhone, con la chiave `it.binario.ios.departures-station.v1`. Testi in tutte le 17 lingue.


## In sviluppo — «I miei treni» pieno non cancella più nulla su Android

- **Android, «I miei treni»**: con 12 treni già salvati, il cuore del dettaglio non elimina più in silenzio il più vecchio, nome personalizzato compreso. Il treno non viene aggiunto e un avviso dice «Puoi salvare al massimo 12 treni.», come su iPhone. Il cuore toglie sempre un treno già salvato, anche con la lista piena, e la richiesta del nome personalizzato compare solo quando un treno viene davvero aggiunto.

## In sviluppo — «I miei treni» e «Affidabilità dei miei treni» su iPhone

- **I miei treni** su iPhone, in una scheda nuova dopo «Tratte»:
  - la stella nel dettaglio di un treno lo salva, fino a 12 treni, solo su questo iPhone; la stella piena lo toglie, come uno scorrimento nella lista;
  - un tocco riapre il dettaglio del treno, con l'orario programmato del giorno in cui è stato salvato;
  - come su Android un treno è il suo numero con la stazione di partenza; i numeri come «RE_11 - 2153» di Trenord si salvano, i nomi di linea no;
  - con 12 treni il tredicesimo non entra finché non se ne toglie uno, invece di cancellare il più vecchio.
- **Affidabilità dei miei treni** sotto la lista: per i primi 8 treni la puntualità di rete all'ultima fermata, per esempio «Arrivo a Pavia: in orario 24 volte su 28 · ritardo medio 6 min». Valgono le regole di Android: la soglia del treno (5 o 15 minuti), almeno 5 rilevazioni, nessun treno indovinato fra più treni con lo stesso numero. Senza il file delle statistiche la sezione non compare. Le corse registrate sul telefono e l'andamento delle ultime 4 settimane restano solo su Android, perché l'iPhone non registra corse.
- [PRIVACY.md](PRIVACY.md): i treni salvati restano nelle `UserDefaults` dell'iPhone, con la chiave `it.binario.ios.favourite-trains.v1`.

## In sviluppo — Treni notturni sulla mappa

- **Mappa dei treni in movimento**: un treno notturno partito ieri prima delle 20 conserva la sua vera stazione di partenza. Prima, al mattino, la mappa leggeva l'orario di ieri solo dalla sera e lo faceva partire dalla prima fermata dopo le 20. Ora il tocco sul treno cerca il numero dalla stazione d'origine giusta, e un ritardo rilevato alla partenza viene associato al treno. L'orario di oggi, letto da mezzanotte, non cambia.

## In sviluppo — Correzioni dopo la revisione di novembre

- **Avviso serale dei lavori**: le date di inizio non si perdono più. «nei giorni 14, 15, 21 e 22 novembre», «dalle 22.50 del 14 alle 5.50 del 15 novembre» e «dal 1° al 3 dicembre» ora partono dal primo giorno (prima risultavano 22, 15 e 3), quindi «Date citate» è corretta e l'avviso arriva la sera prima dell'inizio, non a lavori già cominciati.
- **«Più puntuale»** su Android e iPhone:
  - confronta solo treni con la stessa soglia: un regionale in orario entro 15 minuti non viene più proposto al posto di un Frecciarossa misurato a 5, né viceversa (servono anche biglietti diversi);
  - conta le soppressioni: il treno proposto deve anche circolare ed arrivare in orario in almeno 20 punti percentuali di giorni in più, quindi un treno spesso soppresso non viene proposto perché puntuale nei giorni in cui circola.
- **Alexa, «qual è il treno più puntuale»**: l'ordine e la quota detta contano i giorni di soppressione come giorni non in orario («in orario 5 volte su 10» per un treno sempre puntuale ma soppresso un giorno su due).
- **Riquadro Wear «Prossimo treno»**: quando sono partiti tutti i 24 treni memorizzati non dice più «Nessun altro treno oggi» mentre rilegge l'orario; mostra la tratta finché non ha i treni successivi.

## In sviluppo — Affidabilità dei miei treni, il treno più puntuale su Alexa, «Più puntuale» su iPhone

- **Affidabilità dei miei treni** in Per te, dopo «I miei treni». Per ogni treno preferito, fino a 8, mostra:
  - la puntualità di rete all'ultima fermata («In orario 20 volte su 30 · ritardo medio 6 min»), con la soglia del treno e almeno 5 rilevazioni;
  - le corse registrate su questo telefono negli ultimi 30 giorni («Registrato su questo telefono: in orario 8 volte su 10»);
  - «In miglioramento» o «In peggioramento» quando le ultime 4 settimane distano almeno 15 punti dalle 4 precedenti.
  Il calcolo è lo stesso del riepilogo settimanale. Con `binario.punctualityStats=false` restano solo i dati del telefono.
- **Alexa**: «qual è il treno più puntuale per casa lavoro?».
  - Fra i treni della tratta salvata che di solito partono nelle prossime tre ore, indica i tre più puntuali alla destinazione, con l'orario abituale e quante volte arrivano in orario.
  - Su Echo Show c'è l'elenco nella schermata «Puntualità».
  - Richiede l'account collegato e non nomina mai le stazioni.
- **iPhone**: la riga «Più puntuale» nei risultati, con le stesse regole di Android; il tocco apre quel treno.

## In sviluppo — Avviso serale dei lavori, «Più puntuale» nei risultati, pulizia degli adattatori

- **Lavori di domani sulle tue tratte** (facoltativo, spento in partenza, in Notifiche e sveglie): verso le 19 una notifica se un avviso RFI di lavori o interruzioni che nomina una stazione di una tratta salvata vale il giorno dopo. Ogni avviso è notificato una sola volta, la sera prima del primo giorno; al più una notifica per sera, mai fra le 22 e le 7; il tocco apre Per te. Legge solo il feed infomobilità di RFI, solo con l'interruttore acceso.
- **«Più puntuale»** sotto un treno diretto dei risultati che arriva in orario meno di 7 volte su 10: un altro treno diretto della stessa lista, entro 30 minuti, ancora da partire, in orario almeno 20 punti in più, per esempio «Più puntuale: Regionale 2157 delle 08:12 · in orario 9 volte su 10»; il tocco porta a quel treno. Statistiche incluse nell'app, nessuna richiesta di rete.
- **Pulizia**: gli adattatori STA e SNCF leggono solo i Trip Updates; avvisi e posizioni, che nessuna schermata usava, sono stati tolti. La raccolta serale della puntualità cade prima di mezzanotte anche con l'ora legale. Un test dei risultati che a volte falliva sotto carico ora aspetta le ricerche ancora in corso.

## In sviluppo — Statistiche di puntualità: tutta la giornata e i treni Trenord

- **Raccolta di tutta la giornata**: ogni run chiede tutti i treni partiti oggi e non ancora noti, non solo quelli delle ultime 4 ore. Prima si perdevano le serate, perché ViaggiaTreno dimentica il giorno prima a mezzanotte (alla run delle 00:58 del 9 novembre 585 treni su 602 risultavano sconosciuti), e intere fasce del giorno, perché GitHub salta molte run programmate (l'8 novembre ne sono partite 4 su 12). Il workflow ora prova ogni ora e una volta poco prima di mezzanotte. Ogni treno resta chiesto una sola volta, a 2 richieste al secondo.
- **Treni Trenord** nelle statistiche, da ViaggiaTreno come quelli Trenitalia: circa 2.480 treni al giorno in Lombardia, comprese le linee Ferrovienord da Milano Cadorna. Il pack Trenord usa già i codici ViaggiaTreno delle stazioni.
- **App Android**: le statistiche di un treno Trenord si trovano anche quando il pack lo numera «RE_11 - 2153»; prima il numero diventava 112153.

## In sviluppo — Più presto e più tardi, binario abituale, collegamenti da una stazione, lavori sulle tratte, mappa dei treni, scorciatoie; iPhone, Apple Watch e Alexa

- **Risultati**: «Più presto» sopra e «Più tardi» sotto l’elenco aggiungono fino a 6 soluzioni senza buchi né doppioni, per i treni diretti e per i viaggi con cambi; «Nessun treno fino alle HH:mm» quando la prima soluzione arriva dopo una finestra vuota. Una partenza RFI con «RITARDO» senza minuti resta «In ritardo (minuti non pubblicati)», mai «in orario».
- **Statistiche di rete** (file incluso, nessuna richiesta di rete): «Di solito al binario 12 (8 volte su 10)» nei risultati e nel dettaglio quando nessuna fonte pubblica il binario (almeno 5 giorni e 60%), osservazione sul giorno della settimana nella scheda Affidabilità e nell’intestazione dei risultati (almeno 4 rilevazioni e 15 punti di differenza), «83% in orario» nei widget Tratta e I miei treni (disattivabile per widget) e «In orario N volte su M» sull’orologio Wear OS, solo verso le app Wear che lo dichiarano.
- **Stazioni**: «Collegamenti diretti» dal tabellone, per i prossimi 7 giorni (corse, prima e ultima partenza, viaggio più rapido; anche metro, tram e funicolari vicine), con un tocco per cercare la tratta; scheda «Assistenza Sala Blu» per le stazioni italiane con la pagina RFI e i numeri 800 90 60 60 e 02 32 32 32 (solo compositore, nessuna chiamata automatica).
- **Lavori e interruzioni sulle tue tratte** in Per te e nel tabellone della tratta: avvisi RFI (e Trenitalia nelle build personali) che nominano per intero una stazione della tratta, con le date del testo; scioperi esclusi.
- **Treni in movimento**: mappa a schermo intero dal tabellone della tratta o della stazione con le posizioni stimate dagli orari offline, spostate dal ritardo già caricato dal tabellone (marcatore pieno) o solo da orario (vuoto); «posizione stimata, non GPS», ricalcolo ogni 30 secondi solo a mappa aperta, nessuna richiesta ferroviaria in più.
- **Sistema**: in «Attività in background» quando gira ogni controllo; riepilogo settimanale facoltativo dei treni preferiti (domenica verso le 19, spento in partenza, calcolato sul telefono); fino a 4 scorciatoie dell’icona per le tratte salvate e il riquadro «Prossimo treno» nelle Impostazioni rapide, dall’orario offline.
- **iPhone e Apple Watch**: affidabilità nei risultati, puntualità della tratta, ordine «Più affidabili» e nuovo dettaglio treno con distribuzione, binario abituale e giorni della settimana; bici a bordo e collegamenti con gli aeroporti; «Mostra su Watch» per fissare una tratta salvata, aggiornata anche con l’iPhone in background. La copia iPhone delle statistiche è generata dal file Android a ogni generazione del progetto (`BINARIO_IOS_PUNCTUALITY_STATS=false` la esclude).
- **Alexa**: «quanto è puntuale il treno 9558» (senza collegare l’account) e «quanto è puntuale la mia tratta casa lavoro», con la schermata «Puntualità» su Echo Show e la frase «Sono statistiche non ufficiali di Binario su dati ViaggiaTreno».
- **Pulizia**: rimossi gli adattatori e gli elenchi di fornitori che la produzione non raggiunge (DB Timetables, GTFS-RT svizzero, gateway Trenitalia autenticato, credenziali esterne).
- Testi in tutte le 17 lingue con i plurali CLDR; [PRIVACY.md](PRIVACY.md) e `docs/store/PLAY_DATA_SAFETY.md` descrivono riepilogo, scorciatoie, riquadro, mappa dei treni, avvisi dei lavori, Sala Blu, Mostra su Watch e la puntualità su Alexa.

## In sviluppo — Ritardi in dettaglio, puntualità di tratte e stazioni, bici a bordo, aeroporti, indennizzo per ritardo

- **Distribuzione dei ritardi** nella scheda «Affidabilità ultimi N giorni» del dettaglio treno: senza ritardo, 1–5, 6–15, 16–30 e oltre 30 minuti alla fermata misurata (la prima fascia si chiama «Senza ritardo» per non confonderla con «in orario», che vale 5 o 15 minuti), con una barra e righe con percentuale e conteggio, più «Ritardo massimo: N min» quando il file lo pubblica; TalkBack legge il blocco in una frase.
- **Puntualità della tratta** sotto l’intestazione dei risultati e nel tabellone della tratta attiva: «Questa tratta: 83% in orario · ritardo medio 2 min · 12 treni · 109 corse osservate in 30 giorni», su tutti i treni delle statistiche che fermano all’origine e poi alla destinazione, ciascuno con la sua soglia (5 o 15 minuti), ritardo medio pesato sulle rilevazioni. «Partenze più puntuali: 6–9 · meno puntuali: 18–21» per fascia oraria di partenza solo con almeno 20 rilevazioni per fascia e 5 punti di differenza; nulla sotto 20 rilevazioni.
- **Puntualità degli arrivi** nel tabellone di stazione: quota in orario, ritardo medio e corse osservate di tutti i treni in arrivo, fino a tre treni più spesso in ritardo lì (almeno 5 rilevazioni) e la soglia di «in orario».
- **File delle statistiche**: ogni fermata può avere due campi in più (ritardo massimo, minuto programmato anche oltre le 24:00 o `null`); i file con 5 campi restano validi e un valore non plausibile è ignorato senza perdere la fermata. Test JVM su bande, tratte con soglie miste, fasce orarie, stazioni e testi in tutte le lingue.
- **Bici a bordo**: il campo GTFS `bikes_allowed` delle corse (1 ammessa, 2 non ammessa) arriva ai treni diretti, alle tratte dei viaggi con cambi («Resta a bordo» compreso: ammessa solo se ogni corsa lo dice) e al dettaglio; schede dei risultati, dettaglio del viaggio e dettaglio del treno mostrano la bici con «Bici ammessa» o «Bici non ammessa» solo quando l’orario lo pubblica (oggi STA, ARST, metropolitana ARST, HŽPP; vuoto in Trenitalia ed EAV, assente in Trenord). Il valore del pack Italo (1 su ogni corsa) non è mostrato: Italo ammette solo bici pieghevoli in sacca, quindi resta ignoto e senza collegamento, perché Binario non conosce una pagina Italo sulle bici. Senza il dato nulla sulle schede e, nel dettaglio di un treno Trenitalia o Trenord, «Bici a bordo: condizioni di …» apre la pagina ufficiale già in Biglietti e assistenza. Mai dedotto dalla categoria.
- **Collegamenti con gli aeroporti** nel pianificatore, sotto recenti e preferiti: Roma Termini ↔ Fiumicino Aeroporto, Milano Centrale e Milano Nord Cadorna ↔ Malpensa Aeroporto, Bari ↔ Bari Aeroporto, Palermo Centrale ↔ Punta Raisi, Cagliari ↔ Elmas Aeroporto, Trieste Centrale ↔ Trieste Airport, Torino Porta Susa ↔ Caselle Torinese Aeroporto. Un tocco compila il modulo e cerca verso l’aeroporto, il pulsante accanto cerca il ritorno; nomi come nel catalogo, nessun marchio del servizio (gli orari non lo riportano). Una voce compare solo se il suo pack è nella build; un test JVM trova con i pack di una build store un treno diretto in entrambi i sensi in un giorno feriale.
- **Indennizzo per ritardo** nella scheda Info del dettaglio, sotto lo stato: solo quando la fonte conferma l’arrivo reale all’ultima fermata mostrata con almeno 60 minuti di ritardo, nominata nel testo (dopo una ricerca per numero è il capolinea), «Arrivato a Roma Termini con 75 min di ritardo: per il regolamento UE 2021/782 potresti avere diritto a un indennizzo del 25% del prezzo del biglietto (50% da 120 minuti)», i limiti (importi sotto 4 €, cause esterne; lo sciopero del personale non lo è; treni regionali secondo il contratto di servizio) e, per Trenitalia, Italo e Trenord, «Rimborsi, indennizzi e reclami» sul sito ufficiale. Mai su orari programmati, stime, dati dimostrativi, treni soppressi o fermate saltate.

## In sviluppo — Affidabilità media di ogni treno (statistiche non ufficiali di rete)

- **Nei risultati**: sotto ogni treno diretto «In orario 27 volte su 30 · ritardo medio 3 min» (più «soppresso N volte» quando capita) all’arrivo alla destinazione cercata; per i viaggi con cambi il treno più debole, con il suo nome, e «Coincidenza a Milano Centrale riuscita 25 volte su 30», calcolata dalla distribuzione dei ritardi del treno in arrivo alla stazione del cambio rispetto al margine fra arrivo e partenza programmati meno il tempo di cambio del pianificatore offline (10 minuti nei grandi nodi, 5 altrove, il tratto a piedi quando c’è), con interpolazione lineare fra le soglie. Nulla sotto 5 rilevazioni alla fermata considerata; un treno soppresso in tutti i giorni osservati mostra solo «Soppresso 12 volte su 12» e finisce in fondo a «Più affidabili».
- **Filtro «Più affidabili»** accanto a Tutti, Diretti, Meno cambi, Più veloce e Meno a piedi: ordina per probabilità di arrivare in orario (quota in orario alla destinazione per la quota di giorni non soppressi; per i viaggi il prodotto di treni e coincidenze), poi ritardo medio e partenza; le soluzioni senza statistiche seguono in ordine di partenza. Gli altri filtri non cambiano.
- **Dettaglio treno, scheda Info**: «Affidabilità ultimi N giorni» con quota in orario e ritardo medio all’ultima fermata mostrata (o al capolinea), giorni osservati e soppressioni, la fermata dove il ritardo cresce di più (N sono i giorni davvero coperti, meno di 30 nelle prime settimane), la soglia di «in orario» del treno e «Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al …». In orario entro 5 minuti per FR, FA, FB, EC, ES, ESC, EN e Italo, entro 15 per gli altri, come Treniamo.
- **Dati**: file aggregato incluso nell’app (`assets/punctuality/stats_v1.json.gz`, schema `binario-punctuality-v1`) prodotto dalla pipeline CI; letto una volta, fuori dal thread principale, con limiti di dimensione, e ignorato se assente, corrotto o di un altro schema. Stesso numero con origine diversa = treno diverso; le stazioni si legano ai codici ViaggiaTreno tramite il codice ferroviario del catalogo curato, come negli adapter live. Nessuna richiesta di rete, nessun dato personale; niente su dati dimostrativi. `-Pbinario.punctualityStats=false` esclude file e schermate da qualunque build.
- Testi in tutte le 17 lingue con i plurali CLDR; test JVM su lettura del file (valido, assente, corrotto, troppo grande, schema diverso), soglie, interpolazione, origini diverse, coincidenze, ordinamento e frasi dei risultati. [PRIVACY.md](PRIVACY.md) e `docs/store/PLAY_DATA_SAFETY.md` descrivono il file.

## In sviluppo — Audit indipendente finale: correzioni su tutte le piattaforme (3 novembre 2026)

Sette audit indipendenti (dati, pianificatore, interfaccia, attività in background, Wear/Companion/Apple, Alexa, strumenti e CI) hanno segnalato 103 problemi; ciascuno è stato verificato sul codice e corretto con test di regressione, tranne la rimozione del codice inutilizzato dei vecchi adattatori, rimandata perché ancora citato da test e documentazione.

- **Pianificatore**: due treni diversi con lo stesso numero in pack diversi (EC 41 e Centovallina 41) non vengono più uniti in «Resta a bordo»; le corse lunghe (Milano → Palermo) non sono più tagliate dall'orizzonte di 18 ore; attese ai cambi oltre due ore scartate quando un'alternativa arriva prima, con «Attesa di N minuti» sulla scheda; tempo di cambio nei grandi nodi rispettato anche a piedi; dati live solo della corsa di oggi; «Coincidenza a rischio/persa» anche nell'elenco dei risultati; niente doppioni; il primo treno del mattino dopo anche nelle ricerche pomeridiane. Un controllo di plausibilità su 180 ricerche reali gira con i test.
- **Dati e tabelloni**: i treni in ritardo restano sul tabellone finché l'orario previsto è davanti; risultati offline mostrati subito e poi arricchiti dalle fonti live; ultima tratta e ultimo tabellone riusciti mostrati come «Copia salvata» senza rete; fonti in pausa indicate con l'orario di ripresa; orari sempre con cifre latine (arabo, birmano, hindi); collegamenti degli avvisi in chiaro limitati ai siti FS.
- **Interfaccia**: un orario programmato non è mai indicato come «live» (stati Live, Orario programmato, Copia salvata su ogni superficie); Partenze segue l'ora corrente; ogni scheda conserva il proprio stato e la ricerca sopravvive alla chiusura del processo; Annulla dopo le eliminazioni; inserimento stazioni più rapido; correzioni di accessibilità e traduzioni.
- **Widget, sveglie e notifiche**: avvisi pendolare e sveglie fermata ripristinati senza doppioni dopo riavvio o aggiornamento; pulsante «Consenti sveglie esatte»; elenco «Sveglie fermata attive»; nuovo canale «Cambi del viaggio seguito» (soppressione, cambio binario, coincidenza a rischio); GPS a basso consumo lontano dalla fermata; riquadro «Attività in background» nelle impostazioni.
- **Wear OS, Companion, iPhone e Apple Watch**: complicazioni con conto alla rovescia, aggiornamento richiesto dal telefono o dall'iPhone, solo treni ancora da partire, layout per schermi rotondi, accessibilità del Companion, testi di posizione localizzati su iOS.
- **Alexa**: autenticazione HTTP Basic del token, finestra di 60 secondi per i tentativi ripetuti del refresh token, limiti per utente invece che per IP, log senza dati personali, riconoscimento dei nomi delle tratte, invocazione italiana «binario treni», nuovi locale `en-IN` ed `es-US`, sessione della dashboard di 24 ore (massimo 7 giorni).
- **Strumenti e CI**: pin degli archivi verificati sui byte ricevuti, log dei feed neutralizzati nei workflow, permessi minimi e credenziali non persistenti nei job, parser XML senza entità, licenza ed evidenza per ogni pack con crediti generati nell'app, soglia unica di validità di 30 giorni applicata alle sole release.
- Decisioni ancora del titolare e azioni manuali in [SOURCE_POLICY.md](docs/data-sources/SOURCE_POLICY.md) (repository pubblico e pack personali), `docs/store/PLAY_SUBMISSION.md` (licenze da confermare) e `alexa-companion/docs/ALEXA_SETUP.md` (console Alexa).

## In sviluppo — Tempo reale di metro e tram, bici in condivisione vicino alle stazioni

- **Tabellone delle fermate di metro e tram**: una fermata scelta con il badge «M», «T» o «F» mostra partenze e arrivi del pack della sua rete (tutti i binari con lo stesso nome entro 400 m), con la linea e il capolinea; un tabellone di stazione ferroviaria usa esattamente le fonti di prima.
- **Tempo reale dei tram di Roma e di Mestre** (solo build personali, come i pack): Trip Updates GTFS-Realtime di Roma Servizi per la Mobilità (tram; il feed non pubblica le metropolitane) e di ACTV (tram T1 e T2) sui tabelloni delle fermate e sui tratti in tram dei viaggi con cambi: orario previsto, ritardo, soppressioni, «Tempo reale Roma Servizi per la Mobilità, aggiornato alle …». GTT Torino (fermate senza `stop_id`) e Regione Toscana (senza la tramvia) verificati ma non collegati. Solo identità esatte (pack, `trip_id`, giorno, `stop_id`); feed letto solo con una fermata o un tratto della rete a schermo, cache di 30 secondi e circuit breaker.
- **Bici in condivisione vicino a <stazione>** nel tabellone di una stazione e nel dettaglio di un viaggio (partenza e arrivo), su richiesta: stazioni di bike sharing con bici, e-bike e posti liberi o mezzi liberi con tipo e batteria entro 300 m dalle coordinate della stazione (mai la posizione del dispositivo), ora dei dati, licenza e collegamento al gestore. GBFS di BikeMi e Verona Bike (NLOD 2.0), nextbike Bergamo e Fa.Mo.Se Senigallia (CC0) in ogni build; Bird Roma solo nelle build personali.
- Strumento di scoperta (`tools/transit_source_discovery.py`): token `gtfsrt:`, `rtstatic:`, `gbfs:` e `ckan:` per verificare dal CI feed GTFS-Realtime (decoder protobuf a libreria standard), GBFS e portali open data; campioni ridotti per i test.
- Fonti, verifiche CI, licenze e feed non utilizzabili in [SOURCE_POLICY.md](docs/data-sources/SOURCE_POLICY.md); host e richieste nell'[informativa privacy](PRIVACY.md) e in `docs/store/PLAY_DATA_SAFETY.md`.
## In sviluppo — Conto alla rovescia delle fermate, avviso di arrivo e stima dalla posizione (Android)

- **«Scendi tra N fermate»** nella notifica del viaggio seguito, nella notifica fissa della sveglia GPS e nel suo pannello; all’ultima fermata intermedia «Scendi alla prossima fermata: Monza alle 08:56» con il binario quando noto. L’avanzamento viene dalle fermate confermate dalla fonte (o dall’ultimo rilevamento), poi dalla posizione del telefono sulla linea fra le fermate quando una sessione GPS segue il treno, infine dagli orari previsti o programmati; la posizione può solo far avanzare il conto rispetto al dato live, mai arretrarlo. Nelle ultime tre fermate la notifica del viaggio seguito si aggiorna subito dopo l’orario previsto della fermata seguente (mai prima di due minuti) invece di aspettare i 15 minuti.
- **«Avvisami quando sta arrivando»** per i treni preferiti (scheda Info del dettaglio): stazione scelta fra le fermate del treno (predefinita l’origine salvata del preferito) e anticipo di 5, 10 o 15 minuti. Controlli WorkManager ogni 15 minuti da circa 30 minuti prima del momento dell’avviso, ricalcolato con il ritardo live a ogni controllo, e un’esecuzione singola a quel momento quando cade prima del controllo successivo (nessuna sveglia esatta). Notifica «REG 2153 arriva a Monza tra ~10 min» con ritardo e binario, canale «Treni in arrivo»; mai tra le 22 e le 7, al più una volta al giorno per treno. Si spegne togliendo il treno dai preferiti.
- **«Stima dalla tua posizione»** sotto la notifica del viaggio seguito nel dettaglio del treno, solo da 10 minuti prima della partenza fino all’arrivo: avvia lo stesso servizio in primo piano di tipo `location` della sveglia GPS (posizione precisa, stessa cadenza), che non suona e proietta in memoria ogni posizione sulla linea fra le fermate del catalogo. Mostra «Stima dalla tua posizione: circa +6 min, arrivo ~18:47», sempre come stima e accanto al dato ufficiale quando c’è («(fonte ufficiale: +4 min)»); ignora le posizioni con precisione peggiore di 200 m, tratta la sosta in stazione, in galleria tiene l’ultima stima con il suo orario e oltre 1,5 km dalla linea dice «Posizione fuori dal percorso». Con una sveglia GPS attiva sullo stesso treno seguito la stima arriva dalla sveglia. Si ferma 10 minuti dopo l’arrivo, con la fine del viaggio o con «Interrompi stima»; nulla viene salvato o inviato.
- Testi in tutte le 17 lingue, con varianti per i lettori di schermo; test per conto alla rovescia (live, orario, posizione), pianificazione e anticipo con i ritardi, proiezione e stima (sul percorso, sosta, fuori percorso, precisione scarsa, posizioni mancanti), fasce silenziose e deduplica. Nessun host, SDK o permesso nuovo; `ACCESS_BACKGROUND_LOCATION` resta assente. [PRIVACY.md](PRIVACY.md) e `docs/store/PLAY_DATA_SAFETY.md` descrivono i nuovi usi della posizione e i dati locali.
## In sviluppo — Pianificazione come Moovit: resta a bordo, preferenze di viaggio, accessibilità, ultimo treno

- **Resta a bordo**: nel calcolo offline le corse dello stesso `block_id` (stesso pack e giorno di servizio) e lo stesso numero di treno che prosegue in un altro pack da una stazione condivisa o equivalente (arrivo ≤ partenza, sosta fino a 30 minuti, nessuna inversione di marcia) sono un’unica corsa: nessun cambio contato, nessun tempo di cambio, una tratta con la nota «Resta a bordo: il treno prosegue come …». Sui pack inclusi: EC 88, RJX 186 e REG/REX 1826 fra STA e ÖBB a Brennero diventano viaggi diretti fino a Innsbruck Hbf (prima un cambio a Brennero o un tratto a piedi da Innsbruck Centrale); la seconda copia del treno può partire anche da una fermata intermedia della prima; `block_id` con corse consecutive in STA (2 blocchi), ÖBB (1.484), Svizzera (543), AMT Genova (22), Genova–Casella (4) e, nelle build personali, GTT (143) e ANM (29); SNCF usa un blocco per corsa.
- **Preferenze di viaggio** (Impostazioni › Preferenze di viaggio, anche da «Filtri»): velocità a piedi e tempo massimo per tratto a piedi entrano nella generazione dei tratti a piedi (distanza / velocità + 2 minuti; senza preferenze il calcolo resta a 400 m e 4,5 km/h), «Preferisci» apre i risultati su «Tutti», «Meno cambi» o il nuovo filtro «Meno a piedi» e ordina le parità, i tipi di servizio preferiti salgono a parità e quelli evitati escono dal calcolo e dai treni diretti mostrati (con il conteggio dei treni nascosti). Salvate sul dispositivo e nel backup locale (campo `travel` facoltativo); un cambio ripete la ricerca mostrata.
- **Percorsi accessibili in sedia a rotelle**: lettura di `wheelchair_boarding` (fermate, con eredità dalla stazione madre) e `wheelchair_accessible` (corse); il filtro tiene solo viaggi con corse, salite e discese indicate accessibili e tratti a piedi fra punti accessibili, mai con dati mancanti, e segnala «Dati di accessibilità non disponibili per questa zona». Simbolo della sedia a rotelle con etichetta su stazioni, corse e viaggi accessibili. Copertura dei pack: fermate accessibili in Italo (118 su 128), ARST (26 su 32), metropolitana ARST (40 su 40), Milano ATM (398 su 854) e GTT (393 su 395, build personali); corse accessibili in Italo (528 su 543), ARST (108 su 117), metropolitana ARST (84 su 507), GTT (8.644 su 11.612), STA (3.470 su 4.061, ma senza dati sulle fermate) e HŽPP (20.851 su 43.643, senza dati sulle fermate); Trenitalia, EAV, Roma, Venezia e Napoli hanno le colonne vuote o a 0, gli altri pack non le hanno.
- **Ultimo di oggi**: chip sull’ultima partenza del giorno di servizio secondo gli orari offline nei risultati (origine → destinazione), nel tabellone della tratta e in quello di stazione (per capolinea), e «Non ci sono più treni oggi: primo domani alle HH:mm» dopo l’ultima. Quando la finestra di ricerca supera la mezzanotte e non resta altro da trovare, il calcolo aggiunge la prima soluzione del giorno dopo con le stesse connessioni, senza rileggere i pack. I ritardi in tempo reale restano quelli della fonte; ordine, priorità e abilitazione delle fonti invariati.
- Stringhe in tutte le 17 lingue; test su fixture per blocchi, continuazioni fra pack, velocità a piedi, tipi evitati, accessibilità, ultimo e primo treno, preferenze e backup.

## In sviluppo — 17 lingue su Android e Wear

- **Dodici nuove lingue** per l’app Android e Wear OS: arabo, hindi, indonesiano, giapponese, coreano, birmano, portoghese, russo, thai, turco, vietnamita e cinese semplificato, accanto a italiano, inglese, tedesco, francese e spagnolo. Tutte le stringhe e i plurali hanno le quantità CLDR richieste dal lint (arabo zero/one/two/few/many/other, russo one/few/many/other, portoghese one/many/other, lingue senza plurale solo other). Le traduzioni diverse dall’italiano sono scritte automaticamente e non sono ancora revisionate da madrelingua; l’italiano resta il riferimento.
- Cartelle: `values-in` per l’indonesiano (Android cerca `in` anche quando la lingua è `id`; `values-id` non verrebbe mai usata) e `values-zh-rCN` per il cinese semplificato; tag BCP-47 `id` e `zh-CN` in `locale_config.xml`. Dettagli e glossario in [docs/l10n/LOCALES.md](docs/l10n/LOCALES.md).
- **Selettore della lingua nell’app** (Android 13 e successivi, `LocaleManager`): «Sistema» e le 17 lingue con il proprio nome, ordinate per scrittura; resta il collegamento alle impostazioni di sistema. Prima di Android 13 la lingua segue quella del sistema.
- **Arabo da destra a sinistra**: Wear dichiara `supportsRtl`; tratte e orari composti nel codice («Roma → Torino», «17:40 → 22:05») restano un’unità leggibile da sinistra a destra anche in arabo; la freccia del tabellone stazioni è speculare e la linea della timeline del dettaglio segue il lato iniziale. Nessun carattere personalizzato: arabo, devanagari, thai, birmano e CJK usano i caratteri di sistema.
- Test: parità di chiavi, attributi, segnaposto e quantità per tutte le lingue (app e Wear), nessun BOM o carattere invisibile, cartelle allineate a `locale_config.xml`, testi dei widget per ogni lingua, selettore della lingua, caratteri di sistema; schermate di design `19-pianifica-ar`, `20-risultati-ar` e `21-pianifica-ja`.
- Limite noto: la dettatura interpreta solo frasi in italiano, inglese, tedesco, francese e spagnolo; nelle altre lingue i campi si compilano a mano.

## In sviluppo — Notifiche e stato in tempo reale

- **Notifica del viaggio seguito** (facoltativa, dal viaggio attivo «Segui viaggio» o da «Segui con notifica» nel dettaglio di un viaggio con cambi): ritardo, prossima fermata e binario, e per i viaggi con cambi il rischio della prossima coincidenza; da 30 minuti prima della partenza a 30 minuti dopo l'arrivo (prolungata dal ritardo noto), aggiornata ogni 15 minuti con WorkManager e con il comando «Aggiorna», rimovibile («Interrompi» o scorrendola) e chiusa da sola alla fine. Canale a bassa importanza, senza suono.
- **Avvisi sui treni seguiti e preferiti**: campanella nel dettaglio (Info) dei treni preferiti o seguiti e interruttore generale in Impostazioni → Notifiche e sveglie; ogni 15 minuti nel giorno del viaggio, con rete, controlla gli avvisi ViaggiaTreno per treno (`news/smartcaring`) e l'infomobilità Trenitalia e notifica solo quelli nuovi (identificativi già visti conservati per 7 giorni); mai tra le 22 e le 7.
- **Avvisi pendolare con lo stato del treno**: quando l'avviso suona, la notifica dice ritardo, binario o soppressione del treno di quell'orario, letti in pochi secondi dalle fonti della tratta; altrimenti «stato non disponibile». Allarmi esatti e approssimati invariati.
- **Scioperi in programma** nell'area avvisi dal feed RSS del calendario MIT (`scioperi.mit.gov.it`, HTTPS, cache di sei ore), solo settore ferroviario, con data e collegamento; se il feed non risponde resta il collegamento al calendario. **Promemoria alle 18** del giorno prima di uno sciopero ferroviario (attivo in partenza, solo con le notifiche consentite). Il formato del feed non è ancora verificato dai runner: il controllo `discovery` di `LiveProviderNetworkTest` lo riporta.
- **Coincidenza a rischio / persa** nei risultati e nel dettaglio dei viaggi con cambi quando i treni corrono ora o a breve: arrivo previsto più tempo di cambio contro la partenza prevista del treno successivo, dalla ricerca per numero treno esistente (che include i GTFS-Realtime dei pack); senza dati in tempo reale resta «solo orario programmato».
- **Puntualità dei treni preferiti**: una volta al giorno dopo l'arrivo previsto registra sul telefono il ritardo finale e quello alle fermate; la scheda «Puntualità» mostra ritardo medio, quota in orario e oltre 15 minuti, la fermata dove il ritardo cresce di più, la distribuzione e lo storico per data («Basate sulle tue osservazioni su questo telefono»). Storico locale fino a 365 giorni, incluso nell'export manuale.
- Nuova fonte `mit-strikes-rss` nel registry e nel catalogo; [l'informativa privacy](PRIVACY.md) e `docs/store/PLAY_DATA_SAFETY.md` descrivono l'host e le richieste in background.
## In sviluppo — Binari cambiati, filtri di Partenze, orari salvati, condivisione e collegamenti ufficiali

- **Binario cambiato**: modello dati con il binario dell’orario accanto a quello effettivo (`StationBoardEntry.scheduledPlatform`, `StopCall.scheduledArrivalPlatform`/`scheduledDeparturePlatform`, `TrainService.scheduledPlatform`, `TrainStop.scheduledPlatform`, campi aggiuntivi), letto da ViaggiaTreno (`binarioProgrammato*`/`binarioEffettivo*` di tabelloni, corse e ricerca per numero; «Binario Previsto» della pagina stazione), DB Timetables (binario pianificato e cambiato) e transport.opendata.ch (binario pianificato e `prognosis`). Il monitor RFI pubblica un solo binario e i GTFS inclusi non hanno un binario effettivo da confrontare. Partenze, risultati, dettaglio (intestazione, Info e fermate), tabelloni e widget Tratta, Tabellone stazione e I miei treni mostrano «Bin. 7 (era 5)» nel colore di attenzione e leggono «Binario cambiato: 7, previsto 5». Ordine, priorità e selezione delle fonti invariati.
- **Tipi di treno in Partenze**: «Tipi di treno» nel tabellone della tratta e in quelli di stazione nasconde Frecce/AV, Intercity/notte, Regionali/suburbani, Italo, Bus e Internazionali con la stessa mappatura delle impostazioni dei widget; la scelta resta sul dispositivo per stazione, i tipi ignoti non vengono mai nascosti.
- **Orari salvati**: «Salva orario» nei risultati, elenco «Orari salvati» in Per te con apertura in sola lettura («Salvato il … · non aggiornato»), rinomina ed eliminazione; inclusi nel backup locale JSON (campo facoltativo, i file precedenti restano importabili e non cancellano gli orari del dispositivo).
- **Condivisione** in testo semplice dell’elenco dei risultati, di un viaggio con cambi e di una fermata del treno.
- **Biglietti e assistenza** in Impostazioni: pagine ufficiali di Trenitalia, Italo, Trenord e delle ferrovie regionali incluse; «Apri sul sito ufficiale» per Trenitalia e Italo senza ricerca precompilata; «Ascolta il bollettino FS News» e treni garantiti Trenitalia/Trenord accanto agli avvisi di sciopero. Solo collegamenti aperti su tocco.
- **Stima CO₂** per soluzione (risultati, dettaglio treno e viaggio) con i fattori EEA 2020 per passeggero-km, calcolata sul dispositivo; fonte e valori nei crediti.
- Mappe offline: non implementate. Le condizioni d’uso di OpenFreeMap non autorizzano esplicitamente lo scaricamento massivo di tessere per l’uso offline dall’istanza pubblica.
- Dati e privacy invariati salvo i nuovi dati locali descritti in [l'informativa privacy verificabile](PRIVACY.md).

## In sviluppo — Ferrovie regionali, metropolitane e tram offline

- Nuovi data pack in `gtfs-transit/` (ogni build): ferrovie FAL, Ferrotramviaria, Ferrovie del Gargano e Mi.Co.Tra. Udine–Villach dai Regional Access Point del NAP, Genova–Casella dal GTFS AMT; metropolitane e tram di Milano (ATM), Genova (metro, funicolari, cremagliera e ascensori AMT), Firenze (tramvia) e Cagliari/Sassari (ARST). In tutto 4,9 MB in `gtfs-transit/` e 4,5 MB in `gtfs-personal/`, accanto ai 19,0 MB dei dodici pack del primo rilascio.
- Build personali (`-Pbinario.personalSources=true`, predefinito per l'APK debug, mai per release e Play): metro e tram di Roma, metro, tram e cremagliera GTT Torino (licenza non commerciale), metro, funicolari e tram ANM Napoli, tram ACTV di Mestre, ferrovie Cotral Roma–Lido e Roma–Viterbo (licenze non dichiarate o non verificabili), in `app/src/personal/assets/gtfs-personal/`.
- Convertitore NeTEx → GTFS scritto nel repository (`tools/netex_to_gtfs.py`, libreria standard), validato sul dataset Trenitalia del NAP contro il pack `trenitalia-gtfs`; acquisizione e build solo in CI (`GTFS Asset Integrity`, input `transit_packs`), registro `tools/transit_packs.json`.
- Viaggi con cambi: tratte Metro, Tram e Funicolare («Metro M1», «Tram 14») con icone e testi in 5 lingue; tratti a piedi fino a 400 m fra stazioni e fermate vicine (4,5 km/h più 2 minuti, configurabili), all'inizio, ai cambi e alla fine; un pack urbano entra in una ricerca solo se un estremo è una sua fermata o, fra due stazioni della sua città, quando i treni da soli non trovano soluzioni.
- Ricerca stazioni: fermate di metro, tram e funicolare con badge «M», «T», «F», dietro l'impostazione «Includi fermate di metro e tram nella ricerca» (attiva nelle build personali).
- Impostazioni › Info: crediti e licenza di ogni rete inclusa nella build.

## In sviluppo — Viaggi con cambi nei risultati Android

- **Risultati**: i treni diretti della ricerca (live e catalogo, con fonti, priorità e selezione invariate) e i viaggi con cambi calcolati offline sugli orari GTFS inclusi formano un solo elenco per orario di partenza, ciascuno con la propria fonte; un viaggio diretto che è lo stesso treno di un risultato diretto compare una volta sola, come treno. Righe «17:40 → 22:05», durata «4h 25min», treni, «Diretto» / «1 cambio» / «2 cambi» e «Binario —»; i filtri sono quelli del render: **Tutti**, **Diretti**, **Meno cambi**, **Più veloce**, con conteggio annunciato da TalkBack.
- **Dettaglio del viaggio con cambi**: tratte sulla timeline verde del dettaglio corsa, blocco «Cambio a … · Attesa di N minuti», treno (o bus sostitutivo, autobus, tratto a piedi) di ogni tratta, avviso orari programmati e fonte. Le righe dirette aprono come prima il dettaglio del treno.
- Avvisi dei viaggi con cambi sotto l'elenco: calcolo in corso, orari solo programmati, reti coperte, dati incompleti e motivo dell'assenza di soluzioni (con «Riprova» quando il calcolo non è disponibile).
- Intestazioni brevi «Roma → Torino» in Risultati e nei dettagli (città del catalogo o rimozione prudente di «Centrale», «Termini», «Porta Nuova»…; nomi completi letti da TalkBack e quando le due stazioni sono nella stessa città).
- Impostazioni: **Suggerisci una stazione** prima di «Informazioni» apre nel browser il modulo pubblico delle segnalazioni del progetto su GitHub con il titolo precompilato.

## In sviluppo — Widget Android con le impostazioni di Orario Treni

- Impostazioni per istanza per i widget **Tratta** e **Tabellone stazione**, salvate con la configurazione esistente: aggiornamento automatico disattivato per i nuovi widget, nelle fasce orarie scelte (ogni 15/30/60 minuti, Lun–Ven, tutti i giorni o giorni scelti, una o due fasce anche dopo la mezzanotte) oppure sempre; tipi di treno da nascondere (Frecce/alta velocità, Intercity/notte, regionali/suburbani, Italo, bus sostitutivi, internazionali — i tipi ignoti restano visibili); colonna binario, nome/categoria e titolo personalizzato. I widget già aggiunti mantengono l'aggiornamento ogni quindici minuti e le stesse corse finché non vengono modificati.
- Widget Tratta: «Mostra il ritorno dalle HH:mm» con il pulsante della direzione valido fino al cambio successivo, treni visibili fino all'arrivo segnati «In viaggio», corse delle prossime 3 o 6 ore o di tutto il giorno (orario conservato e leggibile offline, segnato come programmato).
- Widget Tabellone stazione: pulsante facoltativo «📍 Più vicina» che legge una sola posizione al tocco con il permesso in primo piano già usato dall'app, sceglie la stazione più vicina del catalogo e la salva nel widget; gli aggiornamenti automatici non leggono mai la posizione.
- Nuovo widget **I miei treni**: i treni salvati con il cuore del dettaglio, con nome personalizzato, prossima stazione o ultimo rilevamento, ritardo (anticipo in verde), binario, soppressione o variazione; solo i treni in viaggio (facoltativo), ordine per partenza o per nome, aggiornamento in fasce orarie; un tocco apre il treno. Una ricerca fallita lascia l'ultimo valore noto segnato con l'ora del dato.
- **I miei treni** in Per te sostituisce l'elenco dei treni monitorati: dopo il cuore l'app propone un nome, il menu permette di aprire, rinominare o rimuovere; nomi e corsa salvata entrano nel backup locale JSON (campi facoltativi, i backup precedenti restano importabili).
- Un solo lavoro WorkManager pianifica tutti i widget per il prossimo istante dovuto: fuori dalle fasce non resta nulla in coda. «Aggiornato alle HH:mm» in testata a ogni widget.
- I tabelloni di stazione conservano il tipo di servizio già pubblicato dalle fonti (`StationBoardEntry.serviceKind`), senza cambiare ordine, priorità o selezione delle fonti.
## In sviluppo — Orari ufficiali Trenitalia e Italo offline

- Due nuovi data pack embedded: `trenitalia-gtfs` (treni Trenitalia e bus di linea Trenitalia, valido fino all'11 dicembre 2027, 4,8 MB) e `italo-gtfs` (treni Italo e ItaloBus, calendario fino al 12 dicembre 2026, 49 KB), dagli orari NeTEx ufficiali del National Access Point italiano (dataset 1077621 e 1814124) nella conversione GTFS CC BY 4.0 di Clément Desouche, fissati al commit e allo SHA-256 dell'archivio; geometrie escluse. Crediti in Impostazioni › Info.
- Il calcolo offline dei viaggi con cambi e il tier statico delle tratte coprono ora Trenitalia e Italo (es. Domodossola → Milano mostra i regionali oltre agli EuroCity, Merano → Roma, Venezia → Cortina con ItaloBus); le fonti live restano prime e l'ordine delle fonti esistenti non cambia.
- Fermate Trenitalia legate al catalogo dal location id (94,7% delle stazioni, 98,8% con il nome), codici Italo da una tabella rivista (63 su 64).
- Nuova modalità `LegMode.BUS` per gli autobus di linea («Bus Trenitalia», «ItaloBus»), distinta dal bus sostitutivo; un passaggio treno–bus è un cambio. `TrainService.scheduledBus`, `JourneyLegRow.vehicle`/`isBus` e `JourneyOptionRow.hasBusLeg` per icone e filtri.
- Planner: una corsa presente in due pack è una sola corsa; i viaggi lunghi che partono nella finestra possono arrivare dopo (orizzonte di 10 h); i pack regionali che servono un solo estremo affiancano il pack nazionale; lettura GTFS a blocchi (indice Trenitalia in circa 1 s sul JVM dei test).
- `tools/build_gtfs_packs.py`: `--only` e `--source-archive` per aggiornare solo i pack indicati, verifica dell'upstream fissato; l'indice iOS resta sulle stesse 10 fonti (`androidOnlySources` nella policy iOS).

## In sviluppo — Android allineato al render approvato di Binario 2.0

- Palette misurata sul render approvato (testata `#003A28`, azioni `#00703E`, tonali `#D8F4EA`, card `#FCFCFC` su `#F4F7F6`, timeline `#12785A`) con schema scuro derivato e contrasto AA; il design system indica il render come riferimento.
- Testata verde sotto la status bar che continua sotto la prima card, barra in basso bianca e, da 600 dp, barra laterale verde con il logo; Pianifica, Risultati (card con durata, «Diretto», filtri per categoria e ordine), Partenze (card «Prossima corsa», «Altre corse»), Dettaglio corsa con le schede Percorso / Info / Servizi e «Segui questo viaggio», «Le tue tratte» con Preferite / Recenti e la nuova schermata Impostazioni che raggruppa le impostazioni esistenti.
- Su tablet la mappa del percorso (OpenFreeMap, aperta su richiesta) affianca le soluzioni al posto della fotografia del render; i widget hanno la testata verde e righe chiare.
- `DesignScreenshotTest` cattura le schermate con un catalogo dimostrativo fisso; il workflow Android CI le stampa nel log con `capture_design_screens=true`.

## In sviluppo — Binario 2.0 UI/UX sui client non Android

- Design system Binario 2.0 ([design/BINARIO_2_DESIGN_SYSTEM.md](design/BINARIO_2_DESIGN_SYSTEM.md)) e prototipo Echo offline portati dal branch di design, con lo stato reale di ogni superficie; sull’app Android sono applicate le fasi 1–3: palette e testata del planner, navigazione Pianifica / Partenze / Per te con card della prossima partenza e dettaglio a timeline, barra laterale e doppio pannello su tablet e pieghevoli.
- Wear OS: tema verde unico (`WearTheme.kt`, `ColorScheme` Material 3 for Wear con i token scuri su sfondo nero), titoli in `colorScheme.primary`, Tile, icona e anteprime con gli stessi token; contrasto AA verificato da test.
- Apple Watch e iPhone: token nei cataloghi asset (chiaro/scuro su iPhone), `AccentColor` applicato come tinta globale; su iPhone etichetta del pulsante di ricerca e messaggi di errore AA in entrambe le modalità, su Apple Watch stato del dato con i token verde/attenzione/errore e sempre anche in testo.
- Dashboard del companion Alexa: palette verde al posto del blu del template.
- Echo Show: schermate APL (Home, Partenze, Stato treno, Le mie tratte) solo sui dispositivi che dichiarano APL, con stato «dati non disponibili in tempo reale», fonte e ora della verifica, layout per classi di viewport, token chiari/scuri, target touch ≥ 64 dp e nessun dato personale prima dell’account linking. Attive solo dopo l’abilitazione dell’interfaccia APL da parte del titolare; la voce resta invariata su ogni altro dispositivo.


## In sviluppo — Agenda Android

- Promemoria locali dell’Agenda con consenso separato per dispositivo, cancellazione, ripristino e deduplica; contenuti personali redatti nello schermo bloccato.
- Selettori nativi di data e ora, ricerca stazioni per nome/codice e scelta canonica per omonimi; modifica senza perdita di tratte o fuso degli itinerari importati.
- Test UI su emulatore API 35 con notifiche consentite e negate; APK debug firmato e checksum disponibili come artifact CI per le prove su dispositivo.


## 0.20.4 — in stabilizzazione

L’audit indipendente Claude del 29 settembre ha riaperto correttamente lo sviluppo su due High e su un gruppo di finding Medium/Low. La tranche di remediation preserva l’identità canonica delle stazioni Android, migra gli ID backup legacy reali, rende fail-closed gli orari GTFS malformati, conserva lo stato export `PlannedJourney`, assegna una timeline TTL alla Tile Wear, impedisce sovrascritture da archivi Companion corrotti, preserva i marker di rimozione permessi Expo, aggiunge acknowledgement Wear e refresh autonomo del widget, accetta sessioni Alexa prive di display name, elimina Vite dal runtime production ed esegue escaping SSML. Le advisory high/critical correggibili del Companion sono state chiuse con una patch Metro testata; `org.json` è stato aggiornato e le sole dipendenze Android bloccate da AGP 9.1/compileSdk 37 sono ora documentate e protette da allowlist statica. Il rail CI consente inoltre i push `stabilization/*`, mentre i branch `integration/*` restano coperti dalla pull request senza run duplicati. Tutti questi cambi sono sviluppati su un branch separato e non costituiscono merge o release della PR #26.

Le superfici utente sono ora localizzate in **italiano, inglese, tedesco, francese e spagnolo**. L’app Android principale e Wear usano risorse Android con parità di chiavi, attributi, placeholder e quantità CLDR verificata; Companion Expo usa la lingua BCP-47 del dispositivo tramite `expo-localization` e rigenera le stesse cinque lingue nelle superfici native phone/Wear; dashboard e risposte Alexa condividono un catalogo tipizzato; Apple Watch usa uno String Catalog con la stessa copertura. La nuova app iPhone della Draft PR #32 include cataloghi equivalenti. Messaggi dinamici, notifiche, accessibilità, widget, Tile, agenda e testi ICS vengono risolti al boundary UI, senza incorporare copy italiano nello stato persistito.

Una revisione linguistica indipendente per inglese, tedesco, francese e spagnolo ha poi corretto esempi rimasti in italiano, termini ferroviari ambigui e formulazioni privacy. Gli esempi di import vengono ora eseguiti contro il parser reale in tutte le lingue; anche la dettatura locale riconosce i connettivi e le espressioni temporali delle cinque lingue supportate, mantenendo fallback e limiti fail-closed.

L’app iPhone principale dispone ora di un primo provider ferroviario reale **offline e solo programmato**. Una pipeline deterministica deriva dai dieci pack GTFS revisionati un indice SQLite compresso di 7.371.419 byte, con 29.479 corse e 364.120 stop-time; conserva solo corse ferroviarie che toccano identità canoniche del catalogo, incluse le due sole eccezioni TFT revisionate. Una policy revisionabile blocca `sourceId`, URL HTTPS e modalità `OPEN_DIRECT`, mentre il manifest continua ad attestare l’hash dell’archivio aggiornabile; entrambi entrano nella fingerprint dell’artefatto. Builder e verifier rifiutano timezone IANA invalide, orari regressivi e child stop geograficamente incoerenti; le stop-time vengono filtrate tramite staging SQLite disk-backed e budget espliciti. L’installazione locale serializza la cache, verifica metadata, SHA-256, dimensioni, schema SQLite e fingerprint, usa sostituzione atomica e ripara l’esclusione dai backup Apple anche sul fast path; ogni anomalia ricade sul repository fail-closed. La UI distingue assenza di corse da provider invalido, invalida i risultati e il contesto Watch quando cambiano gli input e impedisce risposte/spinner stale. I risultati e il payload Apple Watch dichiarano sempre natura `PROGRAMMED`, fonte reale e data del manifest, senza inventare ritardi, binari, soppressioni o stato realtime.

È stata aggiunta [l'informativa privacy verificabile](PRIVACY.md) per app Android, Wear OS, Companion Expo e Alexa Companion. Il documento distingue dati locali, feed ferroviari esterni e nomi stazione trasmessi, posizione effimera, notifiche, backup manuali, dettatura di sistema, account linking Alexa, revoca, conservazione e cancellazione. Quando viene disattivata la condivisione Wear, il telefono richiede la cancellazione del Data Item e il watch elimina la cache locale alla ricezione della revoca; se l'orologio è offline, la propagazione dipende dalla successiva consegna del Data Layer. Il pairing/sync del Companion Expo resta uno stub senza trasferimento remoto. Alexa Companion espone ora l'erasure del database operativo con revoca transazionale, cascade e invalidazione della sessione residua; il repository non indica ancora titolare, recapito, URL pubblico né retention/espurgo di log e backup. Questi elementi restano bloccanti prima di una distribuzione pubblica; non sono stati inventati dati legali o URL.

Il riesame Fable Extra ha portato alla chiusura di ulteriori difetti riproducibili: protezione CSRF e logging fail-closed per Alexa, adozione sicura delle due discendenze database precedenti, migrazione dei reminder Companion senza perdita, package/firma e contratto Wear coerenti, calendario ferroviario e fuso `Europe/Rome` unificati, digest semantici dei pack GTFS, progetto Apple Watch testabile e trigger CI ridotti ma completi. In quella tranche i branch di lavoro venivano verificati dalle pull request e i push run restavano solo su `main`, eliminando l'esecuzione duplicata sul medesimo commit; il successivo audit Claude ha poi aggiunto i push `stabilization/*` revisionati. I gate locali includono ora anche test Kotlin del Companion, verificatore privacy, test statici Apple Watch, input condivisi dei workflow e una procedura manuale per confrontare byte-per-byte due full rebuild AAB unsigned. Il verifier legge inoltre i manifest protobuf realmente inclusi negli AAB e blocca package o versioni divergenti dalla configurazione release. Anche il Companion separato ha ora una release identity canonica; il packaging single-worker non lo confonde con l'app smartphone principale. I workflow GitHub dell'HEAD auditato risultano falliti prima di esporre qualunque step o log: il pattern è compatibile con un problema di startup/infrastruttura/quota, ma non dimostra la causa e non equivale né a test rossi del codice né a una CI verde. Devono essere rieseguiti realmente prima del merge.

L'audit GPT indipendente sull'HEAD `991b8ad2` ha chiuso il proprio ledger con 0 P0, 0 P1 e quattro P2 confermati. La tranche corrente azzera la `JourneyQuery` quando cambia la saved route senza rompere la pianificazione o il cambio direzione della stessa tratta; aggiunge reuse detection e revoca di famiglia ai refresh token Alexa tramite migrazione 0013; segue manualmente redirect GTFS bounded rivalidando HTTPS; e converte il service day GTFS rispetto a `mezzogiorno − 12 ore` durante i cambi DST. Il presunto finding sull'import capacity di `PlannedJourney` è stato ritirato dopo la controverifica della cardinalità. Sono stati inoltre aggiunti checksum ufficiali ai wrapper Gradle, un gate per i field CSV GTFS multilinea e regressioni contro collisioni di identità nel tabellone.

Il follow-up GPT sull'implementazione ha individuato una revoca eccessiva nel backfill 0013: associare tutti i token legacy dello stesso utente/client a una sola famiglia poteva collegare autorizzazioni storiche indipendenti. La migrazione assegna ora una famiglia deterministica distinta a ogni token pre-0013, perché la lineage originaria non è ricostruibile in modo affidabile; le famiglie create dal runtime dopo la migrazione continuano invece a essere ereditate lungo R0→R1 e supportano la reuse detection completa. Test statici e MariaDB reali verificano entrambe le proprietà.

La regressione MariaDB esegue inoltre il replay attraverso `rotateRefreshToken()` dopo una vera adozione 0012/0013 e dimostra che il grant legacy attivo dello stesso utente/client resta utilizzabile e non incrementa la versione credenziale. Il test è isolato dal lifecycle OAuth successivo: la variante della Draft PR #28 che riusava lo stesso link contaminava infatti la versione attesa e falliva la suite completa. Sul Companion Wear, gli annunci manuali deprecati sono stati sostituiti da regioni live `POLITE` con descrizioni contestuali; plugin, output generato e test di contratto vietano ora la reintroduzione dell'API obsoleta.

Il P3 di release engineering sulla riproducibilità AAB è ora esercitato direttamente dai workflow Android e Companion sulle pull request verso `stabilization/*`; negli altri casi resta disponibile tramite l'input manuale `verify_reproducibility`. Le normali pull request e i push non-release conservano il singolo build: soltanto una PR di stabilizzazione o un `workflow_dispatch` esplicito eseguono due full rebuild `--rerun-tasks`, conservano i primi AAB in `RUNNER_TEMP` e confrontano byte-per-byte telefono e Wear. Il verifier CI blocca la rimozione dell'input booleano, delle condizioni release/dispatch, di uno dei rebuild, dei reference bundle o l'aggiunta di `continue-on-error`; il verifier AAB richiede inoltre che i due reference siano forniti insieme e non coincidano con gli output correnti.

## 0.20.3 — 16 agosto 2026

Rimosso il percorso UI introdotto in v0.20.1 che conservava una seconda selezione di stazione e avvolgeva i suggerimenti in una superficie aggiuntiva. Poiché il dispositivo ha segnalato comandi non reattivi, il pianificatore è stato ripristinato al flusso diretto e leggero: campo testuale, elenco di pulsanti selezionabili e scambio dei soli valori della tratta. Restano il catalogo JSON effettivamente incluso nell’APK e la correzione del suo caricamento.

La regressione è stata isolata confrontando v0.20.0, v0.20.1 e v0.20.2; non restano riferimenti a `selectedOrigin`, `selectedDestination` o allo stato aggiuntivo del catalogo nella UI. Build, test unitari e verifica dell’asset nell’APK sono ripetibili. La conferma interattiva su dispositivo fisico resta obbligatoria prima di dichiarare la correzione conclusa.

## 0.20.2 — 16 agosto 2026

Corretto un difetto di packaging riprodotto dall’APK installato: il catalogo curato veniva generato come `.json.gz`, ma Android lo trasformava in `curated_it_rail_stations.json` nel pacchetto finale; il lettore cercava invece il nome gzip e caricava quindi una lista vuota. Il catalogo viene ora generato e letto esplicitamente come JSON. L’APK contiene la risorsa `assets/stations/curated_it_rail_stations.json`, con 5.239 stazioni e hash `58b441886c5fe8b731f69000c010c860b28424158fcab4ef1124434874b5712a`.

È stato aggiunto `tools/verify_station_catalog_in_apk.py`, controllo di integrazione che legge **l’APK appena generato** e verifica presenza asset, hash, numero stazioni, alias `APPIANO`, Appiano Proba Petronia e Roma Ostiense. Il lettore Kotlin ora registra esclusivamente nei log interni la causa tecnica di un eventuale nuovo errore e mostra all’utente un messaggio sicuro.

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
