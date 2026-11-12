# Lingue dell’app Android e di Wear OS

Le altre piattaforme (iPhone, Apple Watch, Companion Expo, pagine web e skill Alexa) sono descritte in [APPLE_ALEXA_LOCALES.md](APPLE_ALEXA_LOCALES.md).

L’italiano (`values/`) è la lingua di riferimento: ogni testo nasce in italiano e le altre lingue lo traducono chiave per chiave.
**Le traduzioni diverse dall’italiano sono scritte automaticamente e non sono state revisionate da madrelingua.** In caso di dubbio fa fede il testo italiano; l’inglese (`values-en/`) serve solo come controllo del significato.

## Cartelle e tag

| Lingua | Nome nel selettore | Tag BCP-47 (`locale_config.xml`, selettore) | Cartella risorse (app e Wear) | Quantità plurali (CLDR, lint AGP 8.13) |
|---|---|---|---|---|
| Italiano (predefinita) | Italiano | `it` | `values/` | one, many, other |
| Inglese | English | `en` | `values-en/` | one, other |
| Tedesco | Deutsch | `de` | `values-de/` | one, other |
| Francese | Français | `fr` | `values-fr/` | one, many, other |
| Spagnolo | Español | `es` | `values-es/` | one, many, other |
| Arabo | العربية | `ar` | `values-ar/` | zero, one, two, few, many, other |
| Hindi | हिन्दी | `hi` | `values-hi/` | one, other |
| Indonesiano | Bahasa Indonesia | `id` | `values-in/` | other |
| Giapponese | 日本語 | `ja` | `values-ja/` | other |
| Coreano | 한국어 | `ko` | `values-ko/` | other |
| Birmano | မြန်မာ | `my` | `values-my/` | other |
| Portoghese | Português | `pt` | `values-pt/` | one, many, other |
| Russo | Русский | `ru` | `values-ru/` | one, few, many, other |
| Thai | ไทย | `th` | `values-th/` | other |
| Turco | Türkçe | `tr` | `values-tr/` | one, other |
| Vietnamita | Tiếng Việt | `vi` | `values-vi/` | other |
| Cinese semplificato | 中文 (简体) | `zh-CN` | `values-zh-rCN/` | other |

- **Indonesiano in `values-in/`**: Android riscrive il tag `id` nel codice storico `in` prima di cercare le risorse (`ResourcesImpl.adjustLanguageTag`), quindi una cartella `values-id/` non verrebbe mai usata; anche AndroidX usa `values-in/`. Il lint (`LocaleFolder`) accetta `in`. Il selettore usa il tag BCP-47 `id`, mentre `locale_config.xml` dichiara `in`: con `id` il lint (`UnusedTranslation`) considera `values-in/` non dichiarata e blocca la build.
- **Cinese semplificato in `values-zh-rCN/`** con tag `zh-CN`: da Android 7 la corrispondenza delle risorse considera anche la scrittura, quindi la cartella vale anche per `zh-Hans` e `zh-SG`; il cinese tradizionale (`zh-TW`, `zh-HK`) ricade sull’italiano.
- **Portoghese in `values-pt/`**: lessico neutro con preferenza brasiliana (per esempio «trem», «plataforma»), senza forme solo europee.
- **Plurali**: le quantità sono quelle richieste dal lint del progetto (`MissingQuantity`/`UnusedQuantity` con warning trattati come errori), cioè CLDR 42 o successivo. Per questo italiano, francese, spagnolo **e portoghese** hanno anche `many` (milioni, «1 milhão de»), e il russo ha `one/few/many/other`. Ogni voce di un plurale tradotto conserva gli stessi segnaposto della voce italiana `other` (o della voce omonima), anche `one`, `zero` e `two`.
- **Bundle Play con tutte le lingue**: `app/build.gradle.kts` disattiva la divisione per lingua del bundle (`bundle { language { enableSplit = false } }`). Con la divisione attiva un'installazione da Play porterebbe solo le lingue del dispositivo: una lingua scelta nel selettore e assente dal sistema resterebbe in italiano finché Play non scarica la parte mancante, cioè mai senza rete. `tools/verify_android_app_bundles.py` controlla nel bundle del telefono che `BundleConfig.pb` abbia la lingua esclusa dalla divisione e che le stringhe del modulo base coprano tutte le lingue di `locale_config.xml`; `tools/verify_android_release_config.py` controlla l'impostazione in Gradle.
- **Wear OS** usa le stesse cartelle per le sue 27 stringhe; non ha un `locale_config.xml` (Wear OS non offre la scelta della lingua per app) e segue la lingua dell’orologio.

## Regole per i traduttori

- Segnaposto identici e nello stesso numero (`%1$s`, `%2$d`, `%d`); l’ordine nella frase può cambiare usando gli indici.
- Escape XML e Android: `\'` per l’apostrofo ASCII, `\"`, `\n`, `&amp;`, `&lt;`. Nessun BOM, nessun carattere invisibile (spazi a larghezza zero, marcatori di direzione) scritto direttamente nel file.
- Non si traducono: Binario, Trenitalia, Italo, Frecciarossa, Frecciargento, Frecciabianca, Frecce, Intercity, ViaggiaTreno, RFI, Trenord, ItaloBus, FrecciaLink, OpenStreetMap, OpenFreeMap, MIT, Wear OS, Apple Watch, Alexa, GTFS. Nomi di stazioni e testi degli avvisi degli operatori sono dati, non stringhe.
- Testi brevi dove lo spazio è poco: chip, colonne dei widget (`widget_column_*`, stati da 52–56 dp), barra di navigazione, Wear OS. Preferire abbreviazioni d’uso comune nella lingua.
- Tono: interfaccia di un’app di trasporti, frasi brevi, imperativo cortese dove l’italiano usa l’imperativo, nessun registro colloquiale.
- «Programmato» (`service_status_scheduled_*`, `service_scheduled_lowercase`, `widget_status_scheduled`) vuol dire «solo orario, nessun dato in tempo reale» e non deve mai leggersi come «in orario»: niente 定時/時刻表どおり, «По расписанию», 按计划, ตามตาราง o «Theo lịch», che sui tabelloni significano puntuale. Allo stesso modo `freshness_aging` è «dati non recenti», non «in aggiornamento».
- Le durate compatte delle schede (`duration_hours_minutes`, `duration_hours`, `duration_minutes`: «4h 25min» in italiano come nel render) usano le abbreviazioni d’uso della lingua («4 ч 25 мин», «4時間25分», «4 घं 25 मि»), invariabili, quindi senza plurali; le date passano da `LocalizedDates`, che per cinese, giapponese e coreano mette il mese prima del giorno.
- I descrittori delle fonti (`provider_descriptor_*`, per esempio «orario programmato», «Quadro orario programmato») si traducono; i nomi di operatori e servizi intorno a loro (Trenitalia, RFI, ViaggiaTreno, Italo in Viaggio) restano dati.

## Testo da destra a sinistra (arabo)

- `android:supportsRtl="true"` è attivo nell’app e in Wear; layout e widget usano solo `start`/`end`, le icone direzionali sono quelle `AutoMirrored` e la linea della timeline del dettaglio corsa viene disegnata dal lato iniziale.
- Le tratte e gli intervalli d’orario composti nel codice («Roma → Torino», «17:40 → 22:05») vengono mostrati come un’unità da sinistra a destra isolata (U+2066 … U+2069, `BidiText.ltrUnit`): in arabo restano leggibili nell’ordine originale, con la freccia in avanti, e il paragrafo resta allineato a destra. Senza isolamento i soli orari verrebbero invertiti («22:05 → 17:40»).
- Nelle stringhe arabe la freccia fra due **parole arabe** (percorsi di menu come «Per te → I miei treni») è `←`, che in un paragrafo da destra a sinistra punta nella direzione di lettura; fra due segnaposto di stazione resta `→`, perché i nomi italiani formano comunque un’unità da sinistra a destra.

## Caratteri

L’app non include caratteri propri: Compose e i widget usano le famiglie di sistema (`sans`, `monospace`), che su Android hanno il fallback per arabo, devanagari, thai, birmano, hangul, kana e ideogrammi CJK. `FontScriptCoverageTest` impedisce di aggiungere un carattere personalizzato senza rivedere questa scelta. Nel selettore della lingua ogni nome porta la propria lingua (`TextStyle.localeList`), così cinese e giapponese usano le varianti di glifo corrette.

## Verifiche automatiche

- `AndroidStringResourceParityTest` (app) e `WearStringResourceParityTest` (Wear): per tutte le 17 lingue stesse chiavi dell’italiano, stessi attributi, stessi segnaposto, quantità plurali CLDR esatte, nessun testo identico all’italiano fuori dalle liste consentite, nessun BOM o carattere invisibile, `locale_config.xml` uguale all’elenco delle lingue e cartelle presenti per ogni tag.
- `AppLanguagesTest`: il selettore offre esattamente le lingue di `locale_config.xml`, ognuna con il proprio nome.
- `WidgetStringsResourceTest`: i testi dei widget di ogni lingua si compongono senza segnaposto residui.
- `DesignScreenshotTest` (su richiesta, emulatore): schermate `19-pianifica-ar`, `20-risultati-ar` e `21-pianifica-ja` con la lingua forzata nel test.

## Limiti noti

- **Cifre**: in arabo, hindi e birmano i numeri formattati dall’app con `%d` seguono le cifre della lingua (per esempio ٤ in arabo), mentre gli orari che arrivano dalle fonti restano in cifre occidentali. È il comportamento standard di Android; non è stato forzato un sistema di numerazione.
- **Dettatura**: il riconoscitore vocale di sistema trascrive nella lingua dell’interfaccia, ma `PlannerVoiceParser` conosce le parole per giorno, ora e andata/ritorno solo in italiano, inglese, tedesco, francese e spagnolo. Nelle altre dodici lingue non si indovina nulla (`PlannerVoiceParser.offers`): «Detta tratta» non è offerto e una tratta dettata non è mai applicata; «Detta stazione» resta nelle lingue in caratteri latini (portoghese, turco, vietnamita, indonesiano) e non è offerto in arabo, hindi, giapponese, coreano, birmano, russo, thailandese e cinese, dove il riconoscitore scrive i nomi delle stazioni nella scrittura della lingua; «Detta numero treno» funziona ovunque, con le cifre di qualsiasi scrittura. `PlannerVoiceLanguagesTest` dà al parser la frase d’esempio di ogni lingua.
- Testi composti dal view model attraverso il contesto dell’applicazione seguono la lingua attiva dell’app; l’override di `DesignScreenshotTest` cambia solo la composizione, quindi in quelle schermate alcuni testi possono restare nella lingua dell’emulatore.

## Glossario

Termini chiave così come compaiono nelle risorse dell’app (`values*/strings.xml`), per tenere coerenti le nuove stringhe. Se un termine cambia, si aggiorna prima la risorsa e poi questa tabella.

| Italiano | Inglese | `ar` | `hi` | `id` | `ja` | `ko` | `my` |
|---|---|---|---|---|---|---|---|
| Partenza | Departure | المغادرة | प्रस्थान | Keberangkatan | 出発 | 출발 | ထွက်ခွာ |
| Arrivo | Arrival | الوصول | आगमन | Kedatangan | 到着 | 도착 | ဆိုက်ရောက် |
| Binario | Platform | الرصيف | प्लेटफ़ॉर्म | Peron | 番線 | 승강장 | ပလက်ဖောင်း |
| Cambio a … | Change at … | تبديل القطار في … | … पर ट्रेन बदलें | Transit di … | … で乗り換え | …에서 환승 | … တွင် ရထားပြောင်း |
| Diretto | Direct | مباشر | सीधी | Langsung | 乗り換えなし | 직통 | တိုက်ရိုက် |
| In ritardo | Delayed | متأخر | देरी से | Terlambat | 遅れ | 지연 | နောက်ကျ |
| In orario | On time | في الموعد | समय पर | Tepat waktu | 定刻 | 정시 | အချိန်မှန် |
| Soppresso | Cancelled | ملغى | रद्द | Dibatalkan | 運休 | 운행 취소 | ဖျက်သိမ်းပြီး |
| Orario programmato | Scheduled timetable | المواعيد المجدولة | निर्धारित समय-सारणी | Jadwal | 時刻表 | 예정 시간표 | အချိန်ဇယား |
| Tratta | Route | مسار | मार्ग | Rute | 区間 | 구간 | လမ်းကြောင်း |
| Viaggio | Journey | رحلة | यात्रा | Perjalanan | 経路 | 여정 | ခရီးစဉ် |
| Treno | Train | قطار | ट्रेन | Kereta | 列車 | 열차 | ရထား |
| Stazione | Station | المحطة | स्टेशन | Stasiun | 駅 | 역 | ဘူတာ |
| Fermata | Stop | المحطة | ठहराव | Pemberhentian | 停車駅 | 정차역 | ရပ်နားဘူတာ |
| Coincidenze all’arrivo | Connections at arrival | القطارات الموصلة عند الوصول | आगमन पर कनेक्शन | Sambungan saat tiba | 到着後の接続 | 도착 후 연결 열차 | ဆိုက်ရောက်ပြီးနောက် ဆက်စီးရမည့် ရထားများ |
| Sciopero | Strike | إضراب | हड़ताल | Mogok | スト | 파업 | သပိတ် |
| Promemoria | Reminders | التذكيرات | रिमाइंडर | Pengingat | リマインダー | 알림 | သတိပေးချက်များ |
| Sveglia | Alarm | منبّه | अलार्म | Alarm | アラーム | 알람 | နှိုးစက် |
| Pianifica | Plan | تخطيط | योजना | Rencana | 経路検索 | 계획 | စီစဉ်ရန် |
| Partenze | Departures | المغادرات | प्रस्थान | Keberangkatan | 発車案内 | 출발 | ထွက်ခွာမှု |
| Per te | For you | لك | आपके लिए | Untuk Anda | マイページ | 마이 | သင့်အတွက် |
| Impostazioni | Settings | الإعدادات | सेटिंग | Setelan | 設定 | 설정 | ဆက်တင်များ |
| Fonte | Source | المصدر | स्रोत | Sumber | 情報源 | 출처 | ရင်းမြစ် |

| Italiano | Inglese | `pt` | `ru` | `th` | `tr` | `vi` | `zh-CN` |
|---|---|---|---|---|---|---|---|
| Partenza | Departure | Partida | Отправление | ต้นทาง | Kalkış | Ga đi | 出发 |
| Arrivo | Arrival | Chegada | Прибытие | ปลายทาง | Varış | Ga đến | 到达 |
| Binario | Platform | Plataforma | Путь | ชานชาลา | Peron | Sân ga | 站台 |
| Cambio a … | Change at … | Baldeação em … | Пересадка в … | เปลี่ยนขบวนที่ … | Aktarma: … | Đổi tàu tại … | 在 … 换乘 |
| Diretto | Direct | Direto | Прямой | เที่ยวตรง | Aktarmasız | Đi thẳng | 直达 |
| In ritardo | Delayed | Atrasado | Опаздывает | ล่าช้า | Gecikmeli | Trễ | 晚点 |
| In orario | On time | No horário | Вовремя | ตรงเวลา | Zamanında | Đúng giờ | 准点 |
| Soppresso | Cancelled | Cancelado | Отменён | ยกเลิก | İptal edildi | Đã hủy | 停运 |
| Orario programmato | Scheduled timetable | Horário programado | Плановое расписание | ตารางเวลาตามแผน | Planlanan tarife | Lịch tàu theo kế hoạch | 计划时刻表 |
| Tratta | Route | Trajeto | Маршрут | เส้นทาง | Güzergâh | Tuyến | 路线 |
| Viaggio | Journey | Viagem | Поездка | การเดินทาง | Yolculuk | Hành trình | 行程 |
| Treno | Train | Trem | Поезд | ขบวนรถ | Tren | Tàu | 车次 |
| Stazione | Station | Estação | Станция | สถานี | İstasyon | Ga | 车站 |
| Fermata | Stop | Parada | Остановка | จุดจอด | Durak | Điểm dừng | 停靠站 |
| Coincidenze all’arrivo | Connections at arrival | Conexões na chegada | Пересадки по прибытии | ขบวนต่อเมื่อถึงปลายทาง | Varıştaki bağlantılar | Chuyến nối tiếp khi đến | 到达后的接续车次 |
| Sciopero | Strike | Greve | Забастовка | นัดหยุดงาน | Grev | Đình công | 罢工 |
| Promemoria | Reminders | Lembretes | Напоминания | การเตือน | Hatırlatmalar | Lời nhắc | 提醒 |
| Sveglia | Alarm | Alarme | Будильник | ปลุก | Alarm | Báo thức | 闹钟 |
| Pianifica | Plan | Planejar | Поиск | วางแผน | Planla | Lên kế hoạch | 规划 |
| Partenze | Departures | Partidas | Отправления | ขบวนออก | Kalkışlar | Tàu đi | 出发 |
| Per te | For you | Para você | Для вас | สำหรับคุณ | Sana özel | Dành cho bạn | 我的 |
| Impostazioni | Settings | Configurações | Настройки | การตั้งค่า | Ayarlar | Cài đặt | 设置 |
| Fonte | Source | Fonte dos dados | Источник | แหล่งข้อมูล | Kaynak | Nguồn | 来源 |
