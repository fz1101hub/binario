# Verifica Binario v0.16.3

**Data:** 14 agosto 2026  
**Versione Android:** `versionCode 25` · `versionName 0.16.3`

| Correzione | Verifica |
|---|---|
| Testata dark | `Surface` fornisce colore di contenuto `onBackground`; titolo e testo descrittivo non dipendono da valori impliciti. |
| Composizione | Riepilogo, salvataggio e annullamento sono elementi del medesimo `LazyColumn`, senza footer separato né spazio vuoto. |
| Nome widget | Configurazione a quattro campi con decoder retrocompatibile per le superfici salvate a tre campi; test di titolo personalizzato e fallback superati. |
| Formato piccolo | Riusa `route_widget.xml`, mostra due righe e nasconde azioni/testata non essenziali: ogni azione RemoteViews punta a ID del layout stabile. |
| Test e lint | Suite unitaria, APK debug e `:app:lintDebug` superati. |

> Per il collaudo finale, rimuovere dal launcher un eventuale widget corrotto creato con una build precedente, aggiungere una nuova istanza e provare il formato piccolo prima di ingrandirla.
