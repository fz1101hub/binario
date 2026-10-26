# Riferimento On The Rails — verifica del 26 ottobre 2026

## Artefatto e verifica ripetibile

Il file richiesto è [`references/On_The_Rails_1.1.9.xapk`](https://github.com/fz1101hub/binario/blob/c2e905cf1139a2d860c43e16148bb078591c79d6/references/On_The_Rails_1.1.9.xapk), 44.435.213 byte. Il download locale è rimasto bloccato dalla sandbox; l’analisi è stata completata su GitHub Actions, senza installare o eseguire l’app.

- [PR #43: script, test e workflow](https://github.com/fz1101hub/binario/pull/43).
- [Esecuzione riuscita 40385434745](https://github.com/fz1101hub/binario/actions/runs/40385434745).
- Git blob SHA-1 verificato: `84007d65164c327aede305267bd7bca7d0ea5cee`.
- SHA-256: `05e0a37c64cc13e2c0faf4083d5b6a9b07951f42f47b999335708398231368c3`.
- Quattro APK split, due file DEX, 55.735 stringhe esaminate.
- [Risultato normalizzato](ontherails-1.1.9-static-report.json).

La versione 1.1.9 deriva dal nome del file: lo scanner non ha ottenuto metadati dal manifest XAPK e non verifica firma, certificato o disponibilità di versioni successive. La ricerca è limitata alle stringhe DEX e ai domini degli operatori riconosciuti dallo scanner; non è una decompilazione completa né una prova del traffico effettivo dell’app.

## Fonti individuate

| Riferimento presente | Evidenza | Conseguenza per Binario |
|---|---|---|
| `app.lefrecce.it` | Canale `Channels.Mobile.BFF`, inclusa la rotta `api/info/mobility/status` | Conferma il riferimento mobile Trenitalia già documentato. Disponibilità attuale, contratto e accesso per terzi restano da verificare; non viene attivato automaticamente. |
| `iechub.rfi.it` | URL del dominio RFI | Confrontare con l’adapter RFI già esistente e mantenere l’opt-in delle fonti best-effort. |
| `www.viaggiatreno.it` | Riferimenti REST sia HTTP sia HTTPS | Binario conserva HTTPS obbligatorio e non importa gli URL HTTP. Un riferimento statico non prova che oggi la rotta risponda. |
| `www.trenord.it` | URL del sito Trenord | Non dimostra l’accesso al feed realtime E015. GTFS programmato e accesso protetto rimangono distinti. |
| `www.lefrecce.it` | Altri percorsi sul dominio LeFrecce | Nessuna attribuzione automatica di copertura o autorizzazione. |

Le rotte non riconosciute, query, frammenti, userinfo e stringhe grezze sono esclusi dal rapporto. Nessuna chiave, credenziale, classe, libreria, risorsa grafica o database dell’app di riferimento è stato importato nel runtime di Binario. La presenza di un URL non prova licenza di riuso o funzionamento corrente. Non sono stati inventati nuovi provider sulla base di queste stringhe.

## Funzioni e intervento realizzato

La [scheda pubblica Google Play](https://play.google.com/store/apps/details?id=it.pixeldev.ontherails&hl=it), consultata il 26 ottobre 2026, dichiara monitoraggio, distinzione dei ritardi, promemoria configurabili, ricorrenze, riprogrammazione e storico. Le stringhe DEX offrono riscontri testuali, non una verifica funzionale.

| Funzione utile ai pendolari | Stato di Binario dopo questo intervento |
|---|---|
| Anticipo della sveglia fermata | Implementato su Android: 1–60 minuti e scorciatoie 5/10/15/30. |
| Modifica della stessa sveglia | Identità stabile per corsa, giorno e fermata: l’allarme aggiornato sostituisce il precedente. |
| Allarmi ricorrenti | Già presenti nel modulo degli avvisi pendolare; nessun secondo sistema aggiunto. |
| Ritardo ufficiale e previsto | Distinzione conservata nel modello; nessun dato statico viene promosso a live. |
| Aggiornamento automatico dell’allarme dopo un ritardo | Non implementato: il promemoria usa l’orario alla conferma e lo spiega nell’interfaccia. |
| Storico e geofencing | Non aggiunti: richiedono decisioni separate su raccolta, conservazione e consumo energetico. |

La sveglia è un’implementazione originale. Il consenso notifiche viene richiesto dopo la conferma. L’anticipo non crea nuove richieste ferroviarie, tracking o accesso alla posizione. I casi limite includono mezzanotte, viaggi futuri, fermate superate, anticipo invalido, avviso imminente e sostituzione senza duplicati.

## Verifiche locali

- Compilazione diretta di risorse Android, Java e Kotlin/Compose superata.
- 391 test JVM dell’app passati.
- 123 test Python passati, inclusi 6 test dello scanner.
- Verifica privacy e pinning CI superate.
- Consegna della notifica su dispositivo e test TalkBack del nuovo selettore da eseguire nel collaudo reale.
