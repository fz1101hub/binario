# Pubblicazione su App Store — checklist di invio per Binario

**Ambito:** app iPhone `it.binario.ios` (iOS 17 o successivo) con l'app Apple Watch incorporata `it.binario.ios.watchkitapp` (watchOS 10 o successivo) e la sua estensione WidgetKit `it.binario.ios.watchkitapp.widgets`, tutte nello stesso record di App Store Connect. Le azioni sono del titolare dell'account Apple Developer; questa pagina fissa l'ordine dei passaggi, i testi del listing in tutte le lingue dell'app accettate da App Store Connect e i riferimenti ai documenti che il repository mantiene verificati: lo stato tecnico è in [APPLE_PLATFORM_READINESS.md](../../ios/APPLE_PLATFORM_READINESS.md), l'informativa privacy in [PRIVACY.md](../../PRIVACY.md), gli asset grafici in [store/appstore/README.md](../../store/appstore/README.md), il companion in [APPLE_WATCH_IMPLEMENTATION.md](../../apple-watch/APPLE_WATCH_IMPLEMENTATION.md). Le regole di App Store Connect cambiano: ogni soglia riportata qui va riletta nella pagina dell'app al momento dell'invio.

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
2. **Informazioni sull'app**: categoria primaria *Viaggi* (secondaria facoltativa *Navigazione*); diritti sui contenuti: *Sì, l'app contiene, mostra o accede a contenuti di terzi* — gli orari GTFS open data elencati nelle impostazioni dell'app (**Fonti dati GTFS**) con attribuzione, validità, licenza e pagina della fonte e, quando la build include `ios/Resources/binario_punctuality_stats_v1.json.gz`, le statistiche aggregate non ufficiali che Binario calcola da osservazioni pubbliche di ViaggiaTreno, attribuite in ogni schermata («Statistiche non ufficiali di Binario su dati ViaggiaTreno») — e dichiarare di disporre dei diritti solo dopo aver completato il punto 0 sulle licenze. Se Trenitalia o RFI obiettano all'uso delle statistiche, `python3 ios/Scripts/sync-punctuality-stats.py --remove` le toglie dalla build iPhone successiva senza altre modifiche.
3. **Classificazione per età**: rispondere *No* a ogni voce del questionario (nessuna violenza, contenuto sessuale o di nudità, linguaggio scurrile, droghe, gioco d'azzardo, concorsi, horror, contenuti medici o generati dagli utenti; nessun accesso web illimitato: i collegamenti alle fonti aprono il browser di sistema). Esito atteso: **4+**.
4. **Privacy dell'app**: inserire l'URL dell'informativa; nel questionario dichiarare **Dati non raccolti** (*Data Not Collected*). È coerente con i tre `PrivacyInfo.xcprivacy` (nessun tracciamento, nessun dominio di tracciamento, nessun tipo di dato raccolto, sola API `UserDefaults` con motivi `CA92.1`/`1C8F.1`) e con il codice: nessun account, analytics, posizione, microfono né richiesta di rete. Le statistiche di affidabilità sono un file incluso nell'app, letto solo sul dispositivo, e «Mostra su Watch» salva soltanto l'identificativo di una tratta nelle `UserDefaults`: la risposta non cambia.
5. **Prezzo e disponibilità**: *Gratuita*; paesi almeno Italia, poi i paesi delle lingue supportate (Austria, Svizzera, Germania, Francia, Spagna, Regno Unito, Stati Uniti); le lingue aggiunte (sezione 2) non obbligano ad ampliare la disponibilità, perché i dati restano quelli delle reti incluse. La build esclude Mac con Apple Silicon e Apple Vision (`SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD` e `SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD` a `NO`): controllare che le due opzioni risultino disattivate nella pagina di disponibilità.

## 2. Testi del listing

Limiti di App Store Connect: nome 30 caratteri, sottotitolo 30, testo promozionale 170, descrizione 4.000, parole chiave 100 (separate da virgola, senza spazi dopo la virgola), novità 4.000 per lingua; l'URL di supporto è obbligatorio, l'URL marketing facoltativo. Le lingue sono quelle dell'app (`it`, `en`, `de`, `fr`, `es` e le dodici aggiunte in `docs/l10n/LOCALES.md`); in App Store Connect corrispondono a *Italiano*, *Inglese (Stati Uniti)* (aggiungere *Inglese (Regno Unito)* con lo stesso testo), *Tedesco*, *Francese*, *Spagnolo (Spagna)*, *Arabo*, *Hindi*, *Indonesiano*, *Giapponese*, *Coreano*, *Portoghese (Brasile)* (facoltativo *Portoghese (Portogallo)* con lo stesso testo), *Russo*, *Thailandese*, *Turco*, *Vietnamita* e *Cinese (semplificato)*. Il birmano (`my`) è presente nell'app ma App Store Connect non lo offre come lingua del listing: gli utenti birmani vedono la scheda nella lingua di ripiego del loro Store. I testi delle dodici lingue aggiunte sono scritti a macchina e non ancora rivisti da madrelingua: farli rileggere prima della pubblicazione. I testi derivano dalle stringhe dell'app, **non promettono dati in tempo reale** e dichiarano quali reti sono incluse e che Trenitalia e Italo non lo sono. `tests/test_app_store_docs.py` verifica lunghezze, presenza di tutte le lingue e l'esistenza nell'indice incluso delle tratte di prova indicate per la revisione.

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

#### Nome (ar)

```text
Binario
```

#### Nome (hi)

```text
Binario
```

#### Nome (id)

```text
Binario
```

#### Nome (ja)

```text
Binario
```

#### Nome (ko)

```text
Binario
```

#### Nome (pt-BR)

```text
Binario
```

#### Nome (ru)

```text
Binario
```

#### Nome (th)

```text
Binario
```

#### Nome (tr)

```text
Binario
```

#### Nome (vi)

```text
Binario
```

#### Nome (zh-Hans)

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

#### Sottotitolo (ar)

```text
جداول القطارات دون اتصال
```

#### Sottotitolo (hi)

```text
ऑफ़लाइन रेल समय-सारणी
```

#### Sottotitolo (id)

```text
Jadwal kereta offline
```

#### Sottotitolo (ja)

```text
オフラインの鉄道時刻表
```

#### Sottotitolo (ko)

```text
오프라인 철도 시간표
```

#### Sottotitolo (pt-BR)

```text
Horários de trem offline
```

#### Sottotitolo (ru)

```text
Расписание поездов офлайн
```

#### Sottotitolo (th)

```text
ตารางเวลารถไฟแบบออฟไลน์
```

#### Sottotitolo (tr)

```text
Çevrimdışı tren tarifeleri
```

#### Sottotitolo (vi)

```text
Lịch tàu hỏa ngoại tuyến
```

#### Sottotitolo (zh-Hans)

```text
离线铁路时刻表
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

#### Testo promozionale (ar)

```text
مواعيد قطارات مجدولة تعمل دون اتصال لتسع شبكات إقليمية وعابرة للحدود، مع تطبيق لـ Apple Watch. بلا حساب وبلا إعلانات. Trenitalia وItalo غير مضمَّنتين.
```

#### Testo promozionale (hi)

```text
नौ क्षेत्रीय और सीमा-पार नेटवर्क की निर्धारित, ऑफ़लाइन रेल समय-सारणी, Apple Watch ऐप के साथ। न खाता, न विज्ञापन। Trenitalia और Italo शामिल नहीं।
```

#### Testo promozionale (id)

```text
Jadwal kereta terencana dan offline untuk sembilan jaringan regional dan lintas batas, dengan aplikasi Apple Watch. Tanpa akun, tanpa iklan. Tanpa Trenitalia dan Italo.
```

#### Testo promozionale (ja)

```text
地域路線と国境を越える9つの鉄道網の計画時刻表をオフラインで。Apple Watch アプリ付き。アカウント不要、広告なし。Trenitalia と Italo は含まれません。
```

#### Testo promozionale (ko)

```text
지역 및 국경 간 철도망 9곳의 계획 시간표를 오프라인으로 확인하세요. Apple Watch 앱 포함. 계정도 광고도 없습니다. Trenitalia와 Italo는 포함되지 않습니다.
```

#### Testo promozionale (pt-BR)

```text
Horários de trem programados e offline para nove redes regionais e internacionais, com app para Apple Watch. Sem conta, sem anúncios. Trenitalia e Italo não incluídas.
```

#### Testo promozionale (ru)

```text
Плановое расписание поездов офлайн для девяти региональных и трансграничных сетей, с приложением для Apple Watch. Без аккаунта и рекламы. Trenitalia и Italo не включены.
```

#### Testo promozionale (th)

```text
ตารางเวลารถไฟตามแผนแบบออฟไลน์สำหรับเครือข่ายระดับภูมิภาคและข้ามพรมแดน 9 เครือข่าย พร้อมแอป Apple Watch ไม่ต้องมีบัญชี ไม่มีโฆษณา ไม่รวม Trenitalia และ Italo
```

#### Testo promozionale (tr)

```text
Dokuz bölgesel ve sınır ötesi ağ için çevrimdışı, planlı tren tarifeleri ve Apple Watch uygulaması. Hesap yok, reklam yok. Trenitalia ve Italo dahil değildir.
```

#### Testo promozionale (vi)

```text
Lịch tàu theo kế hoạch, ngoại tuyến, của chín mạng lưới khu vực và liên quốc gia, có ứng dụng Apple Watch. Không tài khoản, không quảng cáo. Không gồm Trenitalia, Italo.
```

#### Testo promozionale (zh-Hans)

```text
九个区域和跨境铁路网的离线计划时刻表，并提供 Apple Watch App。无需账户，没有广告。不含 Trenitalia 和 Italo。
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

Disponibile in 17 lingue, tra cui italiano, inglese, tedesco, francese e spagnolo. Richiede iOS 17 o successivo; l'app Apple Watch richiede watchOS 10 o successivo e l'iPhone abbinato.
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

Available in 17 languages, including English, Italian, German, French and Spanish. Requires iOS 17 or later; the Apple Watch app requires watchOS 10 or later and the paired iPhone.
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

Verfügbar in 17 Sprachen, darunter Deutsch, Italienisch, Englisch, Französisch und Spanisch. Erfordert iOS 17 oder neuer; die Apple-Watch-App erfordert watchOS 10 oder neuer und das gekoppelte iPhone.
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

Disponible en 17 langues, dont le français, l'italien, l'anglais, l'allemand et l'espagnol. Nécessite iOS 17 ou version ultérieure ; l'app Apple Watch nécessite watchOS 10 ou version ultérieure et l'iPhone jumelé.
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

Disponible en 17 idiomas, entre ellos español, italiano, inglés, alemán y francés. Requiere iOS 17 o posterior; la app para Apple Watch requiere watchOS 10 o posterior y el iPhone enlazado.
```

#### Descrizione (ar)

```text
Binario لـ iPhone مخطِّط رحلات قطارات يعمل دون اتصال بالكامل: اختر محطتين وشاهد الرحلات المجدولة التالية المأخوذة من جداول GTFS الرسمية المضمَّنة في التطبيق، مع مصدر كل نتيجة.

ما يتضمنه التطبيق
• مواعيد مجدولة لتسع شبكات إقليمية وعابرة للحدود: جنوب تيرول (STA) ولومبارديا (Trenord) وترينتينو وتوسكانا (TFT) وكامبانيا (EAV) وسردينيا (ARST) وسويسرا (Open Data Platform Mobility) والنمسا (ÖBB) وفرنسا (SNCF). Trenitalia وItalo غير مضمَّنتين: يُشار في البحث إلى المحطات التي لا تتوفر لها مواعيد.
• كتالوج دون اتصال يضم 5239 محطة إيطالية بأسمائها البديلة ورموزها؛ يتجاهل البحث علامات التشكيل والترقيم ويعرض المحطات المغطّاة أولًا.
• مسارات محفوظة، حتى 32 مسارًا، على جهاز iPhone ودون حساب.
• إعدادات تعرض كل مصدر مع صلاحيته وترخيصه ورابطًا إلى بوابة البيانات المفتوحة.

Apple Watch
• يعرض تطبيق Apple Watch المسار الذي بحثت عنه والرحلتين التاليتين، مع إضافة لواجهة الساعة. يتلقى البيانات من iPhone فقط، وفقط عندما تفعّل المشاركة في الإعدادات، ولا يتصل بالإنترنت أبدًا.

البيانات والشفافية
• يعرض Binario مواعيد مجدولة، وليست بيانات في الوقت الفعلي: لا يختلق التطبيق تأخيرات أو أرصفة أو إلغاءات، ويوضح متى لا تغطي المواعيد المضمَّنة مسارًا أو تاريخًا ما.
• تذكر كل نتيجة مصدرها وطبيعة بياناتها. للمواعيد تاريخ انتهاء يظهر في الإعدادات، ويجلب تحديث التطبيق المواعيد الجديدة.

الخصوصية
• لا حساب ولا إعلانات ولا تحليلات ولا طلبات شبكة.
• تبقى المسارات والتفضيلات على iPhone، وقد تتضمنها النسخ الاحتياطية للنظام.

متوفر بـ 17 لغة. يتطلب iOS 17 أو أحدث؛ ويتطلب تطبيق Apple Watch نظام watchOS 10 أو أحدث وجهاز iPhone مقترنًا.
```

#### Descrizione (hi)

```text
iPhone के लिए Binario एक रेल यात्रा प्लानर है जो पूरी तरह ऑफ़लाइन काम करता है: दो स्टेशन चुनें और ऐप में शामिल आधिकारिक GTFS समय-सारणियों से ली गई अगली निर्धारित सेवाएँ देखें, हर परिणाम के स्रोत के साथ।

क्या शामिल है
• नौ क्षेत्रीय और सीमा-पार नेटवर्क की निर्धारित समय-सारणियाँ: दक्षिण टायरॉल (STA), लोम्बार्डी (Trenord), ट्रेंटिनो, टस्कनी (TFT), कैम्पानिया (EAV), सार्डिनिया (ARST), स्विट्ज़रलैंड (Open Data Platform Mobility), ऑस्ट्रिया (ÖBB) और फ़्रांस (SNCF)। Trenitalia और Italo शामिल नहीं हैं: बिना समय-सारणी वाले स्टेशन खोज में चिह्नित किए जाते हैं।
• उपनामों और रेलवे कोड के साथ 5,239 इतालवी स्टेशनों का ऑफ़लाइन कैटलॉग; खोज एक्सेंट और विराम चिह्नों को अनदेखा करती है और कवर किए गए स्टेशन पहले दिखाती है।
• आपके iPhone पर, बिना खाते के, अधिकतम 32 सहेजे गए रूट।
• सेटिंग्स में हर स्रोत उसकी वैधता, लाइसेंस और ओपन-डेटा पोर्टल के लिंक के साथ।

APPLE WATCH
• Apple Watch ऐप खोजा गया रूट और अगली दो सेवाएँ दिखाता है, एक कॉम्प्लिकेशन के साथ। यह डेटा केवल iPhone से पाता है, केवल तब जब आप सेटिंग्स में साझाकरण चालू करें, और कभी इंटरनेट से नहीं जुड़ता।

डेटा और पारदर्शिता
• Binario निर्धारित समय दिखाता है। यह रीयल-टाइम डेटा नहीं है: ऐप कभी देरी, प्लेटफ़ॉर्म या रद्दीकरण नहीं गढ़ता और बताता है जब कोई रूट या तारीख शामिल समय-सारणियों में कवर नहीं होती।
• हर परिणाम अपना स्रोत और डेटा का प्रकार बताता है। समय-सारणियों की अवधि समाप्त होती है, जो सेटिंग्स में दिखती है: ऐप अपडेट नई समय-सारणियाँ लाता है।

गोपनीयता
• न खाता, न विज्ञापन, न एनालिटिक्स, न नेटवर्क अनुरोध।
• रूट और प्राथमिकताएँ iPhone पर ही रहती हैं; सिस्टम बैकअप में वे शामिल हो सकती हैं।

17 भाषाओं में उपलब्ध। iOS 17 या बाद का संस्करण आवश्यक है; Apple Watch ऐप के लिए watchOS 10 या बाद का संस्करण और पेयर किया गया iPhone आवश्यक है।
```

#### Descrizione (id)

```text
Binario untuk iPhone adalah perencana perjalanan kereta yang sepenuhnya offline: pilih dua stasiun dan lihat layanan terjadwal berikutnya dari jadwal GTFS resmi yang disertakan dalam aplikasi, lengkap dengan sumber setiap hasil.

YANG DISERTAKAN
• Jadwal terencana dari sembilan jaringan regional dan lintas batas: Tirol Selatan (STA), Lombardia (Trenord), Trentino, Toskana (TFT), Campania (EAV), Sardinia (ARST), Swiss (Open Data Platform Mobility), Austria (ÖBB), dan Prancis (SNCF). Trenitalia dan Italo tidak disertakan: stasiun tanpa jadwal ditandai dalam pencarian.
• Katalog offline berisi 5.239 stasiun Italia dengan nama alias dan kode kereta; pencarian mengabaikan aksen dan tanda baca serta menampilkan stasiun yang tercakup lebih dulu.
• Hingga 32 rute tersimpan di iPhone Anda, tanpa akun.
• Pengaturan mencantumkan setiap sumber beserta masa berlaku, lisensi, dan tautan ke portal data terbuka.

APPLE WATCH
• Aplikasi Apple Watch menampilkan rute yang dicari dan dua layanan berikutnya, dengan komplikasi. Aplikasi ini hanya menerima data dari iPhone, hanya jika Anda mengaktifkan berbagi di Pengaturan, dan tidak pernah terhubung ke internet.

DATA DAN TRANSPARANSI
• Binario menampilkan jadwal terencana. Ini bukan data waktu nyata: aplikasi tidak pernah mengarang keterlambatan, peron, atau pembatalan, dan memberi tahu jika suatu rute atau tanggal tidak tercakup oleh jadwal yang disertakan.
• Setiap hasil menyebutkan sumber dan jenis datanya. Jadwal memiliki masa berlaku yang terlihat di Pengaturan: pembaruan aplikasi membawa jadwal yang baru.

PRIVASI
• Tanpa akun, tanpa iklan, tanpa analitik, tanpa permintaan jaringan.
• Rute dan preferensi tetap di iPhone; cadangan sistem dapat menyertakannya.

Tersedia dalam 17 bahasa. Memerlukan iOS 17 atau versi lebih baru; aplikasi Apple Watch memerlukan watchOS 10 atau versi lebih baru dan iPhone yang dipasangkan.
```

#### Descrizione (ja)

```text
iPhone 版 Binario は、完全にオフラインで動作する鉄道の経路検索アプリです。2つの駅を選ぶと、アプリに収録された公式 GTFS 時刻表から次の計画運行を、結果ごとの出典とともに表示します。

収録内容
• 地域路線と国境を越える9つの鉄道網の計画時刻表：南チロル（STA）、ロンバルディア（Trenord）、トレンティーノ、トスカーナ（TFT）、カンパニア（EAV）、サルデーニャ（ARST）、スイス（Open Data Platform Mobility）、オーストリア（ÖBB）、フランス（SNCF）。Trenitalia と Italo は含まれず、時刻表のない駅は検索で表示されます。
• 別名と鉄道コードを備えたイタリアの 5,239 駅のオフラインカタログ。検索はアクセント記号や句読点を区別せず、時刻表のある駅を先に表示します。
• 最大 32 件のルートを、アカウントなしで iPhone に保存。
• 設定では各出典の有効期限、ライセンス、オープンデータポータルへのリンクを確認できます。

APPLE WATCH
• Apple Watch アプリは検索したルートと次の2本の運行を、コンプリケーションとともに表示します。データは iPhone からのみ、設定で共有をオンにした場合にのみ受け取り、インターネットには一切接続しません。

データと透明性
• Binario が表示するのは計画時刻です。リアルタイムデータではありません。遅延、番線、運休を作り出すことはなく、ルートや日付が収録時刻表の対象外の場合はそのことを示します。
• すべての結果に出典とデータの種類を表示します。時刻表には有効期限があり、設定で確認できます。アプリのアップデートで新しい時刻表が届きます。

プライバシー
• アカウント、広告、分析、ネットワーク通信は一切ありません。
• ルートと設定は iPhone に保存されます。システムのバックアップに含まれる場合があります。

17 言語に対応。iOS 17 以降が必要です。Apple Watch アプリには watchOS 10 以降と、ペアリングされた iPhone が必要です。
```

#### Descrizione (ko)

```text
iPhone용 Binario는 완전히 오프라인으로 작동하는 철도 여정 플래너입니다. 두 역을 선택하면 앱에 포함된 공식 GTFS 시간표에서 다음 계획 운행을 각 결과의 출처와 함께 보여 줍니다.

포함 내용
• 지역 및 국경 간 철도망 9곳의 계획 시간표: 남티롤(STA), 롬바르디아(Trenord), 트렌티노, 토스카나(TFT), 캄파니아(EAV), 사르데냐(ARST), 스위스(Open Data Platform Mobility), 오스트리아(ÖBB), 프랑스(SNCF). Trenitalia와 Italo는 포함되지 않으며, 시간표가 없는 역은 검색에서 표시됩니다.
• 별칭과 철도 코드를 갖춘 이탈리아 5,239개 역의 오프라인 카탈로그. 검색은 악센트와 문장 부호를 무시하고 시간표가 있는 역을 먼저 보여 줍니다.
• 계정 없이 iPhone에 최대 32개의 경로 저장.
• 설정에서 각 출처의 유효 기간, 라이선스, 공개 데이터 포털 링크 확인.

APPLE WATCH
• Apple Watch 앱은 검색한 경로와 다음 두 운행을 컴플리케이션과 함께 보여 줍니다. 데이터는 설정에서 공유를 켠 경우에만 iPhone에서만 받으며, 인터넷에는 절대 연결하지 않습니다.

데이터와 투명성
• Binario는 계획 시각을 보여 줍니다. 실시간 데이터가 아닙니다. 앱은 지연, 승강장, 운행 취소를 지어내지 않으며, 포함된 시간표가 경로나 날짜를 다루지 않으면 그 사실을 알려 줍니다.
• 모든 결과에 출처와 데이터의 성격을 표시합니다. 시간표에는 설정에서 확인할 수 있는 만료일이 있으며, 앱을 업데이트하면 새 시간표가 제공됩니다.

개인정보 보호
• 계정, 광고, 분석, 네트워크 요청이 없습니다.
• 경로와 설정은 iPhone에만 저장되며, 시스템 백업에 포함될 수 있습니다.

17개 언어 지원. iOS 17 이상이 필요하며, Apple Watch 앱에는 watchOS 10 이상과 페어링된 iPhone이 필요합니다.
```

#### Descrizione (pt-BR)

```text
O Binario para iPhone é um planejador de viagens de trem que funciona totalmente offline: escolha duas estações e veja os próximos serviços programados, lidos dos horários GTFS oficiais incluídos no app, com a fonte de cada resultado.

O QUE ESTÁ INCLUÍDO
• Horários programados de nove redes regionais e internacionais: Tirol do Sul (STA), Lombardia (Trenord), Trentino, Toscana (TFT), Campânia (EAV), Sardenha (ARST), Suíça (Open Data Platform Mobility), Áustria (ÖBB) e França (SNCF). Trenitalia e Italo não estão incluídas: as estações sem horários são sinalizadas na busca.
• Catálogo offline de 5.239 estações italianas com nomes alternativos e códigos ferroviários; a busca ignora acentos e pontuação e mostra primeiro as estações cobertas.
• Até 32 trajetos salvos no seu iPhone, sem conta.
• Ajustes com cada fonte, sua validade, licença e link para o portal de dados abertos.

APPLE WATCH
• O app para Apple Watch mostra o trajeto buscado e os próximos dois serviços, com uma complicação. Ele recebe dados apenas do iPhone, somente quando você ativa o compartilhamento nos Ajustes, e nunca se conecta à internet.

DADOS E TRANSPARÊNCIA
• O Binario mostra horários programados. Não são dados em tempo real: o app nunca inventa atrasos, plataformas ou cancelamentos e avisa quando um trajeto ou uma data não é coberto pelos horários incluídos.
• Cada resultado indica a fonte e a natureza dos dados. Os horários têm validade, visível nos Ajustes: uma atualização do app traz os novos.

PRIVACIDADE
• Sem conta, sem publicidade, sem análises, sem solicitações de rede.
• Trajetos e preferências ficam no iPhone; os backups do sistema podem incluí-los.

Disponível em 17 idiomas. Requer iOS 17 ou posterior; o app para Apple Watch requer watchOS 10 ou posterior e o iPhone emparelhado.
```

#### Descrizione (ru)

```text
Binario для iPhone — планировщик железнодорожных поездок, который работает полностью офлайн: выберите две станции и посмотрите ближайшие плановые рейсы из официальных расписаний GTFS, встроенных в приложение, с источником каждого результата.

ЧТО ВКЛЮЧЕНО
• Плановые расписания девяти региональных и трансграничных сетей: Южный Тироль (STA), Ломбардия (Trenord), Трентино, Тоскана (TFT), Кампания (EAV), Сардиния (ARST), Швейцария (Open Data Platform Mobility), Австрия (ÖBB) и Франция (SNCF). Trenitalia и Italo не включены: станции без расписания отмечаются при поиске.
• Офлайн-каталог из 5 239 итальянских станций с альтернативными названиями и железнодорожными кодами; поиск не учитывает диакритику и пунктуацию и сначала показывает станции с расписанием.
• До 32 сохранённых маршрутов на вашем iPhone без аккаунта.
• В настройках указан каждый источник со сроком действия, лицензией и ссылкой на портал открытых данных.

APPLE WATCH
• Приложение для Apple Watch показывает найденный маршрут и два ближайших рейса, а также расширение для циферблата. Оно получает данные только с iPhone, только если вы включили передачу в настройках, и никогда не подключается к интернету.

ДАННЫЕ И ПРОЗРАЧНОСТЬ
• Binario показывает плановое время. Это не данные в реальном времени: приложение никогда не выдумывает задержки, платформы или отмены и сообщает, если маршрут или дата не охвачены включёнными расписаниями.
• Для каждого результата указаны источник и характер данных. У расписаний есть срок действия, он виден в настройках: новые расписания приходят с обновлением приложения.

КОНФИДЕНЦИАЛЬНОСТЬ
• Без аккаунта, рекламы, аналитики и сетевых запросов.
• Маршруты и настройки хранятся на iPhone; они могут попасть в системные резервные копии.

Доступно на 17 языках. Требуется iOS 17 или новее; для приложения Apple Watch нужны watchOS 10 или новее и сопряжённый iPhone.
```

#### Descrizione (th)

```text
Binario สำหรับ iPhone คือแอปวางแผนการเดินทางด้วยรถไฟที่ทำงานแบบออฟไลน์ทั้งหมด: เลือกสองสถานีแล้วดูเที่ยวรถตามแผนถัดไปจากตารางเวลา GTFS อย่างเป็นทางการที่รวมอยู่ในแอป พร้อมแหล่งที่มาของทุกผลลัพธ์

สิ่งที่รวมอยู่
• ตารางเวลาตามแผนของเครือข่ายระดับภูมิภาคและข้ามพรมแดน 9 เครือข่าย: เซาท์ทิโรล (STA), ลอมบาร์เดีย (Trenord), เตรนตีโน, ทัสคานี (TFT), คัมปาเนีย (EAV), ซาร์ดิเนีย (ARST), สวิตเซอร์แลนด์ (Open Data Platform Mobility), ออสเตรีย (ÖBB) และฝรั่งเศส (SNCF) ไม่รวม Trenitalia และ Italo: สถานีที่ไม่มีตารางเวลาจะถูกระบุไว้ในการค้นหา
• แค็ตตาล็อกออฟไลน์ของสถานีในอิตาลี 5,239 แห่ง พร้อมชื่อเรียกอื่นและรหัสรถไฟ การค้นหาไม่สนใจเครื่องหมายเน้นเสียงและเครื่องหมายวรรคตอน และแสดงสถานีที่มีตารางเวลาก่อน
• บันทึกเส้นทางได้สูงสุด 32 เส้นทางบน iPhone ของคุณ โดยไม่ต้องมีบัญชี
• การตั้งค่าแสดงแหล่งข้อมูลทุกแหล่งพร้อมระยะเวลาที่ใช้ได้ สัญญาอนุญาต และลิงก์ไปยังพอร์ทัลข้อมูลเปิด

APPLE WATCH
• แอป Apple Watch แสดงเส้นทางที่ค้นหาและเที่ยวรถสองเที่ยวถัดไป พร้อมคอมพลิเคชัน แอปรับข้อมูลจาก iPhone เท่านั้น เฉพาะเมื่อคุณเปิดการแชร์ในการตั้งค่า และไม่เชื่อมต่ออินเทอร์เน็ตเลย

ข้อมูลและความโปร่งใส
• Binario แสดงเวลาตามแผน ไม่ใช่ข้อมูลเรียลไทม์: แอปไม่สร้างความล่าช้า ชานชาลา หรือการยกเลิกขึ้นเอง และแจ้งเมื่อเส้นทางหรือวันที่ใดไม่อยู่ในตารางเวลาที่รวมอยู่
• ทุกผลลัพธ์ระบุแหล่งที่มาและลักษณะของข้อมูล ตารางเวลามีวันหมดอายุซึ่งดูได้ในการตั้งค่า และการอัปเดตแอปจะนำตารางเวลาใหม่มาให้

ความเป็นส่วนตัว
• ไม่มีบัญชี ไม่มีโฆษณา ไม่มีการวิเคราะห์ ไม่มีคำขอเครือข่าย
• เส้นทางและการตั้งค่าอยู่บน iPhone ข้อมูลสำรองของระบบอาจรวมข้อมูลเหล่านี้ไว้

รองรับ 17 ภาษา ต้องใช้ iOS 17 ขึ้นไป แอป Apple Watch ต้องใช้ watchOS 10 ขึ้นไปและ iPhone ที่จับคู่ไว้
```

#### Descrizione (tr)

```text
iPhone için Binario, tamamen çevrimdışı çalışan bir tren yolculuğu planlayıcısıdır: iki istasyon seçin ve uygulamaya dahil edilen resmî GTFS tarifelerinden okunan sonraki planlı seferleri, her sonucun kaynağıyla birlikte görün.

NELER DAHİL
• Dokuz bölgesel ve sınır ötesi ağın planlı tarifeleri: Güney Tirol (STA), Lombardiya (Trenord), Trentino, Toskana (TFT), Campania (EAV), Sardinya (ARST), İsviçre (Open Data Platform Mobility), Avusturya (ÖBB) ve Fransa (SNCF). Trenitalia ve Italo dahil değildir: tarifesi olmayan istasyonlar aramada işaretlenir.
• Diğer adları ve demiryolu kodlarıyla 5.239 İtalyan istasyonundan oluşan çevrimdışı katalog; arama aksanları ve noktalama işaretlerini yok sayar ve kapsanan istasyonları önce listeler.
• iPhone'unuzda, hesap gerekmeden en fazla 32 kayıtlı güzergâh.
• Ayarlarda her kaynak geçerlilik süresi, lisansı ve açık veri portalının bağlantısıyla listelenir.

APPLE WATCH
• Apple Watch uygulaması aranan güzergâhı ve sonraki iki seferi bir komplikasyonla birlikte gösterir. Verileri yalnızca iPhone'dan, yalnızca Ayarlar'da paylaşımı açtığınızda alır ve internete asla bağlanmaz.

VERİLER VE ŞEFFAFLIK
• Binario planlı saatleri gösterir. Bunlar gerçek zamanlı veri değildir: uygulama asla gecikme, peron veya iptal uydurmaz ve bir güzergâh ya da tarih dahil edilen tarifelerin kapsamında değilse bunu belirtir.
• Her sonuç kaynağını ve verinin niteliğini belirtir. Tarifelerin Ayarlar'da görünen bir son geçerlilik tarihi vardır: uygulama güncellemesi yeni tarifeleri getirir.

GİZLİLİK
• Hesap yok, reklam yok, analiz yok, ağ isteği yok.
• Güzergâhlar ve tercihler iPhone'da kalır; sistem yedekleri bunları içerebilir.

17 dilde kullanılabilir. iOS 17 veya üstü gerekir; Apple Watch uygulaması watchOS 10 veya üstü ve eşleştirilmiş iPhone gerektirir.
```

#### Descrizione (vi)

```text
Binario cho iPhone là ứng dụng lập kế hoạch hành trình tàu hỏa hoạt động hoàn toàn ngoại tuyến: chọn hai ga và xem các chuyến theo kế hoạch tiếp theo từ lịch trình GTFS chính thức có sẵn trong ứng dụng, kèm nguồn của từng kết quả.

ỨNG DỤNG BAO GỒM
• Lịch trình theo kế hoạch của chín mạng lưới khu vực và xuyên biên giới: Nam Tyrol (STA), Lombardia (Trenord), Trentino, Toscana (TFT), Campania (EAV), Sardegna (ARST), Thụy Sĩ (Open Data Platform Mobility), Áo (ÖBB) và Pháp (SNCF). Không bao gồm Trenitalia và Italo: các ga không có lịch trình được đánh dấu khi tìm kiếm.
• Danh mục ngoại tuyến gồm 5.239 ga tại Ý với tên gọi khác và mã đường sắt; tìm kiếm bỏ qua dấu và ký tự chấm câu, đồng thời hiển thị trước các ga có lịch trình.
• Lưu tối đa 32 tuyến trên iPhone của bạn, không cần tài khoản.
• Phần Cài đặt liệt kê từng nguồn kèm thời hạn hiệu lực, giấy phép và liên kết đến cổng dữ liệu mở.

APPLE WATCH
• Ứng dụng Apple Watch hiển thị tuyến đã tìm và hai chuyến tiếp theo, kèm một tiện ích mặt đồng hồ. Ứng dụng chỉ nhận dữ liệu từ iPhone, chỉ khi bạn bật chia sẻ trong Cài đặt, và không bao giờ kết nối Internet.

DỮ LIỆU VÀ TÍNH MINH BẠCH
• Binario hiển thị giờ theo kế hoạch. Đây không phải dữ liệu thời gian thực: ứng dụng không bao giờ bịa ra độ trễ, sân ga hay chuyến bị hủy, và cho biết khi một tuyến hoặc ngày không nằm trong lịch trình đi kèm.
• Mỗi kết quả đều nêu nguồn và tính chất của dữ liệu. Lịch trình có thời hạn, hiển thị trong Cài đặt: bản cập nhật ứng dụng sẽ mang lịch trình mới.

QUYỀN RIÊNG TƯ
• Không tài khoản, không quảng cáo, không phân tích, không yêu cầu mạng.
• Tuyến và tùy chọn được giữ trên iPhone; bản sao lưu hệ thống có thể bao gồm chúng.

Có sẵn bằng 17 ngôn ngữ. Yêu cầu iOS 17 trở lên; ứng dụng Apple Watch yêu cầu watchOS 10 trở lên và iPhone đã ghép đôi.
```

#### Descrizione (zh-Hans)

```text
iPhone 版 Binario 是一款完全离线运行的铁路行程规划工具：选择两个车站，即可查看从 App 内置的官方 GTFS 时刻表中读取的接下来的计划车次，并附有每条结果的来源。

包含内容
• 九个区域和跨境铁路网的计划时刻表：南蒂罗尔 (STA)、伦巴第 (Trenord)、特伦蒂诺、托斯卡纳 (TFT)、坎帕尼亚 (EAV)、撒丁岛 (ARST)、瑞士 (Open Data Platform Mobility)、奥地利 (ÖBB) 和法国 (SNCF)。不含 Trenitalia 和 Italo：没有时刻表的车站会在搜索中标出。
• 离线目录收录 5,239 个意大利车站及其别名和铁路代码；搜索忽略重音和标点，并优先列出有时刻表的车站。
• 无需账户，即可在 iPhone 上保存最多 32 条路线。
• 设置中列出每个来源的有效期、许可和开放数据门户链接。

APPLE WATCH
• Apple Watch App 显示所搜索的路线和接下来的两班车次，并提供表盘复杂功能。它只从 iPhone 接收数据，且仅在你于设置中开启共享后接收，从不连接互联网。

数据与透明度
• Binario 显示的是计划时刻，并非实时数据：App 从不编造晚点、站台或停运信息，并会在路线或日期不在所含时刻表范围内时明确说明。
• 每条结果都会注明来源和数据性质。时刻表有到期日，可在设置中查看：更新 App 即可获得新的时刻表。

隐私
• 无账户、无广告、无分析、无网络请求。
• 路线和偏好设置保存在 iPhone 上；系统备份可能包含这些数据。

支持 17 种语言。需要 iOS 17 或更高版本；Apple Watch App 需要 watchOS 10 或更高版本以及已配对的 iPhone。
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

#### Parole chiave (ar)

```text
قطارات,مواعيد,سكك حديدية,محطات,تنقل يومي,إقليمي,مسار,دون اتصال,GTFS,Apple Watch
```

#### Parole chiave (hi)

```text
ट्रेन,समय-सारणी,रेलवे,स्टेशन,यात्री,क्षेत्रीय,रूट,ऑफ़लाइन,GTFS,Apple Watch
```

#### Parole chiave (id)

```text
kereta,jadwal,kereta api,stasiun,komuter,regional,rute,offline,GTFS,Apple Watch
```

#### Parole chiave (ja)

```text
電車,時刻表,鉄道,駅,通勤,ローカル線,ルート,オフライン,GTFS,Apple Watch
```

#### Parole chiave (ko)

```text
기차,시간표,철도,역,통근,지역 열차,경로,오프라인,GTFS,Apple Watch
```

#### Parole chiave (pt-BR)

```text
trens,horários,ferrovia,estações,trem metropolitano,regional,trajeto,offline,GTFS,Apple Watch
```

#### Parole chiave (ru)

```text
поезда,расписание,железная дорога,станции,электричка,регион,маршрут,офлайн,GTFS,Apple Watch
```

#### Parole chiave (th)

```text
รถไฟ,ตารางเวลา,การรถไฟ,สถานี,เดินทางไปทำงาน,ภูมิภาค,เส้นทาง,ออฟไลน์,GTFS,Apple Watch
```

#### Parole chiave (tr)

```text
tren,tarife,demiryolu,istasyon,banliyö,bölgesel,güzergâh,çevrimdışı,GTFS,Apple Watch
```

#### Parole chiave (vi)

```text
tàu hỏa,lịch tàu,đường sắt,nhà ga,đi làm,khu vực,tuyến,ngoại tuyến,GTFS,Apple Watch
```

#### Parole chiave (zh-Hans)

```text
火车,时刻表,铁路,车站,通勤,区域列车,路线,离线,GTFS,Apple Watch
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

#### Novità (ar)

```text
أول إصدار من Binario 0.1.0 على App Store: مخطِّط يعمل دون اتصال بجداول GTFS الرسمية لتسع شبكات إقليمية وعابرة للحدود، وكتالوج يضم 5239 محطة يشير إلى المحطات التي لا تتوفر لها مواعيد، ومسارات محفوظة وتطبيق لـ Apple Watch. بلا حساب وبلا إعلانات وبلا طلبات شبكة.
```

#### Novità (hi)

```text
App Store पर Binario 0.1.0 की पहली रिलीज़: नौ क्षेत्रीय और सीमा-पार नेटवर्क की आधिकारिक GTFS समय-सारणियों वाला ऑफ़लाइन प्लानर, 5,239 स्टेशनों का कैटलॉग जो बिना समय-सारणी वाले स्टेशनों को चिह्नित करता है, सहेजे गए रूट और Apple Watch ऐप। न खाता, न विज्ञापन, न नेटवर्क अनुरोध।
```

#### Novità (id)

```text
Rilis pertama Binario 0.1.0 di App Store: perencana offline dengan jadwal GTFS resmi dari sembilan jaringan regional dan lintas batas, katalog 5.239 stasiun yang menandai stasiun tanpa jadwal, rute tersimpan, dan aplikasi Apple Watch. Tanpa akun, tanpa iklan, tanpa permintaan jaringan.
```

#### Novità (ja)

```text
Binario 0.1.0 を App Store で初めて公開しました。地域路線と国境を越える9つの鉄道網の公式 GTFS 時刻表を収録したオフライン経路検索、時刻表のない駅を表示する 5,239 駅のカタログ、ルート保存、Apple Watch アプリを備えています。アカウント不要、広告なし、ネットワーク通信なし。
```

#### Novità (ko)

```text
App Store에 Binario 0.1.0을 처음 출시합니다. 지역 및 국경 간 철도망 9곳의 공식 GTFS 시간표를 담은 오프라인 플래너, 시간표가 없는 역을 표시하는 5,239개 역 카탈로그, 경로 저장, Apple Watch 앱을 제공합니다. 계정, 광고, 네트워크 요청이 없습니다.
```

#### Novità (pt-BR)

```text
Primeira versão do Binario 0.1.0 na App Store: planejador offline com horários GTFS oficiais de nove redes regionais e internacionais, catálogo de 5.239 estações que sinaliza as que não têm horários, trajetos salvos e app para Apple Watch. Sem conta, sem anúncios, sem solicitações de rede.
```

#### Novità (ru)

```text
Первый выпуск Binario 0.1.0 в App Store: офлайн-планировщик с официальными расписаниями GTFS девяти региональных и трансграничных сетей, каталог из 5 239 станций с отметкой станций без расписания, сохранённые маршруты и приложение для Apple Watch. Без аккаунта, рекламы и сетевых запросов.
```

#### Novità (th)

```text
Binario 0.1.0 เปิดตัวครั้งแรกบน App Store: ตัววางแผนแบบออฟไลน์พร้อมตารางเวลา GTFS อย่างเป็นทางการของเครือข่ายระดับภูมิภาคและข้ามพรมแดน 9 เครือข่าย แค็ตตาล็อก 5,239 สถานีที่ระบุสถานีที่ไม่มีตารางเวลา การบันทึกเส้นทาง และแอป Apple Watch ไม่มีบัญชี ไม่มีโฆษณา ไม่มีคำขอเครือข่าย
```

#### Novità (tr)

```text
Binario 0.1.0'ın App Store'daki ilk sürümü: dokuz bölgesel ve sınır ötesi ağın resmî GTFS tarifelerini içeren çevrimdışı planlayıcı, tarifesi olmayan istasyonları işaretleyen 5.239 istasyonluk katalog, kayıtlı güzergâhlar ve Apple Watch uygulaması. Hesap yok, reklam yok, ağ isteği yok.
```

#### Novità (vi)

```text
Phát hành Binario 0.1.0 lần đầu trên App Store: trình lập kế hoạch ngoại tuyến với lịch trình GTFS chính thức của chín mạng lưới khu vực và xuyên biên giới, danh mục 5.239 ga có đánh dấu các ga không có lịch trình, lưu tuyến và ứng dụng Apple Watch. Không tài khoản, không quảng cáo, không yêu cầu mạng.
```

#### Novità (zh-Hans)

```text
Binario 0.1.0 首次在 App Store 发布：离线规划工具，内含九个区域和跨境铁路网的官方 GTFS 时刻表；5,239 个车站的目录，并标出没有时刻表的车站；路线保存功能及 Apple Watch App。无需账户，没有广告，没有网络请求。
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
Binario è un pianificatore ferroviario offline: nessun account, nessun login, nessuna richiesta di rete. Gli orari sono dati programmati (GTFS ufficiali di nove reti regionali ed estere) inclusi nell'app; Trenitalia e Italo non sono inclusi e le stazioni senza orari sono contrassegnate "Orari non inclusi" nella ricerca. Tratte di prova coperte: Milano Centrale → Bergamo (Trenord) e Bolzano Bozen → Merano Meran (STA), con data e ora correnti. Apple Watch: abbinare un Watch, attivare "Condividi le tratte con Apple Watch" in Impostazioni › Privacy ed eseguire una ricerca per l'orario corrente; il Watch mostra la tratta, le prossime due corse e la complicazione. In alternativa salvare una tratta, tenerla premuta nella scheda Tratte e scegliere "Mostra su Watch": il Watch la mostra quando sull'iPhone non c'è una ricerca per l'orario corrente, e "Aggiorna" sul Watch la chiede all'iPhone anche con l'app in background. Le righe "In orario … volte su …" sono statistiche non ufficiali incluse nell'app (nessuna rete) e compaiono solo per i treni osservati, per esempio Bolzano Bozen → Merano Meran. Conformità all'esportazione: nessuna crittografia (ITSAppUsesNonExemptEncryption = NO).

Binario is an offline rail journey planner: no account, no login, no network requests. Timetables are scheduled data (official GTFS feeds of nine regional and cross-border networks) bundled in the app; Trenitalia and Italo are not included and stations without timetables are flagged "No timetable included" in the search. Covered test routes: Milano Centrale → Bergamo (Trenord) and Bolzano Bozen → Merano Meran (STA), with the current date and time. Apple Watch: pair a Watch, turn on "Share routes with Apple Watch" in Settings › Privacy and run a search for now; the Watch shows the route, the next two services and the complication. Alternatively save a route, touch and hold it in the Routes tab and choose "Show on Watch": the Watch shows it whenever the iPhone has no search for now, and "Refresh" on the Watch asks the iPhone for it even with the app in the background. The "On time … times out of …" lines are unofficial statistics bundled with the app (no network) and appear only for observed trains, for example Bolzano Bozen → Merano Meran. Export compliance: no encryption (ITSAppUsesNonExemptEncryption = NO).
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
- Le statistiche di affidabilità vengono aggiornate ogni settimana per Android: dopo `tools/apply_ci_punctuality.py` eseguire `python3 ios/Scripts/sync-punctuality-stats.py` (il test `tests/test_verify_ios_project.py` fallisce finché la copia iPhone è diversa) e includerle nella build iPhone successiva.
- Le sette licenze ancora `null` in `ios/Resources/binario_gtfs_source_policy_v1.json` vanno confermate e registrate: aggiornano la sezione *Fonti dati GTFS* dell'app e la dichiarazione sui diritti dei contenuti.
