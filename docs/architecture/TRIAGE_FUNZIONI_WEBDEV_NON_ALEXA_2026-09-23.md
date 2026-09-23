# Triage delle funzioni WebDev non-Alexa

**Data:** 23 settembre 2026
**Stato:** inventario completato; porting subordinato ai gate P0
**Decisione di riferimento:** `ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`

## Risposta sintetica

Sì: lo snapshot storico `surfaces/web-voice-bridge/` contiene alcune capacità non-Alexa più mature rispetto alle implementazioni correnti. Queste capacità non rendono però lo snapshot la nuova app principale e non devono essere fuse in `alexa-companion/`.

L’app Android in `app/` resta il prodotto principale. Il porting deve trasferire **contratti, comportamento, limiti e test** nel modulo corretto, non copiare React, localStorage, tRPC o la pipeline audio browser dentro Kotlin.

## Destinazione delle capacità avanzate

| Capacità più avanzata nello snapshot | Target corretto | Decisione | Priorità |
|---|---|---|---|
| Viaggio pianificato con data/ora, reminder, checklist, stato completato/archiviato e ricorrenze | `app/` | Creare un dominio Kotlin `PlannedJourney` distinto da `SavedRoute` e dallo stato ferroviario live | Dopo i gate P0 |
| Agenda e itinerari multi-tratta | `app/`; adapter di parità in `companion-expo/` | Portare semantica e test, non la UI React | Dopo `PlannedJourney` |
| Import/export con preview, deduplica e conflitti | `app/` proprietaria del formato; `companion-expo/` compatibile | Definire un formato versionato limitato alle tratte pianificate; non confonderlo con il backup completo Android | Dopo i gate P0 |
| Import/export calendario ICS con reminder | `app/` | Implementazione Android nativa con limiti, timezone, escaping e test round-trip | Dopo `PlannedJourney` |
| Voice journey con stazioni ambigue e conferma prima dell’applicazione | `app/` | Portare gli esiti `confirmed`, `needs-review`, `unresolved`; mantenere il riconoscitore di sistema Android | P1 Android UX |
| Pairing di device, token hash, revoca e consenso per installazione | futuro `services/companion-sync/` più client in `app/` | Backend generico separato da Alexa; non usare il Data Layer Wear né Alexa come trasporto universale | Solo quando la sync remota entra nel prodotto |
| Export privacy e cancellazione account transazionale | futuro `services/companion-sync/` | Stesso confine server-authoritative del pairing; cleanup Alexa separato ma componibile | Con la sync remota |
| Validazione qualità catalogo e fixture corrotte | `app/` data layer/build | Unicità ID/codici, alias e coordinate portati; fixture corrotte e checksum restano nella PR dedicata | **Avviato in questo branch** |
| Tie-break deterministico per stazioni equidistanti | `app/` | Portati controllo degli input finiti, esclusione coordinate invalide e tie-break italiano stabile con test | **Completato nel codice; test Gradle in attesa di SDK/CI** |
| Contratto deep link e casi negativi | `app/` | Estrarre e testare allowlist di scheme/host/path/query; il server conserva solo redirect senza dati personali | P1 Android UX |

## Cosa è già più avanzato nell’app Android

Non va trasferito dallo snapshot WebDev perché `app/` possiede già un’implementazione migliore o più adatta al prodotto:

- catalogo stazioni offline e identità canoniche;
- provider registry e policy di qualità/provenienza ferroviaria;
- tabellone, preferiti e ricerca stazioni;
- dettatura tramite riconoscitore di sistema e parser locale;
- shortcut e deep link nativi;
- allarmi e notifiche locali;
- backup validato;
- pubblicazione degli snapshot Wear tramite Data Layer.

## Cosa non deve entrare in Alexa Companion

`alexa-companion/` resta limitato a Custom Skill, account linking, revoca, profilo vocale minimo, redirect gateway e dashboard tecnica. Non deve contenere:

- agenda, checklist o calendario;
- pairing generico degli smartwatch;
- geolocalizzazione o ricerca stazioni vicine;
- database di itinerari completi;
- recorder browser, upload audio o trascrizione remota;
- Google Maps, localStorage, Web Share o shortcut da tastiera;
- provider ferroviari indipendenti dall’app principale.

## Parti da archiviare o dismettere

| Elemento | Trattamento |
|---|---|
| `surfaces/web-voice-bridge/` | Conservare temporaneamente come snapshot di provenienza; rimuovere dopo il porting selettivo e un tag/bundle di archivio |
| `providerGovernance.ts`, `providerAdapterContract.ts`, `railPresentation.ts` | Archivio: l’app Android ha una governance dati più completa |
| Tabellone WebDev senza provider | Archivio; non promuovere un placeholder come funzione reale |
| Google Maps, sidebar, shortcut browser, Blob download/Web Share | Restano capacità web storiche, non requisiti dell’app smartphone |
| `dataApi`, `heartbeat`, `imageGeneration`, `llm`, `map`, `voiceTranscription` nel companion Alexa | Verificare telemetria e rimuovere gradualmente se privi di consumer runtime |
| Storage proxy e helper storage | Decommission controllata dopo verifica di traffico; non eliminare alla cieca |
| Umami/analytics | Restare disattivato finché non esistono decisione privacy, minimizzazione eventi e consenso |

## Ordine operativo

1. Completare i gate P0 di stabilizzazione; nessuna nuova feature durante questa fase.
2. Completare il gate catalogo già avviato e integrare i test dei deep link nelle PR Android previste.
3. Progettare `PlannedJourney` separato da `SavedRoute`, con fixture e test prima della UI.
4. Portare agenda, itinerari, import/export e ICS sopra il nuovo dominio.
5. Decidere se la sincronizzazione remota multi-device è una funzione di prodotto. Solo in caso positivo creare `services/companion-sync/`, ricostruendo schema e migrazioni invece di copiare `0003`–`0011`.
6. Archiviare e rimuovere `surfaces/` soltanto dopo aver registrato per ogni capacità: portata, rifiutata oppure preservata in archivio.

## Vincoli di sincronizzazione

Ogni companion deve usare lo stesso significato dei dati dell’app principale. Un payload sincronizzato deve includere versione, provenienza, istante di generazione, TTL/scadenza e stato programmato oppure operativo. Non sono ammessi polling paralleli non coordinati o cache senza scadenza. Se il dato non è disponibile o è scaduto, la superficie secondaria deve mostrarlo esplicitamente anziché presentare informazioni vecchie come correnti.
