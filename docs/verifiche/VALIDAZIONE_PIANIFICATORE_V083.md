# Validazione su dispositivo — Binario v0.8.3

| Scenario | Risultato atteso |
|---|---|
| Apertura a freddo | L’app apre su **Cerca e consulta**, non su tabellone/partenze. |
| Pianificatore | I campi Partenza e Arrivo, inversione, Sola andata/Andata e ritorno, Data, Ora, Filtri e `Cerca soluzioni` sono nello stesso blocco. |
| Ricerca | Premere `Cerca soluzioni` porta alla schermata Soluzioni con il tabellone della tratta; indietro ritorna al pianificatore. |
| Recenti/preferite | Compaiono sotto il pianificatore; selezionarle compila la tratta senza forzare subito i risultati. |
| Treni | La tab inferiore apre il tabellone della tratta attiva con direzione, refresh e dettaglio. |
| Stazioni | Mostra ricerca stazione e indisponibilità esplicita del live senza dati inventati. |
| Altro | Raccoglie tratte personali e impostazioni; non sposta la ricerca dalla home. |
| Widget | Resta il tabellone paginato di cinque corse, separato dal pianificatore. |
| Accessibilità | Con font 200% il pulsante di ricerca e i controlli di viaggio restano raggiungibili; TalkBack annuncia le etichette dei campi e il percorso attivo. |

## Verifica tecnica

`testDebugUnitTest`, `lintDebug` e `assembleDebug` sono completati con successo. Il report lint conclude con **No issues found**.
