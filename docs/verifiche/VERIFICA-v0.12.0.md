# Verifica Binario v0.12.0

## Controlli eseguiti

| Area | Comando o controllo | Esito |
|---|---|---|
| Build Android | `:app:testDebugUnitTest :app:assembleDebug` | Superato |
| Lint Android | `:app:lintDebug` con heap ridotto | Superato |
| Parser data/ora | Formato ISO, valori non validi | Coperto da test unitari |
| Distanza stazioni | Ordinamento haversine del catalogo locale | Coperto da test unitario |
| Dittatura | Tratta, stazione, treno, data, ora, andata/ritorno | Coperto da test unitari |

## Privacy e sicurezza

La richiesta di posizione è limitata a `ACCESS_COARSE_LOCATION`, parte solo dal comando **Trova stazioni vicine** e non salva coordinate. La dettatura delega l’acquisizione vocale al riconoscitore di sistema tramite intent: l’app non dichiara `RECORD_AUDIO`, non conserva audio e non trasmette testo a servizi Binario.

Il catalogo di coordinate è statico e serve solo a ordinare le stazioni già incluse nell’app per distanza in linea d’aria. Non è una fonte di orari o stato ferroviario. Quando manca un feed autorizzato, Binario continua a comunicare **“dati non disponibili in tempo reale”**.
