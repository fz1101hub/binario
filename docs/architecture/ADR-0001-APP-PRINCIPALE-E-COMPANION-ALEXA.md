# ADR-0001 — App principale e companion Alexa

**Stato:** Accettata
**Data:** 23 settembre 2026
**Ambito:** architettura di prodotto, proprietà dei dati e sorgenti canoniche

## Contesto

Binario è prima di tutto un'applicazione per smartphone. Wear OS, Apple Watch, widget e skill Alexa sono superfici secondarie che estendono l'esperienza dell'app principale; non sono prodotti concorrenti e non devono introdurre una seconda fonte di verità per tratte, stato ferroviario o preferenze.

Nel repository erano presenti due alberi sovrapposti:

- `voice-bridge/`, oggetto dell'audit e già avanzato con hardening OAuth, revoca, migrazioni e test;
- `surfaces/web-voice-bridge/`, snapshot storico di un progetto WebDev più ampio, comprendente funzioni web, companion, dettatura e nove migrazioni aggiuntive non specifiche della skill Alexa.

La sovrapposizione rendeva ambiguo quale progetto fosse il prodotto principale e dove dovessero atterrare le correzioni Alexa.

## Opzioni considerate

| Approccio | Vantaggi | Svantaggi | Decisione |
|---|---|---|---|
| Rinominare e delimitare l'albero hardenizzato come companion Alexa | Conserva i fix di sicurezza già verificati; rende esplicita la gerarchia; riduce il perimetro della skill | Richiede una rinomina e il porting selettivo di eventuali funzioni Alexa utili dallo snapshot | **Scelto** |
| Aggiornare l'intero progetto WebDev storico | Mantiene in un solo progetto molte funzioni web già accumulate | Confonde app web e skill Alexa; riapre il perimetro di audit; richiede di riportare i fix di sicurezza e riconciliare 12 migrazioni | Non scelto |

## Decisione

1. **`app/` è la sorgente canonica e il prodotto principale Binario per smartphone.** Possiede il dominio ferroviario, le preferenze dell'utente e l'esperienza completa.
2. **`alexa-companion/` è il companion canonico della skill Alexa.** Nasce dalla rinomina di `voice-bridge/` e contiene endpoint Alexa, account linking, revoca, profilo vocale minimo, test e una dashboard tecnica di collegamento. Non è l'app principale né una seconda app ferroviaria.
3. **`wear/` e `apple-watch/` restano companion dello stesso livello architetturale.** Ogni companion espone soltanto una vista ridotta e coerente del dato principale.
4. **`surfaces/web-voice-bridge/` resta uno snapshot storico non distribuibile e non canonico.** Non riceve nuovi fix. Il suo contenuto viene eventualmente recuperato per funzione e tramite PR dedicate; non viene fuso in blocco.
5. Un eventuale deployment WebDev futuro deve essere costruito dal contenuto canonico di `alexa-companion/`, oppure da una sua derivazione tracciata. Il vecchio progetto WebDev non deve essere aggiornato indipendentemente.

## Proprietà e sincronizzazione dei dati

L'app smartphone resta la fonte di verità del profilo e del significato dei dati ferroviari. Il companion Alexa può consumare soltanto un contratto versionato proveniente dall'app o dallo stesso backend dati autorizzato, con almeno:

- identificatore e versione dello schema;
- provider e provenienza;
- istante di generazione e scadenza/TTL;
- stato programmato oppure verificato operativamente;
- notice e stato di indisponibilità.

Alexa non deve ricostruire autonomamente una verità ferroviaria diversa da quella mostrata sull'app. Se il profilo non è collegato, lo snapshot è scaduto o manca una fonte autorizzata, la skill fallisce in modo esplicito e non inventa dati live.

I flag locali di Wear OS e Apple Watch non appartengono al contratto pubblico del companion Alexa. Possono restare temporaneamente come colonne legacy nel database per compatibilità di migrazione, ma non devono essere accettati o restituiti dall'API Alexa.

## Conseguenze operative

- La Release A dell'app smartphone non dipende dalla pubblicazione della skill Alexa.
- La skill Alexa appartiene alla Release B e ha gate propri: sicurezza, migrazioni, modello di interazione, certificazione e sincronizzazione dati end-to-end.
- La dashboard inclusa in `alexa-companion/` è una superficie tecnica per consenso, account linking e revoca; non sostituisce l'app smartphone.
- Le funzioni esclusive dello snapshot WebDev sono inventariate in `TRIAGE_FUNZIONI_WEBDEV_NON_ALEXA_2026-09-23.md`. `publicRailQuery` è soltanto un formatter fail-closed, non un provider ferroviario, quindi non giustifica un merge completo.
- Le capacità più mature vengono portate per contratto e comportamento: `PlannedJourney`, agenda, ICS e revisione vocale in `app/`; pairing multi-device soltanto in un futuro `services/companion-sync/`; fixture e test di parità in `companion-expo/`. Nessuno di questi domini entra nel companion Alexa.
- Ogni modifica futura della skill deve usare `alexa-companion/` e la relativa CI dedicata.
