# Informativa privacy di Binario

**Stato del documento:** fotografia del comportamento implementato nel ramo `stabilization/v0.20.4`, aggiornata il 23 settembre 2026. Questa informativa distingue espressamente il codice attivo dalle funzioni presenti ma non operative. Non descrive funzionalità future.

> **Blocco per una distribuzione pubblica.** Il repository non identifica il titolare del trattamento, un recapito per i diritti privacy né un URL pubblico dell'informativa. Prima di una pubblicazione va completata questa sezione e va collegato un URL pubblico verificato nell'app e nei canali di distribuzione. `PRIVACY.md` è il documento sorgente del progetto, non un sostituto di quel collegamento pubblico.

## Ambito e superfici interessate

Questa informativa riguarda il codice attuale di **Binario Android**, del relativo **companion Wear OS**, del **Companion Expo** e di **Alexa Companion** in `alexa-companion/`. Le superfici non si comportano tutte allo stesso modo: l'app Android e il Companion Expo funzionano principalmente con dati locali; il companion Wear riceve snapshot dal telefono; Alexa Companion usa un backend e un database solo quando viene configurato e collegato un account.

Il documento descrive i dati trattati dall'applicazione e dal backend del repository. Non sostituisce le informative del sistema operativo, di Google Play Services, di Amazon Alexa, del gestore dell'account della dashboard o dei gestori ferroviari. Quando l'utente usa tali servizi, essi possono trattare dati tecnici di rete, inclusi indirizzo IP, data e ora della richiesta e intestazioni standard, secondo le rispettive informative.

## Sintesi dei dati per superficie

| Superficie | Dati trattati dal codice | Destinazione e finalità |
|---|---|---|
| Android | Tratte, stazioni, preferiti, ricerche recenti, monitoraggi, avvisi, viaggio attivo, preferenze, configurazioni widget e metadati di refresh. | Restano nella memoria privata dell'app sul telefono, salvo ricerca presso fonti ferroviarie, condivisione o backup scelti dall'utente. |
| Wear OS | Etichetta della tratta, provenienza e freschezza del dato, avviso, e al massimo due corse con numero, destinazione, orario, stato e binario. | Il telefono li invia attraverso il Data Layer a un orologio associato; il watch li conserva nella propria memoria privata. |
| Companion Expo | Tratte pianificate, date e orari, checklist, promemoria, preferenze, itinerari, stazioni preferite e impostazioni di accessibilità locali. | Restano nel dispositivo; i file e il calendario passano al sistema o all'app scelta solo dopo un'azione di esportazione dell'utente. |
| Alexa Companion | Identificativi e profilo dell'account dashboard, tratte personali, profilo minimo, hash dell'identificativo Alexa e artefatti OAuth in forma hash. | Sono conservati nel database configurato dal gestore del backend per il collegamento account e le risposte personali della skill. |

## App Android

### Dati conservati localmente

L'app Android salva nelle `SharedPreferences` private dell'app le tratte salvate e fissate, le ultime tratte cercate, la tratta attiva e la direzione, i numeri treno monitorati, i preferiti, le stazioni inserite manualmente, gli avvisi pendolare, i monitoraggi stazione, il viaggio attivo e le preferenze di mobilità, tema e integrazione. Le configurazioni dei widget conservano inoltre il tipo di destinazione, il nome mostrato, la tratta o la stazione scelta e, quando disponibile, i relativi identificativi e coordinate del catalogo stazioni. La cache principale conserva soltanto l'istante dell'ultimo refresh, il nome del provider e la confidenza; altre cache di processo o di widget possono contenere lo snapshot necessario alla visualizzazione.

Questi dati servono a ripristinare le scelte dell'utente, generare promemoria locali e mostrare app e widget. Non risultano nel codice SDK pubblicitari o analytics configurati. Questa constatazione riguarda il codice di questo repository e non le telemetrie eventualmente applicate dal sistema operativo, dal negozio di app o da servizi terzi.

### Feed ferroviari esterni e nomi di stazione

La build distribuita consulta il quadro orario programmato RFI per le stazioni RFI mappate. La richiesta contiene l'identificativo pubblico RFI della **stazione di partenza**; il filtro della stazione di arrivo viene applicato nell'app sul quadro ricevuto. I data pack GTFS embedded e il catalogo stazioni sono inclusi nell'app e non ricevono la ricerca dell'utente.

La build personale attivata esplicitamente con `BINARIO_PERSONAL_REALTIME_SOURCES=true` può interrogare tabelloni pubblici RFI, Italo in Viaggio e ViaggiaTreno. In quel caso RFI riceve un identificativo pubblico della stazione; Italo riceve nello URL lo slug pubblico della stazione; ViaggiaTreno riceve il **nome della stazione** nel corpo del modulo HTTP. Il nome della destinazione viene usato localmente per filtrare il tabellone ricevuto. Gli adapter di feed GTFS o avvisi pubblici possono scaricare il feed dal rispettivo host senza inserire la tratta dell'utente nella richiesta. Le sorgenti autorizzate, le fonti personali opzionali e quelle non attive sono dettagliate nel README e nel data layer; una fonte non attiva non viene trasformata in un invio di dati.

Le richieste sono dirette dal dispositivo al gestore ferroviario o del feed. Il codice Android richiede HTTPS, disabilita il traffico in chiaro e invia un `User-Agent` che identifica Binario e la versione del client; non invia un account Binario, un token di autenticazione, una posizione o una registrazione vocale alle fonti ferroviarie. Il gestore della fonte può comunque vedere i normali metadati di rete della richiesta.

### Posizione effimera

L'app richiede la posizione approssimata solo dopo un'azione esplicita per trovare le stazioni vicine. Legge una posizione corrente oppure, se non disponibile, l'ultima posizione conosciuta del sistema; usa coordinate e catalogo offline per ordinare le stazioni in memoria. La posizione non è scritta nelle preferenze, non è inclusa nei backup o nei widget, non viene inviata ai feed ferroviari e non è richiesta in background. Negare il permesso lascia disponibile la ricerca manuale.

### Notifiche, condivisione e dettatura

Le notifiche Android sono facoltative. Gli avvisi pendolare e la sveglia fermata sono pianificati localmente dal dispositivo e possono mostrare nome della fermata, tratta, numero treno, destinazione e orario. Il consenso `POST_NOTIFICATIONS` è richiesto solo quando l'utente crea tale funzione. Eliminare un avviso dall'app ne annulla la pianificazione; le notifiche già visualizzate sono gestite dal sistema operativo.

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

## Alexa Companion

### Quando viene usato

Alexa Companion è opzionale e non è necessario per Android, Wear o il Companion Expo. Rimane inattivo finché non sono configurati database, variabili OAuth e skill Alexa e finché l'utente non completa un consenso autenticato per il collegamento. L'app Android attuale non invia ancora il proprio profilo a questo backend; le tratte personali Alexa sono quelle create nella dashboard del companion o tramite la relativa API autenticata.

L'accesso alla dashboard crea o aggiorna un record utente con identificativo del provider di login (`openId`), nome, email, metodo di accesso, ruolo e date di accesso. Il backend conserva per la dashboard le tratte personali — origine, destinazione, nome, fissaggio e date — e un profilo minimo con identificativo della tratta attiva, direzione, numeri treno monitorati, revisione e date. Il modello esclude espressamente cronologia dei viaggi, posizione, biglietti, snapshot live, audio e credenziali dell'app dal profilo remoto.

Nel collegamento Alexa, il backend conserva l'identificativo Alexa solo come digest HMAC, con date di collegamento, verifica e revoca. I codici OAuth e refresh token sono salvati soltanto come hash; il database conserva anche client ID, redirect URI, scope e date di creazione, consumo, scadenza o revoca. Il codice di autorizzazione scade dopo cinque minuti; il refresh token scade dopo trenta giorni; il token di accesso vocale ha durata dichiarata di un'ora. Scadenza o revoca impediscono l'uso dell'artefatto, ma il codice attuale non implementa una cancellazione automatica delle righe scadute.

### Voce, dati inviati da Alexa e risposte

Amazon Alexa tratta l'audio per riconoscere il comando secondo i propri termini. Alla skill il backend riceve la richiesta firmata da Alexa, il token di account linking, l'identificativo Alexa e gli slot testuali pertinenti, per esempio nome della tratta o numero treno. Il backend non salva il contenuto audio e il codice non registra gli slot di intent come cronologia vocale. Per verificare il collegamento salva soltanto l'hash dell'identificativo Alexa; per rispondere può leggere dal database le tratte personali e pronunciare i loro nomi tramite la piattaforma Alexa.

La skill corrente non recupera dati ferroviari live: per richieste di prossimi treni o stato treno risponde che i dati realtime non sono disponibili. Non invia quindi nome della stazione, posizione o numero treno a un feed ferroviario come parte della risposta Alexa attuale.

### Revoca, cancellazione e conservazione

Nella dashboard la funzione **unlink** imposta `revokedAt` sul collegamento Alexa e sui refresh token attivi. Ogni richiesta della skill verifica lo stato del collegamento nel database, perciò un access token già emesso non deve continuare a leggere le tratte dopo la revoca. La revoca non cancella il record di collegamento, il relativo hash, i token revocati, l'utente, le tratte o il profilo. Un nuovo scambio di autorizzazione con consenso ripristina il collegamento.

L'utente può eliminare le singole tratte dalla dashboard. Il codice non espone una cancellazione completa dell'account, del profilo o di tutti i record OAuth; le chiavi esterne del database prevedono il `cascade` se un utente viene cancellato direttamente dal database, ma questo non equivale a una funzione disponibile all'utente. In assenza di una politica operativa di eliminazione configurata nel repository, i record backend non rimossi restano nel database configurato dal gestore. La sezione iniziale di questa informativa segnala che il recapito del titolare per richieste di accesso o cancellazione è un blocco ancora aperto.

La sessione della dashboard è un cookie HTTP-only con durata di trenta giorni. Il logout cancella il cookie nel browser, ma non elimina account o dati nel database. I log e le copie di sicurezza dell'infrastruttura del database o dell'hosting non sono definiti da questo repository; il titolare dovrà indicarne periodo e gestore prima della distribuzione pubblica.

## Scelte dell'utente e richieste sui dati

| Esigenza | Controllo disponibile nel codice attuale |
|---|---|
| Non usare posizione | Non concedere o revocare il permesso di posizione; la ricerca manuale resta disponibile. |
| Fermare notifiche | Non concedere il permesso, rimuovere il singolo avviso/promemoria oppure disattivare le notifiche nelle impostazioni del sistema. |
| Rimuovere dati Android | Rimuovere le singole voci, cancellare i dati dell'app dal sistema o disinstallare; eliminare separatamente gli eventuali backup JSON esportati. |
| Rimuovere dati Companion Expo | Usare **Cancella archivio locale** per tratte, itinerari e promemoria; cancellare dati/disinstallare per le altre preferenze e rimuovere separatamente file o eventi esportati. |
| Interrompere Wear | Disabilitare la condivisione per nuovi invii; per cancellare la copia già ricevuta, cancellare dati o disinstallare l'app Wear. |
| Scollegare Alexa | Usare **unlink** nella dashboard; ciò revoca il collegamento e i refresh token, senza cancellare i dati descritti sopra. |
| Richiedere accesso, correzione o cancellazione del backend | **Non è configurato un recapito nel repository.** La pubblicazione pubblica resta bloccata finché il titolare non ne indica uno e definisce la procedura. |

## Sicurezza e limiti di questa informativa

Le app Android dichiarano HTTPS e traffico in chiaro disabilitato; le app Android e Companion Expo escludono il backup automatico del loro contenitore. Queste misure non rendono i dati locali automaticamente cifrati dal codice applicativo: le preferenze private descritte sopra non sono ulteriormente cifrate dall'app nel sorgente corrente. Nessun segreto Alexa è inserito nel client Android; il backend Alexa richiede variabili d'ambiente e rifiuta la skill se non sono complete.

Ogni modifica del comportamento implementato richiede il riesame di questa informativa prima del rilascio. In particolare, la sincronizzazione Companion reale, un percorso di cancellazione Wear, un servizio Alexa diverso o l'introduzione di analytics, push o nuove fonti non sono coperti da promesse retroattive di questo documento.

## Riferimenti al codice

Questa informativa è verificabile nelle implementazioni Android [1], Wear [2], Companion Expo [3] e Alexa Companion [4]. Il piano di stabilizzazione registra i limiti che restano bloccanti per la pubblicazione [5].

[1]: https://github.com/fz1101hub/binario/tree/stabilization/v0.20.4/app/src/main/java/it/binario/app "App Android: dati locali, posizione, backup e fonti"
[2]: https://github.com/fz1101hub/binario/tree/stabilization/v0.20.4/wear/src/main/java/it/binario/wear "Companion Wear OS: snapshot locale"
[3]: https://github.com/fz1101hub/binario/tree/stabilization/v0.20.4/companion-expo "Companion Expo: dati locali, promemoria e configurazione"
[4]: https://github.com/fz1101hub/binario/tree/stabilization/v0.20.4/alexa-companion "Alexa Companion: database, OAuth e skill"
[5]: https://github.com/fz1101hub/binario/blob/stabilization/v0.20.4/docs/PIANO_STABILIZZAZIONE_v0.20.4.md "Piano di stabilizzazione Binario v0.20.4"
