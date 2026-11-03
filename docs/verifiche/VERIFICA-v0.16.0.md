# Verifica Binario v0.16.0

**Data:** 14 agosto 2026  
**Versione Android:** `versionCode 22` · `versionName 0.16.0`

## Controlli eseguiti

| Controllo | Comando o verifica | Esito |
|---|---|---|
| Test unitari e APK debug | `:app:testDebugUnitTest :app:assembleDebug` | Superato |
| Lint debug | `:app:lintDebug` | Superato |
| Configurazione widget | Tratta, stazione e treno scelti per ID `appWidgetId` distinto | Coperto da build e revisione del provider |
| Ridimensionamento | Politica compatta, standard, espansa coperta da test JVM | Superato |
| Evidenza variazioni | Giallo solo per snapshot non dimostrativi e disponibili; demo etichettata | Superato |

## Collaudo su dispositivo richiesto

Il launcher Android determina le dimensioni finali e l’interfaccia di configurazione viene mostrata dall’host widget. Prima dell’uso personale, verificare su un dispositivo fisico la scelta delle tre destinazioni, il trascinamento da compatto a espanso e la modifica dalla testata.

> Il catalogo incluso è dimostrativo locale. Il widget non presenta ritardi, soppressioni o variazioni come eventi ferroviari reali finché non riceve uno snapshot da provider autorizzato.
