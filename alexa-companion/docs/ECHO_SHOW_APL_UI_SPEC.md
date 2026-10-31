# Binario 2.0 — Echo Show: specifica APL e UX

**Stato:** documento APL **implementato** nella skill (`alexa-companion/server/voice/presentation.ts`, vedi [Implementazione](#implementazione-apl)) ma **non attivo** finché il titolare non abilita l’interfaccia Alexa Presentation Language della skill ([azione del titolare](ALEXA_SETUP.md#schermate-echo-show-apl--azione-del-titolare)); skill non pubblicata. Il companion Alexa (`alexa-companion/`, già «Voice Bridge») richiede ancora la configurazione dei segreti Alexa; in assenza di configurazione il webhook risponde con 503. Il rendering sui dispositivi Echo non è ancora stato verificato.

## Principio

Un unico progetto di Alexa Custom Skill `it-IT`, con risposte vocali brevi e documenti Alexa Presentation Language (APL) solo quando la richiesta e il dispositivo supportano APL. Nessun account obbligatorio sull'app Android; per dati personali Alexa richiede esplicito account linking OAuth. I dati di demo non si pronunciano come reali.

## Matrice viewport / dispositivi (da verificare con simulator e hardware)

| Profilo | Layout | Modalità |
|---|---|---|
| Echo Show 5 | Una colonna, una corsa hero + CTA, max 2 righe | APL touch+voice |
| Echo Show 8 | Tabellone compatto 3–5 corse e collegamento alle preferite | APL touch+voice |
| Echo Show 10 | Dettaglio ampio, percorso verticale e stato fonte | APL touch+voice |
| Echo Show 11 | Home a due pannelli, lista + tratta | APL touch+voice se compatibile |
| Echo Show 15 | Dashboard da parete a tre pannelli | APL touch+voice quando skill a pieno schermo |
| Echo Show 21 | Dashboard con più righe e 3 pannelli | APL touch+voice quando skill a pieno schermo |
| Echo Show prima generazione | Fallback per le reali dimensioni viewport | APL solo quando supportata |
| Echo Hub | Vista estremamente compatta, senza pretendere di rimpiazzare la UI smart home | Condizionale alla capacità APL della skill |
| Echo Spot originale (2017) | Una corsa, testo grande, niente tabelle | Solo se APL supportata |
| Echo Spot 2024 | Nessuna pagina APL personalizzata della skill | **Solo voce** |

Non classificare in base al nome commerciale in runtime: leggere le capacità della request e i profili APL/viewport. Non promettere layout su schermate ambientali, homescreen di sistema o dispositivi che non espongono APL.

## Sei schermate

1. **Home**: saluto breve, tratte preferite, le tre intenzioni supportate, stato account e stato dati.
2. **Partenze**: tratta selezionata, treni ordinati per partenza, numero/categoria/binario, qualità della fonte. Se il provider non è live, **nessun tabellone simulato come operativo**.
3. **Stato treno**: numero treno richiesto, esito voce e disponibilità della fonte; nessuna puntualità ipotetica.
4. **Dettaglio corsa**: origine, destinazione, orari previsti/programmatici, fermate e azioni secondarie solo se arrivano dati autorizzati.
5. **Le mie tratte**: preferite e tratte salvate dall'account effettivamente collegato; nessuna tratta personale in caso di collegamento assente.
6. **Account**: stato di collegamento, privacy, permessi, invito a usare l'app Alexa per completare il collegamento.

## Intent della skill già presenti

- `NextTrainsIntent`: «Alexa, chiedi a Binario quali sono i prossimi treni». In assenza di provider live, rispondere con il messaggio previsto dal progetto anziché una lista fittizia.
- `TrainStatusIntent`: «Alexa, chiedi a Binario lo stato del treno 9512». Mai inventare ritardi.
- `MyRoutesIntent`: «Alexa, chiedi a Binario le mie tratte». Restituire solo nomi autorizzati dopo OAuth.

Il documento APL è una proiezione del medesimo risultato letto da Alexa; nessuna seconda sorgente non autorizzata. Per tutti gli intent personali rispettare `LinkAccount` quando token assente/scaduto. Non incorporare segreti nella ViewModel APL.

## Struttura di componenti proposta

- `BrandHeader`: icona originale Binario, titolo, orario dispositivo solo se coerente con capabilities.
- `QualityBanner`: demo/non disponibile/live + fonte e timestamp, mai solo un colore.
- `RouteSelector`: scelta fra le tratte recuperate dopo account linking.
- `DepartureRow`: ora tabulare, destinazione, categoria+numero, binario e stato.
- `TrainHero`: massimo una corsa in evidenza.
- `StopTimeline`: elenco fermate; non stimare posizione reale.
- `VoiceHint`: esempi di utterance e azione «Chiedi ad Alexa».
- `ConnectState`: account non collegato / collegato / errore.

Usare un unico design token repository: `#2E7D32` per componenti con testo bianco, `#388E3C` come riferimento, `#205E2B` sulle testate, `#DDF0DE` per selezione e `#F7F8F5` per superfici. La palette non implica che una skill possa controllare lo sfondo di sistema dei dispositivi Echo.

## Esempio di risposta, se non esiste feed live

**Domanda:** «Alexa, chiedi a Binario i prossimi treni».  
**Voce:** «Per la tua tratta Roma Torino: dati non disponibili in tempo reale».  
**Echo Show:** pannello della tratta personale con stato «Dati non disponibili in tempo reale», invito ad aprire la ricerca e nessun orario inventato.

## Piano APL originario

1. Creare layout JSON APL responsive e data sources **solo** da valori autorizzati.
2. Rilevare il supporto Alexa.Presentation.APL e fallback vocale; gestire i tap tramite UserEvent e verificare la sessione.
3. Collegare gli intent `NextTrainsIntent`, `TrainStatusIntent`, `MyRoutesIntent` del bridge.
4. Verificare funzionalità personalizzate solo dopo account linking OAuth 2.0.
5. Testare testi lunghi, viewport reali, display piccoli, voice-only, offline, scadenze sessione e accessibilità.
6. Non pubblicare prima di completare privacy policy, termini, endpoint firmati, test skill e provider reale per eventuali orari operativi.

**Prototipo:** [binario-echo-preview.html](../../design/binario-echo-preview.html) è un mock interattivo offline. Tutti gli orari di esempio che mostra sono **fittizi**; non è APL e non comunica con Alexa.

**Contratti esistenti:** [`ALEXA_SETUP.md`](ALEXA_SETUP.md), [`server/voice/alexa.ts`](../server/voice/alexa.ts), [`server/voice/presentation.ts`](../server/voice/presentation.ts), [`server/voice/speech.ts`](../server/voice/speech.ts), [`server/profile/contract.ts`](../server/profile/contract.ts).

## Implementazione APL

Stato sul branch `feat/ux2-clients` (fase 4 della roadmap del [design system](../../design/BINARIO_2_DESIGN_SYSTEM.md)).

**Attivazione.** Un interceptor di risposta aggiunge `Alexa.Presentation.APL.RenderDocument` (documento + datasource `binario`) solo se `context.System.device.supportedInterfaces` contiene `Alexa.Presentation.APL` con runtime ≥ 1.4. Senza l’interfaccia (altoparlanti, Echo Spot 2024, interfaccia non abilitata in console) la risposta resta identica: stessa voce, stessa card, stessa chiusura della sessione. Il documento è la proiezione di ciò che il gestore ha appena pronunciato, descritto come modello strutturato; non esiste una seconda sorgente dati e l’ordine delle fonti non cambia.

**Schermate implementate**

| Schermata | Richiesta | Contenuto |
|---|---|---|
| Home (non personale) | avvio, `AMAZON.HelpIntent`, richieste non riconosciute | testo pronunciato, stato del dato, pulsanti «Prossimi treni» e «Le mie tratte», esempi di richiesta |
| Partenze | `NextTrainsIntent`, tocco su una tratta | nome della tratta, «Dati non disponibili in tempo reale», fonte, ora della verifica, nessun orario |
| Stato treno | `TrainStatusIntent` | numero richiesto (o «Numero treno non indicato»), stesso stato del dato, nessuna puntualità |
| Le mie tratte | `MyRoutesIntent`, tocco «Le mie tratte», tratta toccata non più esistente | le stesse cinque tratte lette a voce con l’etichetta «Fissata» testuale, il resto rimandato alla dashboard; stato vuoto se non ci sono tratte |

**Non implementate:** *Dettaglio corsa* (nessun dato autorizzato su orari e fermate da mostrare), *Account* (senza collegamento la skill resta voce + card LinkAccount, nessun documento), *RouteSelector* separato (la lista «Le mie tratte» ne fa le veci), icona/immagine del marchio (il documento non carica risorse remote).

**Componenti.** `BinarioQualityBanner` (stato, fonte, ora: sempre testo), `BinarioRouteRow` e `BinarioAction` (`TouchWrapper` con `role: button`, etichetta VoiceView, `SendEvent`), testata con marchio testuale e titolo, suggerimenti vocali. Tutti i valori arrivano dal datasource; i testi dell’utente sono resi letterali (`&`, `<`, `>` codificati, caratteri non XML rimossi), mai concatenati nel documento.

**Stati obbligatori.** Lo stato prodotto oggi è «non disponibile»: etichetta «Dati non disponibili in tempo reale» (la stessa frase della voce, nelle cinque lingue), «Fonte: nessun provider ferroviario in tempo reale collegato», «Verificato alle HH:MM» in ora italiana con fuso. Il builder supporta anche lo stato *demo* («Catalogo dimostrativo locale — non live», fonte «dati dimostrativi, non operativi») per un futuro catalogo dimostrativo; lo stato *live* non esiste finché non c’è un provider autorizzato.

**Viewport.** Classi lette dal viewport del dispositivo, mai dal nome commerciale: *small* (Echo Show 5, 960×480 dp e inferiori: una colonna), *medium* (≥ 960×540 dp: Echo Show 8, prima generazione, Hub; una colonna con suggerimenti), *large* (≥ 1280×720 dp: Echo Show 10; stato | contenuto), *xlarge* (≥ 1920×1000 dp: Echo Show 15/21; stato | contenuto | suggerimenti), *round* (Echo Spot 2017: una colonna centrata, niente suggerimenti). Il corpo scorre (`ScrollView`) per testi lunghi.

**Token e accessibilità.** Token Binario 2.0 scuri (default dei dispositivi Echo) e chiari (`theme: light`), testata sul token `#205B2A` (≈ `#205E2B` del concept), pulsanti primari `#9BDD9E`/`#0D1710` al buio e `#2E7D32`/bianco al chiaro; tutti i testi ≥ 4,5:1, bordi e focus ≥ 3:1 (verificato nei test). Target touch 64 dp (80 dp su xlarge), stato focus visibile per telecomando/tastiera.

**Sessione e tocchi.** Con un documento a schermo `shouldEndSession` viene omesso: la sessione resta aperta a microfono chiuso, così il documento resta leggibile e i tocchi arrivano alla skill. Ogni `Alexa.Presentation.APL.UserEvent` è accettato solo per `binario.nextTrains` (con il nome tratta mostrato) e `binario.myRoutes`, e ripassa dalla verifica dell’account linking come una richiesta vocale; senza collegamento valido la risposta è la card LinkAccount, senza documento.

**Privacy.** Nessun documento personale prima del collegamento verificato, dopo Stop/Annulla/Home, su `SessionEndedRequest` o dopo un errore; il datasource non contiene token, identificativi Alexa/Binario, origini o destinazioni (verificato nei test).

**Verificato solo staticamente:** il rendering sui dispositivi, VoiceView e i timeout dello schermo vanno provati dal titolare con il simulatore della console e su hardware Echo Show prima della pubblicazione.
