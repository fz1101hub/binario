# Binario — confronto Git e integrazioni nella baseline Expo v0.1.1

## Regola di integrazione

La baseline primaria resta `companion-expo/`, ossia Binario Expo v0.1.1 recuperato dai file di sessione. La versione nativa precedente conservata in `surfaces/native-android-wear-apple/` è una fonte di confronto funzionale: nessun suo file viene copiato direttamente nell’app Expo.

> Un elemento viene integrato solo se migliora un flusso reale, ha un contratto semplice e testabile, resta local-first e non richiede provider ferroviari non autorizzati, UI non approvata o hardware non disponibile.

| Capacità | Expo v0.1.1 | Versione Git precedente | Decisione |
| --- | --- | --- | --- |
| Planner, autocomplete, inversione, data/ora, stazione vicina | Già presente, con catalogo offline e consenso esplicito. | Presente. | **Mantenere Expo**; nessun valore da duplicare. |
| Agenda, checklist, ricorrenze, archiviazione, ritorno/ripianifica e ICS | Già presente nei contratti e nelle schermate Expo. | Presente in forma diversa. | **Mantenere Expo**; priorità a rendere disponibili i moduli mancanti. |
| Itinerari multi-tratta e importazione testo/condivisione Android | Già presente e più articolato nella linea Expo. | Parzialmente presente. | **Mantenere Expo**. |
| Widget Android e richiesta vocale Wear dal polso | Già presenti nel plugin generativo Android, incluso fallback esplicito per dati live assenti. | Widget ricco, Tile/complication e Wear cache-first. | **Mantenere prima il plugin Expo**; confrontare solo configurabilità per istanza dopo build rigenerabile. |
| Apple Watch | Plugin iOS referenziato ma assente; nessun codice Swift nella cartella Expo. | Modulo watchOS Swift separato, cache-first e Watch Connectivity. | **Rinviare**: non trasferibile direttamente in Expo; richiede un plugin/iOS nativo e Xcode. |
| Alexa / Voice Bridge | Presente e versionato nel progetto web locale; la UI Expo espone solo il companion. | Presente nell’architettura nativa come integrazione esterna. | **Mantenere Voice Bridge web**; nessuna duplicazione nel client. |
| Priorità, etichetta personale e ordinamento delle tratte | Nome automatico e ordinamento agenda; metadati avanzati da confermare dopo ricompilazione. | Pin e ordinamento locale esplicito. | **Candidato**: aggiungere solo dopo ripristino dei moduli Expo e relativi test. |
| Formato 12/24 ore e densità | Il formatter corrente è impostato a 24 ore; densità non è una preferenza globale. | Preferenze visuali locali. | **Rinviare**: modifica UI/Settings subordinata alla scelta del mockup. |
| Catalogo stazioni esteso, alias e qualità del dato | Expo usa un indice offline ma il dataset JSON è assente dalla copia v0.1.1. | Catalogo curato nazionale e alias. | **Candidato tecnico**: recuperare o rigenerare un catalogo offline soltanto da fonte consentita, con test di dimensione e licenza. |
| Realtime, ritardi, binari, cancellazioni e avvisi da provider | Expo dichiara indisponibilità senza fonte autorizzata. | Include adapter storici non autorizzati e best-effort. | **Escluso**: non integrare provider, scraping, endpoint privati o stati realtime non dimostrabili. |

## Ordine operativo

1. Rendere Expo v0.1.1 nuovamente compilabile ricostruendo i moduli e le configurazioni locali mancanti dal suo contratto esistente.
2. Verificare che il plugin Android rigeneri widget, receiver Condividi e companion Wear senza aggiungere funzionalità duplicate.
3. Integrare in incrementi separati i soli candidati locali: ordinamento manuale e priorità/etichetta della tratta, se non già coperti dai moduli recuperati.
4. Mantenere in backlog l’adattatore iOS/Apple Watch e le preferenze visive, senza dichiararli compilati o pronti prima di build e test pertinenti.

## Integrazione verificata nella baseline Expo

L’incremento mantiene Expo come sola base di destinazione. Non copia file Kotlin o Swift dal progetto nativo: riporta soltanto contratti e capacità valutati nella matrice.

| Integrazione | Limite applicato | Evidenza tecnica |
| --- | --- | --- |
| Catalogo stazioni curato offline | Asset locale validato prima dell’uso; nessuna chiamata di rete o stato live. | Indice Expo rigenerato con 5.083 stazioni; test ricerca e normalizzazione aggiornati. |
| Configurazione Expo mancante | Ripristinati alias TypeScript, Vitest, parser URL opzionale e plugin iOS dichiarato. Il plugin iOS non crea Apple Watch fittizio. | `tsc --noEmit` riuscito. |
| Servizi locali recuperati | Template, recenti, promemoria, ICS, importazione testuale/condivisione e sync opt-in restano espliciti, locali o fail-closed. | Test diretti dei contratti locali aggiunti. |
| Priorità della tratta | Fissaggio, ordine e etichetta sono metadati personali sul dispositivo; non entrano in provider, export o sync. | Migrazione retrocompatibile: i vecchi record senza campi nuovi restano validi. |
| Widget e Wear OS | Il prebuild rigenera il bridge e il modulo Wear dalla sorgente plugin Expo già recuperata. | Build debug e release di app Android e Wear riuscite. |

La versione Git originale contiene sorgente fino alla sua v0.20.0 identificata; l’APK v0.20.3 resta una prova di comportamento osservabile, non una sorgente da cui copiare. Non sono stati integrati provider ferroviari, scraping, endpoint privati o dati realtime.

### Verifiche dell’incremento

Il typecheck e la suite Vitest hanno superato tutti gli otto file di test (35 test). Il prebuild Android, la build debug Android/Wear, l’export Expo Android e le build release Android/Wear sono riusciti. Restano necessari build e test reali Xcode/iOS/Apple Watch; non sono dichiarati completati da queste verifiche.

| Artefatto generato | SHA-256 |
| --- | --- |
| App Android release | `d7f04e32a3aa13cc11feda6440e45685fa6e173d9f943580ac904208299b4224` |
| Wear OS release | `04920acd5fa5bb613bcaeb765d0e6f159c5ad4ae04ba3c3c539a90fd7047808b` |

Queste sono build tecniche della baseline recuperata dopo l’integrazione, non rilascio pubblicato né prova di test hardware assistivo.
