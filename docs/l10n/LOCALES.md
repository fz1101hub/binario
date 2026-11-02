# Lingue dell’app Android e di Wear OS

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

- **Indonesiano in `values-in/`**: Android riscrive il tag `id` nel codice storico `in` prima di cercare le risorse (`ResourcesImpl.adjustLanguageTag`), quindi una cartella `values-id/` non verrebbe mai usata; anche AndroidX usa `values-in/`. Il lint (`LocaleFolder`) accetta `in`. Nel `locale_config.xml` e nel selettore il tag resta quello BCP-47 `id`.
- **Cinese semplificato in `values-zh-rCN/`** con tag `zh-CN`: da Android 7 la corrispondenza delle risorse considera anche la scrittura, quindi la cartella vale anche per `zh-Hans` e `zh-SG`; il cinese tradizionale (`zh-TW`, `zh-HK`) ricade sull’italiano.
- **Portoghese in `values-pt/`**: lessico neutro con preferenza brasiliana (per esempio «trem», «plataforma»), senza forme solo europee.
- **Plurali**: le quantità sono quelle richieste dal lint del progetto (`MissingQuantity`/`UnusedQuantity` con warning trattati come errori), cioè CLDR 42 o successivo. Per questo italiano, francese, spagnolo **e portoghese** hanno anche `many` (milioni, «1 milhão de»), e il russo ha `one/few/many/other`. Ogni voce di un plurale tradotto conserva gli stessi segnaposto della voce italiana `other` (o della voce omonima), anche `one`, `zero` e `two`.
- **Wear OS** usa le stesse cartelle per le sue 24 stringhe; non ha un `locale_config.xml` (Wear OS non offre la scelta della lingua per app) e segue la lingua dell’orologio.

## Regole per i traduttori

- Segnaposto identici e nello stesso numero (`%1$s`, `%2$d`, `%d`); l’ordine nella frase può cambiare usando gli indici.
- Escape XML e Android: `\'` per l’apostrofo ASCII, `\"`, `\n`, `&amp;`, `&lt;`. Nessun BOM, nessun carattere invisibile (spazi a larghezza zero, marcatori di direzione) scritto direttamente nel file.
- Non si traducono: Binario, Trenitalia, Italo, Frecciarossa, Frecciargento, Frecciabianca, Frecce, Intercity, ViaggiaTreno, RFI, Trenord, ItaloBus, FrecciaLink, OpenStreetMap, OpenFreeMap, MIT, Wear OS, Apple Watch, Alexa, GTFS. Nomi di stazioni e testi degli avvisi degli operatori sono dati, non stringhe.
- Testi brevi dove lo spazio è poco: chip, colonne dei widget (`widget_column_*`, stati da 52–56 dp), barra di navigazione, Wear OS. Preferire abbreviazioni d’uso comune nella lingua.
- Tono: interfaccia di un’app di trasporti, frasi brevi, imperativo cortese dove l’italiano usa l’imperativo, nessun registro colloquiale.

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

## Glossario

Termini scelti per le lingue di questo ramo; le altre lingue sono descritte nei rami che le introducono.

| Italiano | Inglese | Arabo (`ar`) | Hindi (`hi`) | Indonesiano (`in`) | Giapponese (`ja`) |
|---|---|---|---|---|---|
| Partenza / Partenze | Departure(s) | المغادرة / المغادرات | प्रस्थान | Keberangkatan | 出発 |
| Arrivo | Arrival | الوصول | आगमन | Kedatangan | 到着 |
| Binario (numero) | Platform | الرصيف | प्लेटफ़ॉर्म | Peron | 番線 |
| Cambio (di treno) | Change | تبديل القطار | ट्रेन बदलना / बदलाव | Transit | 乗り換え |
| Diretto | Direct | مباشر | सीधी | Langsung | 乗り換えなし |
| Ritardo | Delay | تأخير | देरी | Keterlambatan / terlambat | 遅れ |
| In orario | On time | في الموعد | समय पर | Tepat waktu | 定刻 |
| Soppresso / cancellato | Cancelled | ملغى | रद्द | Dibatalkan | 運休 |
| Orario programmato | Scheduled time | الموعد المجدول | निर्धारित समय | Jadwal | 時刻表どおり / 予定時刻 |
| Tempo reale | Live | مباشر | लाइव | Langsung (real-time) | リアルタイム |
| Tratta | Route | المسار | मार्ग | Rute | 区間 |
| Viaggio (con cambi) | Journey | الرحلة | यात्रा | Perjalanan | 経路 |
| Corsa / treno | Train run | الرحلة / القطار | ट्रेन | Perjalanan kereta | 列車 |
| Stazione | Station | المحطة | स्टेशन | Stasiun | 駅 |
| Fermata | Stop | محطة توقف | ठहराव | Pemberhentian | 停車駅 |
| Coincidenza | Connection | الاتصال / القطار التالي | कनेक्शन | Sambungan | 接続 |
| Avvisi | Notices | التنبيهات | सूचनाएँ | Pemberitahuan | お知らせ |
| Sciopero | Strike | إضراب | हड़ताल | Mogok kerja | ストライキ |
| Promemoria | Reminder | تذكير | रिमाइंडर | Pengingat | リマインダー |
| Sveglia | Alarm | منبّه | अलार्म | Alarm | アラーム |
| Pianifica / Partenze / Per te | Plan / Departures / For you | تخطيط / المغادرات / لك | योजना / प्रस्थान / आपके लिए | Rencana / Keberangkatan / Untuk Anda | 検索 / 発車案内 / マイ |
| Impostazioni | Settings | الإعدادات | सेटिंग | Setelan | 設定 |
| Widget | Widget | أداة | विजेट | Widget | ウィジェット |
| Fonte (dei dati) | Source | المصدر | स्रोत | Sumber | 情報源 |
