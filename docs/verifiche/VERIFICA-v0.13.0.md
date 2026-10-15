# Verifica Binario v0.13.0

| Area | Verifica | Esito |
|---|---|---|
| Filtri cambi | Test unitario su diretto, massimo uno e massimo due cambi | Superato |
| Dettatura contestuale | Build Kotlin e suite unitaria | Superato |
| Navigazione Consulta | Compilazione delle sezioni Treni/Stazioni e ritorni dai dettagli | Superato |
| Impostazioni | Compilazione delle sezioni tema, viaggi, integrazioni, monitoraggi e privacy | Superato |
| Lint | `:app:lintDebug` | Superato |

La release conserva la distinzione fra il catalogo dimostrativo e un feed ferroviario operativo. Il filtro cambi non costituisce un’informazione di circolazione reale finché non è configurata una fonte autorizzata; l’app continua quindi a comunicare **“dati non disponibili in tempo reale”** quando necessario.
