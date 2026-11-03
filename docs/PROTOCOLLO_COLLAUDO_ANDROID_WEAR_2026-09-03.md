# Binario — protocollo di collaudo Android, TalkBack e Wear OS

## Scopo e limiti

Questo protocollo serve a raccogliere evidenze manuali della build corrente su dispositivi reali. Il superamento dei casi elencati non equivale a certificazione WCAG, EAA o EN 301 549; verifica soltanto i flussi concreti di Binario con le tecnologie assistive e i componenti Wear disponibili.

| Artefatto | Uso previsto | SHA-256 |
| --- | --- | --- |
| `app-debug.apk` | Installazione manuale Android per TalkBack e flussi locali. | `769793743a1446475badaa7c7533f78d8fe00f78328a51befe0438d26c24d148` |
| `wear-debug.apk` | Installazione manuale su Wear OS compatibile e collegato al telefono. | `cc2e3840265d84211ac0cfae3d96280a989a5fea54e229d98bb87cc765f36e96` |
| `app-release-unsigned.apk` | Verifica di packaging tecnico; non installare come build utente poiché non è firmato per la distribuzione. | `12cab23c170a7bcd12ef834ea0152623d2b0c4ae27101e63a220015ad3208ee6` |
| `wear-release-unsigned.apk` | Verifica di packaging tecnico; non installare come build utente poiché non è firmato per la distribuzione. | `a22aae40e081c2818b0f8b92ec9c3d0ee24f658784fc27d985670c6b71e86361` |

Prima del test, annotare modello, versione Android/Wear OS, versione TalkBack, lingua, scala caratteri e scala schermo. Usare tratte di prova non riconducibili a spostamenti personali e non allegare screenshot o registrazioni non oscurati.

## Android con TalkBack

| ID | Flusso | Esito da registrare |
| --- | --- | --- |
| TB-01 | Aprire l’app e attraversare la Home con scorrimento, esplorazione al tocco e doppio tocco. | Ordine di lettura, titoli e tab raggiungibili, nessun controllo senza nome. |
| TB-02 | Compilare Partenza e Arrivo, selezionare un suggerimento, invertire le stazioni e scegliere data/ora. | Etichette distinte, suggerimento annunciato, inversione comprensibile e nessuna perdita del focus. |
| TB-03 | Attivare la stazione vicina dal campo Partenza e negare poi il permesso. | Consenso esplicito, alternativa manuale ancora utilizzabile, nessuna posizione salvata o richiesta in background. |
| TB-04 | Salvare una tratta, aggiungere un nome personale, fissarla, spostarla su/giù e riavviare l’app. | Nome, fissaggio e ordine scelto persistono; i controlli non consentono spostamenti oltre il gruppo fissato/regolare. |
| TB-05 | Aprire un orario programmato e un caso senza dati nel catalogo. | “Orario programmato” e “dati live non disponibili” sono annunciati come stati diversi; nessun ritardo o binario viene inferito. |
| TB-06 | Attivare chiaro, scuro e sistema; ripetere TB-01 con caratteri e display aumentati. | Contrasto leggibile, nessun testo essenziale tagliato e controlli ancora raggiungibili. |
| TB-07 | Esportare un backup locale, importarlo dopo aver rimosso una tratta di prova e verificare l’ordine. | Operazione esplicita, dati ripristinati, nessun cloud o account richiesto. |
| TB-08 | Configurare un widget con una tratta e aprirne l’azione. | Testo descrittivo, stato dati esplicito e deep link verso la schermata corretta. |
| TB-09 | Cancellare una tratta/avviso di prova e confermare l’esito con TalkBack. | Azione identificabile, conferma comprensibile e nessuna cancellazione di dati non selezionati. |

## Wear OS

| ID | Flusso | Esito da registrare |
| --- | --- | --- |
| W-01 | Collegare telefono e orologio; attivare la condivisione snapshot nell’app Android; salvare/aprire una tratta. | L’orologio riceve soltanto il riepilogo della tratta e non richiede account, token o posizione. |
| W-02 | Aprire l’app Wear e il Tile dopo la ricezione di uno snapshot GTFS programmato. | Etichetta “Orario programmato”, tratta e prima corsa leggibili; nessuna affermazione realtime. |
| W-03 | Disattivare la condivisione o simulare l’assenza di snapshot. | App, Tile e complication mostrano indisponibilità o assenza snapshot senza inventare dati. |
| W-04 | Aggiungere la complication breve e lunga, quindi usare TalkBack se disponibile. | Nome accessibile, testo breve comprensibile, nessun contenuto essenziale affidato al solo colore. |

Al termine, registrare per ogni ID **passato**, **fallito**, **non eseguito** oppure **bloccato**, insieme a una breve descrizione. I difetti devono riferirsi al solo flusso e non riportare dati di viaggio personali.
