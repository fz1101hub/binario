# Binario Wear OS — ripristino cache-first

## Perimetro

Il ramo Git storico dichiarava nel manifest un’app Wear, un Tile e una complication, ma non conteneva risorse, modello snapshot, store, Tile o data source della complication. I componenti sono stati ricostruiti come superfici derivate dell’app Android: il telefono resta l’unico soggetto che interroga il catalogo locale e che pubblica un payload ridotto sul Data Layer.

| Componente | Dato gestito | Garanzia |
| --- | --- | --- |
| `WearSnapshotPublisher` sul telefono | Tratta attiva, massimo due corse, fonte, freschezza, confidenza e avviso. | Non trasferisce credenziali, account, posizione, biglietti o trascrizioni. |
| `WearSnapshotCodec` sull’orologio | Schema versione 1, testo normalizzato e limitato, massimo due corse. | Rifiuta schema ignoto, identità mancante e timestamp non valido; un payload errato non sostituisce la cache valida. |
| `WearSnapshotStore` | Ultimo snapshot validato in preferenze private dell’app Wear. | Nessuna richiesta ferroviaria o sincronizzazione cloud avviata dall’orologio. |
| App, Tile e complication | Stessa cache locale e stesso significato degli stati. | “Orario programmato”, ultimo snapshot e indisponibilità restano espliciti; il refresh è una richiesta delegata al telefono. |

## Criteri tecnici

Il Tile è volutamente testuale e compatto: i Tile Wear non sono superfici a scorrimento e devono mantenere contenuti brevi e leggibili anche con testo aumentato. La registrazione resta un servizio protetto dal sistema operativo, mentre la complication rispetta il periodo minimo di 300 secondi già dichiarato nel manifest e riceve aggiornamenti solo quando arriva un nuovo snapshot valido. [1] [2]

Il ripristino non costituisce una prova su hardware reale né una dichiarazione di conformità assistiva. Restano necessari i test su un Wear OS reale per target touch, lettura TalkBack, Tile Carousel, complication, collegamento al telefono e comportamento in ambient mode.

## Riferimenti

[1]: https://developer.android.com/training/wearables/tiles/get_started "Android Developers — Get started with Tiles"
[2]: https://developer.android.com/training/wearables/complications/exposing-data "Android Developers — Expose data to complications"
