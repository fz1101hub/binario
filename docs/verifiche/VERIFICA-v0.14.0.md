# Verifica Binario v0.14.0

| Area | Verifica | Esito |
|---|---|---|
| Widget tabellone | Risorse RemoteViews, cinque righe, paginazione, direzione e refresh | Superato |
| Sveglia fermata | Piano locale, richiesta notifiche, receiver fail-closed e test unitario | Superato |
| Condivisione | Formattazione testo pura, inclusione fermate/stato dati e foglio Android | Superato |
| Consulta | Build delle schede squadrate Treni/Stazioni e semantica accessibile | Superato |
| Lint | `:app:lintDebug` dopo controllo consenso notifiche nel receiver | Superato |

La sveglia viene pianificata localmente cinque minuti prima dell’orario previsto visibile nell’app. Con il catalogo dimostrativo i suoi orari non sono un’informazione live: l’app invita quindi a seguire monitor e annunci ufficiali per le variazioni operative.
