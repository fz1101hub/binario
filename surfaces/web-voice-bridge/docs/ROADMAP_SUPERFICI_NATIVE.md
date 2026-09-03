# Roadmap delle superfici native companion

## Principio architetturale

La web app **Binario** è la fonte delle preferenze, dei monitoraggi e delle decisioni dell’utente. Le app Android/iOS, widget, smartwatch e Alexa sono companion: consumano un contratto dati ridotto e non diventano fonti concorrenti di stato o di verità ferroviaria.

| Superficie | Capacità da recuperare | Repository/tecnologia corretta | Dipendenze da chiudere |
|---|---|---|---|
| Android | Ricerca offline, pianificazione GTFS, dettaglio treno, deep link/App Actions, notifiche | Repository Android dedicato; React Native/Kotlin secondo baseline storica | Catalogo/versioni GTFS, fonti autorizzate, validazione dispositivo |
| iOS | Parità dei monitoraggi e companion Apple Watch | Repository iOS dedicato; Swift/SwiftUI | Contratto profilo stabile, notifiche, privacy voice/localizzazione |
| Widget | Treno, stazione, tratta e riepilogo monitoraggi | App native, non questa web app | Snapshot ridotti, aggiornamenti di sistema, limiti batteria e privacy |
| Smartwatch | Stato sintetico e avvisi urgenti | Wear OS/watchOS companion | Preferenze esplicite, nessuna geolocalizzazione implicita, fallback offline |
| Alexa | Stato treno/stazione/tratta solo da provider autorizzati | Backend voice esistente, attivato dopo configurazione | Account linking, certificazione, privacy notice, feed live autorizzato |
| Offline GTFS | Data pack lazy e planner senza rete | Client mobile o PWA dedicata | Licenze GTFS, versione feed, spazio, update atomic e indicatori di data age |

## Ordine consigliato

1. Stabilizzare il contratto Binario per stazioni, preferiti, monitoraggi e stati dati.
2. Attivare una fonte autorizzata per un singolo caso d’uso (tabellone o avvisi), con provenance completa.
3. Recuperare pianificazione offline e risultati itinerario nel repository mobile.
4. Aggiungere widget e smartwatch dopo aver stabilizzato monitoraggi, refresh e notifiche.
5. Attivare Alexa solo come lettura di monitoraggi e stato, mai come motore centrale dell’app.

