# Verifica provider Italo

## Esito tecnico

Il provider `ItaloPublicStationAdapter` usa esclusivamente le pagine pubbliche di **Italo in Viaggio**. Il 15 agosto 2026 sono state verificate sette pagine di stazione: Roma Termini, Roma Tiburtina, Napoli Centrale, Firenze Santa Maria Novella, Milano Centrale, Torino Porta Nuova e Venezia Santa Lucia. Tutte hanno risposto HTTPS con stato 200, titolo coerente e marcatore di tabellone realtime.

| Voce | Esito |
|---|---|
| Pagine di stazione valide | 7/7 |
| API private, BIG o token | Non utilizzati |
| Parsing | HTML pubblico, controllato con fixture |
| Cache | 60 secondi per stazione e direzione |
| Stazioni senza slug verificato | Fallimento esplicito, poi fallback |
| Assenza di corse | Risultato non parsabile, poi ViaggiaTreno/RFI |

Durante lo smoke test notturno tutte le pagine indicavano correttamente assenza di treni in circolazione. Questa situazione non è un errore del provider: l’adapter restituisce un fallimento controllato e lascia che il repository continui con ViaggiaTreno e RFI; non mostra un tabellone vuoto come dato Italo live.

## Limiti dichiarati

Il tabellone è una pagina pubblica dinamica e non un’API documentata. Per questo è classificato `PUBLIC_BEST_EFFORT`, con parser difensivo, mapping esplicito e nessuna richiesta di autenticazione o vendita. Le URL candidate che restituiscono pagina `PageNotFound` non vengono inserite nella mappa runtime.

## Riferimento

[1]: https://italoinviaggio.italotreno.com/it/stazione/roma-termini "Italo in Viaggio — Roma Termini"
