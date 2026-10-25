# Pubblicazione su App Store — checklist di invio per Binario

**Ambito:** app iPhone `it.binario.ios` (iOS 17 o successivo) con l'app Apple Watch incorporata `it.binario.ios.watchkitapp` (watchOS 10 o successivo) e la sua estensione WidgetKit `it.binario.ios.watchkitapp.widgets`, tutte nello stesso record di App Store Connect. Le azioni sono del titolare dell'account Apple Developer; questa pagina fissa l'ordine dei passaggi, i testi del listing nelle cinque lingue dell'app e i riferimenti ai documenti che il repository mantiene verificati: lo stato tecnico è in [APPLE_PLATFORM_READINESS.md](../../ios/APPLE_PLATFORM_READINESS.md), l'informativa privacy in [PRIVACY.md](../../PRIVACY.md), gli asset grafici in [store/appstore/README.md](../../store/appstore/README.md), il companion in [APPLE_WATCH_IMPLEMENTATION.md](../../apple-watch/APPLE_WATCH_IMPLEMENTATION.md). Le regole di App Store Connect cambiano: ogni soglia riportata qui va riletta nella pagina dell'app al momento dell'invio.

## 0. Prerequisiti che bloccano l'invio

| Prerequisito | Stato nel repository | Azione del titolare |
|---|---|---|
| Iscrizione all'Apple Developer Program | Esterna al repository | Completare l'iscrizione (annuale) e accettare i contratti in App Store Connect, altrimenti il record non può essere creato. |
| Team ID per la firma automatica | `ios/project.yml` imposta `CODE_SIGN_STYLE: Automatic` e `DEVELOPMENT_TEAM: ${BINARIO_APPLE_TEAM_ID}` su tutti i target | Esportare `BINARIO_APPLE_TEAM_ID=<Team ID>` prima di `ios/Scripts/generate-project.sh`; senza variabile il progetto generato resta non firmato (è il caso della CI). |
| App ID e App Group | Identificativi fissati in `ios/project.yml` e `apple-watch/BinarioWatch/BinarioWatch.entitlements` | Registrare `it.binario.ios`, `it.binario.ios.watchkitapp`, `it.binario.ios.watchkitapp.widgets` e l'App Group `group.it.binario.watch`; la firma automatica li crea e abilita la capability App Groups sui due App ID Watch al primo archivio. |
| URL pubblico dell'informativa privacy con titolare e recapito | **Blocco aperto** dichiarato in `PRIVACY.md`, che ora copre iPhone e Apple Watch | Pubblicare il contenuto di `PRIVACY.md` a un URL stabile, con titolare e recapito, e inserirlo come URL informativa sulla privacy nel record. |
| Disponibilità del nome | Da verificare in App Store Connect | Il nome `Binario` può risultare occupato in alcuni storefront: tenere pronto il ripiego `Binario Orari` (i testi sotto non cambiano). |
| URL di supporto ed email di contatto | Esterni al repository | Una pagina pubblica di supporto è obbligatoria; l'email serve per la revisione. |
| Orari inclusi non scaduti | Sette pack su dieci terminano il 2026-12-12/13 (`lastServiceDate` in `app/src/main/assets/gtfs/manifest.json`) | Prima della build di invio aggiornare i pack, eseguire `python3 tools/build_ios_gtfs_schedule.py` e `python3 tools/verify_ios_gtfs_schedule.py` e committare artefatto e metadata. |
| Licenze dei feed confermate | `ios/Resources/binario_gtfs_source_policy_v1.json` registra CC0 (STA), CC BY 4.0 (ÖBB) e CC BY (TFT); le altre sette voci sono `null` | Confermare che ogni licenza consenta la redistribuzione nell'app, registrarla nel file (rigenerando l'artefatto) e rispondere di conseguenza alla dichiarazione sui diritti dei contenuti. |
| macOS con Xcode 16.4 e XcodeGen 2.46.0 | Versioni fissate in `.github/ci-inputs-lock.json`; `tools/install_xcodegen.py` installa il binario verificato | Usare le stesse versioni della CI per archivio e caricamento. |

## 1. Creazione del record in App Store Connect

1. **Le mie app → + → Nuova app**: piattaforma *iOS*, nome `Binario`, lingua principale *Italiano*, ID bundle `it.binario.ios` (registrato al punto 0), SKU `binario-ios`, accesso utente *Accesso completo*. L'app Apple Watch incorporata non ha un record proprio: viene caricata dentro la build iPhone (sezione 6).
2. **Informazioni sull'app**: categoria primaria *Viaggi* (secondaria facoltativa *Navigazione*); diritti sui contenuti: *Sì, l'app contiene, mostra o accede a contenuti di terzi* — gli orari GTFS open data elencati nelle impostazioni dell'app (**Fonti dati GTFS**) con attribuzione, validità, licenza e pagina della fonte — e dichiarare di disporre dei diritti solo dopo aver completato il punto 0 sulle licenze.
3. **Classificazione per età**: rispondere *No* a ogni voce del questionario (nessuna violenza, contenuto sessuale o di nudità, linguaggio scurrile, droghe, gioco d'azzardo, concorsi, horror, contenuti medici o generati dagli utenti; nessun accesso web illimitato: i collegamenti alle fonti aprono il browser di sistema). Esito atteso: **4+**.
4. **Privacy dell'app**: inserire l'URL dell'informativa; nel questionario dichiarare **Dati non raccolti** (*Data Not Collected*). È coerente con i tre `PrivacyInfo.xcprivacy` (nessun tracciamento, nessun dominio di tracciamento, nessun tipo di dato raccolto, sola API `UserDefaults` con motivi `CA92.1`/`1C8F.1`) e con il codice: nessun account, analytics, posizione, microfono né richiesta di rete.
5. **Prezzo e disponibilità**: *Gratuita*; paesi almeno Italia, poi i paesi delle lingue supportate (Austria, Svizzera, Germania, Francia, Spagna, Regno Unito, Stati Uniti). La build esclude Mac con Apple Silicon e Apple Vision (`SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD` e `SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD` a `NO`): controllare che le due opzioni risultino disattivate nella pagina di disponibilità.

## 2. Testi del listing

Limiti di App Store Connect: nome 30 caratteri, sottotitolo 30, testo promozionale 170, descrizione 4.000, parole chiave 100 (separate da virgola, senza spazi dopo la virgola), novità 4.000 per lingua; l'URL di supporto è obbligatorio, l'URL marketing facoltativo. Le lingue sono quelle dell'app (`it`, `en`, `de`, `fr`, `es`); in App Store Connect corrispondono a *Italiano*, *Inglese (Stati Uniti)* (aggiungere *Inglese (Regno Unito)* con lo stesso testo), *Tedesco*, *Francese*, *Spagnolo (Spagna)*. I testi derivano dalle stringhe dell'app, **non promettono dati in tempo reale** e dichiarano quali reti sono incluse e che Trenitalia e Italo non lo sono. `tests/test_app_store_docs.py` verifica lunghezze, presenza di tutte le lingue e l'esistenza nell'indice incluso delle tratte di prova indicate per la revisione.

#### Nome (it)

```text
Binario
```

#### Nome (en)

```text
Binario
```

#### Nome (de)

```text
Binario
```

#### Nome (fr)

```text
Binario
```

#### Nome (es)

```text
Binario
```

#### Sottotitolo (it)

```text
Orari ferroviari offline
```

#### Sottotitolo (en)

```text
Offline rail timetables
```

#### Sottotitolo (de)

```text
Bahnfahrpläne offline
```

#### Sottotitolo (fr)

```text
Horaires de train hors ligne
```

#### Sottotitolo (es)

```text
Horarios de tren sin conexión
```

#### Testo promozionale (it)

```text
Orari ferroviari programmati e offline per nove reti regionali ed estere, con app per Apple Watch. Nessun account, nessuna pubblicità. Trenitalia e Italo non inclusi.
```

#### Testo promozionale (en)

```text
Scheduled, offline rail timetables for nine regional and cross-border networks, with an Apple Watch app. No account, no ads. Trenitalia and Italo not included.
```

#### Testo promozionale (de)

```text
Planmäßige Bahnfahrpläne offline für neun regionale und grenzüberschreitende Netze, mit App für die Apple Watch. Kein Konto, keine Werbung. Ohne Trenitalia und Italo.
```

#### Testo promozionale (fr)

```text
Horaires de train programmés, hors ligne, pour neuf réseaux régionaux et transfrontaliers, avec app Apple Watch. Sans compte ni pub. Trenitalia et Italo non inclus.
```

#### Testo promozionale (es)

```text
Horarios de tren programados y sin conexión para nueve redes regionales y transfronterizas, con app Apple Watch. Sin cuenta ni anuncios. Trenitalia e Italo no incluidos.
```

#### Descrizione (it)

```text
Binario per iPhone è un pianificatore ferroviario che funziona interamente offline: scegli due stazioni e vedi le prossime corse programmate lette dagli orari GTFS ufficiali inclusi nell'app, con la fonte di ogni risultato.

COSA INCLUDE
• Orari programmati di nove reti regionali ed estere: Alto Adige (STA), Lombardia (Trenord), Trentino, Toscana (TFT), Campania (EAV), Sardegna (ARST), Svizzera (Open Data Platform Mobility), Austria (ÖBB) e Francia (SNCF). Trenitalia e Italo non sono inclusi: le stazioni senza orari sono segnalate nella ricerca.
• Catalogo offline di 5.239 stazioni italiane con alias e codici ferroviari; la ricerca ignora accenti e punteggiatura e mostra per prime le stazioni coperte.
• Tratte salvate, fino a 32, sul tuo iPhone e senza account.
• Impostazioni con l'elenco delle fonti, la validità di ogni orario, le licenze e i collegamenti ai portali open data.

APPLE WATCH
• L'app per Apple Watch mostra la tratta cercata e le prossime due corse, con una complicazione. Riceve i dati soltanto dall'iPhone, solo se attivi la condivisione nelle impostazioni, e non si collega a internet.

DATI E TRASPARENZA
• Binario mostra orari programmati. Non sono dati in tempo reale: l'app non inventa ritardi, binari o soppressioni e dichiara quando una tratta o una data non è coperta dagli orari inclusi.
• Ogni risultato indica la fonte e la natura del dato. Gli orari hanno una scadenza, visibile nelle impostazioni: un aggiornamento dell'app porta gli orari nuovi.

PRIVACY
• Nessun account, nessuna pubblicità, nessuna analisi d'uso, nessuna richiesta di rete.
• Tratte e preferenze restano sull'iPhone; i backup di sistema possono includerle.

Disponibile in italiano, inglese, tedesco, francese e spagnolo. Richiede iOS 17 o successivo; l'app Apple Watch richiede watchOS 10 o successivo e l'iPhone abbinato.
```

#### Descrizione (en)

```text
Binario for iPhone is a rail journey planner that works entirely offline: pick two stations and see the next scheduled services read from the official GTFS timetables bundled in the app, with the source of every result.

WHAT IS INCLUDED
• Scheduled timetables of nine regional and cross-border networks: South Tyrol (STA), Lombardy (Trenord), Trentino, Tuscany (TFT), Campania (EAV), Sardinia (ARST), Switzerland (Open Data Platform Mobility), Austria (ÖBB) and France (SNCF). Trenitalia and Italo are not included: stations without timetables are flagged in the search.
• Offline catalogue of 5,239 Italian stations with aliases and railway codes; the search ignores accents and punctuation and lists covered stations first.
• Saved routes, up to 32, on your iPhone and without an account.
• Settings list every source with its validity, licence and a link to the open-data portal.

APPLE WATCH
• The Apple Watch app shows the searched route and the next two services, with a complication. It receives data only from the iPhone, only when you turn sharing on in Settings, and never connects to the internet.

DATA AND TRANSPARENCY
• Binario shows scheduled times. This is not realtime data: the app never invents delays, platforms or cancellations and says so when a route or a date is not covered by the included timetables.
• Every result states its source and the nature of the data. Timetables expire, as shown in Settings: an app update brings the new ones.

PRIVACY
• No account, no advertising, no analytics, no network requests.
• Routes and preferences stay on the iPhone; system backups may include them.

Available in Italian, English, German, French and Spanish. Requires iOS 17 or later; the Apple Watch app requires watchOS 10 or later and the paired iPhone.
```

#### Descrizione (de)

```text
Binario für iPhone ist ein Bahn-Reiseplaner, der vollständig offline arbeitet: Wähle zwei Bahnhöfe und sieh die nächsten planmäßigen Verbindungen aus den offiziellen GTFS-Fahrplänen, die in der App enthalten sind, mit der Quelle jedes Ergebnisses.

WAS ENTHALTEN IST
• Planzeiten von neun regionalen und grenzüberschreitenden Netzen: Südtirol (STA), Lombardei (Trenord), Trentino, Toskana (TFT), Kampanien (EAV), Sardinien (ARST), Schweiz (Open Data Platform Mobility), Österreich (ÖBB) und Frankreich (SNCF). Trenitalia und Italo sind nicht enthalten: Bahnhöfe ohne Fahrplan werden in der Suche gekennzeichnet.
• Offline-Katalog mit 5.239 italienischen Bahnhöfen, Aliasnamen und Bahncodes; die Suche ignoriert Akzente und Satzzeichen und zeigt abgedeckte Bahnhöfe zuerst.
• Gespeicherte Strecken, bis zu 32, auf deinem iPhone und ohne Konto.
• Einstellungen mit allen Quellen, ihrer Gültigkeit, Lizenz und dem Link zum Open-Data-Portal.

APPLE WATCH
• Die App für die Apple Watch zeigt die gesuchte Strecke und die nächsten zwei Verbindungen, mit einer Komplikation. Sie erhält Daten nur vom iPhone, nur wenn du die Freigabe in den Einstellungen aktivierst, und verbindet sich nie mit dem Internet.

DATEN UND TRANSPARENZ
• Binario zeigt Planzeiten. Das sind keine Echtzeitdaten: Die App erfindet keine Verspätungen, Gleise oder Ausfälle und sagt es, wenn eine Strecke oder ein Datum von den enthaltenen Fahrplänen nicht abgedeckt ist.
• Jedes Ergebnis nennt Quelle und Art der Daten. Fahrpläne laufen ab, wie in den Einstellungen angezeigt: Ein App-Update bringt die neuen.

DATENSCHUTZ
• Kein Konto, keine Werbung, keine Nutzungsanalyse, keine Netzwerkanfragen.
• Strecken und Einstellungen bleiben auf dem iPhone; Systembackups können sie enthalten.

Verfügbar auf Italienisch, Englisch, Deutsch, Französisch und Spanisch. Erfordert iOS 17 oder neuer; die Apple-Watch-App erfordert watchOS 10 oder neuer und das gekoppelte iPhone.
```

#### Descrizione (fr)

```text
Binario pour iPhone est un planificateur ferroviaire qui fonctionne entièrement hors ligne : choisissez deux gares et voyez les prochains trains programmés lus dans les horaires GTFS officiels intégrés à l'application, avec la source de chaque résultat.

CE QUI EST INCLUS
• Horaires programmés de neuf réseaux régionaux et transfrontaliers : Haut-Adige (STA), Lombardie (Trenord), Trentin, Toscane (TFT), Campanie (EAV), Sardaigne (ARST), Suisse (Open Data Platform Mobility), Autriche (ÖBB) et France (SNCF). Trenitalia et Italo ne sont pas inclus : les gares sans horaires sont signalées dans la recherche.
• Catalogue hors ligne de 5 239 gares italiennes avec alias et codes ferroviaires ; la recherche ignore accents et ponctuation et affiche d'abord les gares couvertes.
• Trajets enregistrés, jusqu'à 32, sur votre iPhone et sans compte.
• Réglages avec la liste des sources, la validité de chaque horaire, les licences et les liens vers les portails open data.

APPLE WATCH
• L'app Apple Watch affiche le trajet recherché et les deux prochains trains, avec une complication. Elle reçoit les données uniquement de l'iPhone, seulement si vous activez le partage dans les réglages, et ne se connecte jamais à internet.

DONNÉES ET TRANSPARENCE
• Binario affiche des horaires programmés. Ce ne sont pas des données en temps réel : l'application n'invente ni retards, ni voies, ni suppressions et le dit quand un trajet ou une date n'est pas couvert par les horaires inclus.
• Chaque résultat indique sa source et la nature de la donnée. Les horaires expirent, comme indiqué dans les réglages : une mise à jour de l'app apporte les nouveaux.

CONFIDENTIALITÉ
• Pas de compte, pas de publicité, pas d'analyse d'usage, aucune requête réseau.
• Trajets et réglages restent sur l'iPhone ; les sauvegardes système peuvent les inclure.

Disponible en italien, anglais, allemand, français et espagnol. Nécessite iOS 17 ou version ultérieure ; l'app Apple Watch nécessite watchOS 10 ou version ultérieure et l'iPhone jumelé.
```

#### Descrizione (es)

```text
Binario para iPhone es un planificador ferroviario que funciona completamente sin conexión: elige dos estaciones y verás los próximos trenes programados leídos de los horarios GTFS oficiales incluidos en la app, con la fuente de cada resultado.

QUÉ INCLUYE
• Horarios programados de nueve redes regionales y transfronterizas: Alto Adigio (STA), Lombardía (Trenord), Trentino, Toscana (TFT), Campania (EAV), Cerdeña (ARST), Suiza (Open Data Platform Mobility), Austria (ÖBB) y Francia (SNCF). Trenitalia e Italo no están incluidos: las estaciones sin horarios se señalan en la búsqueda.
• Catálogo sin conexión de 5.239 estaciones italianas con alias y códigos ferroviarios; la búsqueda ignora acentos y puntuación y muestra primero las estaciones cubiertas.
• Rutas guardadas, hasta 32, en tu iPhone y sin cuenta.
• Ajustes con la lista de fuentes, la validez de cada horario, las licencias y los enlaces a los portales de datos abiertos.

APPLE WATCH
• La app para Apple Watch muestra la ruta buscada y los dos próximos trenes, con una complicación. Recibe los datos solo del iPhone, únicamente si activas el uso compartido en los ajustes, y nunca se conecta a internet.

DATOS Y TRANSPARENCIA
• Binario muestra horarios programados. No son datos en tiempo real: la app no inventa retrasos, vías ni cancelaciones y lo indica cuando una ruta o una fecha no está cubierta por los horarios incluidos.
• Cada resultado indica su fuente y la naturaleza del dato. Los horarios caducan, como se muestra en los ajustes: una actualización de la app trae los nuevos.

PRIVACIDAD
• Sin cuenta, sin publicidad, sin análisis de uso, sin solicitudes de red.
• Las rutas y los ajustes permanecen en el iPhone; las copias de seguridad del sistema pueden incluirlos.

Disponible en italiano, inglés, alemán, francés y español. Requiere iOS 17 o posterior; la app para Apple Watch requiere watchOS 10 o posterior y el iPhone enlazado.
```

#### Parole chiave (it)

```text
treni,orari,ferrovia,stazioni,pendolari,regionale,tratta,offline,GTFS,Apple Watch
```

#### Parole chiave (en)

```text
trains,timetable,railway,stations,commuter,regional,route,offline,GTFS,Apple Watch
```

#### Parole chiave (de)

```text
Zug,Fahrplan,Bahn,Bahnhof,Pendler,Regionalbahn,Strecke,offline,GTFS,Apple Watch
```

#### Parole chiave (fr)

```text
trains,horaires,ferroviaire,gares,TER,régional,trajet,hors ligne,GTFS,Apple Watch
```

#### Parole chiave (es)

```text
trenes,horarios,ferrocarril,estaciones,cercanías,regional,ruta,sin conexión,GTFS,Apple Watch
```

#### Novità (it)

```text
Prima pubblicazione di Binario 0.1.0 su App Store: pianificatore offline con orari GTFS ufficiali di nove reti regionali ed estere, catalogo di 5.239 stazioni con segnalazione delle stazioni senza orari, tratte salvate e app per Apple Watch. Nessun account, nessuna pubblicità, nessuna richiesta di rete.
```

#### Novità (en)

```text
First App Store release of Binario 0.1.0: offline planner with official GTFS timetables of nine regional and cross-border networks, a catalogue of 5,239 stations that flags those without timetables, saved routes and an Apple Watch app. No account, no ads, no network requests.
```

#### Novità (de)

```text
Erste App-Store-Veröffentlichung von Binario 0.1.0: Offline-Planer mit offiziellen GTFS-Fahrplänen von neun regionalen und grenzüberschreitenden Netzen, Katalog mit 5.239 Bahnhöfen mit Kennzeichnung der Bahnhöfe ohne Fahrplan, gespeicherte Strecken und App für die Apple Watch. Kein Konto, keine Werbung, keine Netzwerkanfragen.
```

#### Novità (fr)

```text
Première publication de Binario 0.1.0 sur l'App Store : planificateur hors ligne avec les horaires GTFS officiels de neuf réseaux régionaux et transfrontaliers, catalogue de 5 239 gares signalant celles sans horaires, trajets enregistrés et app Apple Watch. Pas de compte, pas de publicité, aucune requête réseau.
```

#### Novità (es)

```text
Primera publicación de Binario 0.1.0 en App Store: planificador sin conexión con horarios GTFS oficiales de nueve redes regionales y transfronterizas, catálogo de 5.239 estaciones que señala las que no tienen horarios, rutas guardadas y app para Apple Watch. Sin cuenta, sin publicidad, sin solicitudes de red.
```

## 3. Grafica del listing

`store/appstore/README.md` elenca ogni asset con i vincoli di formato e lo stato. Le icone a 1024 pt dell'iPhone e dell'app Watch sono generate da `tools/build_store_assets.py` dal marchio e vivono nei cataloghi Xcode (`ios/Resources/Assets.xcassets`, `apple-watch/BinarioWatch/Assets.xcassets`); screenshot iPhone (6,9" e, se richiesti, 6,5") e Apple Watch restano da catturare dal titolare su simulatore o dispositivo, senza cornici e senza testo che prometta dati in tempo reale. Gli screenshot Apple Watch sono obbligatori perché il listing include l'app Watch.

## 4. Build, archivio e caricamento

1. **Progetto firmato**: `export BINARIO_APPLE_TEAM_ID=<Team ID>` e `ios/Scripts/generate-project.sh` (XcodeGen 2.46.0). Aprire `ios/Binario.xcodeproj`, confermare in *Signing & Capabilities* che i tre target abbiano il team e che i due target Watch mostrino l'App Group `group.it.binario.watch`.
2. **Versione**: `MARKETING_VERSION` e `CURRENT_PROJECT_VERSION` in `ios/project.yml` sono condivisi dai tre prodotti (App Store Connect rifiuta un numero di build già caricato per la stessa versione): incrementare prima di rigenerare il progetto.
3. **Archivio**: in Xcode *Product → Archive* con lo schema `Binario` e destinazione *Any iOS Device*, oppure `xcodebuild archive -project Binario.xcodeproj -scheme Binario -configuration Release -destination 'generic/platform=iOS' -archivePath build/Binario.xcarchive -allowProvisioningUpdates`. L'archivio contiene `Binario.app`, `Watch/BinarioWatch.app` e `PlugIns/BinarioWatchWidgets.appex`; il job `ios` del workflow *Apple Watch CI* produce lo stesso archivio **non firmato** a ogni run e ne verifica il layout (icone, `ITSAppUsesNonExemptEncryption`, manifesti privacy), ma non lo carica.
4. **Caricamento**: *Organizer → Distribute App → App Store Connect → Upload*, oppure `xcodebuild -exportArchive` con un `ExportOptions.plist` (`method` `app-store-connect`) e Transporter. Con una chiave API di App Store Connect (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_API_KEY_P8` come secret) lo stesso export può essere automatizzato in un job dedicato.
5. **Conformità all'esportazione**: i tre `Info.plist` dichiarano `ITSAppUsesNonExemptEncryption = NO` (nessuna rete, solo un SHA-256 in Swift per l'integrità dell'artefatto), quindi App Store Connect non trattiene la build come *Missing Compliance*; se la domanda compare comunque, rispondere *No*.
6. **TestFlight**: la build elaborata è disponibile subito per i tester interni (fino a 100); i tester esterni richiedono la *Beta App Review*. Provare su iPhone con e senza Apple Watch abbinato: il pannello *Stato condivisione Watch* deve leggere *Disattivata* con l'interruttore spento e *Nessun Apple Watch abbinato con l'app Binario* senza orologio.

## 5. Invio per la revisione

1. Nella versione 0.1.0: selezionare la build, incollare i testi della sezione 2 in ogni lingua, caricare gli screenshot della sezione 3, inserire URL di supporto e, se presente, URL marketing.
2. **Informazioni per la revisione**: nome, email e telefono di contatto; nessun account demo (l'app non ha login); note per la revisione dal blocco sotto. Rilascio: *manuale* alla prima pubblicazione, per controllare la scheda prima che vada online.
3. Inviare per la revisione; la prima revisione può richiedere alcuni giorni. Un rifiuto per la sola app Watch riguarda la stessa build: correggere e ricaricare una nuova build.

#### Note per la revisione

```text
Binario è un pianificatore ferroviario offline: nessun account, nessun login, nessuna richiesta di rete. Gli orari sono dati programmati (GTFS ufficiali di nove reti regionali ed estere) inclusi nell'app; Trenitalia e Italo non sono inclusi e le stazioni senza orari sono contrassegnate "Orari non inclusi" nella ricerca. Tratte di prova coperte: Milano Centrale → Bergamo (Trenord) e Bolzano Bozen → Merano Meran (STA), con data e ora correnti. Apple Watch: abbinare un Watch, attivare "Condividi le tratte con Apple Watch" in Impostazioni › Privacy ed eseguire una ricerca per l'orario corrente; il Watch mostra la tratta, le prossime due corse e la complicazione. Conformità all'esportazione: nessuna crittografia (ITSAppUsesNonExemptEncryption = NO).

Binario is an offline rail journey planner: no account, no login, no network requests. Timetables are scheduled data (official GTFS feeds of nine regional and cross-border networks) bundled in the app; Trenitalia and Italo are not included and stations without timetables are flagged "No timetable included" in the search. Covered test routes: Milano Centrale → Bergamo (Trenord) and Bolzano Bozen → Merano Meran (STA), with the current date and time. Apple Watch: pair a Watch, turn on "Share routes with Apple Watch" in Settings › Privacy and run a search for now; the Watch shows the route, the next two services and the complication. Export compliance: no encryption (ITSAppUsesNonExemptEncryption = NO).
```

## 6. Apple Watch nel listing

1. **Stesso record, stessa build**: l'app Watch `it.binario.ios.watchkitapp` è incorporata nella build iPhone (`ios/project.yml`, target `BinarioWatch` con `WKCompanionAppBundleIdentifier it.binario.ios` e `WKWatchOnly NO`); non si crea un record separato e non si carica nulla a parte.
2. **Non pubblicare l'harness**: `apple-watch/project.yml` (`it.binario.watch`, `WKWatchOnly YES`) è solo il progetto watch-only di CI e test, senza companion iPhone e quindi senza alcuna fonte dati: non creare un record per `it.binario.watch`.
3. **Icona e screenshot**: l'app Watch ha il proprio catalogo `AppIcon` (1024 pt, senza alfa), altrimenti il caricamento fallisce con ITMS-90713; gli screenshot Apple Watch (sezione 3) sono obbligatori.
4. **Capability**: l'App Group `group.it.binario.watch` deve risultare abilitato sui due App ID Watch, perché app e complicazione condividono la cache; la firma automatica lo registra al primo archivio con il team impostato.
5. **Versione**: l'app Watch eredita `MARKETING_VERSION` e `CURRENT_PROJECT_VERSION` dal progetto; App Store Connect richiede che coincidano con quelli dell'iPhone.

## 7. Dopo la pubblicazione

- Gli orari inclusi scadono: pianificare un aggiornamento dati e app almeno mensile, con il primo aggiornamento prima del 2026-12-12. Ogni aggiornamento ripete i punti 0 (pack), 4 e 5 con `CURRENT_PROJECT_VERSION` incrementato e, per una nuova versione, `MARKETING_VERSION`.
- Un cambiamento di permessi, rete o SDK richiede di aggiornare prima `PRIVACY.md` e i manifesti privacy, poi le risposte *Privacy dell'app*; finché il codice non effettua richieste di rete, *Dati non raccolti* resta corretto.
- Le sette licenze ancora `null` in `ios/Resources/binario_gtfs_source_policy_v1.json` vanno confermate e registrate: aggiornano la sezione *Fonti dati GTFS* dell'app e la dichiarazione sui diritti dei contenuti.
