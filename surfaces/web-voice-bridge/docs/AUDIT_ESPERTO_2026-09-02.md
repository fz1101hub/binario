# Audit esperto Binario — 2 settembre 2026

## Perimetro e metodo

Questo audit copre il core web `binario-voice-bridge`, il companion Expo `binario-companion-native`, Android, Widget/Tile Wear OS, iOS/WidgetKit/Apple Watch predisposti in sorgente e l’integrazione Alexa. Valuta il prodotto in base a evidenze di codice, test, build e documentazione disponibili nell’ambiente; non sostituisce certificazioni, pareri legali, test hardware, autorizzazioni commerciali o configurazioni di store/fornitore non eseguite.

> **Scala:** P0 blocca un rilascio o può produrre danno rilevante; P1 è un rischio alto da trattare prima dell’espansione funzionale; P2 migliora affidabilità, accessibilità o conversione; P3 è ottimizzazione pianificabile. Lo stato “verificato” richiede prova automatica o build; “predisposto” indica codice non collaudato su piattaforma; “esterno” richiede un soggetto o un ambiente non disponibile.

| Dominio | Criterio | Evidenze da raccogliere |
| --- | --- | --- |
| Prodotto e UX | Utilità al primo avvio, chiarezza degli stati, assenza di frizioni inutili, percorsi completi. | Home, pianificazione, errori, empty state, copy, screenshot e test UI. |
| Dati ferroviari | Unica semantica di provenienza, timestamp, TTL, confidence e fallback; nessun dato inventato. | Snapshot, governance provider, alert, Alexa, watch e test fail-closed. |
| Privacy e dati personali | Minimizzazione, default protettivi, finalità, retention, diritti e accountability. | Flussi local-first, audio, localizzazione, export/cancellazione, contratti fornitori. |
| IA e voce | Trasparenza, consenso, editabilità, supervisione umana, limiti di output e logging non invasivo. | Dittatura web/mobile/watch/Alexa, documentazione e test. |
| Sicurezza | Autorizzazione, input validation, rate limiting, segreti, supply chain, disclosure e recovery. | Router, storage, pairing, audit, SBOM, policy e build. |
| Accessibilità | WCAG 2.2 AA, tastiera, screen reader, contrasto, motion e target touch. | Semantica UI, tema, test, audit manuale da pianificare. |
| Native e distribuzione | Offline, lifecycle, battery/network, notifica, watch, firma, store e device test. | Expo config, artefatti Android/Wear, sorgenti Apple e checklist. |
| Operatività | Osservabilità privacy-preserving, incident response, release process, SLO e gestione fonti. | Diagnostica, runbook, provider governance, audit e documenti release. |

## Riferimenti di valutazione

La Commissione europea riassume per il GDPR i principi di liceità/trasparenza, limitazione della finalità, minimizzazione, limitazione della conservazione, accuratezza, integrità-riservatezza e accountability. Indica inoltre che l’informativa deve essere chiara e che il titolare deve poter dimostrare il rispetto dei principi. [1] Le linee guida EDPB sull’articolo 25 richiedono protezione dei dati by design e by default lungo il ciclo di vita e revisione continua delle misure. [2]

Per le funzionalità vocali e di trascrizione, l’AI Act adotta un approccio basato sul rischio. Le linee guida della Commissione per l’articolo 50, applicabile dal 2 agosto 2026, richiedono che le persone siano esplicitamente informate quando interagiscono direttamente con un sistema IA nelle fattispecie rilevanti. [3] L’audit non classifica giuridicamente Binario: valuta invece le misure tecniche di trasparenza, revisione ed esclusione di decisioni automatizzate ad alto impatto.

WCAG 2.2 è la Recommendation W3C corrente e il W3C raccomanda di usarla per massimizzare l’applicabilità futura delle attività di accessibilità. Introduce, fra l’altro, criteri su focus non oscurato, dragging, dimensione minima dei target, aiuto coerente, inserimento ridondante e autenticazione accessibile. [4] La guida WCAG2Mobile applica in modo informativo tali criteri alle app native e include orientamento, reflow, gesti, target touch e messaggi di stato; non copre gli indossabili, per i quali occorre anche la verifica specifica della piattaforma. [5]

Il Cyber Resilience Act si applica lungo pianificazione, progettazione, sviluppo e manutenzione dei prodotti digitali e richiede gestione delle vulnerabilità durante il ciclo di vita; secondo la Commissione europea, gli obblighi principali applicheranno dall’11 dicembre 2027 e gli obblighi di segnalazione dal’11 settembre 2026. [6] ENISA considera la disclosure coordinata essenziale perché consente correzione o mitigazione prima della divulgazione pubblica. [7] L’audit tratta quindi SBOM, intake vulnerabilità, triage, patch, comunicazione agli utenti e release rollback come un processo unico, non come documenti isolati.

| Fonte | Impiego nel dossier |
| --- | --- |
| GDPR — principi della Commissione europea [1] | Privacy, retention, informative, diritti e accountability. |
| EDPB — Guidelines 4/2019 [2] | Privacy by design/default e documentazione delle misure. |
| Commissione europea — AI Act [3] | Trasparenza, gestione del rischio e governance dell’IA. |
| W3C — WCAG 2.2 [4] | Obiettivo di accessibilità web e criteri A/AA verificabili. |
| W3C — WCAG2Mobile [5] | Applicazione informativa di WCAG alle app native per telefono/tablet. |
| Commissione europea — Cyber Resilience Act [6] | Lifecycle cyber, aggiornamenti e reporting. |
| ENISA — Vulnerability Disclosure [7] | Processo coordinato di ricezione, mitigazione e divulgazione. |
| Google Play — Data safety [8] | Accuratezza delle dichiarazioni dati e privacy policy per ogni app pubblicata. |
| Apple — App privacy details [9] | Privacy Nutrition Label, SDK di terzi e responsabilità delle disclosure. |
| Android Developers — Wear OS quality [10] | Qualità, target SDK, accessibilità, package/signing e listing Wear. |
| Apple — App Review Guidelines [11] | Completezza, metadati, sicurezza e test del binario prima della revisione. |

## Prime osservazioni da validare nelle fasi successive

Il modello local-first e la governance fail-closed sono una scelta architetturale solida per il rischio ferroviario e privacy. Il maggiore gap di prodotto non è un componente UI: è l’assenza deliberata di una fonte ferroviaria ufficiale autorizzata. Finché resta così, Binario deve presentarsi come pianificatore personale e companion, non come informatore realtime o canale di acquisto.

Le aree ad alto impatto da approfondire sono: definizione del servizio minimo utile senza realtime, modello operativo per eventuali provider, verifica accessibilità su device reali, lifecycle di sicurezza e vulnerability management, realizzazione e collaudo Apple, ergonomia delle richieste vocali watch, qualità di onboarding e misure verificabili di retention audio.

## Architettura, qualità del software e sicurezza

### Punti di forza verificati

Il nucleo dichiara in modo esplicito `dataNature`, `confidence`, provider, osservazione e scadenza nello snapshot comune. Gli avvisi sono soppressi quando un dato realtime è scaduto, in conflitto o indisponibile; lo scadere esatto del TTL è trattato come non fresco. La governance parte con provider non configurato, kill switch attivo e `providerCount` zero. Questa è una base robusta contro la più grave classe di errore del dominio: presentare un’informazione ferroviaria come corrente senza poterla dimostrare.

Le procedure personali sono protette e le operazioni pubbliche principali hanno validazione Zod. La dettatura convalida base64 e MIME, impone 16 MB e azzera il buffer in memoria. La codebase web dispone di test di contratto, UI, router e sicurezza; il companion mantiene contratti TypeScript puri per snapshot, notifiche, dettatura e watch. Le build Android/Wear e quella web sono state eseguite prima di questo audit.

### Rischi e raccomandazioni

| Priorità | Evidenza | Rischio | Miglioramento raccomandato e criterio di chiusura |
| --- | --- | --- | --- |
| **P0** | L’audio della dettatura viene caricato in `dictation/ephemeral/`, ma l’astrazione storage espone put/get e non delete. | “Effimero” descrive il prefisso, non prova la cancellazione del file o la retention del fornitore. | Aggiungere un delete server-side verificabile dopo la trascrizione **oppure** usare un fornitore/contratto con TTL attestabile; registrare esito di cancellazione senza conservare audio. Test: upload → trascrizione → delete chiamato anche su errore. |
| **P0** | Il modulo Wear `release` usa la chiave debug di Android. | Un artefatto installabile per test non è distribuibile come release pubblica affidabile. | Creare una chiave release in un gestore segreti, configurare firma per variante, separare canale internal testing e prod, verificare certificate fingerprint e Play App Signing. Nessuna chiave nel repository. |
| **P1** | La dettatura pubblica e il pairing calcolano il rate-limit da `x-forwarded-for` e il guard è in memoria. | Un header non attendibile può essere manipolato; su più istanze/autoscaling il limite non è globale e si resetta al riavvio. | Stabilire esplicitamente il proxy fidato, usare IP derivato dall’infrastruttura o una quota edge/distribuita con finestra, limiti per costo e circuit breaker del provider. Test: header spoofed, multi-request, errore upstream e degradazione. |
| **P1** | Il parser JSON globale Express ammette 50 MB, mentre la trascrizione ne accetta 16 MB solo dopo il body parsing. | Una richiesta pubblica grande può consumare memoria prima della validazione applicativa. | Applicare limite più basso globale e un parser/endpoint strettamente dedicato al payload audio; rifiutare per `Content-Length` e timeout prima della decodifica. Misurare con test di carico controllati. |
| **P1** | `loadCanonicalStationCatalog()` memoizza una singola Promise di rete senza retry, reset dopo fallimento, manifest versione o controllo d’integrità. | Un errore transitorio rende l’autocomplete inutilizzabile per tutta la sessione; il file può cambiare senza strategia cache esplicita. | Pubblicare un manifest versionato con checksum, ETag/cache strategy, retry esponenziale con reset della Promise e fallback UI. Testare offline, 5xx, aggiornamento catalogo e regressione formato. |
| **P1** | Il planner mobile usa testo libero per stazioni/data/ora e non conserva il codice canonico della stazione. | Omografie, errori di dettatura e date/orari non validi riducono parità, deep link e futura interoperabilità con fonti autorizzate. | Portare il catalogo canonico in un indice offline o scaricabile verificato; usare autocomplete e controlli data/ora nativi, persistendo codice e label. Il salvataggio deve rifiutare input non selezionato, salvo modalità esplicita “nota libera”. |
| **P1** | I campi `travelDate` e `travelTime` del router web hanno solo limite di lunghezza. | Dati non ISO possono entrare da client diversi, produrre reminder errati o introdurre incoerenza fra superfici. | Validare semanticamente `YYYY-MM-DD` e `HH:mm`, normalizzare timezone e rifiutare date impossibili; aggiungere property tests sui confini ora legale. |
| **P2** | Il plugin Expo concentra circa 468 righe di Kotlin/Gradle/manifest serializzati in stringhe JavaScript. | Manutenibilità e review fragili; errori di escaping o drift tra plugin e progetto generato sono già un rischio concreto. | Estrarre template `.kt`/`.gradle` versionati, testare il rendering del plugin e compilare Android/Wear in CI su ogni modifica. Conservare sorgenti generati come artefatti confrontabili o rigenerarli deterministicamente. |
| **P2** | Web e native hanno TypeScript/Vitest e build manuali, ma non emergono workflow CI, lint check, Prettier check, coverage gate o E2E. | Le regressioni dipendono dall’esecuzione manuale e gli artefatti non sono attestati per commit. | Aggiungere CI con install bloccato da lockfile, typecheck, test, format-check, build, audit non bloccante con policy, SBOM firmato e artefatti. Introdurre soglie di coverage per contratti critici e Playwright/Detox per i percorsi P0. |
| **P2** | Il pairing legacy resta disponibile nel backend pur non essendo un flusso standard. | Una capability ad alta sensibilità può riemergere accidentalmente in UI o integrazioni future. | Proteggerla con feature flag server-side disabilitato di default, audit log minimizzato, scadenze brevi, revoca globale e test che il primo avvio non la invoca. |
| **P2** | Lo snapshot è coerente ma la provenienza è a livello di oggetto. | In futuro binario, ritardo, composizione e avviso potrebbero provenire da fonti o tempi diversi. | Evolvere a “fact envelope” per campo: valore, provider/licenza, osservato, scade, confidence, conflitto e motivo. L’UI deve poter sopprimere soltanto il campo non affidabile. |
| **P3** | `Home.tsx` e la shell di dashboard concentrano molte responsabilità; l’area personale include planner, archivio, preferiti, ricerca e companion. | Aumenta il costo di modifica e di test visuale. | Estrarre feature components e view models puri; mantenere Home come composizione. Criterio: moduli sotto una soglia di complessità concordata e test dei comportamenti senza rendering totale. |

L’esecuzione attuale ha superato `pnpm check`, **110 test Vitest** e `pnpm build`. Il bundle web principale compresso resta significativo (circa 142 kB gzip per il chunk React vendor e 40 kB per quello applicativo); prima di aggiungere funzioni, misurare LCP/INP su rete mobile e impostare un performance budget per chunk, CSS e catalogo stazioni. L’audit dipendenze web ha rilevato due advisory **moderate** di `qs` (`GHSA-x5fp-wj9c-mxmx` e `GHSA-4mjr-xmp4-gh2g`) nella versione 6.15.3, raggiunta attraverso Express/body-parser e l’adapter Alexa; la versione corretta dichiarata è `>=6.16.0`. La remediation è un aggiornamento mirato della catena e relativa suite, non `audit --fix` indiscriminato.

### Decisione architetturale raccomandata

Il prossimo incremento non dovrebbe essere una nuova UI realtime. Dovrebbe essere un **Rail Facts Gateway** con adapter per fonte autorizzata, registry contrattuale, cache per campo, prova licenza, monitoraggio freschezza, circuit breaker, test di conformità provider e un replay dataset autorizzato. Le applicazioni, Alexa e watch dovrebbero consumare soltanto questo contratto, mai endpoint di operatori direttamente. Fino alla sua attivazione, l’attuale comportamento `unavailable` va mantenuto.

## Esperienza, accessibilità e governance dei dati

### Evidenze UX osservate

La Home comunica bene il comportamento prudente: il planner è dominante, la trasparenza “Dati realtime non disponibili” è visibile e l’utente non viene indotto a credere che nessun disservizio equivalga a nessun dato. La pagina di trasparenza è particolarmente riuscita: rende visibile il registro provider, il kill switch e le condizioni di pubblicazione senza tecnicismi superflui.

La stessa pagina personale, però, accumula planner, viaggi, tabellone, preferiti, avvisi, stato dati, Alexa, smartwatch, privacy e portabilità. La densità riduce la gerarchia quando l’utente deve solo preparare una tratta. La verifica diretta di `/tabellone` ha inoltre mostrato un 404 generico in inglese, non coerente con lingua, brand e modello fail-closed di Binario. Non è prova che i deep link canonici siano rotti, ma è una regressione del percorso di errore e di link manuali.

| Priorità | Evidenza | Rischio per utente o business | Raccomandazione e misura di esito |
| --- | --- | --- | --- |
| **P1** | Le tratte ospite vivono in `localStorage`; dopo il login la vista autenticata passa al repository personale senza un percorso visibile di migrazione. | L’utente può credere che le tratte siano scomparse o essere spinto a creare account senza capire il cambio di archivio. | Offrire, solo dopo accesso, una scelta esplicita: “Importa N tratte da questo browser”, “Mantieni locali”, “Elimina”. Nessun trasferimento automatico. Testare doppio consenso, deduplicazione e logout. |
| **P1** | Il value proposition senza realtime non è ancora formalizzato come prodotto completo. | Il confronto con app di orari può generare aspettative errate e abbandono. | Definire una “promessa v1” misurabile: pianificazione privata, stazioni affidabili, preparazione viaggio, promemoria, checklist e companion; usare copy coerente su store/onboarding e non dare prominenza a binari/ritardi finché inattivi. |
| **P2** | Home lunga e con molte card di pari peso. | Scansione lenta, sovraccarico cognitivo, azione primaria meno evidente su mobile. | Riordinare in tre livelli: **Prepara il viaggio**, **I tuoi viaggi**, **Contesto e impostazioni**. Spostare privacy, portabilità e Alexa in pagine/accordions secondari senza nasconderli. Misurare completion del primo viaggio e tempo al primo reminder. |
| **P2** | La pagina 404 è generica e in inglese. | Deep link manuali o condivisioni errate rompono fiducia e coerenza. | Progettare un errore Binario localizzato con “Torna ai viaggi”, “Cerca stazione” e messaggio che nessun dato ferroviario è stato richiesto. Testare route sconosciuta, codice stazione non valido e URL malformato. |
| **P2** | Il catalogo stazioni è eccellente sul web, ma il companion usa testo libero. | Esperienza e qualità dati divergono fra piattaforme. | Considerare il catalogo come feature di prodotto comune, con UX adattiva e indice offline mobile. Ogni superficie deve mostrare codice canonico quando rilevante, senza esporre codici come obbligo all’utente. |
| **P2** | Il watch può ricevere domande vocali ma ha vocabolario e azioni limitate. | Riconoscimenti ambigui o risposte non comprese su schermo piccolo. | Disegnare una matrice di intent con esempi, conferma concisa della tratta interpretata, annullamento, retry e output testuale + TTS. Monitorare solo metriche aggregate e opt-in, mai transcript grezzi. |
| **P3** | Manca una specifica product analytics privacy-preserving. | Senza feedback non si può migliorare onboarding e affidabilità percepita; con telemetria eccessiva si viola la promessa privacy-first. | Definire eventi locali aggregati, opt-in, senza località/tratte/audio/identificativi e con retention dichiarata. Misurare funnel di funzionalità, non persone. |

### Accessibilità e inclusione

Il codice mostra segnali favorevoli: etichette accessibili, live region per gli stati, focus visibile, tema e preferenze di contrasto/movimento. Tuttavia non esiste una prova di conformità WCAG 2.2 AA: la validazione automatica non sostituisce test con tastiera, zoom, screen reader, sintesi vocale e device. Il W3C sottolinea proprio che la conformità richiede una combinazione di valutazione automatica e umana. [4]

Il piano di verifica dovrebbe coprire: navigazione da tastiera senza trap e con focus sempre visibile; 200–400% di zoom e reflow; lettura VoiceOver/TalkBack/NVDA; contrasto di testo, icone e stati disabilitati in chiaro/scuro/alto contrasto; target touch; ordine e annunci di errore; cancellazione/annullamento di recording e richieste vocali; assenza di solo-colore per realtime/non realtime; riduzione del movimento. Per watch, aggiungere test su schermo piccolo, gesture alternative e volume/TTS: WCAG2Mobile non include wearable e non può essere dichiarato sufficiente da solo. [5]

### Privacy, IA e retention

| Priorità | Evidenza | Rischio | Miglioramento raccomandato |
| --- | --- | --- | --- |
| **P0** | Il repository consente `personalNote`, `accessibilityNote` e profilo `accessibility`. | Le note potrebbero contenere dati sensibili; “accessibility” può rivelare bisogni di salute/disabilità. | Rimuovere l’invito a inserire dettagli sanitari, fornire avviso contestuale, privilegiare locale-first e condurre DPIA/valutazione del titolare prima di sync/cloud. Limitare ruoli/accesso e retention. |
| **P1** | Codici OAuth, refresh token, dispositivi, pairing e link Alexa hanno scadenze nel dato ma non emerge un job di purge dei record scaduti. | La scadenza logica riduce accesso ma lascia metadati/artefatti più a lungo del necessario. | Definire retention per ciascuna tabella e un cleanup idempotente con report minimizzato; allineare export/delete utente e backup. Non usare cron finché non è definito ownership operativo. |
| **P1** | La dettatura web è IA/servizio esterno; la mobile usa input di sistema senza upload. | Informativa, base giuridica, subfornitore, area di trattamento e retention non sono ancora una prova completa. | Pubblicare notice a livelli prima del consenso: scopo, destinatario, durata, alternativa manuale, come ritirare il consenso. Documentare DPA/trasferimenti e testare rifiuto/errore. |
| **P1** | I comandi vocali watch inviano al telefono testo effimero per query realtime. | Anche senza persistirlo, un nome di stazione o tratta può essere personale nel transito/logging. | Imporre no-log di transcript, limite, cancellazione in memoria, UI di consenso per la prima richiesta e una policy di telemetria; le richieste realtime devono restare inattive senza fonte autorizzata. |
| **P2** | Esistono export e delete per l’area autenticata, ma la portabilità non copre automaticamente i dati locali browser/app/watch. | L’utente può non sapere dove restano copie locali o cache companion. | Creare una “mappa dei dati sul dispositivo”: browser, app, watch, cloud opzionale, audio; offrire clear-local-data guidato e spiegare backup OS senza promesse non verificabili. |
| **P2** | L’IA interpreta audio/testo, ma output e limiti sono descritti in punti diversi. | Trasparenza frammentata e over-reliance su parsing automatico. | Usare un’etichetta coerente “Trascrizione assistita: verifica prima di salvare”; vietare esecuzione automatica di prenotazioni, notifiche o cambi senza conferma. Conservare test di parsificazione avversa. |

La base local-first è coerente con minimizzazione e privacy by default; resta però necessaria un’implementazione organizzativa dimostrabile, non solo una buona intenzione. La Commissione europea richiede informativa su finalità, basi, durata, destinatari, trasferimenti e diritti, oltre a misure di sicurezza e accountability. [1] L’EDPB evidenzia che la protezione by design/default deve essere valutata e aggiornata in modo continuo nel ciclo di vita. [2]

## App, widget, smartwatch, Alexa e distribuzione

### Stato delle superfici

Il companion ha una base locale coerente: AsyncStorage per tratte, promemoria pianificati esplicitamente non realtime, bridge con payload ridotto, nessun microfono app-side per il planner e una richiesta Wear basata sul riconoscimento vocale di sistema. La semantica degli alert watch è ben separata: reminder locale contro eventi realtime ammessi esclusivamente con snapshot fresco. L’APK contiene il bundle JavaScript e non dipende da Metro per l’esecuzione installata.

Alexa verifica configurazione prima di esporre il webhook, usa la verifica di Alexa SDK e distingue query pubbliche da tratte personali opzionalmente collegate. Fino a quando non esiste un provider ferroviario autorizzato, le risposte operative restano fail-closed. Questo è un design corretto; non equivale a una Skill pubblicata o certificata su Amazon.

| Priorità | Evidenza | Rischio o requisito | Raccomandazione e definizione di pronto |
| --- | --- | --- | --- |
| **P0** | Il modulo Wear usa `applicationId im.binario.companion.wear`, mentre l’app telefono usa `im.binario.companion`; entrambe le release sono firmate debug. | Le linee guida Wear OS richiedono per app e companion lo stesso package name e signing key; una chiave debug non è idonea a pubblicazione. [10] | Ridisegnare il packaging secondo il modello Wear ufficiale prima di Play, con application ID/signing compatibili, variante release sicura e Play App Signing. Verificare con bundle finale e Console, non solo con `assembleRelease`. |
| **P0** | iOS, WidgetKit e watchOS sono predisposti solo come file/handoff. | Mancano target membership, App Group effettivo, firma, build Xcode, test simulator e hardware. | Completare la pipeline macOS: target, entitlement, provisioning, privacy manifests, TestFlight e test iPhone/Apple Watch. Non promuovere la parità Apple come “disponibile” prima del collaudo. |
| **P1** | Il Tile Wear non dichiara ancora un preview verificato; l’attività vocale usa layout `TextView` essenziale e TTS immediato. | Le quality guidelines richiedono preview Tile, target touch 48dp, sfondo nero, supporto font size, back navigation, stato e test su 192/227dp. [10] | Aggiungere preview, layout Compose/ProtoLayout accessibile, contrasto e dimensione touch verificabili, setting “leggi risposta ad alta voce”, haptic leggero, annullamento e retry. Eseguire test fisico/emulatore piccolo e grande. |
| **P1** | La richiesta realtime watch invia transcript breve al telefono e attende 12 secondi. | Connessione intermittente, timeout e TTS possono lasciare un’azione ambigua o esporre testo sensibile nell’ambiente. | Mostrare stato in attesa, annulla, retry e risposta visiva; non loggare transcript; inviare request ID effimero e associare solo la risposta corrispondente; valutare privacy lock-screen per payload. |
| **P1** | Planner mobile con campi liberi, promemoria fissato a 15 minuti e feedback in-card. | Qualità inferiore al web, inserimenti ripetuti, nessuna scelta reminder e minore accessibilità su tablet. | Autocomplete canonico offline, picker nativi data/ora, stepper reminder 5/10/15/30/60, scelta disattiva, visualizzazione timezone e conferma di riassunto prima di salvare. Test portrait/tablet/font scale/rotazione. |
| **P1** | App config usa `POST_NOTIFICATIONS` e integra Expo Notifications; non esistono ancora Privacy Manifest/risposte store finali. | Google/Apple richiedono dichiarazioni esatte che includono SDK terzi; anche app senza dati devono fornire privacy policy in Play. [8] [9] | Costruire un data map per versione/SDK/permesso, privacy policy pubblica, Data safety Play e Privacy Nutrition Label Apple. Rivedere a ogni dipendenza o endpoint nuovo; non dichiarare “nessun dato” senza attestarne i SDK. |
| **P2** | Versione root 0.1.1/code 3 e modulo Wear 0.1.0/code 1; no pipeline release comune. | Diagnostica, compatibilità e rollback cross-surface possono divergere. | Definire release train unico: compatibilità contratto, versioni semantiche, migration policy, changelog, feature flags e kill switch remoto solo se privacy-preserving. |
| **P2** | I sorgenti Android specifici sono rigenerati da plugin Expo; i target Apple sono handoff manuale. | Il rischio di differenza fra codice condiviso, output nativo e binary distribuito cresce con ogni release. | Definire “source of truth” per ogni piattaforma, script deterministico di prebuild, diff check e matrice compatibilità schema. L’output Xcode va riproducibile o descritto mediante patch/xcconfig versionati. |
| **P2** | Alexa non è configurata nella console Amazon e non ci sono test certificazione/integrazione account linking esterni. | Non può essere promossa come Skill disponibile; endpoint pubblici e OAuth richiedono valutazione end-to-end. | Preparare interaction model, privacy policy, test utterance, card/error strategy, verifica firma/timestamp, review notes e controllo produzione. Mantenere account linking opzionale e mai esporre password/OTP ferroviari. |
| **P2** | Biglietteria è un contratto inattivo con allowlist rigorosa. | Una futura UX può ricadere in open redirect o claims commerciali non autorizzati. | Trattare ogni venditore come onboarding separato: contratto, licenza, host pinning, review legale, monitoraggio URL e test di regression. Aprire esternamente il sito ufficiale senza callback di pagamento. |

### Readiness store e piattaforme

Google richiede una Data safety section accurata per ogni app pubblicata, incluse pratiche di SDK terzi, e una privacy policy anche in assenza di raccolta dati. [8] Apple richiede la dichiarazione delle pratiche dell’app e dei partner terzi; specifica inoltre che dati solo on-device non sono “collected” se non inviati fuori dal dispositivo, ma ciò non elimina l’obbligo di descrivere gli altri flussi effettivi. [9] Entrambe le piattaforme richiedono inoltre metadati accurati e build realmente funzionanti. [11]

La release Android/Wear attuale è quindi adatta a **test tecnico interno**, non a distribuzione pubblica. La priorità non è aggiungere funzioni: è costruire una release pipeline con signing, package alignment, privacy declarations, screenshot reali, crash/performance testing, test device matrix, policy della batteria e revisione del comportamento offline. Per iOS vale lo stesso principio, ma il blocco è precedente: prima va completata l’integrazione Xcode.

## Valutazione di maturità e roadmap raccomandata

Le valutazioni seguenti non sono una certificazione; sono una scala interna da **1 (non pronto)** a **5 (operativo e misurato)** derivata dalle evidenze di codice, build e test disponibili al 2 settembre 2026.

| Dimensione | Maturità | Motivazione sintetica | Obiettivo successivo |
| --- | ---: | --- | --- |
| Onboarding e uso senza account | 4/5 | Local-first effettivo, pianificazione e promemoria senza login; rimane la migrazione esplicita post-login. | Rendere le copie locali e cloud comprensibili e reversibili. |
| Affidabilità del dato ferroviario | 2/5 | Policy fail-closed e contratti robusti, ma nessuna fonte ufficiale attiva. | Rail Facts Gateway e onboarding contrattuale di una fonte. |
| Privacy by design | 3/5 | Minimizzazione e local-first buoni; retention, notice, DPA e dati accessibilità non sono ancora chiusi. | Data inventory, retention attuabile, DPIA proporzionata e store disclosures. |
| Sicurezza applicativa | 3/5 | Contratti, test, hashing e fallback; rimangono body limit, rate-limit distribuito, advisory e canale disclosure. | Patch `qs`, edge controls, deletion audio, security contact e CI. |
| Accessibilità e UX | 3/5 | Buona intenzione e controlli base; audit manuale/device e semplificazione gerarchia mancanti. | Test WCAG e journey-first redesign. |
| Android e Wear | 2/5 | Build e contratti testati; signing, package alignment, visual quality e test device bloccano lo store. | Internal track firmata, device matrix e quality checklist Wear. |
| iOS e Apple Watch | 1/5 | Codice/handoff predisposti, nessuna integrazione Xcode o build. | Target reali, entitlements, TestFlight e device test. |
| Alexa e assistenti vocali | 2/5 | Backend prudente e account opzionale; nessuna console/Skill testata in produzione. | Interaction model, console setup e test certificazione. |
| Operatività e delivery | 2/5 | Build/test manuali e SBOM; mancano CI, SLO, runbook, owner e release management. | Pipeline attestate, incident playbook, metriche privacy-preserving. |

### Roadmap a gate

| Finestra | Decisione e risultati da ottenere | Criterio oggettivo per passare al gate successivo |
| --- | --- | --- |
| **0–2 settimane — sicurezza e verità del prodotto** | Aggiornare `qs` a versione corretta e testare; implementare o attestare delete/TTL dell’audio; ridurre body parser; definire security contact reale; decidere se rimuovere/sospendere i campi accessibility cloud; correggere 404 e separare Home in journey. | `pnpm audit --prod` senza advisory ad alta/moderata accettata; prova di retention audio; policy/notice reali; nessun copy che suggerisce realtime attivo. |
| **2–6 settimane — qualità release locale-first** | CI per web/native, coverage/format/lint, test E2E P0, performance budget; mobile autocomplete canonico, picker data/ora, import locale→account opzionale; packaging/firma Android e test Wear. | Build firmata su internal track, test 192/227dp e telefono, test accessibilità manuale P0, Data safety draft coerente con SDK. |
| **6–10 settimane — parità Apple e operatività** | Creare target iOS/widget/watchOS, App Group/provisioning, TestFlight; issue/runbook/incident process; crash/perf telemetry opt-in e aggregata; release train comune. | Build TestFlight e test iPhone/Apple Watch reali; privacy label Apple verificata; rollback esercitato; ownership on-call definita. |
| **Solo dopo — realtime ferroviario** | Selezionare una fonte tramite licenza/autorizzazione, implementare un adapter isolato e Rail Facts Gateway, validation/replay/circuit breaker/monitoring; aggiornare UX e comunicazioni. | Contratto firmato o licenza verificabile, SLO/TTL per tipo di dato, test di conflitto e kill switch, revisione privacy e legal completate. |
| **Solo dopo — acquisto e Alexa pubblica** | Onboarding venditore ufficiale esterno con allowlist; setup Amazon Developer Console e review della Skill. | Nessuna credenziale o checkout Binario; test open redirect; Skill validata con interaction model, OAuth opzionale e note di review. |

### Miglioramenti di prodotto ad alto valore

Il valore distintivo di Binario non è “un’altra schermata di ritardi”, ma una **cabina di preparazione del viaggio affidabile**: una tratta privata, stazioni canoniche, orari dichiaratamente programmati, checklist, promemoria rispettosi e companion immediati. L’interfaccia dovrebbe rendere questa promessa tangibile in tre gesti: scegliere origine/destinazione, impostare il momento e ricevere un supporto discreto su telefono/watch. Il realtime, quando e se autorizzato, deve essere un livello verificato sopra questo nucleo, non la condizione per rendere utile l’app.

In particolare, consiglierei di sviluppare nell’ordine: migrazione locale opzionale dopo login; autocomplete offline/paritario; reminder configurabili; checklist sul watch; Tile rifinito con preview e controlli accessibili; “apri sul telefono” contestuale; data map/privacy center; quindi la pipeline provider. Non consiglierei ora mappe, geolocalizzazione continua, tracciamento di treni, social/community, chat IA generica, acquisto in-app o stime predittive: aumenterebbero superficie legale, tecnica e di privacy senza migliorare la promessa principale quanto le azioni sopra.

### Limiti dell’audit

Questo audit ha esaminato sorgenti, configurazioni, contratti, documentazione, build e test disponibili. La nuova esecuzione ha superato **110 test web** e **39 test companion**, TypeScript, build web e verifica della configurazione Expo; la build Android/Wear era stata verificata nel ciclo immediatamente precedente. Non sono state eseguite verifiche su dispositivi fisici, analisi dinamica di rete su app installata, penetration test, load test, review legale, DPA, test assistivi con utenti, console Play/App Store/Alexa, firma release reale o produzione di fonti ferroviarie. Le raccomandazioni P0/P1 restano perciò gate di rilascio, non mere migliorie estetiche.

## Riferimenti

[1]: https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/principles-gdpr_en "Commissione europea — Principles of personal data processing under the GDPR"
[2]: https://www.edpb.europa.eu/sites/default/files/files/file1/edpb_guidelines_201904_dataprotection_by_design_and_by_default_v2.0_en.pdf "EDPB — Guidelines 4/2019 on Article 25 Data Protection by Design and by Default"
[3]: https://digital-strategy.ec.europa.eu/en/policies/guidelines-ai-transparency-obligations "Commissione europea — Guidelines on transparency obligations for providers and deployers of certain AI systems"
[4]: https://www.w3.org/TR/WCAG22/ "W3C — Web Content Accessibility Guidelines 2.2"
[5]: https://www.w3.org/TR/wcag2mobile-22/ "W3C — Guidance on Applying WCAG 2.2 to Mobile Applications"
[6]: https://digital-strategy.ec.europa.eu/en/policies/cyber-resilience-act "Commissione europea — Cyber Resilience Act"
[7]: https://www.enisa.europa.eu/topics/vulnerability-disclosure "ENISA — Vulnerability Disclosure"
[8]: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en "Google Play Console — Provide information for Google Play's Data safety section"
[9]: https://developer.apple.com/app-store/app-privacy-details/ "Apple — App privacy details on the App Store"
[10]: https://developer.android.com/docs/quality-guidelines/wear-app-quality "Android Developers — Wear OS app quality"
[11]: https://developer.apple.com/app-store/review/guidelines/ "Apple — App Review Guidelines"
