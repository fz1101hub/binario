# Audit regressioni e parità prodotto — Binario

**Data dell’audit:** 1 settembre 2026.  
**Fonti confrontate:** sei conversazioni storiche collegate al progetto, la verifica release del 12 agosto 2026 e la codebase corrente al checkpoint `d34612e8`.

## Conclusione principale

La codebase attuale è nata come **servizio remoto web per profilo, Alexa e companion**, non come copia della precedente app Android. Per questo motivo non tutte le capacità delle versioni Android sono tecnicamente regressioni di questo repository: molte non sono mai state portate qui. Sono però requisiti di **parità prodotto** da recuperare in modo selettivo, mantenendo l’app Binario come centro dell’esperienza.

La dark mode, al contrario, è una regressione certa dell’esperienza corrente: i token scuri e il relativo contesto esistono, ma il provider è impostato con `switchable = false` e la shell non offre alcun controllo del tema.

## Inventario comparativo

| Area | Evidenza storica | Stato attuale | Valutazione | Priorità |
|---|---|---|---|---|
| Tema scuro e persistenza tema | Tema scuro accessibile per monitoraggio/dettaglio; controllo tema già presente nei componenti di progetto | Token `.dark` e `ThemeContext` esistono, ma toggle disattivato e assente dalla shell | **Regressione certa** | P0 |
| App-first: ricerca e pianificazione | Ricerca nazionale, campi separati, inversione tratta, suggerimenti e monitoraggi | Home app-first con campi, swap e tratte personali | Mantenuto, da affinare | P0 |
| Catalogo stazioni | Database canonico di 5.239 stazioni, alias e codici multipli | Catalogo reale caricato lazy, autocomplete e codici salvati sulle tratte | Mantenuto | P0 |
| Dati non riconosciuti | Salvataggio locale di ricerche fuori catalogo | Inserimento manuale possibile, ma nessuna persistenza dedicata | Parità parziale | P1 |
| Stazioni vicine | Coordinate e calcolo prossimità | Non esposto nella UI o nei servizi | Mancante | P2 |
| Preferiti e recenti stazioni | Pagina stazione con preferiti/monitoraggi prevista | Nessuna persistenza di stazioni preferite o recenti | Mancante | P1 |
| Tabellone stazione | Tabelloni live e layout operativo | Rotta e vista Partenze/Arrivi esistono, ma senza feed autorizzato | Parità strutturale, dati assenti | P0 condizionata a fonti |
| Ricerca itinerari e alternative | Pianificazione offline GTFS e confronto soluzioni | Salvataggio tratta; nessun motore itinerari o alternative | Mancante | P1 |
| Dati realtime e ritardi | Adapter multipli, doppio ritardo, detection operativa, freshness e score | Stato esplicito “non disponibile”; nessun adapter o osservazione | Mancante; non implementare senza autorizzazione | P0 dati/legale |
| Avvisi circolazione | RSS RFI Infomobilità, GTFS-RT alert | Card di indisponibilità trasparente, nessuna ingestione | Mancante | P1 condizionata a fonte |
| Offline/cache | SQLite offline-first e 12 pack GTFS lazy | Nessuna cache offline di orari o data pack nella web app | Mancante nella parità Android; progetto separato | P1 Android |
| Mappe ferroviarie | OpenStreetMap, OpenRailwayMap e MapLibre | Componente mappa generico disponibile, non integrato | Mancante | P2 |
| Dittatura nativa | Interazione vocale dell’app principale | Microfoni per campi, trascrizione italiana, fallback e test UI; parser tratta completa pronto ma non ancora esposto nella home | Parità parziale, incremento in corso | P0 |
| Widget | Quattro modalità: treno, stazione, tratta, riepilogo | Solo documentazione/contratto companion; nessun widget nativo | Mancante nella web app; progetto Android separato | P2 Android |
| Smartwatch | Wear OS/watchOS companion, snapshot limitati | Contratto e flag profilo presenti; nessuna app companion nativa | Mancante nella web app; progetto Android/iOS separato | P2 mobile |
| Alexa | Skill con account linking e fallback senza fonte live | Backend, OAuth e fail-closed presenti; volutamente non attiva senza credenziali | Presente come companion | P2 |
| Android App Actions | Deep link a ricerca treno, prossime partenze e tratta attiva | Endpoint di deep link presenti; nessuna superficie Android in questo repository | Parità backend, client separato | P2 Android |
| Profilo e sincronizzazione | Payload unico con concorrenza/idempotenza | Profilo remoto protetto, idempotente e versionato | Mantenuto | P1 |
| Sicurezza e trasparenza dati | Nessun dato live inventato, provenienza/timestamp obbligatori | Vincolo rispettato; dettatura non salva testo nel profilo | Mantenuto, retention audio da completare prima di pubblicare | P0 privacy |

## Funzioni da non reimportare senza condizioni

Le conversazioni storiche citano endpoint backend non documentati di operatori, chiavi private e dati proprietari osservati in APK terzi. Questi elementi **non devono essere reintrodotti**. Analogamente, ritardi, binari, cancellazioni, posizioni o avvisi non possono essere simulati per rendere l’interfaccia più ricca.

L’integrazione realtime dovrà passare da fonti open o da accordi/autorizzazioni esplicite, conservando per ogni dato provider, timestamp, freshness, livello di fiducia e natura del dato (realtime, programmato, stimato oppure indisponibile).

## Sequenza di recupero proposta

| Ordine | Intervento | Motivo |
|---|---|---|
| 1 | Riattivare dark mode, preferenza persistente e controllo accessibile nella shell | Regressione certa, implementabile senza dipendenze esterne |
| 2 | Esporre la dettatura della tratta completa con revisione e matching canonico | Funzione primaria dell’app già avviata nella codebase corrente |
| 3 | Aggiungere stazioni preferite/recenti e una pagina stazione utile anche senza feed live | Completa il nucleo app-first senza inventare dati |
| 4 | Definire un adapter per una fonte autorizzata di orari/avvisi prima del realtime | Trasforma tabellone e avvisi in superfici operative in modo lecito |
| 5 | Recuperare pianificazione offline, widget e smartwatch nel repository mobile appropriato | Le implementazioni storiche Android non appartengono a questa codebase web |

## Vincolo di qualità

Ogni ripristino deve confrontare le versioni precedenti, trattenere il miglior comportamento già validato e non introdurre regressioni su layout, accessibilità, privacy, selezione stazioni o trasparenza dei dati. Il riferimento visuale resta il layout del task `A7wgn2uY9sZNsGcfh3dMU3`; le versioni e il benchmark servono a migliorarlo, non a sostituirlo arbitrariamente.
