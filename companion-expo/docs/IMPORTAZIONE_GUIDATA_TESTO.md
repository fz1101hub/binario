# Importazione guidata da testo

## Comportamento incluso

Binario accetta testo che l’utente **incolla esplicitamente** nel campo Agenda oppure, su Android, decide di inviare a Binario dal menu di sistema **Condividi**. Il target Android dichiara esclusivamente `ACTION_SEND` per `text/plain`: non riceve file, immagini, URL da risolvere o MIME generici.[1]

Ogni riga deve contenere origine, destinazione, data e ora; le righe incomplete sono ignorate. Prima del salvataggio l’app mostra un’anteprima selezionabile e richiede la conferma che data, ora e stazioni siano state verificate. Le tratte vengono aggiunte, mai sovrascritte, e i duplicati non sono importati.

Il payload ricevuto è limitato a 4.000 caratteri e viene trattenuto solo nella coda locale necessaria ad aprire l’Agenda: viene cancellato subito dopo la consegna al runtime attivo oppure dopo il consumo all’avvio. Il testo non viene inviato a server, provider ferroviari o modelli AI. Date e orari restano **programmati**, non realtime.

## Limite iOS e Web

`expo-sharing` 14.0.8, la versione risolta per Expo SDK 54, supporta l’invio da Binario ad altre app ma non registra l’app come destinataria di contenuti condivisi.[2] Perciò, su iOS e sul web, l’importazione guidata resta basata sull’incolla deliberato. Una futura estensione iOS richiede integrazione Xcode/manuale, verifica su dispositivo e non è dichiarata come implementata o testata.

## Riferimenti

[1]: https://developer.android.com/training/sharing/receive "Android Developers — Receive simple data from other apps"
[2]: https://docs.expo.dev/versions/v54.0.0/sdk/sharing/ "Expo SDK 54 — Sharing"
