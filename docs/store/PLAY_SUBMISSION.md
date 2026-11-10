# Pubblicazione su Google Play — checklist di invio per Binario

**Ambito:** app Android `it.binario.app` (telefono) con companion Wear OS nello stesso listing. Le azioni sono del titolare dell'account sviluppatore; questa pagina fissa l'ordine dei passaggi nella Play Console, i testi del listing nelle cinque lingue principali dell'app (che ne ha 17) e i riferimenti ai documenti che il repository mantiene verificati. Gli artifact da caricare sono quelli prodotti da `release-sign` (vedi [RELEASE_BUILD.md](../RELEASE_BUILD.md)); le risposte del modulo Sicurezza dei dati sono in [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md); gli asset grafici sono descritti in [store/play/README.md](../../store/play/README.md). Le norme Play cambiano: ogni soglia riportata qui va riletta nella Console al momento dell'invio.

## 0. Prerequisiti che bloccano l'invio

| Prerequisito | Stato nel repository | Azione del titolare |
|---|---|---|
| Account sviluppatore Google Play con identità verificata | Esterno al repository | Completare la verifica dell'identità e, per un account personale, la verifica del dispositivo. |
| Test chiuso obbligatorio per gli account personali | Non applicabile al codice | Un account **personale** creato dopo il 13 novembre 2023 deve far girare un test chiuso con almeno 12 tester iscritti per 14 giorni consecutivi e poi richiedere l'accesso alla produzione dalla dashboard; pianificarlo prima di qualunque data di uscita. |
| URL pubblico dell'informativa privacy con titolare e recapito | **Blocco aperto** dichiarato in `PRIVACY.md` | Pubblicare il contenuto di `PRIVACY.md` a un URL stabile, con titolare e recapito, e collegarlo nell'app e nella Console. |
| Keystore di upload e iscrizione a Play App Signing | Procedura in `RELEASE_BUILD.md` | Generare il keystore, caricare i quattro secret nell'environment protetto `release` (non nel repository), eseguire il dispatch con `sign_release=true` su `main` o `stabilization/*` e approvarlo. |
| `versionCode` mai riusati | Garantito da `tools/verify_android_release_config.py` | Incrementare `BINARIO_APP_VERSION_CODE` a ogni upload; Wear deriva automaticamente `1000000 + codice telefono`. |
| Licenze dei pack orari confermate | Ogni voce di `app/src/main/assets/gtfs/manifest.json` e `gtfs-transit/manifest.json` registra `attribution`, `licence` e `licenceEvidence`; `tools/verify_embedded_assets.py` fallisce se mancano o se una licenza non dichiarata esce da `gtfs-personal/`, e avvisa per i pack ancora «Da confermare dal titolare» (oggi `ch-otd-gtfs`, `hzpp-gtfs`, `lombardia-trenord-gtfs`, `sncf-gtfs`, `trentino-gtfs`). Impostazioni › Info mostra per ogni pack della build licenziante, licenza e pagina della fonte, generati da questi campi (`GtfsPackCredits.kt`) | **OWNER ACTION**: per ogni pack confermare che la licenza consenta la redistribuzione in un'app pubblicata su Google Play e i suoi obblighi di attribuzione (CC BY 4.0 §3(a), IODL), registrarla nel manifest con l'evidenza, poi eseguire `python3 tools/build_android_gtfs_credits.py` e ricostruire l'indice iOS (`tools/build_ios_gtfs_schedule.py`). Un pack la cui licenza non si può confermare va escluso dal bundle prima dell'invio. |
| Risposte Sicurezza dei dati coerenti con il codice | `PLAY_DATA_SAFETY.md`, verificato da `tools/verify_privacy_policy.py` | Trascrivere le risposte nella Console e confermarle. |
| Dichiarazione del servizio in primo piano di tipo `location` (sveglia GPS e «Stima dalla tua posizione») | Manifest: `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, servizio non esportato di tipo `location`; nessuna posizione in background | **OWNER ACTION**: compilare *Autorizzazioni dei servizi in primo piano* con la descrizione e un video dimostrativo come indicato in `PLAY_DATA_SAFETY.md`; senza la dichiarazione la Console non accetta il bundle. |

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
   - *Autorizzazioni dei servizi in primo piano*: dichiarare il tipo *Posizione* per la sveglia GPS e la «Stima dalla tua posizione» con descrizione e link al video, come in `PLAY_DATA_SAFETY.md` («OWNER ACTION — dichiarazione del servizio in primo piano»). La spiegazione del permesso è già mostrata nell'app prima della richiesta di sistema.
   - *Autorizzazioni per la posizione*: solo accesso in primo piano; nessuna dichiarazione di posizione in background.
   - *App governative*: no. *Funzionalità finanziarie*: nessuna. *Salute*: nessuna funzionalità sanitaria.
   - *Categoria dell'app e dettagli di contatto*: categoria *Mappe e navigazione* (alternativa: *Viaggi e info locali*), tag *Treni* e *Trasporto pubblico*; email di contatto pubblica; sito web facoltativo.
   - *Scheda dello Store*: testi della sezione 2 e grafica della sezione 3.
3. **Impostazioni avanzate → Fattori di forma**: aggiungere *Wear OS* prima della prima release, così la Console espone i canali di rilascio Wear (sezione 5).

## 2. Testi del listing

Limiti Play: titolo 30 caratteri, descrizione breve 80, descrizione completa 4.000, note di rilascio 500 per lingua. L'app è in 17 lingue (`app/src/main/res/xml/locale_config.xml`: `it`, `en`, `de`, `fr`, `es`, `ar`, `hi`, `id`, `ja`, `ko`, `my`, `pt`, `ru`, `th`, `tr`, `vi`, `zh-CN`); il listing è tradotto nelle cinque principali, che nella Console corrispondono a *it-IT*, *en-US* (aggiungere *en-GB* con lo stesso testo), *de-DE*, *fr-FR*, *es-ES*. Per le altre dodici la Console mostra il listing predefinito *it-IT* finché il titolare non aggiunge una traduzione. I testi derivano dal README, dalle stringhe dell'app e da [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md) e **non promettono dati in tempo reale**: la build pubblicata (`release-sign`, che passa `-PBINARIO_PERSONAL_REALTIME_SOURCES=true`, il valore predefinito della release) mostra gli orari programmati e, quando le fonti pubbliche best-effort rispondono, ritardi e binari, e dichiara quando un dato live manca. Le righe sulla privacy descrivono quella build: codici stazione, data e ora e numeri treno alle fonti ferroviarie (ViaggiaTreno in HTTP non cifrato), coordinate arrotondate della stazione di arrivo a Open-Meteo, tessere OpenFreeMap della zona mostrata. Se il titolare decide uno store senza fonti best-effort (`false`), vanno riscritte insieme alle risposte di `PLAY_DATA_SAFETY.md`. `tests/test_play_store_docs.py` verifica lunghezze, presenza delle cinque traduzioni, il numero di lingue dichiarato (da `locale_config.xml`) e il numero di reti (dai manifest dei pack).

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
• Quadri orario programmati RFI per le stazioni hub coperte e orari GTFS ufficiali inclusi nell'app per 21 reti: Trenitalia e Italo in tutta Italia, 15 ferrovie regionali e transfrontaliere, metro e tram di Milano, Genova, Firenze e Cagliari.
• Dettaglio corsa con operatore, categoria, orari programmati e fermate.
• Tratte salvate, preferiti e monitoraggio locale di un numero treno.
• Sveglia fermata e avvisi pendolare come notifiche locali; la sveglia può anche avvisarti in base alla posizione, che resta sul telefono.

WIDGET E WEAR OS
• Widget ad alto contrasto, ridimensionabile e configurabile per istanza su tratta, stazione o numero treno.
• Companion Wear OS con la tratta attiva, le prossime due corse, un riquadro (Tile) e una complicazione; l'aggiornamento resta delegato al telefono.

DATI E TRASPARENZA
• Binario mostra orari programmati e, quando le fonti pubbliche li forniscono, ritardi e binari in tempo reale. Quando un dato in tempo reale non è disponibile o non è identificabile con certezza, l'app lo dichiara e non inventa ritardi, binari o soppressioni.
• Ogni risultato indica la fonte, la validità e il livello di confidenza. Se una tratta non è coperta dalle fonti incluse, l'app lo dice invece di mostrare orari fittizi.

PRIVACY
• Nessun account, nessuna pubblicità, nessun SDK di analisi.
• Tratte, preferiti e impostazioni restano sul dispositivo; il backup è un file JSON esportato solo su tua scelta.
• Ricerche, tabelloni e avvisi interrogano le fonti pubbliche (RFI, ViaggiaTreno, Italo, Trenord, MIT e altre) inviando solo i dati della richiesta: codici delle stazioni, data e ora, numero del treno. Le richieste a ViaggiaTreno viaggiano in HTTP non cifrato quando la fonte lo impone.
• Meteo a destinazione (disattivabile): a Open-Meteo vanno le coordinate arrotondate della stazione di arrivo. Le mappe scaricano da OpenFreeMap le tessere della zona mostrata, per le stazioni vicine l'area intorno a te; le coordinate del dispositivo restano sul telefono.

Disponibile in 17 lingue: italiano, inglese, tedesco, francese, spagnolo, arabo, hindi, indonesiano, giapponese, coreano, birmano, portoghese, russo, thai, turco, vietnamita e cinese semplificato. Richiede Android 8.0 o successivo; il companion richiede Wear OS 3 (Android 11) o successivo e l'app installata sul telefono.
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
• RFI scheduled departure boards for the covered hub stations and official GTFS timetables bundled in the app for 21 networks: Trenitalia and Italo across Italy, 15 regional and cross-border railways, and the metro and tram lines of Milan, Genoa, Florence and Cagliari.
• Service details with operator, category, scheduled times and all stops.
• Saved routes, favourites and local monitoring of a train number.
• Stop alarm and commuter alerts as local notifications; the alarm can also alert you based on your location, which stays on the phone.

WIDGET AND WEAR OS
• High-contrast, resizable widget, configured per instance for a route, a station or a train number.
• Wear OS companion with the active route, the next two services, a Tile and a complication; refreshes stay delegated to the phone.

DATA AND TRANSPARENCY
• Binario shows scheduled times and, when the public sources provide them, real-time delays and platforms. When real-time data is unavailable or cannot be identified with certainty, the app says so and never invents delays, platforms or cancellations.
• Every result states its source, validity and confidence level. If a route is not covered by the bundled sources, the app says so instead of showing made-up times.

PRIVACY
• No account, no advertising, no analytics SDK.
• Routes, favourites and settings stay on the device; the backup is a JSON file exported only when you choose to.
• Searches, departure boards and alerts query the public sources (RFI, ViaggiaTreno, Italo, Trenord, MIT and others) with only what the request needs: station codes, date and time, train number. Requests to ViaggiaTreno travel over unencrypted HTTP when the source requires it.
• Weather at destination (can be turned off): Open-Meteo receives the rounded coordinates of the arrival station. Maps download from OpenFreeMap the tiles of the area shown, for nearby stations the area around you; the device's coordinates stay on the phone.

Available in 17 languages: Italian, English, German, French, Spanish, Arabic, Hindi, Indonesian, Japanese, Korean, Burmese, Portuguese, Russian, Thai, Turkish, Vietnamese and Simplified Chinese. Requires Android 8.0 or later; the companion requires Wear OS 3 (Android 11) or later and the app installed on the phone.
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
• Planmäßige RFI-Abfahrtstafeln für die abgedeckten Knotenbahnhöfe und offizielle GTFS-Fahrpläne in der App für 21 Netze: Trenitalia und Italo in ganz Italien, 15 regionale und grenzüberschreitende Bahnen sowie U-Bahn und Tram in Mailand, Genua, Florenz und Cagliari.
• Verbindungsdetails mit Betreiber, Kategorie, Planzeiten und allen Halten.
• Gespeicherte Strecken, Favoriten und lokale Überwachung einer Zugnummer.
• Haltwecker und Pendlerwarnungen als lokale Benachrichtigungen; der Wecker kann dich auch anhand deines Standorts warnen, der auf dem Telefon bleibt.

WIDGET UND WEAR OS
• Kontrastreiches, größenveränderbares Widget, je Instanz für eine Strecke, einen Bahnhof oder eine Zugnummer konfigurierbar.
• Wear-OS-Begleiter mit aktiver Strecke, den nächsten zwei Verbindungen, einer Kachel (Tile) und einer Komplikation; die Aktualisierung bleibt beim Telefon.

DATEN UND TRANSPARENZ
• Binario zeigt Planzeiten und, wenn die öffentlichen Quellen sie liefern, Verspätungen und Gleise in Echtzeit. Wenn Echtzeitdaten fehlen oder nicht eindeutig zuzuordnen sind, sagt die App das und erfindet keine Verspätungen, Gleise oder Ausfälle.
• Jedes Ergebnis nennt Quelle, Gültigkeit und Vertrauensstufe. Deckt keine enthaltene Quelle eine Strecke ab, sagt die App das, statt erfundene Zeiten zu zeigen.

DATENSCHUTZ
• Kein Konto, keine Werbung, kein Analyse-SDK.
• Strecken, Favoriten und Einstellungen bleiben auf dem Gerät; das Backup ist eine JSON-Datei, die nur auf deinen Wunsch exportiert wird.
• Suchen, Abfahrtstafeln und Warnungen fragen die öffentlichen Quellen ab (RFI, ViaggiaTreno, Italo, Trenord, MIT und andere) und senden nur, was die Anfrage braucht: Bahnhofscodes, Datum und Uhrzeit, Zugnummer. Anfragen an ViaggiaTreno laufen unverschlüsselt über HTTP, wenn die Quelle es verlangt.
• Wetter am Ziel (abschaltbar): Open-Meteo erhält die gerundeten Koordinaten des Zielbahnhofs. Karten laden bei OpenFreeMap die Kacheln des gezeigten Gebiets, bei Bahnhöfen in der Nähe die Umgebung deines Standorts; die Koordinaten des Geräts bleiben auf dem Telefon.

Verfügbar in 17 Sprachen: Italienisch, Englisch, Deutsch, Französisch, Spanisch, Arabisch, Hindi, Indonesisch, Japanisch, Koreanisch, Birmanisch, Portugiesisch, Russisch, Thai, Türkisch, Vietnamesisch und vereinfachtes Chinesisch. Erfordert Android 8.0 oder neuer; der Begleiter erfordert Wear OS 3 (Android 11) oder neuer und die auf dem Telefon installierte App.
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
• Tableaux des départs programmés RFI pour les gares principales couvertes et horaires GTFS officiels intégrés à l'application pour 21 réseaux : Trenitalia et Italo dans toute l'Italie, 15 lignes ferroviaires régionales et transfrontalières, métro et tram de Milan, Gênes, Florence et Cagliari.
• Détail du train avec opérateur, catégorie, horaires programmés et tous les arrêts.
• Trajets enregistrés, favoris et suivi local d'un numéro de train.
• Réveil à l'arrêt et alertes pendulaires sous forme de notifications locales ; le réveil peut aussi vous avertir selon votre position, qui reste sur le téléphone.

WIDGET ET WEAR OS
• Widget à fort contraste, redimensionnable et configurable par instance pour un trajet, une gare ou un numéro de train.
• Compagnon Wear OS avec le trajet actif, les deux prochains trains, une tuile (Tile) et une complication ; l'actualisation reste déléguée au téléphone.

DONNÉES ET TRANSPARENCE
• Binario affiche des horaires programmés et, quand les sources publiques les fournissent, retards et voies en temps réel. Quand une donnée en temps réel est indisponible ou ne peut pas être identifiée avec certitude, l'application le dit et n'invente ni retards, ni voies, ni suppressions.
• Chaque résultat indique sa source, sa validité et son niveau de confiance. Si un trajet n'est pas couvert par les sources intégrées, l'application le dit au lieu d'afficher des horaires fictifs.

CONFIDENTIALITÉ
• Pas de compte, pas de publicité, pas de SDK d'analyse.
• Trajets, favoris et réglages restent sur l'appareil ; la sauvegarde est un fichier JSON exporté uniquement à votre demande.
• Recherches, tableaux et alertes interrogent les sources publiques (RFI, ViaggiaTreno, Italo, Trenord, MIT et d'autres) en n'envoyant que les données de la demande : codes des gares, date et heure, numéro de train. Les requêtes vers ViaggiaTreno passent en HTTP non chiffré quand la source l'impose.
• Météo à destination (désactivable) : Open-Meteo reçoit les coordonnées arrondies de la gare d'arrivée. Les cartes téléchargent depuis OpenFreeMap les tuiles de la zone affichée, pour les gares proches la zone autour de vous ; les coordonnées de l'appareil restent sur le téléphone.

Disponible en 17 langues : italien, anglais, allemand, français, espagnol, arabe, hindi, indonésien, japonais, coréen, birman, portugais, russe, thaï, turc, vietnamien et chinois simplifié. Nécessite Android 8.0 ou version ultérieure ; le compagnon nécessite Wear OS 3 (Android 11) ou version ultérieure et l'application installée sur le téléphone.
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
• Cuadros de salidas programadas de RFI para las estaciones principales cubiertas y horarios GTFS oficiales incluidos en la app para 21 redes: Trenitalia e Italo en toda Italia, 15 ferrocarriles regionales y transfronterizos, y metro y tranvía de Milán, Génova, Florencia y Cagliari.
• Detalle del tren con operador, categoría, horarios programados y todas las paradas.
• Rutas guardadas, favoritos y seguimiento local de un número de tren.
• Alarma de parada y avisos para viajeros habituales como notificaciones locales; la alarma también puede avisarte según tu ubicación, que se queda en el teléfono.

WIDGET Y WEAR OS
• Widget de alto contraste, redimensionable y configurable por instancia para una ruta, una estación o un número de tren.
• Complemento para Wear OS con la ruta activa, los dos próximos trenes, un mosaico (Tile) y una complicación; la actualización sigue delegada al teléfono.

DATOS Y TRANSPARENCIA
• Binario muestra horarios programados y, cuando las fuentes públicas los ofrecen, retrasos y vías en tiempo real. Cuando un dato en tiempo real no está disponible o no puede identificarse con certeza, la app lo indica y no inventa retrasos, vías ni cancelaciones.
• Cada resultado indica su fuente, su validez y su nivel de confianza. Si una ruta no está cubierta por las fuentes incluidas, la app lo dice en lugar de mostrar horarios ficticios.

PRIVACIDAD
• Sin cuenta, sin publicidad, sin SDK de análisis.
• Rutas, favoritos y ajustes permanecen en el dispositivo; la copia de seguridad es un archivo JSON que se exporta solo si tú lo decides.
• Búsquedas, paneles y avisos consultan las fuentes públicas (RFI, ViaggiaTreno, Italo, Trenord, MIT y otras) enviando solo los datos de la petición: códigos de estación, fecha y hora, número de tren. Las peticiones a ViaggiaTreno van por HTTP sin cifrar cuando la fuente lo exige.
• Tiempo en el destino (desactivable): Open-Meteo recibe las coordenadas redondeadas de la estación de llegada. Los mapas descargan de OpenFreeMap las teselas de la zona mostrada, para las estaciones cercanas la zona a tu alrededor; las coordenadas del dispositivo se quedan en el teléfono.

Disponible en 17 idiomas: italiano, inglés, alemán, francés, español, árabe, hindi, indonesio, japonés, coreano, birmano, portugués, ruso, tailandés, turco, vietnamita y chino simplificado. Requiere Android 8.0 o posterior; el complemento requiere Wear OS 3 (Android 11) o posterior y la app instalada en el teléfono.
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
