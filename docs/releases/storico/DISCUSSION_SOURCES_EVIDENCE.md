# Evidenze dalla discussione sulle fonti ferroviarie

## Stato della lettura

Questo documento raccoglie progressivamente le conclusioni della chat di riferimento `42R4SMH4wcGw5WqE9fCfDV`. La chat è una fonte di **motivazioni e piste di verifica**; codice, contratti e licenze del branch `feature/data-foundations` restano l’autorità tecnica per l’abilitazione effettiva.

## Evidenze consolidate nella prima parte della discussione

| Tema | Conclusione emersa | Decisione di governance coerente |
|---|---|---|
| Treniamo e On The Rails | Sono casi di studio utili a individuare fonti originarie e pattern UX. | Non riutilizzare API, chiavi client, database, asset, codice o dataset estratti da tali prodotti. |
| Copertura | L’obiettivo copre tutti i servizi ferroviari passeggeri sul territorio italiano, inclusi transfrontalieri e operatori esteri. | Combinare fonti realtime identificate, GTFS ufficiali e fallback RFI; non dichiarare copertura realtime totale. |
| Open data | Sono emersi GTFS/open data per Svizzera, SNCF, ÖBB, Lombardia/Trenord, STA Alto Adige, EAV, AMT Genova–Casella e reti regionali. | Usare soltanto pack verificati con manifest, licenza e integrità; attribuzione quando prevista. |
| Fonti best-effort | ViaggiaTreno, RFI e Italo in Viaggio sono stati individuati come canali pubblici operativi, ma non equivalgono automaticamente a una licenza API. | Adapter isolati, cache, rate limit, fallback e degradazione sicura; nessun aggiramento di login, CAPTCHA, WAF o token. |
| Dati operativi | Ritardi, binari, soppressioni e posizioni non possono derivare dall’orario statico. | Realtime identificato → GTFS programmato → indisponibilità; provenienza, freshness e qualità sempre visibili. |
| Posizione stimata | Un’eventuale interpolazione non è GPS. | Rappresentarla solo come stima e mai come posizione reale. |
| Canali protetti | Realtime Svizzera e Trenord richiedono rispettivamente gateway/token o adesione E015. | Lasciarli disabilitati nell’APK finché non esistono autorizzazione, gateway, fixture, cache e rate limit. |

> Nessuna conclusione storica che suggerisca l’uso diretto di endpoint non documentati, token di app terze o sorgenti private modifica i vincoli di sicurezza e provenienza già fissati nel branch canonico.

## Evidenze consolidate nella seconda parte della discussione

La parte conclusiva della chat documenta l’evoluzione della baseline fino al commit originario `0e0867ad`: dodici data pack GTFS embedded con manifest e checksum, adapter per ViaggiaTreno, RFI, Italo in Viaggio, RSS RFI, STA Alto Adige e SNCF GTFS-Realtime. Essa chiarisce anche, in modo non ambiguo, che ampia copertura oraria **non equivale** a realtime universale.

| Area | Conclusione integrale della discussione | Stato da mantenere nel branch canonico |
|---|---|---|
| GTFS embedded | Il massimo praticabile include dodici pack statici: Alto Adige, Svizzera, ÖBB, SNCF, Lombardia/Trenord, HŽPP, EAV, ARST, AMT Genova–Casella, Cotral/ASTRAL, TFT e Trentino Trasporti. | Validare sempre manifest, licenza e SHA-256; mostrare l’esito come **programmato**. |
| Realtime Trenitalia/RFI/Italo | ViaggiaTreno, RFI e Italo in Viaggio sono canali best-effort identificati; RSS RFI fornisce avvisi. | Isolare in adapter, applicare timeout/cache/rate limit/circuit breaker e non presentare il canale come API garantita. |
| STA Alto Adige | Alert e posizioni di veicolo sono disponibili solo per le entità presenti nel feed STA. | Esporre provider e natura del dato; non generalizzare la posizione a operatori diversi. |
| SNCF | Alert e Trip Updates GTFS-Realtime sono fonti ufficiali; il matching richiede identità di corsa verificata. | Non attribuire aggiornamenti a una corsa UI in assenza di un match controllato. |
| Svizzera | Il GTFS statico è disponibile; il GTFS-Realtime richiede un token gratuito. | Il token rimane in un gateway; l’APK non lo contiene né lo sostituisce con stime. |
| Trenord | Il GTFS Lombardia è fallback programmato; il realtime strutturato richiede E015/adesione. | Non attivare realtime Trenord senza autorizzazione e contratto di accesso. |
| ÖBB e altre reti estere | Gli orari statici consentono pianificazione; la presenza nelle stazioni RFI può offrire informazione parziale del tratto italiano. | Non dedurre posizione o ritardo diretto dal solo GTFS o da una copertura parziale. |
| Storico e posizione stimata | Storico e interpolazioni furono indicati come possibili elaborazioni future. | Raccolta esplicita, retention e trasparenza prima di creare uno storico; ogni interpolazione è una **stima**, non GPS. |

## Decisioni di governance consolidate

La lettura completa conferma quattro regole che prevalgono sulle proposte intermedie della conversazione.

1. **Una sola baseline Git.** Ogni intervento parte dal branch canonico aggiornato, non da APK, ZIP, transcript o commit storici.
2. **Le fonti originarie prevalgono sugli aggregatori.** Treniamo e On The Rails sono evidenze di ricerca, non dipendenze dati né fonti di credenziali.
3. **La qualità si propaga fino alla UI.** Provider, confidenza, freshness, score e warning sono parte del risultato, non dettagli rimovibili dalla presentazione.
4. **Il realtime è granulare.** Nessuna funzione deve estendere artificialmente il realtime di un provider a operatori, tratte o attributi non coperti dal feed.

> La discussione contiene proposte transitorie, inclusa l’ipotesi di interrogazioni dirette o di uso personale gratuito. Tali proposte non annullano licenze, termini, rate limit o protezioni tecniche. Il branch canonico conserva quindi soltanto fonti tracciabili, adapter isolati e percorsi di degradazione sicura.

## Decisione operativa

La baseline `feature/data-foundations` resta coerente con le conclusioni finali della chat. Il suo commit originario `0e0867ad` ha portato al massimo grado attivabile senza token o API private; gli aggiornamenti successivi del branch ne estendono la UI senza ridurre i guardrail. Nuove fonti devono aggiungere adapter, fixture, test, manifest/licenza, timeout, cache, rate limit e comportamento di fallback prima dell’abilitazione.

## Verifica contro il codice corrente

La revisione del `ProviderRegistry` ha confermato che ViaggiaTreno, RFI Monitor e Italo in Viaggio sono effettivamente `PUBLIC_BEST_EFFORT` abilitati, mentre Swiss GTFS-RT e Trenord realtime restano disabilitati. Questa è una correzione importante rispetto a documenti storici che descrivevano tutti i canali best-effort come solo catalogati: l’abilitazione runtime non trasforma i canali in API open e non elimina i requisiti di cache, rate policy, circuit breaker, provenienza e spegnimento immediato.
