# Riconciliazione del backlog storico

## Criterio

Questo documento confronta le richieste dell’allegato `pasted_content.txt` con il codice corrente di Binario. Una voce viene considerata completata solo quando è presente nel prodotto o nel relativo contratto verificato; i test reali con tecnologie assistive e hardware restano invece **gate esterni**, non sostituiti da build o test automatici.

| Voce storica | Stato | Evidenza corrente |
| --- | --- | --- |
| Ricorrenze pendolari | Completata | Agenda locale, giorni/orari/occorrenze espliciti e nessuna automazione di rete. |
| Agenda giornaliera o settimanale | Completata | Agenda web con filtri e companion con filtri Imminenti, Settimana e Archivio. |
| Statistiche locali | Completata | Conteggi e destinazioni frequenti calcolati nel browser, senza profilazione. |
| Condivisione e calendario | Completata | Riepilogo minimizzato, export/import ICS esplicito e nessuna sincronizzazione silenziosa. |
| Widget prossima tratta | Completata | Tratta futura, countdown, promemoria e disclosure non realtime. |
| Duplicazione rapida | Completata nell’area account; parziale in locale | La procedura personale duplica tratte; manca un modello riutilizzabile completo per origine, destinazione e preferenze del browser locale. |
| Template viaggio completi | Da sviluppare | Esistono modelli checklist, ma non un archivio locale esplicito di template origine/destinazione/orario. |
| Ricerca globale delle tratte | Da sviluppare | Esistono ricerca stazioni, Agenda e azioni rapide, ma non una ricerca locale unificata su tratte salvate. |
| Checklist o azione rapida watch | Completata nel codice; verifica hardware aperta | La checklist a voci fisse resta sul telefono; il Tile riceve solo il conteggio residuo e apre l’Agenda del telefono associato. |
| Onboarding progressivo | Già coperto nel nucleo locale-first | Uso iniziale senza account, azioni rapide, disclosure e collegamento/sync separati. |
| Accessibilità codice e UX | Ampiamente implementata; prove reali aperte | Semantica, focus, errori, reflow, riduzione movimento e modalità semplificata sono nel codice; restano i collaudi NVDA, VoiceOver, TalkBack e Wear reali. |
| Raffinatezza visuale Tile | Da validare su hardware | Il codice è compilato e semantico; la valutazione visiva/touch reale richiede Wear OS. |

## Perimetro degli incrementi residui

Gli incrementi ancora realizzabili senza provider ferroviari, account operatore, console esterne o nuove dipendenze sono intenzionalmente limitati a tre funzioni locali: **template viaggio**, **ricerca tratte** e **accesso alla checklist dal Tile Wear**. Saranno progettati riusando i contratti locali esistenti, senza introdurre un motore di automazione, un database parallelo o trasferimenti automatici.

## Gate non sostituibili

Restano esterni al codice: test TalkBack/VoiceOver/NVDA e Wear reali, zoom reale, firma e distribuzione store, target Xcode/App Group, provider ferroviario autorizzato, seller allowlist reale, console Alexa e un canale pubblico di feedback con processo di gestione.
