# Binario v0.8 — direzione UX/UI

## Principi di partenza

Binario non replica interfacce, marchi, codice o asset di altre app. Prende invece tre pattern di prodotto: il **controllo tema esplicito e persistente**, un linguaggio cromatico ferroviario pulito con colori di segnale, e una home che parte da una ricerca origine/destinazione veloce con risultati immediati.

L’app Trenord pone ricerca, monitoraggio del treno, binario e aggiornamenti al centro del viaggio e propone preferiti per corse, linee e stazioni.[1] TrainPal struttura la pianificazione come una sequenza essenziale di partenza, arrivo, data/ritorno e ricerca.[2] Binario adotta questa gerarchia informativa, ma esclude vendita, prezzi, pubblicità, geolocalizzazione continua, profili obbligatori e tracking.

## Nuova home Binario

| Ordine | Blocco | Obiettivo |
|---|---|---|
| 1 | Barra superiore con tema e ricerca numero | Controlli globali, sempre disponibili. |
| 2 | Scheda di pianificazione | Origine, destinazione, inverti, data “Oggi”, direzione/ritorno e CTA `Cerca`. |
| 3 | Risultato immediato | Prossime corse della ricerca o della tratta attiva, in righe dense e leggibili. |
| 4 | Scorciatoie personali | Tratte fissate e ricerche recenti, mai prima della ricerca. |
| 5 | Stato dati e avvisi | Fonte, aggiornamento, non-live e azioni secondarie. |

## Tema e palette

Il selettore offrirà **Sistema**, **Chiaro** e **Scuro**, come scelta esplicita persistita localmente. L’interfaccia non userà il colore come unica indicazione di regolarità, ritardo, soppressione o dato non-live.

| Ruolo | Chiaro | Scuro | Uso |
|---|---:|---:|---|
| Verde ferroviario | `#007F5F` | `#4FE0AE` | Azioni primarie, conferme e ricerca. |
| Antracite | `#15211E` | `#E1E8E3` | Testo e gerarchia primaria. |
| Neutro carta | `#F7F8F5` | `#101714` | Sfondo e superfici. |
| Azzurro percorso | `#006F96` | `#78D4FA` | Navigazione e informazioni di percorso. |
| Ambra segnale | `#A55400` | `#FFB870` | Ritardi e attenzioni con testo esplicito. |
| Rosso segnale | `#B3261E` | `#FFB4AB` | Soppressioni ed errori. |

## Vincoli invariati

La ricerca non creerà tratte salvate senza un comando esplicito. Il catalogo locale continuerà a dichiarare **“Catalogo dimostrativo locale — non live”**. Le informazioni personali restano locali e la nuova UI conserva font scalabile, descrizioni TalkBack, target touch ampi, dark mode e contrasto elevato.

## Riferimenti

[1]: https://www.trenord.it/biglietti/dove-acquistare/app-trenord/ "Trenord — App Trenord"
[2]: https://www.mytrainpal.com/train-journey "TrainPal — Train Journey Planner"
[3]: https://www.trenord.it/accessibilita-digitale/ "Trenord — Accessibilità digitale"
