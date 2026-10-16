# Fonti live: protocolli correnti e verifica

Aggiornamento: 16 ottobre 2026. Questo documento sostituisce le ipotesi e i sondaggi dell’agosto 2026; i rapporti storici restano evidenza della sola data indicata. Una risposta HTTP 200, un feed con header recente o un adapter abilitato non bastano a confermare ritardi live.

## Percorso comune dell’app

`BinarioDataGraph` collega ricerca, widget e worker alle stesse fonti. Per oggi RFI cerca la stessa corsa nei tabelloni di partenza e arrivo; ViaggiaTreno JSON viene tentato come altra fonte live. Quando serve l’orario programmato, i Trip Updates STA/SNCF arricchiscono le corse GTFS tramite fonte, trip ID, fermata, sequenza e giorno operativo originali. I tabelloni disponibili possono aggiungere dati operativi identificabili. Non sono presenti regole di produzione specifiche per Appiano–Roma Ostiense.

Le fonti pubbliche sono abilitate per default nelle build personali debug e release. Un opt-out esplicito resta possibile con `-PBINARIO_PERSONAL_REALTIME_SOURCES=false`. Feed con credenziali non vengono abilitati senza autorizzazioni. I dettagli del funzionamento non costituiscono una verifica delle condizioni di distribuzione dei singoli servizi.

## RFI IECHub

- Catalogo ufficiale: `https://iechub.rfi.it/ArriviPartenze` (`select#ElencoLocalita`).
- Tabelloni: `https://iechub.rfi.it/ArriviPartenze/ArrivalsDepartures/Monitor?placeId=<id>&arrivals=True|False`.
- Dati: numero, operatore, orario, ritardo esplicito, soppressione e binario quando pubblicati. Ritardo vuoto non significa zero verificato.
- Gli ID dei monitor sono distinti da quelli dei quadri orario PRM. Le stazioni aggiuntive vengono risolte dal catalogo; alias ambigui vengono scartati.
- Le due estremità richieste sono collegate solo con identità della corsa coerente e univoca, tempi plausibili e osservazioni recenti. Non vengono inventate fermate intermedie o un ritardo d’arrivo a partire dal ritardo di partenza.

Verifica esterna riuscita: [run 38841303515](https://github.com/fz1101hub/binario/actions/runs/38841303515), commit `929f31f`: catalogo Appiano/Bressanone, partenze e arrivi Milano Centrale, tratta reale Appiano → Roma Ostiense senza dipendenza dal quadro orario statico.

## Italo in Viaggio

Il sito passeggeri usa `https://italoinviaggio.italotreno.com/api/RicercaStazioneService?CodiceStazione=<code>`. Il client legge il catalogo quando disponibile e ha un fallback curato di 50 codici Italo, separati dagli ID RFI. La risposta JSON deve identificare la stazione richiesta. `LastUpdate`, ritardo esplicito e nuovo orario sono controllati prima di dichiarare il dato operativo.

La pagina HTML `/it/stazione/<slug>` non è più la fonte HTTP del tabellone. Nessun token o proxy esterno viene usato. Nel [run 38841303515](https://github.com/fz1101hub/binario/actions/runs/38841303515) lo schema JSON era raggiungibile ma Roma Termini non aveva partenze e riportava `LastUpdate=00:28`: quell’esito non provava la disponibilità di ritardi numerici. Il controllo aggiornato campiona Roma, Milano e Napoli e richiede osservazioni operative reali: è passato nel [run 38895679873](https://github.com/fz1101hub/binario/actions/runs/38895679873).

## STA Alto Adige e SNCF

| Fonte | Orario statico originale | Trip Updates |
|---|---|---|
| STA | `https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw` | `https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/trip-updates` |
| SNCF | `https://eu.ftp.opendatasoft.com/sncf/plandata/Export_OpenData_SNCF_GTFS_NewTripId.zip` | `https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates` |

Entrambi gli archivi statici sono stati riscaricati e ricostruiti nel [run 38842400398](https://github.com/fz1101hub/binario/actions/runs/38842400398), con integrità e validità minima di 30 giorni verificate. Provenienza, hash prima/dopo e date sono in [LIVE_GTFS_REFRESH.json](LIVE_GTFS_REFRESH.json): STA fino al 12 dicembre 2026, SNCF fino al 31 marzo 2027. Gli altri otto orari non sono cambiati; è stata rimossa solo la geometria non usata. Anche l’archivio iOS derivato è stato rigenerato; il workflow di refresh ora esegue questo passaggio prima di salvare la branch dati.

Il collegamento alla ricerca richiede identità GTFS esatta e osservazioni entro cinque minuti; non associa linee bus a treni con somiglianze nel nome. Solo un aggiornamento della partenza richiesta verifica lo stato principale della corsa; una previsione d’arrivo isolata non basta. Il ritardo ricavato da una previsione resta distinto dal ritardo ufficiale pubblicato. Le righe senza aggiornamenti rimangono riconoscibili come programmate, anche in un risultato misto.

SNCF ha superato il controllo reale di tempestività e matching GTFS sia nel [run 38838184996](https://github.com/fz1101hub/binario/actions/runs/38838184996), sia con il nuovo archivio nel [run 38895679873](https://github.com/fz1101hub/binario/actions/runs/38895679873). Nel nuovo controllo STA conteneva 2.101 aggiornamenti recenti ma nessun trip ID del pack ferroviario aggiornato: il solo aggiornamento statico non ha quindi risolto la disponibilità dei ritardi ferroviari STA. Anche European Sleeper non aveva corrispondenze. RFI in quel runner non ha reso disponibili il catalogo e i tabelloni, dopo il precedente controllo riuscito: la disponibilità esterna non è garantita da un test passato in precedenza. Il test espone ora anche l’errore di trasporto RFI per distinguere rete, HTTP e contenuto.

## ViaggiaTreno

Il client JSON usa HTTPS e verifica stazione, numero, origine e giorno. Nei controlli Android reali l’endpoint ha reindirizzato verso HTTP; il client rifiuta il downgrade. Anche la variante mobile HTTPS ha restituito HTTP 301 nel [run 38842497071](https://github.com/fz1101hub/binario/actions/runs/38842497071). Non viene quindi dichiarata una fonte live funzionante in quei controlli. L’adapter mobile legacy non è collegato al percorso runtime. I tabelloni RFI restano utilizzabili indipendentemente dal problema ViaggiaTreno.

## Altri canali e limiti

European Sleeper/JBB è un feed comunitario best-effort: `https://jbb.ghsq.de/gtfs/european-sleeper/TripUpdates`. Nel controllo notturno i trip ID riportavano servizi dell’11 ottobre, assenti dall’orario corrente: header recente senza identità corrispondente non prova un ritardo attuale. RSS RFI, alert SNCF e posizioni STA restano informazioni distinte dai ritardi numerici. Svizzera GTFS-RT e Trenord E015 richiedono credenziali/autorizzazioni non disponibili: vedere [REALTIME_ACCESS_GAPS.md](REALTIME_ACCESS_GAPS.md).

## Verifica ripetibile

Il workflow `Live source acceptance` esegue `LiveProviderNetworkTest` contro i servizi reali, separato dai test deterministici. I test richiedono osservazioni operative per i tabelloni e aggiornamenti recenti con trip/stop ID risolvibili per GTFS-RT. I fallimenti di disponibilità esterna restano visibili e non vengono trasformati in test verdi tramite fixture o allentamento dei criteri. Il workflow `Refresh public live GTFS counterparts` rigenera solo i due archivi registrati STA/SNCF e pubblica una branch dati da revisionare; non modifica main.
