# Lingue supportate — iPhone, Apple Watch, Companion Expo (congelato) e Alexa

Questa pagina copre le superfici non Android del repository: app iPhone (`ios/`), app e widget Apple Watch (`apple-watch/`), Companion Expo (`companion-expo/`, congelato dal 12 novembre 2026 e non distribuito, vedi [companion-expo/README.md](../../companion-expo/README.md); incluse le superfici native widget/Wear che il suo plugin genera), dashboard web e pagine di consenso del companion Alexa (`alexa-companion/client`, `alexa-companion/server/voice/consent.ts`) e skill Alexa (`alexa-companion/skill-package`, risposte vocali e APL in `alexa-companion/server/voice`). L'app Android principale (`app/`) e Wear (`wear/`) sono documentate a parte.

L'italiano resta lingua di sviluppo e ripiego ovunque; inglese, tedesco, francese e spagnolo esistevano già. Le dodici lingue aggiunte sono **scritte a macchina e non ancora riviste da madrelingua**: arabo, hindi, indonesiano, giapponese, coreano, birmano, portoghese (brasiliano neutro), russo, thai, turco, vietnamita e cinese semplificato. Prima di una pubblicazione vanno fatte rileggere da madrelingua, a partire dai testi dello store e delle risposte vocali.

## Codici per piattaforma

| Lingua | iPhone `.lproj` | Watch `.xcstrings` | App Store Connect | Companion JS | Companion Android (risorse / `localeConfig`) | Alexa web | Skill Alexa |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Arabo | `ar` | `ar` | Arabo | `ar` | `values-ar` / `ar` | `ar-SA` | `ar-SA` |
| Hindi | `hi` | `hi` | Hindi | `hi` | `values-hi` / `hi` | `hi-IN` | `hi-IN` |
| Indonesiano | `id` | `id` | Indonesiano | `id` (anche `in`) | `values-in` / `in` | `id-ID` | — |
| Giapponese | `ja` | `ja` | Giapponese | `ja` | `values-ja` / `ja` | `ja-JP` | `ja-JP` |
| Coreano | `ko` | `ko` | Coreano | `ko` | `values-ko` / `ko` | `ko-KR` | — |
| Birmano | `my` | `my` | **non offerto** | `my` | `values-my` / `my` | `my-MM` | — |
| Portoghese | `pt-BR` | `pt-BR` | Portoghese (Brasile) | `pt` | `values-pt` / `pt` | `pt-BR` | `pt-BR` |
| Russo | `ru` | `ru` | Russo | `ru` | `values-ru` / `ru` | `ru-RU` | — |
| Thai | `th` | `th` | Thailandese | `th` | `values-th` / `th` | `th-TH` | — |
| Turco | `tr` | `tr` | Turco | `tr` | `values-tr` / `tr` | `tr-TR` | — |
| Vietnamita | `vi` | `vi` | Vietnamita | `vi` | `values-vi` / `vi` | `vi-VN` | — |
| Cinese semplificato | `zh-Hans` | `zh-Hans` | Cinese (semplificato) | `zh` | `values-b+zh+Hans` / `zh` | `zh-Hans` | — |

Note sui codici:

- **Apple.** `ios/project.yml` non elenca le lingue: XcodeGen ricava `knownRegions` dalle cartelle `.lproj` di `ios/Resources` e il bundle dichiara le localizzazioni presenti. `tests/test_verify_ios_project.py` richiede le diciassette cartelle con le stesse chiavi e gli stessi segnaposto dell'italiano.
- **Portoghese.** Il testo è brasiliano scritto in forma neutra; iOS usa `pt-BR` (un dispositivo `pt-PT` di norma ripiega su un'altra variante della stessa lingua, da verificare su dispositivo), Companion e Android usano `pt`, che copre tutte le varianti, Alexa `pt-BR`.
- **Cinese.** Solo semplificato. Le preferenze tradizionali (`zh-Hant`, `zh-TW`, `zh-HK`, `zh-MO`) non ricevono il cinese semplificato in silenzio: Companion JS e web Alexa passano alla preferenza successiva o all'italiano, e la cartella `values-b+zh+Hans` vale solo per la scrittura semplificata. È senza regione perché `expo-localization` scrive la stessa lista anche in `resourceConfigurations`, dove `zh` conserva le risorse prive di regione.
- **Indonesiano su Android.** La cartella è `values-in`, il codice storico che Android usa per le risorse; il resolver JS accetta sia `id` sia `in`.
- **Plurali.** Il catalogo Watch usa le categorie CLDR di ciascuna lingua (arabo `zero/one/two/few/many/other`, russo `one/few/many/other`, hindi/portoghese/turco `one/other`, le lingue dell'Asia orientale e sudorientale solo `other`), verificate da `tests/test_verify_apple_watch_project.py`. Il Companion seleziona le stesse categorie in `pluralCategory` senza `Intl.PluralRules`, che Hermes può non avere; le cinque lingue originali mantengono `one` solo per 1. I messaggi plurali Alexa accettano le forme aggiuntive (`few`, `many`, …) dove la lingua le usa.
- **Plurali iPhone.** I testi che contano (statistiche di affidabilità, «In orario 27 volte su 30», binario abituale, giorni della settimana) stanno in `Localizable.stringsdict` di ogni `.lproj` con il numero contato come primo argomento (`%1$#@count@`) e le categorie CLDR della lingua (italiano, francese, spagnolo e portoghese `one/many/other`, arabo e russo come sopra, inglese, tedesco, hindi e turco `one/other`, le lingue dell'Asia orientale e sudorientale solo `other`); `Localizable.strings` conserva la forma `other` come ripiego. Verificati da `tests/test_verify_ios_project.py`.
- **Destra-sinistra.** L'arabo è l'unica lingua RTL. SwiftUI rispecchia da sé; il chevron del selettore di stazione usa `chevron.forward`. Il Companion abilita `supportsRTL` di `expo-localization` e `supportsRtl` nel manifest Wear, e i chevron "avanti" si girano. Dashboard e pagine di consenso Alexa impostano `dir="rtl"`; gli schermi Echo Show (APL 1.4, senza `layoutDirection`) allineano il testo a destra.

## Alexa: lingue disponibili e limiti

Le risposte vocali, gli schermi Echo Show e i modelli di interazione esistono solo per i locale offerti da Alexa per le skill personalizzate. Fra le lingue richieste lo sono **`ar-SA`, `hi-IN`, `ja-JP` e `pt-BR`**, aggiunti con modello di interazione (stessi intent e slot di `it-IT`), dati di pubblicazione in `skill.json` (nome, descrizione, frasi d'esempio, parole chiave), paesi di distribuzione SA, IN, JP, BR e risposte localizzate. La documentazione Amazon non era raggiungibile dalla sessione che ha fatto il lavoro: la lista va riverificata nella console Alexa prima della certificazione. Sono pubblicati anche **`en-IN`** ed **`es-US`**, che riusano senza modifiche modelli, testi e dizionari di `en-US` ed `es-ES` (un test mantiene identiche le copie); i paesi IN e US erano già fra quelli di distribuzione. Restano da decidere `en-CA`, `en-AU`, `es-MX` e `fr-CA`, che richiedono i nuovi paesi CA, AU e MX.

Indonesiano, coreano, birmano, russo, thai, turco, vietnamita e cinese **non sono locale delle skill Alexa**: per queste lingue sono tradotte la dashboard web e le pagine di consenso OAuth (che sono pagine web, non risposte di Alexa), mentre la skill resta raggiungibile solo in una lingua che Alexa offre. Per questo i suggerimenti «Prova a chiedere» di queste lingue citano l'invocazione inglese «Binario Trains».

Scelte da verificare in certificazione:

- **Invocazioni.** `it-IT` «binario treni» (due parole, come richiesto per le invocazioni non legate a un marchio registrato; tornare a «binario» richiede la prova del marchio), `ar-SA` «قطارات بيناريو», `hi-IN` «बिनारियो ट्रेन», `ja-JP` «ビナリオトレイン», `pt-BR` «binario trens». Le frasi d'esempio di `skill.json` e i suggerimenti APL contengono l'invocazione esatta (test in `server/voice/alexaSkill.test.ts`).
- **Pronuncia del marchio.** Nelle risposte vocali arabe, hindi e giapponesi il nome è traslitterato (بيناريو, बिनारियो, ビナリオ) perché la sintesi lo pronunci; nei testi a schermo e nei nomi della skill resta «Binario».
- `SUPPORTED_LOCALES` (skill) e `WEB_LOCALES` (web) in `shared/i18n.ts` sono distinti: il test che allinea modelli, `skill.json` e runtime vale solo per i primi.

## App Store

`docs/store/APP_STORE_SUBMISSION.md` contiene nome, sottotitolo, testo promozionale, descrizione, parole chiave e novità in tutte le lingue dell'app accettate da App Store Connect, entro i limiti verificati da `tests/test_app_store_docs.py`. Il **birmano non è una lingua del listing di App Store Connect**: l'app è localizzata in birmano, la scheda no.

## Verifiche

Eseguibili su Linux e già eseguite: `python3 -m unittest discover -s tests`, `python3 tools/verify_ci_pinning.py`, `python3 tools/verify_privacy_policy.py`; nel Companion `vitest run`, `tsc --noEmit`, `verify-branding`, `verify-android-manifest`, `verify-managed-config`, `verify-release-inputs` e due `expo prebuild --platform android --clean` consecutivi senza differenze; nel companion Alexa `vitest run`, `tsc --noEmit` e `pnpm run build`.

Da fare su macOS/dispositivo: build e test Xcode di iPhone e Watch (job *Apple Watch CI*), resa dei testi lunghi (tedesco, russo, birmano, thai) nei layout Watch e nei widget, mirroring arabo su iPhone, Watch, Companion ed Echo Show, build Gradle del Companion con le nuove cartelle di risorse (le voci del Companion Expo, congelato, solo se viene ripreso), riconoscimento delle invocazioni Alexa e lettura da parte di madrelingua.
