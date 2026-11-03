# Binario — gap analysis benchmark e base Git

## Criterio di lettura

Questa analisi mette a confronto le sintesi dei report, delle matrici, delle verifiche UX/accessibilità e delle dieci analisi applicative disponibili nel task storico con il ramo Git `feature/data-foundations` di Binario. Non tratta i benchmark come sorgenti da importare: ogni riferimento è usato solo per ricavare requisiti, casi limite e criteri di qualità.

> **Regola di prodotto.** Binario resta locale, privacy-first e ferroviario. Un dato operativo è mostrato come realtime soltanto con una fonte espressamente autorizzata, identificabile e verificabile. In assenza di tale fonte, l’app conserva il fallback programmato locale oppure dichiara l’indisponibilità.

| Area emersa dal benchmark | Evidenza nella base Git | Esito |
| --- | --- | --- |
| Tratta personale fissabile | `SavedRoute` ha già `isPinned`; la UI consente il fissaggio e ordina le tratte fissate per prime. | **Già presente.** Non aggiungere una seconda nozione concorrente di “tratta principale”. |
| Nome o etichetta ferroviaria personale | La creazione della tratta accetta già un nome facoltativo, limitato alla relazione origine–destinazione. | **Già presente.** Non introdurre note generiche o categorie estranee al viaggio. |
| Preferiti e ricerche recenti di stazioni/tratte | Esistono preferiti locali, cronologia tratta, catalogo stazioni e ricerca per alias/codice. | **Già presente.** Conservare limiti locali e assenza di profilazione. |
| Stazione vicina consensuale nel campo di ricerca | La base Git include catalogo locale, consenso puntuale e nessuna persistenza delle coordinate. | **Già presente.** Verificare su dispositivo reale in un incremento di test, non modificare il modello di consenso. |
| Tabellone, dettaglio e stati dati | App e widget modellano provenienza, freschezza, confidenza e indisponibilità; il fallback programmato è distinto dal live. | **Già presente in parte.** Va mantenuta la stessa semantica in ogni superficie. |
| Widget per istanza, cache e accesso rapido | Il widget ha configurazione indipendente, resize e cache. | **Già presente.** Il modulo Wear richiede però il ripristino dei sorgenti mancanti prima di poterlo verificare. |
| Riordino esplicito delle tratte personali | Le tratte sono oggi ordinate come fissate prima, poi alfabeticamente; non esiste un ordine scelto dall’utente. | **Da implementare localmente.** L’ordine deve restare limitato alla raccolta di tratte salvate, non agli orari ferroviari. |
| Densità di consultazione | Esiste una preferenza locale per “tabelloni compatti”, ma nel ramo corrente non è ancora applicata alle righe. | **Subordinato alla scelta UI.** La correzione va progettata con il mockup approvato, evitando un restyling parziale. |
| Formato 12/24 ore | I formatter del pianificatore sono esplicitamente a 24 ore e manca una preferenza separata. | **Subordinato alla scelta UI.** Non modificare ora etichette, spaziatura o gerarchia visiva. |
| Copywriting progressivo e pagine meno descrittive | L’intento è corretto, ma molte superfici sono volutamente verbose e richiedono una decisione grafica coerente. | **Subordinato alla scelta UI.** Ridurre il testo solo nel redesign approvato, senza perdere disclosure di fonte e privacy. |
| Realtime, allarmi su variazioni, acquisto e account operatori | Richiedono provider, licenze, timestamp/TTL e accordi esterni; non sono funzioni locali. | **Bloccato esternamente.** Nessuna integrazione, deep link vietato, scraping o endpoint privato. |
| Test TalkBack, Wear OS, VoiceOver e firma/store | Non sostituibili da test unitari o build del sandbox. | **Bloccato da hardware e governance.** Preparare protocolli e raccogliere evidenze solo quando i dispositivi saranno disponibili. |

## Anomalia rilevata nel Git storico

Il ramo Git storico collega direttamente gli adapter `ViaggiaTrenoStationAdapter`, `RfiStationBoardAdapter` e `ItaloPublicStationAdapter` alla composizione di produzione. Questa scelta confligge con il vincolo corrente del progetto: non riusare fonti senza autorizzazione documentata, non interrogare endpoint privati e non presentare il risultato come dato live.

Il prossimo incremento tecnico pertanto non aggiunge nuove fonti: rimuove quegli adapter dal percorso eseguito e conserva il solo adapter GTFS embedded programmato. Il comportamento risultante è esplicito: orario programmato quando esiste nel catalogo locale, altrimenti nessuna corsa disponibile. Gli adapter storici e i loro test possono restare come materiale non eseguibile fino a una futura autorizzazione formale, ma non devono essere raggiungibili dal grafo dati di produzione.

## Pacchetto implementabile senza restyling

Il pacchetto immediato mantiene una singola responsabilità per componente e non introduce account, cloud, Room/KMP, automazioni nascoste o nuove dipendenze. Prima viene messo in sicurezza il grafo dati; segue il solo riordino manuale delle tratte locali. Il nome personale e il fissaggio non vengono duplicati perché già disponibili. Il backup JSON deve preservare l’ordine con una migrazione tollerante verso backup e storage precedenti.

| Incremento | Contratto previsto | Compatibilità e privacy |
| --- | --- | --- |
| Percorso dati locale programmato | Il grafo dati di produzione usa unicamente il fallback GTFS embedded. | Nessuna richiesta esterna, nessuna chiave nel client, stato non-live esplicito. |
| Ordine manuale tratte | Ogni tratta può avere un rango locale validato; le azioni spostano una tratta di una posizione e mantengono `isPinned` come filtro separato. | I dati esistenti ricevono un ordine deterministico al primo salvataggio; massimo 32 tratte, nessun impatto su orari, widget o account. |
| Codec e backup | Il codec storage e il backup JSON accettano record precedenti senza il nuovo campo e rifiutano valori fuori limite. | Il rango resta nel file di backup locale solo su richiesta dell’utente; nessuna sincronizzazione o telemetria. |

## Evidenze interne

L’analisi prende come riferimento la sintesi delle matrici e delle analisi applicative raccolte nel task storico, il contratto `SavedRoute`, il repository dati e le superfici Compose del ramo Git. I percorsi pertinenti sono riportati nei riferimenti seguenti.

## Riferimenti

[1]: ../../../../sintesi_matrici_benchmark.json "Sintesi matrici benchmark"
[2]: ../../../../sintesi_analisi_app_ferroviarie.json "Sintesi analisi app ferroviarie"
[3]: ../../app/src/main/java/it/binario/app/data/TrainDataSource.kt "Contratti dati e SavedRoute"
[4]: ../../app/src/main/java/it/binario/app/TrainViewModel.kt "Persistenza e gestione tratte locali"
[5]: ../../app/src/main/java/it/binario/app/data/repository/BinarioDataGraph.kt "Composizione delle fonti"
[6]: ../../app/src/main/java/it/binario/app/MainActivity.kt "Superfici Compose delle tratte e preferenze"
