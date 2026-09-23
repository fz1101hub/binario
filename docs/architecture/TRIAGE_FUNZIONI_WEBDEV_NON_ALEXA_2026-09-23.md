# Triage delle funzioni WebDev non-Alexa

**Data:** 23 settembre 2026
**Stato:** inventario completato; snapshot archiviati e rimossi dalla linea di stabilizzazione
**Decisione di riferimento:** `ADR-0001-APP-PRINCIPALE-E-COMPANION-ALEXA.md`

## Risposta sintetica

Sì: lo snapshot storico già presente in `surfaces/web-voice-bridge/` conteneva alcune capacità non-Alexa più mature rispetto alle implementazioni correnti. Queste capacità non rendevano però lo snapshot la nuova app principale e non dovevano essere fuse in `alexa-companion/`. L'intero albero è ora preservato nel branch `archive/surfaces-20260923`, commit `37a408d662374aa1c8f9765b20c9f2fd90e5b81f`, e rimosso dalla linea attiva.

L’app Android in `app/` resta il prodotto principale. Il porting deve trasferire **contratti, comportamento, limiti e test** nel modulo corretto, non copiare React, localStorage, tRPC o la pipeline audio browser dentro Kotlin.

## Destinazione delle capacità avanzate

| Capacità più avanzata nello snapshot | Target corretto | Decisione | Priorità |
|---|---|---|---|
| Viaggio pianificato con data/ora, reminder, checklist, stato completato/archiviato e ricorrenze | `app/` | Dominio Kotlin `PlannedJourney` distinto da `SavedRoute` e dallo stato ferroviario live | **Implementato e verificato in PR #5** |
| Agenda e itinerari multi-tratta | `app/`; adapter di parità in `companion-expo/` | Portare semantica e test, non la UI React | Differito sopra `PlannedJourney`; sorgente preservata nell'archivio |
| Import/export con preview, deduplica e conflitti | `app/` proprietaria del formato; `companion-expo/` compatibile | Definire un formato versionato limitato alle tratte pianificate; non confonderlo con il backup completo Android | Differito; sorgente preservata nell'archivio |
| Import/export calendario ICS con reminder | `app/` | Implementazione Android nativa con limiti, timezone, escaping e test round-trip | Differito; sorgente preservata nell'archivio |
| Voice journey con stazioni ambigue e conferma prima dell’applicazione | `app/` | Portare gli esiti `confirmed`, `needs-review`, `unresolved`; mantenere il riconoscitore di sistema Android | P1 Android UX |
| Pairing di device, token hash, revoca e consenso per installazione | futuro `services/companion-sync/` più client in `app/` | Backend generico separato da Alexa; non usare il Data Layer Wear né Alexa come trasporto universale | Solo quando la sync remota entra nel prodotto |
| Export privacy e cancellazione account transazionale | futuro `services/companion-sync/` | Stesso confine server-authoritative del pairing; cleanup Alexa separato ma componibile | Con la sync remota |
| Validazione qualità catalogo e fixture corrotte | `app/` data layer/build | Unicità ID/codici, alias, coordinate, checksum e fixture corrotte | **Portato e coperto da test** |
| Tie-break deterministico per stazioni equidistanti | `app/` | Controllo input finiti, esclusione coordinate invalide e tie-break italiano stabile | **Portato e coperto da test** |
| Contratto deep link e casi negativi | `app/` | Allowlist di scheme/host/path/query e consumo idempotente; il server conserva solo redirect senza dati personali | **Portato e coperto da test** |

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
| `surfaces/` | **Archiviato** in `archive/surfaces-20260923` al commit `37a408d662374aa1c8f9765b20c9f2fd90e5b81f`; rimosso dalla linea di stabilizzazione |
| `providerGovernance.ts`, `providerAdapterContract.ts`, `railPresentation.ts` | Archivio: l’app Android ha una governance dati più completa |
| Tabellone WebDev senza provider | Archivio; non promuovere un placeholder come funzione reale |
| Google Maps, sidebar, shortcut browser, Blob download/Web Share | Restano capacità web storiche, non requisiti dell’app smartphone |
| `dataApi`, `heartbeat`, `imageGeneration`, `llm`, `map`, `voiceTranscription` nel companion Alexa | Rimossi dopo verifica di assenza consumer runtime |
| Storage proxy e helper storage | Rimossi dal companion canonico con regressione HTTP nella PR #22 |
| Umami/analytics | Restare disattivato finché non esistono decisione privacy, minimizzazione eventi e consenso |

## Ordine operativo

1. Completare i gate P0 di stabilizzazione; nessuna nuova feature durante questa fase.
2. Completare il gate catalogo già avviato e integrare i test dei deep link nelle PR Android previste.
3. Progettare `PlannedJourney` separato da `SavedRoute`, con fixture e test prima della UI.
4. Portare agenda, itinerari, import/export e ICS sopra il nuovo dominio.
5. Decidere se la sincronizzazione remota multi-device è una funzione di prodotto. Solo in caso positivo creare `services/companion-sync/`, ricostruendo schema e migrazioni invece di copiare `0003`–`0011`.
6. **Completato:** tutte le capacità sono state marcate come portate, differite, rifiutate oppure preservate; `surfaces/` è disponibile nel branch e commit archivio indicati sopra.

## Vincoli di sincronizzazione

Ogni companion deve usare lo stesso significato dei dati dell’app principale. Un payload sincronizzato deve includere versione, provenienza, istante di generazione, TTL/scadenza e stato programmato oppure operativo. Non sono ammessi polling paralleli non coordinati o cache senza scadenza. Se il dato non è disponibile o è scaduto, la superficie secondaria deve mostrarlo esplicitamente anziché presentare informazioni vecchie come correnti.
