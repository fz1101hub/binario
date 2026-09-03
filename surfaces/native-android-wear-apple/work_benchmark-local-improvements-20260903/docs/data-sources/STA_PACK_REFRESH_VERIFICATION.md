# Aggiornamento del data pack STA Alto Adige

## Artefatto aggiornato

Il data pack `alto-adige-sta-gtfs.zip` è stato rigenerato il **15 agosto 2026** dal raw endpoint ufficiale Open Data Hub/STA:

`https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw`

Il feed è stato filtrato alle tipologie GTFS ferroviarie standard ed estese. L’asset risultante contiene 161 route, 3.791 corse, 47.454 stop time e 277 fermate. L’impronta SHA-256 registrata nel manifest è:

`af4ed31afa5ecc8f5507dd3a2acc21f58b3de7b8a6629595972ba21d3c46da7f`

## Verifica del raccordo realtime

Il feed `trip-updates` STA ha risposto HTTP 200 durante il controllo successivo. Il campione conteneva cinque entità e 123 aggiornamenti di fermata; nessun `trip_id` apparteneva alle corse ferroviarie filtrate, dunque il matching ferroviario era correttamente pari a zero.

La diagnosi dell’artefatto raw ha confermato che gli identificativi realtime e statici coincidono per una corsa presente nel feed completo, inclusi `route_id`, `trip_id` e `stop_id`. Il campione realtime osservato in quel momento riguardava servizi non ferroviari, esclusi intenzionalmente dal data pack ferroviario.

> Lo zero del matching non segnala più un data pack obsoleto. Indica che, nell’istante del test, non erano presenti aggiornamenti realtime per una corsa ferroviaria inclusa nel pack.

Il resolver conserva questa distinzione: potrà risolvere i Trip Updates ferroviari quando il feed li pubblicherà, ma non assocerà dati bus a una corsa ferroviaria né inventerà una rilevazione.
