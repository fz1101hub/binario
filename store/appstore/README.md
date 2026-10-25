# Asset del listing App Store

Icone generate da `python3 tools/build_store_assets.py`: non modificare a mano i file generati, rigenerali e committa il risultato. `tests/test_build_store_assets.py` fallisce se i file committati divergono dal generatore. Le dimensioni richieste da App Store Connect cambiano: rileggerle nella pagina dell'app al momento dell'invio.

| Asset | Requisito App Store Connect | Stato |
|---|---|---|
| `ios/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` | Icona app iPhone: PNG 1024×1024 a 8 bit, senza canale alfa né angoli arrotondati (iOS applica la maschera); Xcode deriva le altre dimensioni dal catalogo | Generata dal marchio `companion-expo/assets/brand/binario-companion-icon.png` (1024 px, lo stesso segno di `ic_binario`), ricodificata in RGB senza trasparenza |
| `apple-watch/BinarioWatch/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` | Icona app Apple Watch: PNG 1024×1024 senza alfa nel catalogo `platform: watchos`; obbligatoria per l'app Watch incorporata, altrimenti il caricamento fallisce con ITMS-90713 (`CFBundleIconName` assente in `it.binario.ios.watchkitapp`) | Generata dallo stesso marchio: pixel identici all'icona iPhone |
| Screenshot iPhone 6,9" | Da 1 a 10, PNG o JPEG, verticali 1320×2868 o 1290×2796 px; senza cornice del dispositivo e senza testo che prometta dati in tempo reale | Da catturare: pianificatore con stazioni coperte, risultato con riga fonte, tratte salvate, impostazioni privacy, fonti dati |
| Screenshot iPhone 6,5" | Da 1 a 10, verticali 1284×2778 o 1242×2688 px | Facoltativi se App Store Connect riusa i 6,9"; stesse schermate |
| Screenshot Apple Watch | Da 1 a 10, PNG o JPEG, nel formato del modello più grande supportato (per esempio 410×502 px Ultra o 416×496 px Series 10 46 mm), senza cornice dell'orologio | Obbligatori perché il listing include l'app Watch: schermata principale con tratta, dettaglio corsa, complicazione |
| Anteprima video | Facoltativa, da 15 a 30 s per formato | Non prevista |

I testi del listing nelle cinque lingue, le risposte sulla privacy, la classificazione per età, i diritti sui contenuti e la procedura di invio sono in `docs/store/APP_STORE_SUBMISSION.md`.
