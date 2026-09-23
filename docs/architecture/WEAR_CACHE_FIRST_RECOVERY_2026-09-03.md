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

Telefono e orologio usano entrambi l'application ID `it.binario.app`; i namespace Kotlin restano distinti. Le build destinate a comunicare devono inoltre essere firmate con la stessa identità. Il listener accetta soltanto il path Data Layer dello snapshot, mentre il telefono accetta richieste di refresh solo su `/binario/wear/request-refresh`, con deduplica per nodo e rate limit. La condivisione è disattivata per default; quando viene revocata, il telefono elimina il DataItem e l'orologio cancella cache, Tile e complication alla ricezione di `TYPE_DELETED`.

`com.google.android.wearable.standalone=false` descrive correttamente questa superficie: il prodotto dipende dal telefono e non esegue query ferroviarie autonome. Prima della pubblicazione va documentato il canale distributivo scelto e va verificata sul Play Console l'associazione telefono–Wear; questo documento non sceglie tra track Play e distribuzione di collaudo.

Il ripristino non costituisce una prova su hardware reale né una dichiarazione di conformità assistiva. Restano necessari i test su un Wear OS reale per target touch, lettura TalkBack, Tile Carousel, complication, collegamento al telefono e comportamento in ambient mode.

## Riferimenti

[1]: https://developer.android.com/training/wearables/tiles/get_started "Android Developers — Get started with Tiles"
[2]: https://developer.android.com/training/wearables/complications/exposing-data "Android Developers — Expose data to complications"
