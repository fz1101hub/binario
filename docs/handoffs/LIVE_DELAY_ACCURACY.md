# Precisione dei ritardi live

Segnalazione del 16 ottobre 2026: treno 23263 ad Appiano, app a 0 minuti rispetto a 6 minuti osservati dall’utente. La fonte e l’istante della rilevazione di confronto non sono ancora stati confermati.

## Difetti riprodotti e correzioni

Cinque regressioni unit hanno fallito prima della correzione: valore visibile 6 con aria-label a zero; etichetta accessibile generica che nasconde il valore visibile; campo ritardo mancante erroneamente promosso a zero da messaggi della riga; differenza tra orario statico e orario del tabellone conteggiata come ritardo; osservazione operatore scaduta preferita al dato RFI recente.

- RFI legge prima il contenuto numerico della cella visibile e poi l’etichetta accessibile, con parsing dell’intero campo. Un campo sconosciuto non diventa un ritardo ufficiale zero; messaggi estranei non provano puntualità.
- La riconciliazione usa l’orario programmato del medesimo tabellone che fornisce l’orario atteso. Osservazioni scadute, future o oltre la validità della fonte non prevalgono per il solo rango del provider.
- La cache dei monitor dura 20 secondi, entro il minimo RFI, e scade prima del refresh UI ogni 30 secondi. La vecchia scadenza calcolata 30 secondi dopo il completamento della rete poteva far riusare lo stesso zero al refresh successivo. La regressione simula latenza e passaggio da 0 a 6 minuti, senza una pausa reale.
- `TrainStop.operationalStatusVerified` distingue una fermata aggiornata dalle altre. Il valore nullable conserva la compatibilità legacy; gli orari programmati e le fonti live parziali specificano ora lo stato per fermata. Un arrivo senza aggiornamento non diventa puntuale perché la partenza è live; un aggiornamento solo all’arrivo rimane visibile senza verificare la partenza.
- Per le fermate con eventi effettivi ViaggiaTreno, il ritardo è calcolato sul relativo evento; non viene copiato il ritardo corrente della corsa a un evento già passato.
- Il dettaglio conserva gli ultimi orari ma toglie la verifica operativa quando la corsa sparisce dal nuovo tabellone o una lettura fallisce dopo la scadenza. Il timestamp mostrato è quello della fonte utilizzata, non quello di una nuova lettura della cache.

## Verifica sulla fonte reale

Il [controllo sul codice precedente](https://github.com/fz1101hub/binario/actions/runs/38901090548), commit `487808e`, ha trovato 7 partenze riconciliate Appiano–Ostiense e ha confrontato un ritardo numerico visibile ad Appiano con il parser senza divergenze in quel campione. Per il 23263 la cella numerica visibile era vuota e il vecchio parser restituiva zero. Questo risultato **non dimostra** che i 6 minuti segnalati siano stati letti né che la precedenza dell’aria-label sia la causa specifica di quella segnalazione.

`rfiPublishedDelayAccuracy` confronta i numeri del tabellone con quelli dell’app; il test regionale espone anche partenza e arrivo del 23263 quando presente su entrambi i monitor. Le diagnostiche contengono solo gli esiti del test e i campi necessari al confronto, non copie dei payload remoti. Un treno assente al momento del controllo non viene dichiarato verificato per quel caso.

La disponibilità di un feed non garantisce accuratezza o simultaneità fra fonti: gli esiti specifici e l’APK corretto vanno riportati nella PR, separando regressioni riprodotte, osservazioni reali e limiti non risolti. Le limitazioni di STA, European Sleeper e ViaggiaTreno descritte in `docs/data-sources/LIVE_PROVIDER_PROTOCOLS.md` non vengono dichiarate risolte da queste modifiche.
