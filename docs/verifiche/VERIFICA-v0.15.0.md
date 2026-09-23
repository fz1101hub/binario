# Verifica Binario v0.15.0

**Data:** 14 agosto 2026  
**Versione Android:** `versionCode 21`, `versionName 0.15.0`

| Verifica | Esito |
|---|---|
| Test unitari | Superati, inclusi pianificazione avvisi, viaggio attivo, backup JSON e confine live fail-closed. |
| APK debug | Generato da `:app:assembleDebug`. |
| Lint Android | Superato con `:app:lintDebug`. |
| Avvisi | Locale, ricorrenza, anticipo 5–60 minuti, quiet hours e richiesta notifiche solo al salvataggio. |
| Monitoraggi e viaggio | Persistenza locale per stazione, viaggio attivo e superficie widget; nessun tabellone live simulato. |
| Portabilità | JSON scelto dall’utente tramite file picker; schema versionato e validato prima dell’import. |
| Provider live | Adapter e guardrail testati; nessun endpoint o segreto nel client, rete disabilitata senza gateway autorizzato. |

## Collaudi manuali richiesti

La pianificazione delle notifiche, il picker file e il rendering effettivo del widget devono essere provati su un dispositivo Android fisico. La configurazione di un provider ferroviario reale rimane fuori dalla release, in attesa di un canale documentato e autorizzato.
