# Verifica — Binario v0.20.0

## Obiettivo

Sostituire ogni catalogo concorrente con il database curato dell’utente e propagare l’identità della stazione a ricerca, tratte, widget, vicinanza e tabelloni live compatibili.

## Risultati verificati

| Controllo | Esito |
|---|---|
| Integrità SQLite | `PRAGMA integrity_check` restituisce `ok`. |
| Copertura | 5.239 stazioni; 3.430 codici ferroviari; 4.988 coordinate. |
| Codici Trenitalia | 3.086 ID univoci mantenuti; 40 ID ambigui esclusi dall’associazione automatica. |
| Roma Termini | `curated:S08409`, codice ferroviario `S08409`, location ID Trenitalia `830008409`. |
| Alias | Il test trova `Abano Terme` come alias della stazione canonica `Abano`. |
| Widget | Una tratta configurata conserva codice ferroviario e location ID di entrambi gli estremi. |
| ViaggiaTreno | Query per nome mantenuta dopo probe: il form pubblico non ha prodotto un tabellone usando `830008409`; la cache usa l’identità univoca della stazione. |
| Dati operativi | Inalterati: realtime qualificato → GTFS programmato → indisponibilità esplicita. |

## Validazione tecnica

| Controllo | Esito |
|---|---|
| `:app:compileDebugKotlin` | Superato. |
| `:app:testDebugUnitTest` | Superato. |
| Codice e asset precedenti OSM | Rimossi dal progetto. |

## Limite esplicito

Il catalogo non rende disponibile un ritardo per ogni stazione. Risolve l’identità ferroviaria e limita gli errori di matching; i ritardi restano visibili soltanto se un provider operativo restituisce dati aggiornati e coerenti.
