# Protocollo di test TalkBack — Binario Android

**Scopo:** raccogliere un primo ciclo di evidenze reali su un telefono Android. Non è una certificazione né una dichiarazione di conformità all’European Accessibility Act.

> Usare esclusivamente l’APK interno fornito per questo test. Non contiene fonti ferroviarie realtime: ritardi, binari e cancellazioni devono restare esplicitamente indisponibili.

## Artefatto interno da identificare prima del test

| Superficie | File locale di build | Identificativo manifest | SHA-256 |
| --- | --- | --- | --- |
| Telefono Android — richiesto per questo protocollo | `android/app/build/outputs/apk/release/app-release.apk` | `im.binario.companion`, label **Binario**, versione `0.1.1` (code `3`) | `aa1c7676127279cffc3b4f3251ca290a4856361ab3d664abbe56e3e60d2e9e12` |
| Wear OS — solo per un successivo test su orologio | `android/wear/build/outputs/apk/release/wear-release.apk` | `im.binario.companion.wear`, label **Binario**, versione `0.1.0` (code `1`) | `2b5c08bbf8a8c08c5245235c5d1d1d98a1e7a33a69a4e36808f255960158add4` |

L’APK telefono è l’unico artefatto necessario al ciclo TB-01–TB-09. La directory Gradle `release` non implica una pubblicazione: l’artefatto è un build interno di collaudo e non una release Play Store. L’APK Wear non va usato come sostituto dell’app telefono e richiede un test separato su hardware Wear OS associato tramite il sistema operativo.

## Preparazione

1. Verificare che l’hash dell’APK telefono corrisponda alla tabella precedente, installarlo sul telefono Android e aprire **Binario**. La label visibile deve essere **Binario**.
2. In **Impostazioni → Accessibilità → TalkBack**, attivare TalkBack e completare l’eventuale tutorial. Android indica la navigazione lineare con swipe destra/sinistra e doppio tocco per attivare l’elemento selezionato. [1]
3. Se disponibile, in **TalkBack → Impostazioni → Avanzate → Opzioni sviluppatore**, attivare *Mostra output vocale*. Non inviare a Binario testo personale, tratte, email o screenshot non oscurati.
4. Eseguire il test una prima volta con dimensioni testo/display abituali, poi con **dimensione carattere** e **dimensione display** maggiorate nelle impostazioni Android.

## Casi obbligatori

| ID | Azione con TalkBack | Esito atteso | Esito rilevato |
| --- | --- | --- | --- |
| TB-01 | Aprire Binario e scorrere tutti gli elementi dall’alto con swipe destra. | Titolo, stato offline e azioni sono letti in un ordine logico; nessun elemento operativo è irraggiungibile. | Pass / Fail / N.A. |
| TB-02 | Aprire il planner e raggiungere **Partenza**. Scrivere almeno tre caratteri e navigare i suggerimenti. | Campo ha nome leggibile; ogni suggerimento identifica una stazione; doppio tocco seleziona una sola stazione. | Pass / Fail / N.A. |
| TB-03 | Ripetere con **Arrivo** e tentare stessa origine/destinazione. | L’errore è annunciato, comprensibile e non blocca la correzione. | Pass / Fail / N.A. |
| TB-04 | Aprire selettore data e ora, cambiare valore, chiudere e rientrare nel form. | Controlli descritti dal sistema, valore aggiornato, focus torna a un punto prevedibile. | Pass / Fail / N.A. |
| TB-05 | Aprire promemoria, scegliere un anticipo e poi disattivarlo. | Stato scelto/disattivato è letto e il testo chiarisce che è programmato, non realtime. | Pass / Fail / N.A. |
| TB-06 | Salvare una tratta di prova e navigare il messaggio risultante. | Conferma o errore vengono annunciati; non serve account; nessun dato realtime viene promesso. | Pass / Fail / N.A. |
| TB-07 | Attivare tema scuro e contrasto elevato; ripetere TB-02 e TB-06. | Etichette e ordine focus invariati; testo e comandi restano utilizzabili visivamente. | Pass / Fail / N.A. |
| TB-08 | Aumentare font/display size e ripetere TB-02–TB-06. | Nessuna azione essenziale è troncata, coperta dalla barra di sistema o resa inaccessibile. | Pass / Fail / N.A. |
| TB-09 | Rifiutare permesso notifiche, se proposto, e creare una tratta. | Il planner resta usabile; il rifiuto è spiegato senza loop o pressione indebita. | Pass / Fail / N.A. |

## Evidenze da restituire

Per ogni caso `Fail`, annotare il codice, modello del dispositivo, versione Android, versione TalkBack, dimensione carattere/display, lingua di sistema, passaggio preciso e frase letta da TalkBack. Indicare inoltre la versione `0.1.1 (3)` e l’hash dell’APK sopra riportato, così che l’evidenza sia attribuibile al build provato. È sufficiente testo; allegare screenshot o registrazioni solo dopo aver oscurato dati personali. Segnalare anche messaggi troppo lunghi, ripetuti, ambigui o azioni che richiedono più tocchi del previsto.

## Secondo ciclo consigliato

Dopo i casi obbligatori, Android raccomanda di combinare test manuale, strumenti di analisi, test automatici e feedback di utenti. [1] Se disponibile, eseguire **Accessibility Scanner** sulle schermate planner e dettaglio tratta, quindi testare **Switch Access** o tastiera esterna almeno sul salvataggio di una tratta. I risultati di scanner non sostituiscono l’uso reale di TalkBack.

## Criterio di uscita

Il ciclo è accettabile solo se TB-01–TB-09 sono tutti `Pass`, oppure ogni `Fail` ha un difetto registrato, priorità assegnata, correzione verificata e retest superato. Questo costituisce evidenza di un ciclo Android, non la conformità completa: restano necessari i test Wear OS, iOS/VoiceOver, browser/screen reader, contrasto misurato e l’analisi del perimetro legale.

## Riferimenti

[1]: https://developer.android.com/guide/topics/ui/accessibility/testing "Android Developers — Test your app's accessibility"
