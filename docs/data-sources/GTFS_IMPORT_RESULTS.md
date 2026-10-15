# Esito dell’import GTFS di prova

Il test del 14 agosto 2026 ha processato gli otto feed GTFS con URL diretto. Gli archivi sono stati scaricati in una directory temporanea esterna al repository, aperti come ZIP e verificati per la presenza delle tabelle GTFS minime. Nessun file è stato eseguito.

| Fonte | Esito | Metriche principali | Disposizione |
|---|---|---|---|
| AMT Genova–Casella | **PASS** | 2.754 fermate, 164 route, 67.522 viaggi | Data pack offline/gateway: URL HTTP |
| Cotral/ASTRAL Ferro | **PASS** | 35 fermate, 3 route, 1.066 viaggi | Data pack offline/gateway: URL HTTP |
| HŽPP | **PASS** | 460 fermate, 141 route, 44.888 viaggi | Data pack importabile |
| SNCF | **PASS** | 8.912 fermate, 728 route, 49.065 viaggi | Data pack importabile, da filtrare al tratto italiano |
| Alto Adige/STA | **LIMITED** | Download non completato entro il limite | Import offline o gateway dedicato |
| ARST | **LIMITED** | Download non completato entro il limite | Nuovo tentativo offline, non all’avvio dell’app |
| ÖBB | **LIMITED** | Download parziale prima del timeout | Import offline o gateway dedicato |
| Trentino Trasporti | **LIMITED** | Download non completato entro il limite | Nuovo tentativo offline, non all’avvio dell’app |

I due feed SNCF GTFS-Realtime sono stati inoltre verificati come payload `application/x-protobuf` e risultano pronti a un adapter separato. Il file machine-readable è `gtfs_import_test_2026-08-14.csv`.

L’esito `LIMITED` non indica un feed invalido: significa soltanto che il download completo non è stato possibile entro i limiti di rete e durata adottati per questa sessione. Nessuna fonte limitata viene abilitata come download diretto dell’app.
