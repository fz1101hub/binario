# Informativa privacy di Binario

**Stato del documento:** fotografia del comportamento implementato nel ramo di integrazione `integration/stabilization-complete-v0204`, aggiornata il 25 ottobre 2026. Questa informativa distingue espressamente il codice attivo dalle funzioni presenti ma non operative. Non descrive funzionalità future.

> **Blocco per una distribuzione pubblica.** Il repository non identifica il titolare del trattamento, un recapito per i diritti privacy, un URL pubblico dell'informativa né la retention operativa di log e backup dell'hosting. Prima di una pubblicazione vanno completati questi elementi e va collegato un URL pubblico verificato nell'app e nei canali di distribuzione. `PRIVACY.md` è il documento sorgente del progetto, non un sostituto di quel collegamento pubblico.

## Ambito e superfici interessate

Questa informativa riguarda il codice attuale di **Binario Android**, del relativo **companion Wear OS**, del **Companion Expo**, di **Alexa Companion** in `alexa-companion/` e dell'**app iPhone con companion Apple Watch** in `ios/` e `apple-watch/`. Le superfici non si comportano tutte allo stesso modo: l'app Android e il Companion Expo funzionano principalmente con dati locali; il companion Wear riceve snapshot dal telefono; Alexa Companion usa un backend e un database solo quando viene configurato e collegato un account; l'app iPhone lavora soltanto con dati inclusi nell'app e invia al Watch abbinato, su scelta dell'utente, lo stesso snapshot ridotto.

Il documento descrive i dati trattati dall'applicazione e dal backend del repository. Non sostituisce le informative del sistema operativo, di Google Play Services, di Amazon Alexa, del gestore dell'account della dashboard o dei gestori ferroviari. Quando l'utente usa tali servizi, essi possono trattare dati tecnici di rete, inclusi indirizzo IP, data e ora della richiesta e intestazioni standard, secondo le rispettive informative.

## Sintesi dei dati per superficie

| Superficie | Dati trattati dal codice | Destinazione e finalità |
|---|---|---|
| Android | Tratte, stazioni, preferiti, ricerche recenti, monitoraggi, avvisi, viaggio attivo, preferenze, configurazioni widget e metadati di refresh. | Restano nella memoria privata dell'app sul telefono, salvo ricerca presso fonti ferroviarie, condivisione o backup scelti dall'utente. |
| Wear OS | Etichetta della tratta, provenienza e freschezza del dato, avviso, e al massimo due corse con numero, destinazione, orario, stato e binario. | Il telefono li invia attraverso il Data Layer a un orologio associato; il watch li conserva nella propria memoria privata. |
| Companion Expo | Tratte pianificate, date e orari, checklist, promemoria, preferenze, itinerari, stazioni preferite e impostazioni di accessibilità locali. | Restano nel dispositivo; i file e il calendario passano al sistema o all'app scelta solo dopo un'azione di esportazione dell'utente. |
| Alexa Companion | Identificativi e profilo dell'account dashboard, tratte personali, profilo minimo, hash dell'identificativo Alexa e artefatti OAuth in forma hash. | Sono conservati nel database configurato dal gestore del backend per il collegamento account e le risposte personali della skill. |
| iPhone | Tratte salvate (al massimo 32) e preferenza di condivisione con il Watch nelle `UserDefaults` dell'app; cache dell'indice orari in Application Support. | Restano sull'iPhone; nessuna richiesta di rete. I backup di sistema Apple possono includere tratte e preferenza, non la cache. |
| Apple Watch | Etichetta della tratta, provenienza, freschezza, avviso e al massimo due corse con numero, operatore, categoria, destinazione, orari, stato e binario. | L'iPhone li invia tramite Watch Connectivity solo con la condivisione attiva; il Watch li conserva nell'App Group `group.it.binario.watch` per app e complicazione. |

## App Android

### Dati conservati localmente

L'app Android salva nelle `SharedPreferences` private dell'app le tratte salvate e fissate, le ultime tratte cercate, la tratta attiva e la direzione, i numeri treno monitorati, i preferiti, le stazioni inserite manualmente, gli avvisi pendolare, i monitoraggi stazione, il viaggio attivo e le preferenze di mobilità, tema e integrazione. Le configurazioni dei widget conservano inoltre il tipo di destinazione, il nome mostrato, la tratta o la stazione scelta e, quando disponibile, i relativi identificativi e coordinate del catalogo stazioni. La cache principale conserva soltanto l'istante dell'ultimo refresh, il nome del provider e la confidenza; altre cache di processo o di widget possono contenere lo snapshot necessario alla visualizzazione.

Questi dati servono a ripristinare le scelte dell'utente, generare promemoria locali e mostrare app e widget. Non risultano nel codice SDK pubblicitari o analytics configurati. Questa constatazione riguarda il codice di questo repository e non le telemetrie eventualmente applicate dal sistema operativo, dal negozio di app o da servizi terzi.

### Feed ferroviari esterni, European Sleeper/JBB e nomi di stazione

La build distribuita consulta il quadro orario programmato RFI per le stazioni RFI mappate. La richiesta contiene l'identificativo pubblico RFI della **stazione di partenza** o, per consultare gli arrivi programmati, della **stazione di destinazione**; il filtro della stazione di arrivo viene applicato nell'app sul quadro ricevuto. I data pack GTFS embedded e il catalogo stazioni sono inclusi nell'app e non ricevono la ricerca dell'utente.

Le build personali debug e release abilitano i tabelloni pubblici RFI, Italo in Viaggio e ViaggiaTreno per impostazione predefinita. L’opzione esplicita `false` li disattiva. Per ricavare una tratta live vengono interrogati i tabelloni RFI delle due stazioni. RFI riceve un identificativo pubblico della stazione e Italo il codice della stazione pubblicato nel proprio catalogo nello URL del servizio che alimenta il tabellone. Il catalogo stazioni pubblico Italo viene scaricato e confrontato sul dispositivo. Per trovare stazioni aggiuntive viene scaricato il catalogo pubblico RFI, confrontando nomi e alias solo sul dispositivo. ViaggiaTreno riceve nello URL il **codice ferroviario della stazione di partenza e la data/ora richiesta**, quindi il numero del treno, il codice della sua stazione di origine e il giorno della corsa per recuperarne le fermate. Il codice della destinazione richiesta viene confrontato localmente con tali fermate. Le richieste sono dirette dal dispositivo, senza account o credenziali Binario. ViaggiaTreno espone la propria API pubblica legacy in HTTP: il client prova prima HTTPS e segue il reindirizzamento HTTP solo alla stessa risorsa API sul dominio esatto `www.viaggiatreno.it`. Le richieste di stazione, orario e corsa a questa API non sono cifrate. Le altre fonti usano HTTPS. I feed Trip Updates STA Alto Adige e SNCF vengono scaricati dai rispettivi host per arricchire le corse GTFS della ricerca. Gli adapter di feed GTFS o avvisi pubblici possono scaricare il feed dal rispettivo host senza inserire la tratta dell’utente nella richiesta. Le sorgenti personali best-effort e quelle non attive sono dettagliate nel README e nel data layer; una fonte non attiva non viene trasformata in un invio di dati.

Per la copertura di **European Sleeper**, il codice dispone di un feed statico GTFS e di Trip Updates GTFS-Realtime dal mirror **European Sleeper/JBB** sull'host `jbb.ghsq.de` (`/gtfs/eu-es.gtfs.zip` e `/gtfs/european-sleeper/TripUpdates`). È una fonte **community best-effort**, non un feed ufficiale dell'operatore. Nel percorso a tier, può essere interrogata solo dopo che il **tier primario fallisce** nel costruire la tratta richiesta; una risposta del tier primario evita la chiamata al fallback e il feed JBB non lo sostituisce. Il risultato resta segnalato come fallback a copertura limitata.

Quando tale fallback viene interrogato, l'implementazione esegue una richiesta HTTPS `GET` diretta al relativo URL JBB e imposta intestazioni `Accept` e `User-Agent` di Binario. I metadati di rete della richiesta sono visibili all'host `jbb.ghsq.de`, inclusi l'indirizzo IP del collegamento, data e ora, risorsa richiesta e intestazioni HTTP standard. JBB può quindi trattarli secondo le proprie condizioni; il repository non definisce tali condizioni né la loro conservazione.

Le richieste sono dirette dal dispositivo al gestore ferroviario o del feed. Il codice Android usa HTTPS per le fonti ferroviarie, con una sola eccezione HTTP per le API pubbliche ViaggiaTreno su `www.viaggiatreno.it`; il traffico in chiaro resta disabilitato per gli altri domini. Il client invia un `User-Agent` che identifica Binario e la versione del client; non invia un account Binario, un token di autenticazione, una posizione o una registrazione vocale alle fonti ferroviarie. Il gestore della fonte può comunque vedere i normali metadati di rete della richiesta.

L'inventario completo degli host presenti nel codice Android, con la classificazione di quando ciascuno viene contattato (build distribuita, build personale, adapter non collegato, provenienza offline dei data pack o soli metadati del catalogo fonti) e le risposte derivate per il modulo Sicurezza dei dati di Google Play, è in [docs/store/PLAY_DATA_SAFETY.md](docs/store/PLAY_DATA_SAFETY.md). `tools/verify_privacy_policy.py` fallisce se un host del codice manca da quella tabella o se la tabella elenca un host non più presente nel codice.

### Posizione effimera

L'app richiede la posizione approssimata solo dopo un'azione esplicita per trovare le stazioni vicine. Legge una posizione corrente oppure, se non disponibile, l'ultima posizione conosciuta del sistema; usa coordinate e catalogo offline per ordinare le stazioni in memoria. La posizione non è scritta nelle preferenze, non è inclusa nei backup o nei widget, non viene inviata ai feed ferroviari e non è richiesta in background. Negare il permesso lascia disponibile la ricerca manuale.

### Notifiche, condivisione e dettatura

Le notifiche Android sono facoltative. Gli avvisi pendolare e la sveglia fermata sono pianificati localmente dal dispositivo e possono mostrare nome della fermata, tratta, numero treno, destinazione e orario. Il consenso `POST_NOTIFICATIONS` è richiesto solo quando l'utente crea tale funzione. Eliminare un avviso dall'app ne annulla la pianificazione; le notifiche già visualizzate sono gestite dal sistema operativo.

L’Agenda conserva sul dispositivo viaggi pianificati con nome, stazioni, data, ora, fuso orario, checklist, ricorrenze e anticipo preferito. Le sue notifiche richiedono un’attivazione esplicita separata, inizialmente disabilitata, e il permesso Android. Mostrano il nome del viaggio, origine, destinazione e partenza programmata; la versione pubblica per lo schermo bloccato contiene soltanto un titolo generico. Non sono notifiche push e non inviano dati a un server. Android può ritardarne la consegna; senza accesso agli allarmi esatti si usa la pianificazione approssimativa del sistema.

L’attivazione vale solo per questo dispositivo e non viene esportata o importata con i file dei viaggi. Un registro locale limitato conserva identificatori opachi degli avvisi pianificati e già consegnati per ripristino, cancellazione e prevenzione dei duplicati. Modificare, completare, archiviare o eliminare un viaggio riconcilia gli avvisi; disattivare le notifiche dell’Agenda annulla gli allarmi e ritira le sue notifiche ancora attive. L’eventuale cronologia delle notifiche del sistema resta gestita da Android. Il calendario ICS conserva l’anticipo come preferenza per l’app calendario scelta dall’utente e non abilita il consenso alle notifiche di Binario.

Il comando di condivisione apre il selettore Android e trasferisce testo ferroviario alla destinazione scelta dall'utente. Binario non controlla il successivo trattamento effettuato dall'app destinataria.

La dettatura Android avvia il riconoscitore vocale di sistema. Binario non richiede il permesso microfono e non registra né carica l'audio. Riceve il testo restituito dal servizio di sistema per compilare o interpretare la ricerca; il testo grezzo non viene salvato come registrazione o trascrizione autonoma. Il trattamento dell'audio da parte del riconoscitore è soggetto alle condizioni del sistema o del fornitore di quel servizio.

### Backup, conservazione e cancellazione

Il manifest Android imposta `allowBackup="false"` e le regole escludono preferenze, database e file sia dal cloud backup sia dal trasferimento del dispositivo. Non esiste quindi un backup Android automatico gestito da Binario. L'utente può invece esportare volontariamente un file JSON: contiene tratte, identificativi di stazioni preferite, monitoraggi, avvisi, preferenze di mobilità e superficie widget. Il file è scritto nella destinazione selezionata dall'utente; Binario non ne riceve una copia e l'utente deve proteggerlo e cancellarlo dalla destinazione scelta quando non serve più.

Le singole tratte, stazioni preferite, monitoraggi, viaggio attivo e avvisi possono essere rimossi dai relativi comandi dell'app. Non è presente un comando Android unico che cancelli ogni categoria di dato locale. I dati non rimossi restano nel contenitore privato dell'app fino alla loro sostituzione o rimozione, alla cancellazione dei dati dell'app dalle impostazioni del dispositivo o alla disinstallazione. La scadenza di una cache indica quando non deve essere considerata aggiornata, non è una promessa di cancellazione fisica immediata.

## Companion Wear OS

Il companion Wear OS non interroga da sé fonti ferroviarie: mostra la cache ricevuta dal telefono. Quando la condivisione Wear è attiva nell'app Android, il telefono invia tramite Google Play Services Data Layer l'etichetta della tratta attiva, provider, confidenza, freschezza, istante di ricezione, avviso e fino a due corse con numero, operatore, categoria, destinazione, orario previsto, stato e binario. Non invia credenziali, biglietti, posizione, audio o trascrizioni.

Il watch salva lo snapshot nella propria `SharedPreferences` per app, Tile e complication. Quando l'utente disattiva la condivisione, il telefono richiede la cancellazione del Data Item; alla ricezione dell'evento di cancellazione il watch elimina la cache locale e aggiorna Tile e complication in stato non disponibile. Se l'orologio è offline, la propagazione dipende dalla consegna successiva del Data Layer; la cancellazione dei dati dell'app Wear o la disinstallazione rimuovono direttamente la copia locale.

Il manifest Wear **non** dichiara il permesso `INTERNET` e il codice del modulo Wear corrente non avvia richieste ferroviarie: il refresh è delegato al telefono. La comunicazione con il watch dipende dall'infrastruttura Google Play Services e dalle impostazioni dell'account/dispositivo associato.

## Companion Expo

### Dati locali e promemoria

Il Companion Expo conserva in `AsyncStorage` le tratte pianificate con origine, destinazione, eventuali codici stazione, nome, data, ora, checklist e stato di completamento; conserva inoltre itinerari a più tratte, stazioni preferite, preferenze di anticipo, identificativi dei promemoria pianificati, tema e preferenze di accessibilità. I promemoria sono notifiche locali del sistema e includono il nome della tratta e l'orario programmato. Non sono notifiche push gestite da un server Binario.

Se l'utente elimina una tratta, il codice prima annulla il relativo promemoria e poi rimuove la tratta; riconcilia inoltre gli itinerari che la contenevano. Il comando **Cancella archivio locale** annulla i promemoria registrati e rimuove tratte e itinerari dal telefono. Non cancella tema, preferenze di accessibilità o eventuali dati già esportati. Il sistema operativo può mantenere per un proprio periodo una notifica già mostrata o un file selezionato dall'utente.

### Posizione, voce, backup e file scelti dall'utente

La funzione di stazione più vicina chiede il permesso di posizione in primo piano e legge una singola posizione corrente. La confronta con l'indice stazioni offline e non la conserva né la invia a un server. Se la tastiera fornisce la dettatura, l'audio è trattato dalla tastiera o dal sistema; il Companion Expo non apre il microfono, non registra e riceve solo il testo modificabile nel campo.

Il Companion Expo può creare un file di trasferimento o un file calendario e aprire la condivisione di sistema solo dopo una scelta dell'utente. L'archivio di trasferimento contiene tratte e checklist, ma esclude account, token, coordinate, audio, note e stati realtime. Le app o i servizi scelti nel selettore di sistema ricevono il file secondo le loro regole. Il backup automatico Android e il trasferimento automatico dei dati dell'app sono disabilitati nel manifest del Companion Expo.

L'implementazione corrente di `companion-api.ts` non effettua richieste di rete: le funzioni di pairing, consenso, revoca e sincronizzazione sono stub che restituiscono esiti vuoti. Un eventuale URL configurato tramite variabile d'ambiente non rende attiva una sincronizzazione. Di conseguenza il Companion Expo attuale non crea un account remoto, non invia tratte a un backend e non può cancellare dati remoti. Il bridge Wear sperimentale conserva sul watch solo titolo, stato, date di validità e numero di elementi checklist; le sue funzioni vocali usano servizi di sistema e messaggi tra telefono e watch, non un upload audio del Companion.

## App iPhone e Apple Watch

### Dati conservati sull'iPhone

L'app iPhone (`it.binario.ios`) salva nelle `UserDefaults` standard dell'app le tratte salvate, al massimo 32, con la chiave `it.binario.ios.saved-routes.v1` (identificativo e nome della stazione di partenza e di destinazione, nome della tratta e data di creazione) e la preferenza di condivisione con Apple Watch con la chiave `it.binario.ios.watch-sharing-enabled.v1`, disattivata per impostazione predefinita. Il catalogo stazioni e l'indice degli orari programmati sono inclusi nell'app: al primo avvio l'indice viene decompresso e verificato in `Application Support/Binario/GTFS`, in un file escluso dai backup. Non esistono account, analytics, pubblicità, posizione, microfono, notifiche push, modalità in background né alcuna richiesta di rete: la ricerca e i risultati restano sul dispositivo. L'unica uscita verso l'esterno è il collegamento, nella sezione **Fonti dati GTFS** delle impostazioni, alla pagina pubblica di ciascun feed, che si apre nel browser soltanto su tocco dell'utente ed è trattato da quel sito secondo le sue condizioni.

I backup iCloud e del computer gestiti dal sistema possono includere le `UserDefaults` dell'app, quindi tratte salvate e preferenza di condivisione; la cache dell'indice è esclusa. Le tratte si eliminano singolarmente o tutte insieme dalla scheda **Tratte**; la cancellazione dell'app dal dispositivo rimuove tutto. I manifesti privacy dei tre eseguibili dichiarano l'uso delle `UserDefaults` (motivi `CA92.1` per l'app e `1C8F.1` per l'App Group del Watch), nessun tracciamento, nessun dominio di tracciamento e nessun dato raccolto: la risposta coerente al questionario App Privacy di App Store Connect è «Dati non raccolti».

### Condivisione con Apple Watch

Con la condivisione attiva, dopo una ricerca per l'orario corrente l'iPhone pubblica tramite Watch Connectivity un application context con l'etichetta della tratta, la provenienza e la natura del dato (programmato o non disponibile), la fonte, l'avviso e al massimo due corse con numero, operatore, categoria, destinazione, orario programmato e previsto, stato, binario e ritardo; il payload è limitato a 8.192 byte e scade con l'ultima corsa elencata. Non vengono inviati tratte salvate, posizione, credenziali o dati personali. Il Watch (`it.binario.ios.watchkitapp`) convalida lo snapshot e lo conserva nelle `UserDefaults` dell'App Group `group.it.binario.watch`, condiviso con la complicazione, con la chiave `binario_watch_snapshot_v1`. Su un iPhone senza Watch abbinato nulla viene inviato e le impostazioni mostrano uno stato neutro.

Disattivare la condivisione, o cambiare gli input della ricerca, sostituisce qualsiasi contesto in attesa con il segnale di cancellazione `binario_snapshot_clear`: alla ricezione il Watch elimina lo snapshot dall'App Group e aggiorna la complicazione nello stato non disponibile. Se il Watch è irraggiungibile, la propagazione dipende dalla consegna successiva di Watch Connectivity; la disinstallazione dell'app Watch rimuove direttamente la copia locale.

### English summary (iPhone and Apple Watch)

The iPhone app keeps up to 32 saved routes (`it.binario.ios.saved-routes.v1`) and the Watch-sharing preference (`it.binario.ios.watch-sharing-enabled.v1`, off by default) in the app's standard `UserDefaults`; the bundled timetable index is cached in Application Support and excluded from backups. The app has no account, analytics, advertising, location, microphone, push or background activity and makes no network request; links to the public page of each data source open in the browser only when tapped. Apple system backups may include the saved routes and the preference. When sharing is on, a search for now sends the paired Watch an application context with the route label, provenance, notice and at most two services; the Watch stores it in the `group.it.binario.watch` App Group for the app and its complication, and clears it when the iPhone sends the revocation signal. App Privacy answer: Data Not Collected.

## Alexa Companion

### Quando viene usato

Alexa Companion è opzionale e non è necessario per Android, Wear o il Companion Expo. Rimane inattivo finché non sono configurati database, variabili OAuth e skill Alexa e finché l'utente non completa un consenso autenticato per il collegamento. L'app Android attuale non invia ancora il proprio profilo a questo backend; le tratte personali Alexa sono quelle create nella dashboard del companion o tramite la relativa API autenticata.

L'accesso alla dashboard crea o aggiorna un record utente con identificativo del provider di login (`openId`), nome, email, metodo di accesso, ruolo e date di accesso. Il backend conserva per la dashboard le tratte personali — origine, destinazione, nome, fissaggio e date — e un profilo minimo con identificativo della tratta attiva, direzione, numeri treno monitorati, revisione e date. Il modello esclude espressamente cronologia dei viaggi, posizione, biglietti, snapshot live, audio e credenziali dell'app dal profilo remoto.

Nel collegamento Alexa, il backend conserva l'identificativo Alexa solo come digest HMAC, con date di collegamento, verifica e revoca. I codici OAuth e refresh token sono salvati soltanto come hash; il database conserva anche client ID, redirect URI, scope, famiglia di rotazione e date di creazione, consumo, scadenza o revoca. Il codice di autorizzazione scade dopo cinque minuti; il refresh token scade dopo trenta giorni; il token di accesso vocale ha durata dichiarata di un'ora. Il riuso di un refresh token già ruotato revoca i discendenti ancora attivi della stessa famiglia e invalida la generazione delle credenziali vocali. Scadenza o revoca impediscono l'uso dell'artefatto, ma il codice attuale non implementa una cancellazione automatica delle righe scadute.

### Voce, dati inviati da Alexa e risposte

Amazon Alexa tratta l'audio per riconoscere il comando secondo i propri termini. Alla skill il backend riceve la richiesta firmata da Alexa, il token di account linking, l'identificativo Alexa e gli slot testuali pertinenti, per esempio nome della tratta o numero treno. Il backend non salva il contenuto audio e il codice non registra gli slot di intent come cronologia vocale. Per verificare il collegamento salva soltanto l'hash dell'identificativo Alexa; per rispondere può leggere dal database le tratte personali e pronunciare i loro nomi tramite la piattaforma Alexa.

La skill corrente non recupera dati ferroviari live: per richieste di prossimi treni o stato treno risponde che i dati realtime non sono disponibili. Non invia quindi nome della stazione, posizione o numero treno a un feed ferroviario come parte della risposta Alexa attuale.

### Revoca, cancellazione e conservazione

Nella dashboard la funzione **unlink** imposta `revokedAt` sul collegamento Alexa e sui refresh token attivi. Ogni richiesta della skill verifica lo stato del collegamento e la generazione delle credenziali nel database, perciò un access token già emesso non deve continuare a leggere le tratte dopo la revoca. La revoca non cancella il record di collegamento, il relativo hash, i token revocati, l'utente, le tratte o il profilo. Un nuovo scambio di autorizzazione con consenso ripristina il collegamento.

L'utente può eliminare le singole tratte oppure usare **Elimina account e dati** nella dashboard, confermando con la parola `ELIMINA`. La cancellazione revoca prima gli artefatti OAuth nella stessa transazione, elimina il record utente e, tramite chiavi esterne `ON DELETE CASCADE`, rimuove tratte, preferenze stazione, profilo, dispositivi e codici di pairing, collegamento Alexa, authorization code e refresh token associati. Il cookie di sessione viene cancellato; un cookie residuo emesso prima dell'erasure non ricrea automaticamente l'utente. Un successivo nuovo login OAuth, se il servizio resta disponibile, può creare un nuovo account vuoto.

La sessione della dashboard è un cookie HTTP-only con durata di trenta giorni. Il logout cancella il cookie nel browser, ma non elimina account o dati nel database. La cancellazione account riguarda le righe del database operativo controllate dal codice; i log e le copie di sicurezza dell'infrastruttura del database o dell'hosting non sono definiti da questo repository. Il titolare dovrà indicarne periodo, eventuale ciclo di espurgo e gestore prima della distribuzione pubblica. Il recapito del titolare per richieste di accesso, rettifica o cancellazione resta un blocco aperto.

## Scelte dell'utente e richieste sui dati

| Esigenza | Controllo disponibile nel codice attuale |
|---|---|
| Non usare posizione | Non concedere o revocare il permesso di posizione; la ricerca manuale resta disponibile. |
| Fermare notifiche | Non concedere il permesso, rimuovere il singolo avviso/promemoria oppure disattivare le notifiche nelle impostazioni del sistema. |
| Rimuovere dati Android | Rimuovere le singole voci, cancellare i dati dell'app dal sistema o disinstallare; eliminare separatamente gli eventuali backup JSON esportati. |
| Rimuovere dati Companion Expo | Usare **Cancella archivio locale** per tratte, itinerari e promemoria; cancellare dati/disinstallare per le altre preferenze e rimuovere separatamente file o eventi esportati. |
| Interrompere Wear | Disattivare la condivisione nell'app Android: il telefono richiede la cancellazione del Data Item e il watch elimina la cache locale alla ricezione della revoca; se l'orologio è offline la cancellazione avviene alla consegna successiva del Data Layer. Cancellare dati o disinstallare l'app Wear rimuove direttamente la copia locale. |
| Rimuovere dati iPhone | Eliminare le tratte dalla scheda **Tratte** o disinstallare l'app; eliminare separatamente i backup di sistema che le contengono. |
| Interrompere Apple Watch | Disattivare **Condividi le tratte con Apple Watch**: l'iPhone invia la cancellazione e il Watch elimina lo snapshot dall'App Group alla ricezione; disinstallare l'app Watch rimuove direttamente la copia. |
| Scollegare Alexa | Usare **unlink** nella dashboard; ciò revoca il collegamento e i refresh token, senza cancellare i dati descritti sopra. |
| Eliminare l'account Alexa Companion | Usare **Elimina account e dati** nella dashboard e confermare con `ELIMINA`; il database operativo elimina l'utente e i record figli, mentre retention di log e backup resta da definire dal titolare. |
| Richiedere accesso, correzione o cancellazione del backend | **Non è configurato un recapito nel repository.** La pubblicazione pubblica resta bloccata finché il titolare non ne indica uno e definisce la procedura. |

## Sicurezza e limiti di questa informativa

Le app Android usano HTTPS; solo il client smartphone ha una eccezione HTTP circoscritta a `www.viaggiatreno.it` per le API pubbliche ferroviarie, senza account o credenziali; le app Android e Companion Expo escludono il backup automatico del loro contenitore; l'app iPhone non effettua richieste di rete. Queste misure non rendono i dati locali automaticamente cifrati dal codice applicativo: le preferenze private descritte sopra non sono ulteriormente cifrate dall'app nel sorgente corrente. Nessun segreto Alexa è inserito nel client Android; il backend Alexa richiede variabili d'ambiente e rifiuta la skill se non sono complete.

Ogni modifica del comportamento implementato richiede il riesame di questa informativa prima del rilascio. In particolare, la sincronizzazione Companion reale, un percorso di cancellazione Wear diverso dalla revoca descritta, un servizio Alexa diverso o l'introduzione di analytics, push o nuove fonti non sono coperti da promesse retroattive di questo documento.

## Riferimenti al codice

Questa informativa è verificabile nelle implementazioni Android [1], Wear [2], Companion Expo [3], Alexa Companion [4], iPhone [6] e Apple Watch [7]. Il piano di stabilizzazione registra i limiti che restano bloccanti per la pubblicazione [5].

[1]: https://github.com/fz1101hub/binario/tree/integration/stabilization-complete-v0204/app/src/main/java/it/binario/app "App Android: dati locali, posizione, backup e fonti"
[2]: https://github.com/fz1101hub/binario/tree/integration/stabilization-complete-v0204/wear/src/main/java/it/binario/wear "Companion Wear OS: snapshot locale"
[3]: https://github.com/fz1101hub/binario/tree/integration/stabilization-complete-v0204/companion-expo "Companion Expo: dati locali, promemoria e configurazione"
[4]: https://github.com/fz1101hub/binario/tree/integration/stabilization-complete-v0204/alexa-companion "Alexa Companion: database, OAuth e skill"
[5]: https://github.com/fz1101hub/binario/blob/stabilization/v0.20.4/docs/PIANO_STABILIZZAZIONE_v0.20.4.md "Piano di stabilizzazione Binario v0.20.4"
[6]: ios/BinarioApp "App iPhone: tratte locali, preferenza Watch, cache dell'indice orari"
[7]: apple-watch/BinarioWatch "Companion Apple Watch: snapshot nell'App Group e revoca"
