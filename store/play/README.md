# Asset del listing Google Play

Cartella generata da `python3 tools/build_store_assets.py`: non modificare a mano i file generati, rigenerali e committa il risultato. `tests/test_build_store_assets.py` fallisce se i file committati divergono dal generatore.

| Asset | Requisito Play | Stato |
|---|---|---|
| `icon-512.png` | Icona app: PNG 512×512 a 32 bit, max 1024 KB, quadrato pieno senza angoli arrotondati (Play applica la maschera) | Generata dal marchio `companion-expo/assets/brand/binario-companion-icon.png` (1024 px, lo stesso segno di `ic_binario`) con box filter 2×2; PNG RGBA a 32 bit con canale alfa interamente opaco, nessuna trasparenza |
| Grafica in evidenza | 1024×500 px, PNG o JPEG, max 15 MB; niente testo essenziale ai bordi, nessuna cornice di dispositivo | Da progettare dal titolare |
| Screenshot telefono | Da 2 a 8, PNG o JPEG, rapporto 16:9 o 9:16, lato minore ≥ 320 px, lato maggiore ≤ 3840 px | Da catturare: pianificatore, Consulta, dettaglio corsa, widget, impostazioni privacy |
| Screenshot tablet 7" e 10" | Facoltativi finché il listing non dichiara il supporto tablet; stessi vincoli di formato | Da decidere |
| Screenshot Wear OS | Da 1 a 8, rapporto 1:1, da 384×384 a 3840×3840 px, senza cornice dell'orologio | Da catturare: tratta attiva, riquadro (Tile), complicazione |
| Video promozionale | Facoltativo, URL YouTube pubblico | Non previsto |

I testi del listing nelle cinque lingue, le risposte del modulo Sicurezza dei dati e la procedura di invio sono in `docs/store/PLAY_SUBMISSION.md` e `docs/store/PLAY_DATA_SAFETY.md`.
