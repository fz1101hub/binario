# Correttezza temporale e operativa v0.20.4

Questa tranche separa la **data civile** dalla **data di servizio GTFS**, fissa i clock ferroviari italiani su `Europe/Rome` e impedisce che un orario ufficiale solo programmato venga presentato come stato live verificato.

Per le corse GTFS notturne, una ricerca dopo mezzanotte valuta anche i due giorni di servizio precedenti. Un orario `25:10` del venerdì viene quindi esposto come sabato alle `01:10`, senza duplicare corse già trascorse. Lo stesso criterio è usato quando gli aggiornamenti realtime vengono riconciliati con il pack statico.

Le periodicità RFI distinguono `LUN-VEN`, `FERIALE`, `DOM` e `FESTIVO`. `FERIALE` include il sabato ma esclude domeniche e festività nazionali; `FESTIVO` include domeniche, festività fisse (dal 2026 anche il 4 ottobre, legge 151/2025), Pasqua e lunedì dell’Angelo. `PREFESTIVO` indica i sabati e le vigilie delle festività; `LAVORATIVO` equivale a `FERIALE`.

I parser realtime riconoscono come cancellazione soltanto espressioni operative esplicite e non sottostringhe di nomi geografici come **Cancello Arnone**. I payload GTFS-RT svizzeri possono essere compressi gzip. `stop_sequence=0` viene conservato quando il campo protobuf è presente, ma il valore predefinito zero di un campo assente non viene trasformato in dato reale.

Infine, un operatore non classificato resta `null` nel contratto station-board invece di assumere l’identità fittizia `other`, e `ExternalRailCredentials.toString()` è sempre redatto.

Le istanze dei provider locali sono ora condivise tra le capability, quindi cache TTL e stato interno non vengono persi ricreando gli adapter. Il grafo filtra e ordina le liste runtime usando `ProviderRegistry`; i vecchi tipi `RetryPolicy` e `CircuitBreakerPolicy`, che non avevano alcun esecutore, sono stati rimossi per non promettere protezioni inesistenti. Anche l’adapter RFI supplemental usa una cache TTL. Quando solo la destinazione è un hub mappato, i quadri RFI di partenza restituiscono un limite direzionale esplicito invece di un errore GTFS generico.
