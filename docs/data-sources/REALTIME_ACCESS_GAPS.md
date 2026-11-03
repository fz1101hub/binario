# Canali realtime protetti — attivazione sicura

Binario usa direttamente soltanto feed pubblici verificati. I canali seguenti possono aumentare la copertura, ma non devono ricevere token o chiavi nell’APK.

| Fonte | Dati disponibili | Accesso | Configurazione corretta |
|---|---|---|---|
| Open Data Platform Mobility Switzerland GTFS-RT | Ritardi, previsioni, cancellazioni e alert; nessuna posizione veicolo nel feed documentato | Token gratuito/access code | Gateway personale HTTPS; token conservato solo sul server |
| Trenord E015 | Ritardi effettivi/predetti, cancellazioni e stato direttrici; nessuna posizione o binario garantiti | Adesione e autorizzazione E015/Trenord | Gateway HTTPS dopo accettazione delle condizioni dell’ecosistema E015 |
| SNCF GTFS-RT | Alert e trip updates | Open direct | Ricerca comune già collegata ai Trip Updates tramite trip ID, fermata e data GTFS; aggiornamenti scaduti o senza corrispondenza esclusi |
| ÖBB | Orari statici ufficiali; nessun GTFS-RT open verificato | Nessun feed realtime pubblico equivalente | Conservare GTFS statico + RFI sulle stazioni italiane; non costruire un gateway di scraping |
| Italo | Tabellone pubblico | Servizio JSON del sito passeggeri, senza credenziali | Adapter best-effort su `RicercaStazioneService?CodiceStazione=...`; identità della stazione e freschezza verificate, nessun token BIG |

## Opzioni per i token protetti

| Approccio | Vantaggi | Compromessi | Complessità |
|---|---|---|---|
| Lasciare le fonti protette disabilitate | Nessuna credenziale, nessun servizio da gestire | Svizzera realtime e Trenord diretto restano parziali | Bassa |
| Gateway personale HTTPS | Token custoditi fuori dall’APK; cache, rate limit e audit centralizzati | Richiede un piccolo servizio da mantenere e il token/accordo del titolare | Media |

Il gateway deve offrire soltanto endpoint read-only, applicare cache per fonte, limitare le richieste, non registrare localizzazioni utente e restituire payload normalizzati con timestamp e provider. L’app deve poter funzionare anche se il gateway non è configurato: in quel caso continua con GTFS statico e fallback RFI/ViaggiaTreno.

## Regola di attivazione

Un canale protetto può entrare nella UI soltanto dopo che esistono:

1. una credenziale ottenuta dal titolare o dall’utente con le relative condizioni accettate;
2. un gateway che non trasferisca il segreto nell’APK;
3. fixture, test di errore, cache e rate limit;
4. una dichiarazione di provenienza che distingua il dato realtime dal fallback statico.

## Riferimenti

[1]: https://opentransportdata.swiss/en/cookbook/realtime-prediction-cookbook/gtfs-rt/ "Open Data Platform Mobility Switzerland — GTFS-RT"
[2]: https://www.e015.regione.lombardia.it/site/api-detail?id=347 "E015 — dati GTFS e GTFS-Realtime Trenord"
[3]: https://transport.data.gouv.fr/datasets/horaires-sncf "SNCF — GTFS-Realtime"
