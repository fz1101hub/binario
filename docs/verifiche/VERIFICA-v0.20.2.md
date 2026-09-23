# Verifica — Binario v0.20.2

## Difetto riprodotto

Lo screenshot dell’utente mostrava “Elenco stazioni non disponibile” in entrambi i campi. L’ispezione dell’APK v0.20.1 ha riprodotto la causa: l’asset sorgente `curated_it_rail_stations.json.gz` non era presente con quel nome nell’APK; il packaging Android conteneva invece `assets/stations/curated_it_rail_stations.json`. Il lettore richiedeva il percorso gzip, riceveva un errore di apertura e degradava a lista vuota.

## Correzione e prova sul pacchetto

| Controllo | Esito |
|---|---|
| Asset richiesto dal lettore | `stations/curated_it_rail_stations.json` |
| Asset effettivamente nell’APK | `assets/stations/curated_it_rail_stations.json` |
| Dimensione asset APK | 955.016 byte |
| Hash APK e sorgente | Uguali: `58b441886c5fe8b731f69000c010c860b28424158fcab4ef1124434874b5712a` |
| Catalogo | 5.239 stazioni |
| Appiano | `S08328`, alias `APPIANO` presente |
| Roma Ostiense | `S08406` presente |
| Verifica ripetibile | `python3 tools/verify_station_catalog_in_apk.py app/build/outputs/apk/debug/app-debug.apk` superato |

## Validazione tecnica

| Controllo | Esito |
|---|---|
| Test unitari | Superati |
| Compilazione Kotlin | Superata |
| APK debug | Generato e ispezionato direttamente |

## Cosa resta da verificare sul dispositivo

Questa release corregge una causa riprodotta nel pacchetto installabile, non soltanto in un test unitario. Il passaggio finale resta l’installazione dell’APK su un dispositivo: digitando `Appiano` e `Roma Osti` devono comparire le scelte sotto ciascun campo. Se non accade, il log interno `BinarioCatalog` contiene ora la causa tecnica senza esporla nell’interfaccia.
