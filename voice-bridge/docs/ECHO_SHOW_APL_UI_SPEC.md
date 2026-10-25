# Binario 2.0 — Echo Show: specifica APL e UX

**Stato:** design e prototipo locale, **non** skill Echo pubblicata, non documento APL già agganciato al backend. Il Voice Bridge esistente richiede ancora la configurazione dei segreti Alexa; in assenza di configurazione il webhook risponde con 503.

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

## Intent del bridge già presenti

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

## Implementazione APL in una fase successiva

1. Creare layout JSON APL responsive e data sources **solo** da valori autorizzati.
2. Rilevare il supporto Alexa.Presentation.APL e fallback vocale; gestire i tap tramite UserEvent e verificare la sessione.
3. Collegare gli intent `NextTrainsIntent`, `TrainStatusIntent`, `MyRoutesIntent` del bridge.
4. Verificare funzionalità personalizzate solo dopo account linking OAuth 2.0.
5. Testare testi lunghi, viewport reali, display piccoli, voice-only, offline, scadenze sessione e accessibilità.
6. Non pubblicare prima di completare privacy policy, termini, endpoint firmati, test skill e provider reale per eventuali orari operativi.

**Prototipo:** [binario-echo-preview.html](../../design/binario-echo-preview.html) è un mock interattivo offline. Tutti gli orari di esempio che mostra sono **fittizi**; non è APL e non comunica con Alexa.

**Contratti esistenti:** `voice-bridge/docs/ALEXA_SETUP.md`, `server/voice/alexa.ts`, `server/voice/speech.ts`, `server/profile/contract.ts`.
