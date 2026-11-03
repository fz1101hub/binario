# Audit UX/UI e redesign — Binario v0.7

## Diagnosi della build v0.6 installata

La build attuale è funzionalmente densa ma non è una buona esperienza di consultazione ferroviaria quotidiana. La schermata iniziale mescola selezione della tratta, direzione, refresh, provenienza del dato, partenze rapide, preferenza Lazio, ricerca, filtro operatore e lista completa nello stesso flusso verticale. Il risultato costringe la persona a superare molte decisioni e molte card prima di raggiungere l’informazione primaria: **quale treno prendere adesso, a che ora, con quale stato e da quale binario**.

| Problema osservato | Effetto nell’uso reale | Correzione v0.7 |
|---|---|---|
| Home composta da molte card indipendenti | Scorrimento eccessivo; le corse utili iniziano troppo in basso. | Home editoriale a tre livelli: tratta attiva, prossima corsa principale, elenco compatto delle successive. |
| Informazioni di qualità duplicate | Fonte, confidence e data refresh occupano spazio in banner, card e dettaglio. | Un solo indicatore di stato dati, compatto e apribile, sempre separato dalla lista delle corse. |
| Card delle corse troppo alte | Bassa densità; impossibile confrontare rapidamente orari, ritardi e binari. | Righe ferroviarie compatte a griglia: ora, ritardo/stato, destinazione, operatore, binario. |
| Filtri sempre esposti | La ricerca occasionale compete con l’azione principale “vedere le prossime partenze”. | Ricerca e filtri in una superficie dedicata, richiamata da un’azione chiara. |
| Priorità Lazio nella home | È una preferenza persistente, non un’azione di viaggio. | Spostamento nelle impostazioni/scheda della tratta. |
| Gestione tratte e creazione nella stessa lunga pagina | Aumenta il carico cognitivo e rende le tratte esistenti meno immediate. | Elenco tratte prima; creazione/modifica in sheet a passo singolo. |
| Linguaggio visivo generico | Le card Material standard non costruiscono familiarità né senso di “tabellone ferroviario”. | Sistema Binario originale: indaco ferroviario, avorio caldo, rosso segnale riservato a eventi critici, numeri orari tabulari e indicatori di linea. |
| Dettaglio fermate poco strutturato | La sequenza è leggibile ma non trasmette avanzamento e contesto della corsa. | Timeline con linea, fermata corrente, arrivo/partenza, binario e scostamento visibili a colpo d’occhio. |

## Obiettivo dell’interazione

La home deve rispondere entro pochi secondi a quattro domande: **dove sto andando, quale corsa mi interessa per prima, quando parte davvero e cosa devo fare dopo**. La ricerca per numero e la gestione delle tratte sono importanti, ma entrano dopo la consultazione immediata e non devono competere visivamente con essa.

## Architettura informativa proposta

| Sezione | Scopo | Azioni principali |
|---|---|---|
| Oggi | Tratta attiva, prossime partenze e stato dato. | Cambia direzione, aggiorna, apri corsa, cambia tratta. |
| Cerca | Ricerca per numero treno, tratta o stazione; filtri per operatore. | Cerca, filtra, apri risultato, salva come tratta. |
| Le mie tratte | Selezione, riordino, fissaggio, modifica e creazione. | Attiva, fissa, modifica, elimina, aggiungi. |
| Dettaglio corsa | Stato, binario, tempi e fermate. | Torna alla home, aggiorna, monitoraggio futuro. |

## Sistema visivo originale

La palette proposta non imita l’identità di alcun operatore: usa **indaco profondo** per navigazione e contesto, **avorio chiaro** per superficie, **teal freddo** per stati regolari, **ambra** per attenzione e **rosso segnale** per soppressione. Gli orari usano cifre tabulari e peso elevato; destinazione e binario sono i secondi ancoraggi visivi. Gli elementi decorativi saranno limitati a una linea di percorso sottile e ai marker di stato, mai a immagini o texture che riducano densità o contrasto.

Il design system applicherà le tre leve di Material 3 — colore, tipografia e forme — come token centrali anziché colorare singole card in modo isolato. La guida ufficiale raccomanda inoltre di usare una navigation bar per poche destinazioni sui dispositivi compatti e di sostituire le card con liste più compatte sugli schermi piccoli quando la densità informativa è prioritaria.[2] [3]

| Token | Decisione Binario |
|---|---|
| Superfici | Background avorio/ardesia e righe lista a tonalità molto leggere; una sola card hero per la prossima corsa. |
| Tipografia | Orari in `titleLarge`/cifre tabulari, destinazione in `titleMedium`, metadati in `labelMedium`; nessun testo essenziale sotto la dimensione `bodySmall`. |
| Forme | Angoli da 14–20 dp solo per hero, sheet e gruppi; le corse successive sono righe separate da divisori, non card annidate. |
| Navigazione | Tre destinazioni: Oggi, Cerca, Tratte. Il dettaglio è un livello successivo con ritorno esplicito. |
| Semantica | La riga corsa è un solo elemento accessibile e ordinato logicamente: destinazione, orario, stato, binario, operatore. |

## Vincoli del redesign

1. Il catalogo demo non deve sembrare un feed live: il microcopy vincolante resta **“Catalogo dimostrativo locale — non live”** o **“dati non disponibili in tempo reale”** quando appropriato.
2. Ogni azione primaria mantiene target minimo di 48 dp e semantica TalkBack completa.
3. Font al 200%, dark mode e display stretto non possono nascondere orario, stato, destinazione, binario o azione refresh.
4. Nessun elemento UI introduce account, tracking, vendita o geolocalizzazione continua.

## Decisione di implementazione

Il redesign non sarà un restyling di card esistenti. La schermata principale, la navigazione, la lista corse, la gestione tratte e il dettaglio verranno ricostruiti come componenti separati, mantenendo ViewModel e contratti dati già testati.

## Riferimenti

Le scelte derivano dalla matrice multi-app già consolidata: consultazione rapida e widget da Orario Treni, home personale da Trenìt e Île-de-France Mobilités, densità informativa da Info Treno e TrenScan, stati e dettaglio da Trenitalia/Italo, filtri espliciti da Omio e gestione anomalie da Trainline.[1]

[1]: /home/ubuntu/consegna_binario_v05/MATRICE-UNICA-ISPIRAZIONE-E-ROADMAP.md "Matrice unica di ispirazione e roadmap per Binario"
[2]: https://developer.android.com/develop/ui/compose/designsystems/material3 "Android Developers — Material Design 3 in Compose"
[3]: https://m3.material.io/components/cards/guidelines "Material Design 3 — Cards"
[4]: https://developer.android.com/develop/ui/compose/accessibility "Android Developers — Accessibility in Jetpack Compose"
