# Ricerca Appiano → Roma Ostiense

La presenza di una stazione nel catalogo non garantiva la copertura degli orari.
Appiano Proba Petronia (`S08328`, alias `APPIANO`) era ricercabile, ma non aveva
un ID PRM RFI mappato. Il datasource consultava soltanto le partenze dall'origine;
i pack GTFS inclusi non contengono questa tratta del Lazio.

Quando l'origine non è mappata e la destinazione ha un ID PRM verificato, la
ricerca consulta ora il quadro **arrivi della destinazione**. Per Roma Ostiense
usa l'ID PRM `2211`, distinto dall'ID dei monitor operativi. Accetta una corsa
soltanto se le fermate precedenti contengono l'origine e il suo **orario di
partenza**, e se il quadro pubblica l'orario di arrivo alla destinazione.
Non ricava gli orari invertendo le corse della direzione opposta.

Le fermate vengono ritagliate tra l'origine richiesta e la destinazione;
filtro orario, calendario, validità e passaggio della mezzanotte vengono
applicati prima di restituire il risultato. I dati restano programmati, con
stato operativo non verificato, senza ritardi o binari presunti. Il fallback
locale senza copertura conserva gli errori specifici della fonte ufficiale.
Un timeout o errore di connessione conserva il motivo `NetworkUnavailable` e
mostra un messaggio localizzato sul servizio degli orari non raggiungibile,
distinto da tratta non coperta, orari scaduti o corse assenti dopo l'ora scelta.
Gli errori non vengono memorizzati come quadri vuoti: una nuova ricerca può
ritentare la richiesta.

## Verifica

Le regressioni JVM includono il repository con gli asset GTFS reali e senza
provider realtime. Le tabelle HTML dei test JVM sono sintetiche: i loro orari
non dimostrano l'esistenza di corse reali.

Il test opzionale `RfiJourneyNetworkTest` esegue la ricerca del giorno corrente
tramite il graph dell'app su Android. Si avvia esplicitamente con:

```bash
gh workflow run android-ci.yml --ref fix/appiano-route-search -f verify_rfi_route=true
```

La suite UI ordinaria lo esclude per non dipendere dalla disponibilità esterna
di RFI. Il workflow esplicito richiede anche il successo del test di rete prima
di produrre l'APK. Conserva il risultato in `binario-ui-rfi-route.log`, incluso
nell'artifact dei report Android. Un fallimento non va trasformato in un dato
dimostrativo o ignorato.

L'accesso diretto dal runtime cloud a `prm.rfi.it` ha ricevuto un rifiuto dal
proxy; il dominio è stato aggiunto alla bozza di rete, senza applicazione o
pubblicazione implicita. Questo limite del cloud non dimostra un guasto RFI
sul telefono. Le verifiche Android dei servizi esterni usano il normale client
dell'app, preservando TLS, e non esportano le pagine HTML dal runner.

L'APK di test usa la firma debug del runner. Se Android segnala una firma
incompatibile con una precedente installazione, esportare prima l'Agenda e
reimportarla dopo la reinstallazione.
