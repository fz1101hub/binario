# Verifica Binario v0.16.2

**Data:** 14 agosto 2026  
**Versione Android:** `versionCode 24` · `versionName 0.16.2`

| Correzione | Verifica |
|---|---|
| Taglio in alto e basso | La radice Compose applica `safeDrawingPadding`, quindi rispetta status bar e navigation bar. |
| Tema configurazione | Sfondo, barra di stato, barra di navigazione e contrasto icone derivano dal tema persistito. |
| Caricamento widget | Il layout compatto dichiara tutte le `TextView` invocate da `RemoteViews`, incluse quelle di righe nascoste. |
| Test e APK | Suite unitaria e APK debug superati. |
| Lint | `:app:lintDebug` superato. |

> Il collaudo sul launcher fisico resta necessario per verificare l’host del widget; la causa di caricamento individuata nel layout RemoteViews è stata rimossa dal codice.
