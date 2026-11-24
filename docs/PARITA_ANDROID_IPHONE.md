# Parità Android ↔ iPhone e Wear OS ↔ Apple Watch

Regola del titolare: «la versione iPhone DEVE essere uguale a quella Android». Stesse funzioni, stesso comportamento,
stessi testi; un equivalente nativo di iOS solo dove iOS lo impone. Questo documento confronta ogni schermata, foglio,
impostazione e azione dell'app Android (`app/src/main/java/it/binario/app/ui/`, `AppNavigation.kt`,
`TrainViewModel.kt`, la tabella «Funzionalità» del README e `app/src/main/res/values/strings.xml`) con l'app iPhone
(`ios/BinarioApp`, `ios/BinarioKit`), e Wear OS (`wear/`) con Apple Watch (`apple-watch/`).

Base del confronto: commit `5f153b2` (PR #120) più i lavori di parità del 24 novembre 2026 descritti sotto. Le
tabelle e le regole comuni seguono [ADR-0002](architecture/ADR-0002-BASE-COMUNE.md).

**Stati.** «Uguale»: stessa funzione e stessi testi. «Uguale (nativo iOS)»: stessa funzione con il componente di
sistema dell'iPhone. «Parziale»: c'è, ma manca una parte (detta nella riga). «Manca»: non c'è sull'iPhone.

**Aree.** 1 = viaggi con cambi, preferenze, percorsi accessibili, «Ultimo di oggi», «Più affidabili», percorso live nel
pianificatore. 2 = notifiche di viaggio, sveglia di fermata, GPS, avvisi di arrivo, avvisi ViaggiaTreno. 3 = widget.
4 = audit e funzioni minori (tutto il resto). 5 = European Sleeper e tabelloni programmati RFI.

## Riepilogo

| | Funzioni | Uguali | Parziali | Mancano |
|---|---|---|---|---|
| Area 1 | 14 | 0 | 3 | 11 |
| Area 2 | 7 | 0 | 0 | 7 |
| Area 3 | 5 | 0 | 0 | 5 |
| Area 4 | 93 | 89 | 4 | 0 |
| Area 5 | 2 | 0 | 0 | 2 |
| **Telefono** | **121** | **89** | **7** | **25** |
| Orologio | 16 | 16 | 0 | 0 |

«Uguali» comprende gli «Uguale (nativo iOS)». I conteggi sono quelli dopo i lavori del ramo `ios-misc` (24 novembre 2026, secondo giro); le righe «Manca» e «Parziale» dell'area 4 sono l'elenco di ciò che resta, in fondo al documento.

## 1. Navigazione e struttura

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Tre destinazioni: Pianifica, Partenze, Per te | Barra in basso, barra laterale da 600 dp (`AppNavigation.kt`) | Tre schede: Pianifica, Partenze, Per te (`RootView.swift`) | Uguale | 4 |
| «Impostazioni» dentro «Per te» | Riga «Impostazioni» di Per te, gruppi in sottoschermate | Riga «Impostazioni» di Per te, gruppi in sottopagine | Uguale | 4 |
| «I miei treni» dentro «Per te» | Sezione di Per te | Riga di Per te che apre «I miei treni» | Uguale | 4 |
| Doppio pannello da 840 dp | Elenco e dettaglio affiancati | Solo iPhone (`TARGETED_DEVICE_FAMILY = 1`): nessun tablet | Uguale (nativo iOS) | 4 |
| Scorciatoie dell'icona | Tratta attiva, Prossime partenze, Ricerca treno, Agenda | Le stesse quattro azioni rapide della schermata Home (`Binario-Info.plist`, `RouteBoard.swift`) | Uguale (nativo iOS) | 4 |
| Collegamenti profondi (`binario://`) | `binario://agenda`, `route/active`, `departures/next`, `train/search[?number=]` (`BinarioDeepLinkParser`); Per te e treno preferito solo da notifiche e widget | Gli stessi quattro collegamenti, con la stessa lista chiusa (`AppLinks` in `RouteBoard.swift`) | Uguale | 4 |

## 2. Pianifica

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Ricerca tratta con partenza, arrivo, data e ora | `PlannerScreen.kt` | `PlannerView.swift` | Uguale | 4 |
| Inverti partenza e arrivo | Pulsante | Pulsante | Uguale | 4 |
| Modalità «Treno» (ricerca per numero) | `TrainNumberSearchScreen.kt`, ViaggiaTreno e orario offline | «Treno» nel pianificatore: corse di oggi dalle fonti in tempo reale, ciascuna apre il dettaglio (`PlannerExtras.swift`) | Parziale: le corse dell'orario incluso non sono cercate per numero | 4 |
| Modalità «Stazione» | Apre il tabellone di stazione | Scheda Partenze | Uguale | 4 |
| Andata e ritorno | Opzione con data e ora del ritorno, risultati in due parti | «Andata e ritorno» con data e ora del ritorno, «RITORNO» sotto l'andata, anche in «Salva orario» e «Condividi» | Uguale | 4 |
| Ricerche recenti e preferite nel pianificatore | «Recenti e preferite» | «Recenti e preferite» sotto il modulo: tratte salvate (fissate prima) e ultime otto ricerche | Uguale | 4 |
| Salva tratta | Stella | Stella «Salva tratta» | Uguale | 4 |
| Partenza dalla stazione più vicina | «Usa la posizione per la partenza» | «Partenza dalla stazione più vicina» | Uguale | 4 |
| Dettatura | Riconoscitore di sistema | `RouteAssistSection.swift` | Uguale (nativo iOS) | 4 |
| Collegamenti con gli aeroporti | Tabella condivisa | Tabella condivisa | Uguale | 4 |
| Filtri: cambi, preferenze di viaggio, percorsi accessibili | «Filtri», `TravelPreferencesSettings.kt` | Nessuno | Manca | 1 |
| Viaggi con cambi | `ConnectionScanPlanner.kt` | Solo treni diretti | Manca | 1 |

## 3. Risultati

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Elenco dei treni diretti con orario, numero, categoria, operatore | `RouteResultsScreen.kt` | `PlannerView.swift` | Uguale | 4 |
| Bus dell'orario e linee urbane con il loro simbolo | Sì | Sì | Uguale | 4 |
| Valori live (ritardo, soppressione, binario) | Sì | Sì | Uguale | 4 |
| «Più affidabili» e puntualità della tratta | Filtro e intestazione | «Più affidabili» e «Questa tratta» | Parziale: i viaggi con cambi mancano | 1 |
| «Più puntuale: …» | Sì | Sì | Uguale | 4 |
| Bici a bordo | Sì | Sì | Uguale | 4 |
| Stima CO₂ in ogni riga | «CO₂ stimata: X kg (auto ~Y kg, aereo ~Z kg)» | Stessa riga e stessa lettura per VoiceOver | Uguale | 4 |
| «Ultimo di oggi» e «Non ci sono più treni oggi» nei risultati | Sì | Nessuno | Manca | 1 |
| Prima / Dopo | Pagine di risultati | Nessuno | Manca | 1 |
| «Salva orario» | Salva l'elenco mostrato | Pulsante «Salva orario» sotto i risultati | Uguale | 4 |
| Arrivo e durata di ogni treno nei risultati | Sì | Solo la partenza | Manca | 1 |
| Condividi l'elenco dei risultati | `TimetableShareFormatter.list` | Stesso testo dal foglio di condivisione | Uguale (nativo iOS) | 4 |
| Mappa dei risultati | `ResultsMapPane.kt`, solo nel doppio pannello da 840 dp (tablet) | Solo iPhone (`TARGETED_DEVICE_FAMILY = 1`): nessun doppio pannello, come sui telefoni Android | Uguale (nativo iOS) | 4 |
| Percorso live nel pianificatore | Sì | Nessuno | Manca | 1 |
| Orari dei paesi confinanti («Scarica») | Sì | Sì | Uguale | 4 |

## 4. Dettaglio treno

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Intestazione, operatore, categoria, fermate e orari live | `ServiceDetailScreen.kt` | `TrainDetailView.swift` | Uguale | 4 |
| Schede Info / Percorso / Servizi | Tre schede | Un solo elenco con le stesse sezioni | Uguale (nativo iOS) | 4 |
| Salva in «I miei treni» | Cuore | Stella: scelta tenuta, i testi dell'iPhone dicono «stella» in 17 lingue | Uguale | 4 |
| Condividi il treno | `TrainShareFormatter` | Stesso testo dal foglio di condivisione | Uguale (nativo iOS) | 4 |
| Condividi una fermata | «Treno X, fermata Y: arr. 10:05, bin. 3» | Stesso testo dal menu della fermata | Uguale (nativo iOS) | 4 |
| Stima CO₂ con nota | Sì | Sì | Uguale | 4 |
| Affidabilità della rete | Scheda «Affidabilità ultimi N giorni» | Stessa sezione | Uguale | 4 |
| Puntualità personale | Scheda «Puntualità» dalle osservazioni sul telefono, registrate anche una volta al giorno in background | Stessa sezione; osservazioni registrate quando l'app legge lo stato del treno e una volta al giorno nel compito in background di iOS per i treni preferiti (`EveningAlerts.swift`) | Uguale (nativo iOS) | 4 |
| Indennizzo per ritardo | Sì | Sì | Uguale | 4 |
| Meteo a destinazione | Sì | Sì | Uguale | 4 |
| Avvisi per questo treno | Sì | Sì | Uguale | 4 |
| «Apri sul sito ufficiale» | Trenitalia e Italo | Stesso collegamento e nota | Uguale | 4 |
| Bici a bordo: condizioni dell'operatore | Collegamento quando manca il dato | Stesso collegamento | Uguale | 4 |
| Aggiungi al calendario | Evento con gli orari programmati | Editor di eventi di iOS già compilato (treno, stazioni, ora, operatore, nota), senza permesso del calendario (`RouteBoard.swift`) | Uguale (nativo iOS) | 4 |
| Mappa del percorso | OpenFreeMap, su tocco | Mappa di Apple (MapKit), su tocco: fermate live o dell'orario incluso, ultima confermata e prossima, elenco sotto la mappa (`StationMaps.swift`) | Uguale (nativo iOS) | 4 |
| Cambi di percorso («Fermata aggiunta», «Saltata») | Sì | Fermate aggiunte e soppresse nell'elenco live | Uguale | 4 |
| Segui questo viaggio | Viaggio attivo con notifica | Nessuno | Manca | 2 |
| Sveglia di fermata e sveglia GPS | Sì | Nessuna | Manca | 2 |
| «Avvisami quando sta arrivando» | Sì | Nessuno | Manca | 2 |
| Campanella degli avvisi ViaggiaTreno | Sì | Nessuna | Manca | 2 |

## 5. Viaggi con cambi

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Dettaglio del viaggio, cambi, «Resta a bordo» | `JourneyDetailScreen.kt` | Nessuno | Manca | 1 |
| Coincidenze a rischio | Sì | Nessuno | Manca | 1 |
| Percorsi accessibili in sedia a rotelle | Sì | Nessuno | Manca | 1 |
| Stima CO₂ del viaggio | Sì | Regola comune pronta (`Co2Estimator.estimate` con più tratti) | Parziale: arriva con i viaggi con cambi | 1 |
| Condividi un viaggio | `TimetableShareFormatter.solution` | Nessuno | Manca | 1 |

## 6. Partenze

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Tabellone di stazione (partenze) | `StationBoardScreen.kt` | `DeparturesView.swift` | Uguale | 4 |
| Arrivi | Sì | Sì | Uguale | 4 |
| Dettagli corsa di una riga live | Sì | Sì | Uguale | 4 |
| «Più tardi» | Sì | Sì | Uguale | 4 |
| Stazioni preferite (stella) | Nel tabellone e in Per te | Stella nel tabellone, elenco nel tabellone e in Per te | Uguale | 4 |
| Stazioni vicine | Elenco ordinato per distanza, consenso su tocco | «Stazioni più vicine»: elenco per distanza, una posizione su tocco | Uguale | 4 |
| Stazione più vicina | Sì | Sì | Uguale | 4 |
| «Tipi di treno» | Nasconde Frecce/AV, Intercity/notte, Regionali, Italo, Bus, Internazionali per stazione, con il conteggio e «Mostra tutti» | Stesso foglio, stessa scelta per stazione, stesso conteggio | Uguale | 4 |
| Collegamenti diretti | `DirectConnectionsSheet.kt` | Stesso foglio: sette giorni, filtro, treni e linee urbane, «Cerca i treni» e «Cerca le corse» (`DirectConnections.swift`) | Uguale | 4 |
| Assistenza Sala Blu | Pagina RFI e numeri | Stessa sezione nel tabellone | Uguale | 4 |
| Tabellone della tratta attiva | Prossima partenza evidenziata, cambio direzione, scelta tratta | «Le tue partenze» in cima a Partenze: prossima corsa, altre corse, «Direzione», «Cambia tratta» (`RouteBoard.swift`) | Uguale | 4 |
| «Ultimo di oggi» nei tabelloni | Sì | «Non ci sono più treni oggi: primo domani» | Parziale: manca il segno «Ultimo di oggi» | 1 |
| Monitora questa stazione | Monitoraggio locale | «Monitora questa stazione» nel tabellone (partenze, arrivi o entrambi), elenco in Per te | Uguale | 4 |
| Treni in movimento | Mappa | Mappa (MapKit) | Uguale (nativo iOS) | 4 |
| Mappa della stazione e delle stazioni vicine | OpenFreeMap, su tocco | Mappa di Apple (MapKit), su tocco: le sei stazioni vicine, o le stazioni trovate con la posizione approssimativa (`StationMaps.swift`) | Uguale (nativo iOS) | 4 |
| Bici in condivisione | Sì | Sì | Uguale | 4 |
| Cambio binario «Bin. 7 (era 5)» | Partenze, risultati, dettaglio, tabelloni | Partenze, arrivi, risultati e dettaglio | Uguale | 4 |
| Tabelloni programmati RFI | Sì | Nessuno | Manca | 5 |
| European Sleeper | Sì | Nessuno | Manca | 5 |

## 7. Avvisi

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Area avvisi e infomobilità | Sì | Sì | Uguale | 4 |
| Scioperi in programma (MIT) | Sì | Sì | Uguale | 4 |
| FS News | Collegamento | Collegamento | Uguale | 4 |
| Treni garantiti accanto agli scioperi | Trenitalia, Trenord | Trenitalia, Trenord | Uguale | 4 |
| Avvisi Trenord per stazione e direttrice | Sì | Sì | Uguale | 4 |
| Lavori sulle tue tratte | Sì | Sì | Uguale | 4 |
| Promemoria scioperi e lavori della sera | Sì | Sì | Uguale | 4 |

## 8. Per te

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Le tue tratte | Preferite | Tratte salvate | Uguale | 4 |
| Recenti | Scheda «Recenti» | «Recenti» in Per te: le ultime otto ricerche | Uguale | 4 |
| Nuova tratta (foglio) | «Nuova tratta» | «Nuova tratta» in Per te: partenza, arrivo, nome, «Salva e attiva tratta» | Uguale | 4 |
| Fissa, sposta, elimina una tratta | Menu della tratta | Menu della tratta: fissa, sposta su e giù, elimina, «Mostra su Watch»; le stesse azioni per VoiceOver | Uguale | 4 |
| Agenda | Sì | Sì | Uguale | 4 |
| Pronto per partire | Sì | Sì | Uguale | 4 |
| Orari salvati | Elenco, sola lettura, rinomina, condividi, elimina | Stesso elenco, stesse azioni, stesso formato | Parziale: le righe salvate sull'iPhone non hanno arrivo e durata, che arrivano con l'arrivo e la durata nei risultati (area 1) | 4 |
| Stazioni preferite | Sezione | Sezione | Uguale | 4 |
| I miei treni: apri, rinomina, rimuovi | Sì | Apri, rinomina, rimuovi | Uguale | 4 |
| Affidabilità dei miei treni | Rete e corse registrate sul telefono, con l'andamento | La rete; le corse registrate sono nel dettaglio di ogni treno e nel riepilogo settimanale | Parziale: la sezione non mostra ancora le corse registrate e l'andamento | 4 |
| Viaggio attivo e monitoraggi | Pannello | Nessuno | Manca | 2 |
| Riepilogo settimanale | Notifica della domenica | Stessa notifica senza suono, dal compito serale in background di iOS (`EveningAlerts.swift`) | Uguale (nativo iOS) | 4 |

## 9. Impostazioni

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Tema Sistema / Chiaro / Scuro | Scelta locale | Stessa scelta, applicata a tutta l'app | Uguale | 4 |
| Lingua nell'app | Da Android 13, elenco nell'app e nel sistema | Riga che apre la lingua di Binario nelle Impostazioni di iOS | Uguale (nativo iOS) | 4 |
| Biglietti e assistenza | Pagine ufficiali degli operatori | Stesse pagine, dalla tabella comune | Uguale | 4 |
| Esporta e importa i dati (JSON) | `LocalDataBackup.kt` | Stesso file `binario-local-backup`: tratte, stazioni preferite e monitorate, treni preferiti con nome, destinazione e operatore (anche nel file di Android), orari salvati e puntualità | Parziale: gli avvisi e le preferenze di Android (area 2) non esistono sull'iPhone | 4 |
| Suggerisci una stazione | Modulo pubblico del progetto | Stesso collegamento | Uguale | 4 |
| Informazioni e crediti | Versione, OSM, mappe, orari, CO₂ | Versione, fonti, CO₂ | Uguale | 4 |
| Fonti aggiuntive e dati in tempo reale | Sì | Sì (con interruttore) | Uguale | 4 |
| Orari dei paesi confinanti | Sì | Sì | Uguale | 4 |
| Meteo a destinazione | Interruttore | Interruttore | Uguale | 4 |
| Fermate di metro e tram nella ricerca | Interruttore | Interruttore | Uguale | 4 |
| Privacy | Testi | Testi | Uguale | 4 |
| Preferenze di viaggio e mobilità | Sì | Nessuna | Manca | 1 |
| Integrazioni: orologio | Wear, Apple Watch | Apple Watch | Uguale | 4 |
| Integrazioni: profilo vocale Alexa | Sì | Stesso interruttore e stesse note; nessun collegamento dell'account, come su Android senza collegamento | Uguale | 4 |
| Puntualità dei treni preferiti e riepilogo settimanale | Interruttori (acceso e spento di serie) | Stessi interruttori e testi in Impostazioni | Uguale | 4 |
| Sveglie e avvisi, avvisi pendolare, notifiche in tempo reale | Sì | Promemoria della sera soltanto | Manca | 2 |
| Stato del lavoro in background | Sì | Nessuno | Manca | 2 |
| Impostazioni dei widget | Testi e impostazioni | Nessun widget sull'iPhone | Manca | 3 |

## 10. Widget

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| Tratta | Sì | Nessuno | Manca | 3 |
| Tabellone stazione | Sì | Nessuno | Manca | 3 |
| I miei treni | Sì | Nessuno | Manca | 3 |
| Aggiornamento automatico per fasce | Sì | Nessuno | Manca | 3 |

## 11. Lingue e accessibilità

| Funzione | Android | iPhone | Stato | Area |
|---|---|---|---|---|
| 17 lingue con gli stessi testi | `values*/strings.xml` | `*.lproj/Localizable.strings` copiati da Android | Uguale | 4 |
| Arabo da destra a sinistra | Sì | Sì | Uguale | 4 |
| Lettura dello schermo | TalkBack | VoiceOver, stesse frasi | Uguale (nativo iOS) | 4 |

## 12. Wear OS ↔ Apple Watch

| Funzione | Wear OS | Apple Watch | Stato | Area |
|---|---|---|---|---|
| Tratta attiva con al più due corse | Sì | Sì | Uguale | 4 |
| Stato del dato, fonte e qualità | Sì | Sì | Uguale | 4 |
| Valori live, «Programmato» quando scadono | Sì | Sì | Uguale | 4 |
| Affidabilità della corsa | Sì | Sì | Uguale | 4 |
| Bus dell'orario con il suo simbolo | Sì | Sì | Uguale | 4 |
| Aggiorna dal telefono | Data Layer | Watch Connectivity | Uguale (nativo iOS) | 4 |
| App del telefono assente | Messaggio | Messaggio | Uguale | 4 |
| Dettaglio della corsa | Sì | Sì | Uguale | 4 |
| Tile | Tile | Widget della Smart Stack | Uguale (nativo iOS) | 4 |
| Complication | Testuale | Circolare, rettangolare, in linea | Uguale (nativo iOS) | 4 |
| Conto alla rovescia | Sì | Sì | Uguale | 4 |
| Contratto dello snapshot | `contracts/wear-snapshot/v1` | Stesso contratto | Uguale | 4 |
| Tratta fissata dal telefono | Tratta attiva | «Mostra su Watch» | Uguale | 4 |
| Lingue | 17 | 17 | Uguale | 4 |
| Tema | Material 3 for Wear, senza scelta | Palette Binario, senza scelta | Uguale (nativo iOS) | 4 |
| Corse oltre le due | No | No | Uguale | 4 |

## Cosa resta

Area 4 (audit e funzioni minori), dopo i lavori di questo ramo:

- **Mancano (0):** nessuna.
- **Parziali (4):** Modalità «Treno» (ricerca per numero); Orari salvati; Affidabilità dei miei treni; Esporta e importa i dati (JSON). Il dettaglio è nella colonna «Stato».
- **Scelte tenute:** la stella al posto del cuore di Android per «I miei treni», perché i testi dell'iPhone in 17 lingue dicono «stella»; la mappa dei risultati di Android esiste solo nel doppio pannello dei tablet, che l'app iPhone (solo telefono) non ha.
- **Orologio:** nessuna differenza aperta.

Le righe delle altre aree restano ai lavori in corso: area 1 14, area 2 7, area 3 5, area 5 2 righe non ancora uguali. «Arrivo e durata di ogni treno nei risultati» è passata all'area 1 (viaggi).
