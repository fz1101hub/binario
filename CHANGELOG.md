# Changelog

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
