# Mappa stazioni — Italo in Viaggio

Il 15 agosto 2026 sono state verificate tramite richiesta HTTPS pubblica le pagine di stazione Italo sotto `https://italoinviaggio.italotreno.com/it/stazione/{slug}`.

| ID Binario | Slug pubblico | Esito |
|---|---|---|
| `roma-termini` | `roma-termini` | Verificato |
| `roma-tiburtina` | `roma-tiburtina` | Verificato |
| `napoli-centrale` | `napoli-centrale` | Verificato |
| `firenze-smn` | `firenze-santa-maria-novella` | Verificato |
| `milano-centrale` | `milano-centrale` | Verificato |
| `torino-pn` | `torino-porta-nuova` | Verificato |
| `venezia-sl` | `venezia-santa-lucia` | Verificato |

Le URL candidate `bologna-centrale` e `bari-centrale` restituiscono HTTP 200 ma titolo `PageNotFound`; non entrano pertanto nella mappa runtime. Il provider deve fallire in modo esplicito sulle stazioni non mappate, consentendo a RFI e ViaggiaTreno di fornire gli altri dati disponibili.

Il tabellone Italo è classificato come `PUBLIC_BEST_EFFORT`: è una pagina pubblica dinamica e non un’API documentata. L’adapter effettua una singola GET HTTPS, usa cache e non tenta autenticazione, acquisto, endpoint BIG o token.

Riferimento: https://italoinviaggio.italotreno.com/it/stazione/roma-termini

## Codici delle stazioni nel GTFS Italo (novembre 2026)

Il pack `italo-gtfs` pubblica come `stop_code` i codici Italo a tre caratteri (`MC_`, `RMT`, `SMN`, …), gli stessi del tabellone pubblico dove entrambi esistono (Villa San Giovanni è `SGM` nel GTFS, `VSG` sul tabellone). La tabella `ItaloGtfsStationCodes` (`data/gtfs/GtfsStationCodeIndex.kt`) lega 63 dei 64 codici a una stazione del catalogo curato, rivisti per nome e posizione il 2 novembre 2026. Restano fermate del pack: `LUX`, la fermata stagionale «Lucca Comics and Games BUS», a circa un chilometro dalla stazione di Lucca. `TRX` (Treviso Centrale) è legato per nome: il feed lo colloca 5 km a est della stazione. Le fermate ItaloBus di Cortina (`CDZ`), San Vito di Cadore (`SDX`) e Tai di Cadore (`TDX`) sono legate alle voci del catalogo con lo stesso nome.
