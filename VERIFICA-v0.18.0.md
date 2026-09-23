# Verifica Binario v0.18.0 — input libero e widget indipendenti

**Data:** 15 agosto 2026  
**Branch canonico:** `feature/data-foundations`  
**Ambito:** eliminazione di ogni preset dimostrativo e configurazione delle istanze widget tramite input libero.

| Controllo | Esito | Evidenza |
|---|---|---|
| Tratta all’avvio | Superato | Nessuna coppia origine–destinazione è caricata automaticamente; l’app chiede di inserirla. |
| Ricerca libera | Superato | Un nome digitato con almeno due caratteri genera una stazione locale cercabile senza creare orari. |
| Widget tratta | Superato | La configurazione salva origine e destinazione nell’istanza, senza dipendere da tratte o preferiti dell’app. |
| Widget stazione/treno | Superato | Nome stazione e numero treno sono inseribili liberamente; le fonti mancanti producono solo indisponibilità esplicita. |
| Stato per istanza | Superato | Direzione, pagina, snapshot e messaggio di errore hanno chiavi per singolo `appWidgetId`. |
| Dati dimostrativi | Superato | `DemoTrainDataSource` e le relative righe artificiali sono rimossi dal codice di produzione. |
| Test unitari | Superato | `:app:testDebugUnitTest` completato con successo, inclusi i nuovi test sulla tratta widget libera e sul percorso iniziale neutro. |

> Il fatto che una stazione o un numero treno sia configurabile non implica che la fonte autorizzata disponga di un tabellone o stato realtime per quel target. In quel caso Binario mantiene la configurazione e dichiara l’indisponibilità; non sostituisce il dato con simulazioni.
