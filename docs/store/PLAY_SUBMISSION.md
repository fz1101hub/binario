# Pubblicazione su Google Play — checklist di invio per Binario

**Ambito:** app Android `it.binario.app` (telefono) con companion Wear OS nello stesso listing. Le azioni sono del titolare dell'account sviluppatore; questa pagina fissa l'ordine dei passaggi nella Play Console, i testi del listing nelle cinque lingue dell'app e i riferimenti ai documenti che il repository mantiene verificati. Gli artifact da caricare sono quelli prodotti da `release-sign` (vedi [RELEASE_BUILD.md](../RELEASE_BUILD.md)); le risposte del modulo Sicurezza dei dati sono in [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md); gli asset grafici sono descritti in [store/play/README.md](../../store/play/README.md). Le norme Play cambiano: ogni soglia riportata qui va riletta nella Console al momento dell'invio.

## 0. Prerequisiti che bloccano l'invio

| Prerequisito | Stato nel repository | Azione del titolare |
|---|---|---|
| Account sviluppatore Google Play con identità verificata | Esterno al repository | Completare la verifica dell'identità e, per un account personale, la verifica del dispositivo. |
| Test chiuso obbligatorio per gli account personali | Non applicabile al codice | Un account **personale** creato dopo il 13 novembre 2023 deve far girare un test chiuso con almeno 12 tester iscritti per 14 giorni consecutivi e poi richiedere l'accesso alla produzione dalla dashboard; pianificarlo prima di qualunque data di uscita. |
| URL pubblico dell'informativa privacy con titolare e recapito | **Blocco aperto** dichiarato in `PRIVACY.md` | Pubblicare il contenuto di `PRIVACY.md` a un URL stabile, con titolare e recapito, e collegarlo nell'app e nella Console. |
| Keystore di upload e iscrizione a Play App Signing | Procedura in `RELEASE_BUILD.md` | Generare il keystore, caricare i quattro secret, eseguire il dispatch con `sign_release=true`. |
| `versionCode` mai riusati | Garantito da `tools/verify_android_release_config.py` | Incrementare `BINARIO_APP_VERSION_CODE` a ogni upload; Wear deriva automaticamente `1000000 + codice telefono`. |
| Risposte Sicurezza dei dati coerenti con il codice | `PLAY_DATA_SAFETY.md`, verificato da `tools/verify_privacy_policy.py` | Trascrivere le risposte nella Console e confermarle. |

## 1. Creazione dell'app nella Console

1. **Crea app**: nome `Binario`, lingua predefinita *Italiano (Italia) — it-IT*, tipo *App*, *Gratuita*. Accettare le dichiarazioni sulle norme per sviluppatori e sulle esportazioni USA.
2. **Dashboard → Configura la tua app**, nell'ordine proposto dalla Console:
   - *Norme relative all'informativa sulla privacy*: inserire l'URL pubblico del punto 0.
   - *Accesso all'app*: tutte le funzionalità sono disponibili senza accesso speciale (nessun login).
   - *Annunci*: l'app non contiene annunci.
   - *Classificazione dei contenuti*: questionario IARC, categoria *Utilità, produttività, comunicazione o altro*; nessuna violenza, contenuto sessuale, linguaggio scurrile, sostanze, gioco d'azzardo, acquisti o interazione fra utenti (la condivisione usa solo il foglio di condivisione del sistema; nessuna posizione condivisa con altri utenti). Esito atteso: *PEGI 3 / Everyone*.
   - *Pubblico di destinazione e contenuti*: fasce *13-15*, *16-17* e *18 e oltre*; l'app non è pensata per bambini e non deve attrarli involontariamente. Selezionare una fascia sotto i 13 anni attiverebbe le norme Famiglie, non richieste.
   - *App di notizie*: no. *App di monitoraggio e tracciamento dei contatti COVID-19*: no.
   - *Sicurezza dei dati*: copiare le risposte di `PLAY_DATA_SAFETY.md`, sezione «Risposte proposte al modulo».
   - *App governative*: no. *Funzionalità finanziarie*: nessuna. *Salute*: nessuna funzionalità sanitaria.
   - *Categoria dell'app e dettagli di contatto*: categoria *Mappe e navigazione* (alternativa: *Viaggi e info locali*), tag *Treni* e *Trasporto pubblico*; email di contatto pubblica; sito web facoltativo.
   - *Scheda dello Store*: testi della sezione 2 e grafica della sezione 3.
3. **Impostazioni avanzate → Fattori di forma**: aggiungere *Wear OS* prima della prima release, così la Console espone i canali di rilascio Wear (sezione 5).

## 2. Testi del listing

Limiti Play: titolo 30 caratteri, descrizione breve 80, descrizione completa 4.000, note di rilascio 500 per lingua. Le lingue sono quelle dell'app (`it`, `en`, `de`, `fr`, `es`); nella Console corrispondono a *it-IT*, *en-US* (aggiungere *en-GB* con lo stesso testo), *de-DE*, *fr-FR*, *es-ES*. I testi derivano dal README e dalle stringhe dell'app e **non promettono dati in tempo reale**: la build pubblicata mostra orari programmati e dichiara quando un dato live manca. `tests/test_play_store_docs.py` verifica lunghezze e presenza di tutte le lingue.

#### Titolo (it)

```text
Binario
```

#### Titolo (en)

```text
Binario
```

#### Titolo (de)

```text
Binario
```

#### Titolo (fr)

```text
Binario
```

#### Titolo (es)

```text
Binario
```

#### Descrizione breve (it)

```text
Orari ferroviari programmati per le tue tratte in Italia, con widget e Wear OS.
```

#### Descrizione breve (en)

```text
Scheduled rail timetables for your routes in Italy, with widget and Wear OS.
```

#### Descrizione breve (de)

```text
Fahrplanzeiten für deine Bahnstrecken in Italien, mit Widget und Wear OS.
```

#### Descrizione breve (fr)

```text
Horaires ferroviaires programmés pour vos trajets en Italie, widget et Wear OS.
```

#### Descrizione breve (es)

```text
Horarios ferroviarios programados para tus rutas en Italia, widget y Wear OS.
```

#### Descrizione completa (it)

```text
Binario è un'app originale per seguire le proprie tratte ferroviarie in Italia: un pianificatore di viaggio con orari programmati ufficiali, un widget per la schermata Home e un companion per Wear OS.

PIANIFICA
• Modalità Viaggio, N. treno e Stazione, con partenza, arrivo, inversione andata/ritorno, data e ora, filtri e ricerche recenti.
• Catalogo offline di 5.239 stazioni italiane con alias e codici ferroviari; la ricerca ignora accenti e punteggiatura.
• Stazioni vicine su richiesta esplicita: la posizione approssimata viene usata solo in memoria.
• Dettatura della ricerca tramite il riconoscitore vocale di sistema.

CONSULTA
• Quadri orario programmati RFI per le stazioni hub coperte e orari GTFS ufficiali inclusi nell'app per dieci reti regionali e transfrontaliere.
• Dettaglio corsa con operatore, categoria, orari programmati e fermate.
• Tratte salvate, preferiti e monitoraggio locale di un numero treno.
• Sveglia fermata e avvisi pendolare come notifiche locali.

WIDGET E WEAR OS
• Widget ad alto contrasto, ridimensionabile e configurabile per istanza su tratta, stazione o numero treno.
• Companion Wear OS con la tratta attiva, le prossime due corse, un riquadro (Tile) e una complicazione; l'aggiornamento resta delegato al telefono.

DATI E TRASPARENZA
• Binario mostra orari programmati. Quando un dato in tempo reale non è disponibile o non è identificabile con certezza, l'app lo dichiara e non inventa ritardi, binari o soppressioni.
• Ogni risultato indica la fonte, la validità e il livello di confidenza. Se una tratta non è coperta dalle fonti incluse, l'app lo dice invece di mostrare orari fittizi.

PRIVACY
• Nessun account, nessuna pubblicità, nessun SDK di analisi.
• Tratte, preferiti e impostazioni restano sul dispositivo; il backup è un file JSON esportato solo su tua scelta.
• Le ricerche inviano alla fonte ferroviaria soltanto l'identificativo pubblico della stazione richiesta.

Disponibile in italiano, inglese, tedesco, francese e spagnolo. Richiede Android 8.0 o successivo; il companion richiede Wear OS 3 (Android 11) o successivo e l'app installata sul telefono.
```

#### Descrizione completa (en)

```text
Binario is an original app for following your own rail routes in Italy: a journey planner based on official scheduled timetables, a Home screen widget and a Wear OS companion.

PLAN
• Journey, Train no. and Station modes, with departure, arrival, outbound/return swap, date and time, filters and recent searches.
• Offline catalogue of 5,239 Italian stations with aliases and railway codes; the search ignores accents and punctuation.
• Nearby stations on explicit request: the approximate location is used in memory only.
• Dictate a search through the system speech recogniser.

BROWSE
• RFI scheduled departure boards for the covered hub stations and official GTFS timetables bundled in the app for ten regional and cross-border networks.
• Service details with operator, category, scheduled times and all stops.
• Saved routes, favourites and local monitoring of a train number.
• Stop alarm and commuter alerts as local notifications.

WIDGET AND WEAR OS
• High-contrast, resizable widget, configured per instance for a route, a station or a train number.
• Wear OS companion with the active route, the next two services, a Tile and a complication; refreshes stay delegated to the phone.

DATA AND TRANSPARENCY
• Binario shows scheduled times. When real-time data is unavailable or cannot be identified with certainty, the app says so and never invents delays, platforms or cancellations.
• Every result states its source, validity and confidence level. If a route is not covered by the bundled sources, the app says so instead of showing made-up times.

PRIVACY
• No account, no advertising, no analytics SDK.
• Routes, favourites and settings stay on the device; the backup is a JSON file exported only when you choose to.
• Searches send the railway source nothing but the public identifier of the requested station.

Available in Italian, English, German, French and Spanish. Requires Android 8.0 or later; the companion requires Wear OS 3 (Android 11) or later and the app installed on the phone.
```

#### Descrizione completa (de)

```text
Binario ist eine eigenständige App, mit der du deine Bahnstrecken in Italien verfolgst: ein Reiseplaner auf Basis offizieller Fahrplandaten, ein Widget für den Startbildschirm und ein Begleiter für Wear OS.

PLANEN
• Modi Reise, Zugnr. und Bahnhof mit Abfahrt, Ankunft, Tausch von Hin- und Rückrichtung, Datum und Uhrzeit, Filtern und letzten Suchen.
• Offline-Katalog mit 5.239 italienischen Bahnhöfen, Aliasnamen und Bahncodes; die Suche ignoriert Akzente und Satzzeichen.
• Bahnhöfe in der Nähe auf ausdrückliche Anfrage: Der ungefähre Standort wird nur im Arbeitsspeicher verwendet.
• Suche per Diktat über die Spracherkennung des Systems.

ANSEHEN
• Planmäßige RFI-Abfahrtstafeln für die abgedeckten Knotenbahnhöfe und offizielle GTFS-Fahrpläne in der App für zehn regionale und grenzüberschreitende Netze.
• Verbindungsdetails mit Betreiber, Kategorie, Planzeiten und allen Halten.
• Gespeicherte Strecken, Favoriten und lokale Überwachung einer Zugnummer.
• Haltwecker und Pendlerwarnungen als lokale Benachrichtigungen.

WIDGET UND WEAR OS
• Kontrastreiches, größenveränderbares Widget, je Instanz für eine Strecke, einen Bahnhof oder eine Zugnummer konfigurierbar.
• Wear-OS-Begleiter mit aktiver Strecke, den nächsten zwei Verbindungen, einer Kachel (Tile) und einer Komplikation; die Aktualisierung bleibt beim Telefon.

DATEN UND TRANSPARENZ
• Binario zeigt Planzeiten. Wenn Echtzeitdaten fehlen oder nicht eindeutig zuzuordnen sind, sagt die App das und erfindet keine Verspätungen, Gleise oder Ausfälle.
• Jedes Ergebnis nennt Quelle, Gültigkeit und Vertrauensstufe. Deckt keine enthaltene Quelle eine Strecke ab, sagt die App das, statt erfundene Zeiten zu zeigen.

DATENSCHUTZ
• Kein Konto, keine Werbung, kein Analyse-SDK.
• Strecken, Favoriten und Einstellungen bleiben auf dem Gerät; das Backup ist eine JSON-Datei, die nur auf deinen Wunsch exportiert wird.
• Suchen senden der Bahnquelle nur die öffentliche Kennung des angefragten Bahnhofs.

Verfügbar auf Italienisch, Englisch, Deutsch, Französisch und Spanisch. Erfordert Android 8.0 oder neuer; der Begleiter erfordert Wear OS 3 (Android 11) oder neuer und die auf dem Telefon installierte App.
```

#### Descrizione completa (fr)

```text
Binario est une application originale pour suivre vos trajets ferroviaires en Italie : un planificateur fondé sur les horaires programmés officiels, un widget pour l'écran d'accueil et un compagnon pour Wear OS.

PLANIFIER
• Modes Trajet, N° de train et Gare, avec départ, arrivée, inversion aller/retour, date et heure, filtres et recherches récentes.
• Catalogue hors ligne de 5 239 gares italiennes avec alias et codes ferroviaires ; la recherche ignore accents et ponctuation.
• Gares à proximité sur demande explicite : la position approximative n'est utilisée qu'en mémoire.
• Dictée de la recherche via la reconnaissance vocale du système.

CONSULTER
• Tableaux des départs programmés RFI pour les gares principales couvertes et horaires GTFS officiels intégrés à l'application pour dix réseaux régionaux et transfrontaliers.
• Détail du train avec opérateur, catégorie, horaires programmés et tous les arrêts.
• Trajets enregistrés, favoris et suivi local d'un numéro de train.
• Réveil à l'arrêt et alertes pendulaires sous forme de notifications locales.

WIDGET ET WEAR OS
• Widget à fort contraste, redimensionnable et configurable par instance pour un trajet, une gare ou un numéro de train.
• Compagnon Wear OS avec le trajet actif, les deux prochains trains, une tuile (Tile) et une complication ; l'actualisation reste déléguée au téléphone.

DONNÉES ET TRANSPARENCE
• Binario affiche des horaires programmés. Quand une donnée en temps réel est indisponible ou ne peut pas être identifiée avec certitude, l'application le dit et n'invente ni retards, ni voies, ni suppressions.
• Chaque résultat indique sa source, sa validité et son niveau de confiance. Si un trajet n'est pas couvert par les sources intégrées, l'application le dit au lieu d'afficher des horaires fictifs.

CONFIDENTIALITÉ
• Pas de compte, pas de publicité, pas de SDK d'analyse.
• Trajets, favoris et réglages restent sur l'appareil ; la sauvegarde est un fichier JSON exporté uniquement à votre demande.
• Les recherches n'envoient à la source ferroviaire que l'identifiant public de la gare demandée.

Disponible en italien, anglais, allemand, français et espagnol. Nécessite Android 8.0 ou version ultérieure ; le compagnon nécessite Wear OS 3 (Android 11) ou version ultérieure et l'application installée sur le téléphone.
```

#### Descrizione completa (es)

```text
Binario es una app original para seguir tus rutas ferroviarias en Italia: un planificador basado en horarios programados oficiales, un widget para la pantalla de inicio y un complemento para Wear OS.

PLANIFICAR
• Modos Viaje, N.º de tren y Estación, con salida, llegada, inversión ida/vuelta, fecha y hora, filtros y búsquedas recientes.
• Catálogo sin conexión de 5.239 estaciones italianas con alias y códigos ferroviarios; la búsqueda ignora acentos y puntuación.
• Estaciones cercanas solo si lo pides: la ubicación aproximada se usa únicamente en memoria.
• Dictado de la búsqueda mediante el reconocimiento de voz del sistema.

CONSULTAR
• Cuadros de salidas programadas de RFI para las estaciones principales cubiertas y horarios GTFS oficiales incluidos en la app para diez redes regionales y transfronterizas.
• Detalle del tren con operador, categoría, horarios programados y todas las paradas.
• Rutas guardadas, favoritos y seguimiento local de un número de tren.
• Alarma de parada y avisos para viajeros habituales como notificaciones locales.

WIDGET Y WEAR OS
• Widget de alto contraste, redimensionable y configurable por instancia para una ruta, una estación o un número de tren.
• Complemento para Wear OS con la ruta activa, los dos próximos trenes, un mosaico (Tile) y una complicación; la actualización sigue delegada al teléfono.

DATOS Y TRANSPARENCIA
• Binario muestra horarios programados. Cuando un dato en tiempo real no está disponible o no puede identificarse con certeza, la app lo indica y no inventa retrasos, vías ni cancelaciones.
• Cada resultado indica su fuente, su validez y su nivel de confianza. Si una ruta no está cubierta por las fuentes incluidas, la app lo dice en lugar de mostrar horarios ficticios.

PRIVACIDAD
• Sin cuenta, sin publicidad, sin SDK de análisis.
• Rutas, favoritos y ajustes permanecen en el dispositivo; la copia de seguridad es un archivo JSON que se exporta solo si tú lo decides.
• Las búsquedas envían a la fuente ferroviaria únicamente el identificador público de la estación solicitada.

Disponible en italiano, inglés, alemán, francés y español. Requiere Android 8.0 o posterior; el complemento requiere Wear OS 3 (Android 11) o posterior y la app instalada en el teléfono.
```

#### Note di rilascio (it)

```text
Prima pubblicazione di Binario 0.20.4 su Google Play: pianificatore con quadri orario programmati RFI e orari GTFS ufficiali, catalogo offline di 5.239 stazioni, widget configurabile e companion Wear OS. Nessun account, nessuna pubblicità: i dati restano sul dispositivo.
```

#### Note di rilascio (en)

```text
First Google Play release of Binario 0.20.4: planner with RFI scheduled boards and official GTFS timetables, offline catalogue of 5,239 stations, configurable widget and Wear OS companion. No account, no ads: data stays on the device.
```

#### Note di rilascio (de)

```text
Erste Google-Play-Veröffentlichung von Binario 0.20.4: Planer mit planmäßigen RFI-Abfahrtstafeln und offiziellen GTFS-Fahrplänen, Offline-Katalog mit 5.239 Bahnhöfen, konfigurierbares Widget und Wear-OS-Begleiter. Kein Konto, keine Werbung: Die Daten bleiben auf dem Gerät.
```

#### Note di rilascio (fr)

```text
Première publication de Binario 0.20.4 sur Google Play : planificateur avec tableaux programmés RFI et horaires GTFS officiels, catalogue hors ligne de 5 239 gares, widget configurable et compagnon Wear OS. Pas de compte, pas de publicité : les données restent sur l'appareil.
```

#### Note di rilascio (es)

```text
Primera publicación de Binario 0.20.4 en Google Play: planificador con cuadros programados de RFI y horarios GTFS oficiales, catálogo sin conexión de 5.239 estaciones, widget configurable y complemento para Wear OS. Sin cuenta, sin publicidad: los datos permanecen en el dispositivo.
```

## 3. Grafica del listing

`store/play/README.md` elenca ogni asset con i vincoli di formato e lo stato. L'icona `store/play/icon-512.png` è generata da `tools/build_store_assets.py` dal marchio a 1024 px e non va ritoccata a mano; grafica in evidenza e screenshot (telefono e Wear OS) restano da produrre dal titolare, senza cornici di dispositivo e senza testo che prometta dati in tempo reale.

## 4. Release e canali

1. **Test interno**: caricare `app-release.aab` dall'artifact `binario-play-bundles-signed` del dispatch con `sign_release=true`; la Console rifiuta bundle non firmati con l'upload key registrata e `versionCode` già usati. Al primo upload completare l'iscrizione a Play App Signing (vedi `RELEASE_BUILD.md`).
2. **Test chiuso**: per gli account personali recenti è il passaggio obbligatorio di 14 giorni con almeno 12 tester; raccogliere i riscontri e, se serve una nuova build, incrementare `BINARIO_APP_VERSION_CODE` e rieseguire il dispatch.
3. **Richiesta di accesso alla produzione** dalla dashboard, quando la Console la propone.
4. **Produzione**: creare la release con le note di rilascio della sezione 2, scegliere i paesi (almeno Italia, poi gli altri paesi delle lingue supportate) e inviare per la revisione. La prima revisione può richiedere diversi giorni.
5. **Report pre-lancio** e avvisi delle norme: controllare la scheda *Panoramica delle norme* dopo ogni invio; un rifiuto del solo fattore di forma Wear non blocca il telefono.

## 5. Pubblicazione Wear OS su Google Play

Il companion Wear OS è lo stesso listing `it.binario.app`, non una seconda app: la Console lo gestisce come **fattore di forma** aggiuntivo con canali di rilascio propri.

1. **Opt-in del fattore di forma**: *Impostazioni avanzate → Fattori di forma → Aggiungi fattore di forma → Wear OS*. Senza questo passaggio la Console non mostra i canali Wear e rifiuta `wear-release.aab`.
2. **Due bundle, due canali, una chiave**: caricare `wear-release.aab` nel canale *Wear OS* (test interno, chiuso o produzione «solo Wear OS») e `app-release.aab` nel canale telefono della stessa app. Entrambi provengono dall'artifact `binario-play-bundles-signed` e sono firmati con la stessa upload key; Play li ri-firma con la stessa chiave di firma dell'app, condizione necessaria perché il Data Layer riconosca telefono e orologio come la stessa identità.
3. **`versionCode` distinti e mai riusati**: il telefono carica `BINARIO_APP_VERSION_CODE`, Wear carica `1000000 + codice telefono` (`wear/build.gradle.kts`). I due canali condividono lo spazio dei codici dell'app: un codice già caricato in uno dei due canali non può essere ricaricato in nessuno dei due. Incrementare la sola proprietà del telefono aggiorna entrambi.
4. **Dipendenza dal telefono**: il manifest Wear dichiara `com.google.android.wearable.standalone=false` e `android.hardware.type.watch` obbligatorio, non richiede `INTERNET`. Il listing deve dirlo esplicitamente (le descrizioni della sezione 2 lo fanno: il companion richiede l'app installata sul telefono) e i tester devono installare prima l'app telefono dallo stesso canale, altrimenti l'orologio non riceve alcuno snapshot.
5. **Screenshot Wear OS**: da 1 a 8 immagini in rapporto 1:1, da 384×384 a 3840×3840 px, senza cornice dell'orologio né testo sovrapposto; catturare tratta attiva, riquadro (Tile) e complicazione su un emulatore rotondo. Senza screenshot Wear il fattore di forma non viene pubblicato.
6. **Criteri di revisione Wear**: Google valuta il bundle Wear con le linee guida di qualità per Wear OS: interfaccia progettata per schermo rotondo e piccolo (nessuna UI telefono riadattata), avvio dal launcher dell'orologio, riquadro e complicazione dichiarati nel manifest, nessun permesso non necessario, livello API target entro la scadenza annuale per Wear OS. Un rifiuto del fattore di forma Wear non blocca la release telefono: correggere e ricaricare solo `wear-release.aab` con un nuovo `BINARIO_APP_VERSION_CODE`.
7. **Note di rilascio Wear**: la Console accetta note separate per il canale Wear; in assenza, riusare quelle della sezione 2 (limite 500 caratteri).

## 6. Dopo la pubblicazione

- Ogni aggiornamento ripete solo i punti 4.1–4.4 e 5.2 con `BINARIO_APP_VERSION_CODE` incrementato; Wear riceve automaticamente il codice derivato.
- Un cambiamento di permessi, host contattati o SDK richiede di aggiornare prima `PRIVACY.md` e `PLAY_DATA_SAFETY.md` (il gate `tools/verify_privacy_policy.py` lo impone per gli host) e poi il modulo Sicurezza dei dati nella Console.
- Rispettare le scadenze annuali di Google sul livello API target: la toolchain corrente è su API 36.
