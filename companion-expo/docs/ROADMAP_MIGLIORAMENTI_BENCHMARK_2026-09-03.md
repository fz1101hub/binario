# Roadmap di miglioramento da benchmark — Binario

> **Origine.** Questa roadmap sintetizza il benchmark completo condiviso dall’utente, i pattern pubblici delle app ferroviarie e le tre schermate di riferimento. Non costituisce prova di disponibilità dei dati o autorizzazione a riusare fonti di terzi.

## Principio di selezione

Binario non migliora aggiungendo indiscriminatamente funzioni. Ogni incremento deve ridurre i tocchi necessari a pianificare o preparare un viaggio ferroviario, rimanere local-first per impostazione predefinita e preservare la distinzione fra piano personale e stato ferroviario esterno. Il benchmark suggerisce una gerarchia concreta: **tratta personale → stazione → corsa → avviso**, senza confondere queste superfici.

| Priorità | Miglioramento | Stato attuale | Intervento proposto | Vincolo |
| --- | --- | --- | --- | --- |
| **P1** | Tratta principale nominabile e fissabile | La tratta può già avere un nome ed essere ripianificata. | Consentire di fissare una sola tratta personale in Home, con andata/ritorno come azioni immediate e riordino locale esplicito. | Local-first; attuabile senza provider. |
| **P1** | Densità Home e microcopy progressivo | Le funzioni sono presenti ma il testo persistente è troppo lungo. | Sostituire spiegazioni ripetute con chip di stato, icona + etichetta e dettaglio su gesto volontario; mantenere testo completo nei reader screen e nella guida. | Da applicare dopo la scelta grafica. |
| **P1** | Memorie personali separate | Preferiti stazione e tratte esistono, ma non sono trattati come categorie visive distinte. | Rendere esplicite e raggiungibili le tre memorie: **tratte**, **stazioni**, **corse**; nessuna cronologia di posizione. | Le corse richiedono una fonte autorizzata per diventare operative. |
| **P2** | Densità e formato orario scelti dall’utente | Tema, contrasto, movimento e modalità semplificata sono già locali. | Aggiungere preferenze locali per densità compatta/confortevole e formato orario, rispettando font scale e impostazioni di sistema. | Attuabile senza provider; test assistivi reali ancora necessari. |
| **P2** | Passaggio rapido fra soluzioni e dettaglio | La pianificazione e la prossima tratta sono già separate. | Stabilizzare il linguaggio e la navigazione: “Piano viaggio” per dati personali, “Tabellone” per stazione, “Dettaglio corsa” solo con dati consentiti. | Microcopy/UI; nessun dato inventato. |
| **P2** | Widget come sintesi della routine | Widget e Tile esistono con prossima tratta locale. | Predisporre una gerarchia fino a cinque elementi, paginazione e refresh solo quando un adapter autorizzato fornirà snapshot validi; nel frattempo mostrare esclusivamente piano locale. | Funzione piena bloccata da provider e test Wear reali. |

## Miglioramenti che non vanno anticipati

| Area | Perché resta bloccata |
| --- | --- |
| Corse successive, ritardi, binari, soppressioni e timeline reale | Richiedono una fonte autorizzata, provenienza, timestamp, TTL e fallback verificabili. |
| Allarmi di ritardo, avvisi di sciopero o servizio | Devono dipendere da dati affidabili; non si attivano soltanto perché l’utente ha una tratta salvata. |
| Avviso “scendi tra X fermate” | Richiede andamento corsa e posizione/fermate attendibili; non va simulato da un piano locale. |
| Geofencing di arrivo | Implica location background, consumo e test hardware; non è necessario al nucleo. |
| Acquisti, prezzi o WebView operatore | Richiedono accordi e perimetri specifici; Binario non diventa un checkout ferroviario. |

## Criteri UX e accessibilità per l’implementazione

Ogni stato usa **icona, testo e non solo colore**. I dettagli sulla provenienza del dato devono essere comprimibili visivamente ma disponibili via tecnologia assistiva. I controlli tattili devono rimanere riconoscibili e con target adeguati; la densità compatta non può ridurre la leggibilità con font scale. Nessuna frase come “in orario” o “nessun avviso” può comparire quando manca la fonte: l’interfaccia deve dire rispettivamente **“non verificato”** o **“nessun avviso disponibile”**.

## Decisione richiesta prima dell’implementazione

La sequenza consigliata, dopo la scelta del mockup grafico, è: **P1 tratta fissata + Home più compatta**, poi **P2 densità/formato orario e navigazione**, infine un raffinamento widget condizionato a dati autorizzati. I test TalkBack, Condividi Android e Wear reale restano gate separati e non vengono chiusi da questa roadmap.
