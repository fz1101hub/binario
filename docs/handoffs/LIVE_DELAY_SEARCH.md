# Ritardi live su tutte le tratte coperte dalle fonti

Le build personali debug e release abilitano `PERSONAL_REALTIME_SOURCES` per impostazione predefinita. La precedente build di test ereditava il valore `false` e non soddisfaceva il caso d’uso dei ritardi. `-PBINARIO_PERSONAL_REALTIME_SOURCES=false` resta disponibile. Le fonti che richiedono credenziali non sono abilitate né contengono segreti nel client.

Per oggi il graph prova prima la riconciliazione diretta dei due tabelloni RFI e poi ViaggiaTreno: partenze per codice stazione, quindi dettagli per origine della corsa, numero e giorno. Appiano S08328 → Ostiense S08406 viene riconosciuta fra le fermate anche quando il capolinea è Tiburtina. Il risultato contiene soltanto gli orari pubblicati delle fermate e le previsioni calcolate dal ritardo pubblicato. Un ritardo è operativo solo con rilevazione recente (massimo cinque minuti, tolleranza futura un minuto) e treno partito, oppure cancellazione esplicita. Mancanze e dati vecchi non diventano zero minuti live. Il fallback programmato resta disponibile; le date future non interrogano il realtime diretto.

Il client usa solo HTTPS, segue al massimo tre redirect fra gli host www.viaggiatreno.it e viaggiatreno.it nel percorso API, rifiuta downgrade HTTP, host estranei, credenziali e porte diverse da 443; usa timeout, risposta limitata a 2 MiB, cache/coalescing di trenta secondi, cooldown sugli errori e massimo tre richieste simultanee. Numero massimo di dettagli per ricerca: dodici. I risultati, il dettaglio e la tratta nel pannello operativo vengono aggiornati ogni trenta secondi mentre la relativa schermata è visibile e l’Activity è STARTED. Un refresh automatico non scavalca uno già in corso né cambia data/ora della ricerca.

## Verifiche

I test sintetici dell’adapter coprono destinazione intermedia, identità e direzione sbagliate, rilevazione assente/vecchia, cancellazione, partenza ritardata ancora prendibile, date future, coalescing e cooldown. I test del wrapper coprono precedenza del live sul programmato, fallback dopo errore e assenza di chiamate live per date future. Queste fixture non provano la disponibilità attuale del servizio esterno.

`RealtimeBuildConfigurationTest` controlla sull’APK installato che il flag e i tre provider operativi siano abilitati. Appartiene alla suite Android deterministica. `ViaggiaTrenoJourneyNetworkTest` è invece un test esterno esplicito per Appiano → Ostiense, attivato da `workflow_dispatch verify_live_route=true`. Di notte consulta le prime corse dalle 05:00: un risultato programmato letto dalla fonte non viene descritto come ritardo operativo verificato. Il test RFI resta separato e interroga direttamente l’adapter programmato, per non passare incidentalmente grazie a ViaggiaTreno.

Da una shell attivata con `/workspace/.binario-tools/activate.sh`:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :wear:testDebugUnitTest :app:lintDebug :wear:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/verify_privacy_policy.py
python3 tools/verify_ci_pinning.py
```

La macchina cloud non ha KVM e il proxy corrente nega `www.viaggiatreno.it` e `prm.rfi.it`. Non tentare di aggirarlo, declassare HTTPS o dichiarare superati i test esterni senza evidenza. I domini richiesti sono stati aggiunti alla bozza di configurazione; il salvataggio non cambia automaticamente il runtime. Il test funzionale Android esterno si esegue sul runner CI con KVM; non esporta risposte del servizio come proxy di rete. Consultare gli esiti CI dello SHA e distinguere suite deterministica e verifica esterna.

Il primo test Android esterno sul commit 8f35fe0 ha ricevuto HTTP 301 (run 38830277070): il fetcher iniziale rifiutava ogni redirect. La gestione HTTPS vincolata e i suoi test correggono quel limite; il test esterno deve essere ripetuto sul commit successivo, senza attribuirgli l’esito della suite deterministica.

## Riferimenti di schema

Gli endpoint ViaggiaTreno sono interfacce pubbliche best-effort, non API open con SLA: `partenze/{codice}/{dataJavascriptCodificata}` e `andamentoTreno/{origine}/{numero}/{giornoMillis}`. Per il contratto dei campi sono stati consultati i client pubblici `matteoredaelli/pyviaggiatreno` e `marcocot/trenitalia-api`; non sono documentazione ufficiale né prova di una risposta attuale. Non usare chiavi di applicazioni terze o dati dimostrativi per superare un errore del servizio.

Il secondo test esterno su 053da3f (run 38831466655) ha identificato un redirect dallo URL HTTPS verso lo stesso URL HTTP. Il client non lo segue: tenta soltanto l’altro host noto viaggiatreno.it in HTTPS, con TLS normale, e rifiuta un successivo downgrade. L’esito reale di questo tentativo deve essere verificato sul nuovo SHA. La ricerca consulta prima il tabellone all’ora selezionata e poi un lookback facoltativo per i treni in ritardo; un errore del lookback non elimina un tabellone corrente valido.

Il tentativo su dbfc24f (run 38832668617) è fallito con redirect alla home HTTP. Il messaggio originale non identificava l’host emittente. La variante successiva tenta l’API originale sull’altro host HTTPS anche in quel caso e include l’host emittente nell’errore, per verificare il limite senza attribuzioni presunte.


## Copertura comune dei ritardi

La priorità di prodotto è il ritardo live per tutte le tratte coperte: Appiano–Ostiense è un caso di regressione. Il graph collega ora i Trip Updates STA e SNCF al percorso comune di ricerca, widget e worker. Le corse embedded conservano fonte statica, trip_id, data di servizio e coppie stop_id/sequence; vengono risolti soltanto gli aggiornamenti delle corse richieste. Nessuna associazione fra feed diversi o basata sul solo numero commerciale. Osservazioni scadute, future, di altro giorno, fermate NO_DATA/SKIPPED e corse non SCHEDULED non diventano ritardi verificati. Un update solo in arrivo non verifica la partenza. Le previsioni non diventano eventi effettivi.

Il monitor live RFI non è più limitato ai circa venticinque hub della mappa storica: le altre stazioni vengono risolte dal select ElencoLocalita del catalogo ufficiale HTTPS. Match esatto di nome/alias normalizzato, omonimie rifiutate, cache di un giorno e cooldown sugli errori. Il monitor può arricchire una corsa programmata anche quando la destinazione cercata è intermedia; senza un percorso programmato il fallback diretto resta limitato alla destinazione pubblicata dal monitor. Italo usa le stazioni previste dal suo adapter. European Sleeper/JBB conserva il raccordo interno con il proprio feed statico di fallback. Svizzera GTFS-RT, DB e Trenord E015 richiedono credenziali/autorizzazioni e non vengono dichiarati operativi.

Su 9fbbb01 il test Android ViaggiaTreno 38834056109 ha confermato che anche l’host HTTPS senza www rimanda alla home HTTP. Resta un blocco esterno reale; il client non declassa il trasporto e un build verde non lo risolve.

Il workflow manuale `Live source acceptance` esegue test funzionali separati per fonte. Il parametro sources è una lista esplicita fra rfi,sta,sncf,italo,european-sleeper,viaggiatreno. Il workflow si esegue anche sulle PR che modificano adapter, GTFS o la verifica esterna. RFI verifica il catalogo generico e il monitor Milano Centrale; Italo il monitor Roma Termini; STA e SNCF richiedono aggiornamenti recenti e almeno un match campionato con il GTFS incluso. Questi test non provano l’intera copertura né la presenza di ritardo per ogni corsa. Sono esclusi dalla suite deterministica e non esportano i payload.


La prima accettazione su fea0141 (38838184996) ha superato SNCF e catalogo/monitor RFI Milano, ma ha rilevato assenza di corse operative Italo e sulla tratta regionale RFI. Il primo campionamento STA/European Sleeper non trovava identità GTFS: il controllo successivo seleziona prima l’intersezione esatta dei trip_id con il relativo archivio statico per non campionare autobus estranei al pack ferroviario. Un’intersezione vuota resta un errore esplicito. Non correggere gli identificativi con confronti fuzzy.

Italo viene ora letto dal servizio JSON della pagina pubblica, RicercaStazioneService?CodiceStazione=..., e dal catalogo stationList/stationCoding della pagina /it/stazione. I codici Italo restano distinti da RFI e ViaggiaTreno; nuove stazioni vengono risolte solo dal catalogo ufficiale, con omonimie rifiutate. Il vecchio parser HTML resta compatibile con le fixture ma non è la sorgente HTTP della build. Campi mancanti e timestamp scaduti non verificano lo stato; una previsione derivata non viene presentata come ritardo ufficiale.


La verifica successiva su 74bf05d (38839453725) ha confermato gli URL STA/European Sleeper ma nessun trip_id comune con il rispettivo statico: il campione STA è una corsa autobus 87-421; quello European Sleeper contiene ES-452-2026-10-11 mentre lo statico corrente pubblica corse successive. Non sostituire gli ID con euristiche. Il quadro RFI programmato è andato in timeout. La pagina Italo non espone più il catalogo in JSON inline: il resolver conserva un catalogo curato di codici Italo come fallback, con validazione dell'identità nella risposta del tabellone.

Il graph dispone ora di RfiLiveRouteDataSource: verifica la stessa corsa nei monitor partenze/arrivi di origine/destinazione, per numero pubblico, operatore, ordine temporale e match univoco. Questo riconosce anche fermate intermedie senza dipendere da prm.rfi.it. Non inventa fermate fra i due punti richiesti e non propaga il ritardo in partenza all'arrivo senza una previsione pubblicata lì. La finestra dei monitor è ancorata all'ora attuale; gli aggiornamenti scaduti e i match ambigui vengono rifiutati. Cache/coalescing di trenta secondi per stazione e direzione.

Il workflow Refresh static counterparts of live feeds acquisisce soltanto gli archivi HTTPS OPEN_DIRECT registrati per STA/SNCF tramite la pipeline esistente, controlla hash/filtro/copertura di trenta giorni, sincronizza le date Kotlin e registra provenienza su un ramo dati separato. Non modifica main né pubblica una release. L'esito del refresh va verificato prima di incorporarlo nell'APK.
