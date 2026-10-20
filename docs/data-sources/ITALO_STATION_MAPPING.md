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
