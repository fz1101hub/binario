# Binario 2.0 — sistema UI/UX multipiattaforma

**Stato:** proposta di prodotto del 25 ottobre 2026, in applicazione al codice. **Non è una release pubblicata**.

> **Stato sul branch `feat/ux2-clients` (31 ottobre 2026).** Token applicati a Wear OS, Apple Watch, iPhone e dashboard del companion Alexa; schermate Echo Show (APL) implementate nella skill ma attive solo dopo che il titolare abilita l’interfaccia APL ([azione del titolare](../alexa-companion/docs/ALEXA_SETUP.md#schermate-echo-show-apl--azione-del-titolare)). Le fasi Android 1–3 (app e widget) sono **in applicazione** sul branch `feat/ux2-android`, sviluppato in parallelo: le righe Android di questo documento descrivono quel lavoro come in corso e vanno finalizzate dall’integratore al merge dei due branch. Le verifiche Swift e il rendering APL sui dispositivi sono, per ora, solo statici.

## Visione e rispetto del prodotto attuale

Binario resta un assistente personale di consultazione ferroviaria, senza biglietteria, pubblicità, tracciamento continuo o account obbligatorio nell'app Android. La home resta **planner-first**: «Dove andiamo?»; le partenze abituali sono a un solo passaggio. Non replicare layout, icone, loghi, componenti o testo dell'app usata come riferimento cromatico.

Il verde della schermata condivisa dall'utente è il riferimento visivo, non un'interfaccia da copiare. Campionamento indicativo: barra principale `#388E3C`, variante scura `#2E7D32`.

## Token condivisi

| Ruolo | Chiaro | Scuro | Vincolo |
|---|---|---|---|
| Primary interattivo | `#2E7D32` | `#9BDD9E` | Contrasto migliore sul bianco rispetto al verde chiaro di riferimento |
| Verde di riferimento | `#388E3C` | `#9BDD9E` | Accent/identità, mai unico segnale di stato |
| Primary container | `#DDF0DE` | `#205B2A` | Chip, selezioni, hero leggeri |
| Sfondo | `#F7F8F5` | `#0D1710` | Lettura continua |
| Surface | `#FFFFFF` | `#142018` | Form e contenuti |
| Attenzione | `#99571B` | `#FFC489` | Solo variazioni corroborate da fonte non-demo |
| Errore | `#B3261E` | `#FFB4AB` | Errori e soppressioni documentate |

**Regola accessibilità:** `#388E3C` con testo bianco normale può non soddisfare WCAG AA (contrasto circa 4,12:1); usare `#2E7D32` per pulsanti, barre con etichette piccole e grafici informativi. Target minimo 48 dp su Android, font 200%, TalkBack/VoiceOver, stati con testo oltre al colore.

I token sono definiti **una volta per superficie** e i componenti li leggono da lì, senza colori RGB nelle viste:

| Superficie | Definizione dei token | Stato |
|---|---|---|
| Android app e widget | `app/src/main/java/it/binario/app/Theme.kt`, `app/src/main/res/values/colors.xml` | In applicazione su `feat/ux2-android` |
| Wear OS | `wear/src/main/java/it/binario/wear/WearTheme.kt` (`BinarioWearPalette` + `ColorScheme` Material 3 for Wear, colonna scura, sfondo nero); Tile e anteprime usano la stessa palette | Applicato |
| Apple Watch | `apple-watch/BinarioWatch/Assets.xcassets` (`AccentColor` tinta globale, `BrandGreen`, `StatusWarning`, `StatusError`, valori scuri) letti da `BinarioPalette.swift` | Applicato, compilazione in CI |
| iPhone | `ios/Resources/Assets.xcassets` (`AccentColor` tinta globale, `OnAccentColor`, `BrandGreen`, `StatusError`, chiaro e scuro) letti da `BinarioPalette.swift` | Applicato, compilazione in CI |
| Dashboard companion Alexa | `alexa-companion/client/src/index.css` (variabili `--brand-*` mappate sui ruoli shadcn) | Applicato |
| Echo Show | `SCREEN_COLORS` in `alexa-companion/server/voice/presentation.ts` (risorse APL chiare e scure) | Implementato, attivo dopo abilitazione APL |

Mantenere i token convergenti senza introdurre asset di terzi.

## Architettura di navigazione prevista (fase successiva)

- **Pianifica:** Viaggio / N. treno / Stazione; partenza-arrivo, inversione, data/ora, andata-ritorno, dettatura, filtri collassabili, CTA «Cerca soluzioni»; recenti e preferite subito sotto.
- **Partenze:** tratta attiva, cambio direzione, accesso alle corse, orario tabulare, destinazione, categoria/numero e binario; riga compatta e dettaglio contestuale.
- **Per te:** tratte fissate, preferiti, viaggi attivi, sveglie, monitoraggi, tema, dati e privacy, esportazione locale, connessioni/integrazioni.

**Nota di stato:** sul branch di base la barra Android è ancora `Cerca / Consulta / Impostazioni` (più `Agenda`). La nuova architettura `Pianifica / Partenze / Per te`, la testata «Dove andiamo?» e la qualità del dato sopra la lista di partenze sono **in applicazione** su `feat/ux2-android`; l’integratore aggiornerà questa nota quando quel branch sarà unito.

## Layout per superficie

| Superficie | Layout | Informazione dominante | Implementazione |
|---|---|---|---|
| Smartphone Android | Una colonna, bottom navigation | Ricerca → soluzioni → dettaglio | Fasi 1–2 in applicazione su `feat/ux2-android` |
| Tablet portrait | Planner con scorciatoie laterali / rail | Confronto percorsi e preferiti | Fase 3, in applicazione su `feat/ux2-android` |
| Tablet landscape | Due o tre pannelli, elenco+detail | Più corse e timeline | Fase 3, in applicazione su `feat/ux2-android` |
| Foldable chiuso | Phone responsive | Pianifica e corsa | Fase 3, in applicazione su `feat/ux2-android` |
| Foldable aperto | Doppio pannello | Elenco e dettaglio persistenti | Fase 3, in applicazione su `feat/ux2-android` |
| Widget Android compatto | Due righe complete | Ora e destinazione | Implementato; palette in applicazione su `feat/ux2-android` |
| Widget Android standard/espanso | Fino a cinque righe, paginazione | Tabellone tratta/stazione/treno | Implementato; palette in applicazione su `feat/ux2-android` |
| Wear OS | Prossimo treno, 2 righe, refresh al telefono | Ora, tratta, qualità dato | Tema verde Material 3 for Wear, Tile e anteprime con gli stessi token; funzionalità esistenti |
| Apple Watch | Tratta, 2 corse, dettaglio, complication | Ora, destinazione, stato cache | Tinta globale e stati dal catalogo asset; funzionalità esistenti; la complication mantiene lo stile del quadrante |
| iPhone | Planner, tratte salvate, impostazioni | Ricerca e risultati programmati | Tinta globale verde chiaro/scuro, pulsante principale e messaggi di errore AA; funzionalità esistenti |
| Dashboard companion Alexa | Web, una o due colonne | Tratte personali e collegamento Alexa | Palette applicata |
| Web / PWA | Layout multipannello | Planner, confronto, timeline | Solo concept, non esiste client ferroviario PWA dedicato |
| Android Auto | Solo attività consentite e template ufficiali | Sicurezza alla guida | Esplorazione futura, nessuna UI Auto implementata |
| Alexa speaker | Conversazione sintetica | Richieste vocali | Companion predisposto ma dipendente dalla configurazione; risposta invariata |
| Alexa Echo Show | APL responsive, touch+voice | Tratte, partenze, dettagli | APL implementata (Home, Partenze, Stato treno, Le mie tratte); attiva solo dopo l’abilitazione dell’interfaccia APL da parte del titolare; rendering su dispositivo da verificare |

## Stati e contenuti obbligatori

1. **Demo:** dicitura esplicita «Catalogo dimostrativo locale — non live». Non inserire countdown («in 3 minuti»), ritardi o binari come fossero reali.
2. **Non disponibile:** «dati non disponibili in tempo reale», senza tabelle operative inventate.
3. **Live con provider autorizzato:** solo dopo integrazione documentata, con provenienza, aggiornamento, TTL, errore e qualità visibili.
4. **Offline/cache:** informazioni limitate, timbro dell'ultimo snapshot; aggiornare non implica necessariamente «dato live».
5. **Account Alexa:** le informazioni personali solo dopo OAuth verificato; nessun token nella UI, nei log o nello snapshot watch.

## UX dei flussi

### Pianifica → Soluzioni → Dettaglio

Le modalità Tratta/Treno/Stazione sono esclusive, i filtri secondari progressivi, la data/ora sempre esplicita e la CTA disabilitata finché la ricerca non è valida. Soluzioni ordinate neutrali per partenza e numero treno. Nel dettaglio: testata con orario, stato e binario; timeline con fermate; pulsanti «Segui viaggio», «Salva» e «Condividi» secondo le capacità reali.

### Pendolare → Partenze

La tratta attiva compare subito; un comando cambia direzione senza perdere la selezione, un altro permette di scegliere la tratta salvata. Evidenziare al massimo una corsa, mantenendo il resto in righe compatte. Non mostrare banner che suggeriscano una puntualità verificata se la fonte è dimostrativa.

### Smartwatch e widget

Coerenza di nomi e stato con la home Android. Su orologi: lettura in pochi secondi, orario grande, numero treno e destinazione, nessuna interazione che richieda scrolling eccessivo. Widget indipendenti per istanza e compatti accessibili anche con ridimensionamento launcher.

## Accettazione prima di sostituire l'interfaccia esistente

- Test Pixel piccolo, telefono grande, tablet portrait/landscape, fold/unfold, Wear OS rotondo, Apple Watch, widget ridimensionato.
- Controlli screen reader, large text 200%, lingua italiana, dark mode e contrasto AA.
- Verifica restore preferenze, deep link esistenti, widget multiistanza e ritorno dal dettaglio.
- Errori visibili se la sincronizzazione non è disponibile; mai inventare partenze o alert.
- Test unitari, build Android e Wear, lint, verifiche Xcode iOS/watchOS e test del companion Alexa, quando disponibili.

## Roadmap

- **Fase 1:** verde condiviso accessibile. **Applicata** su Wear OS, Apple Watch, iPhone e dashboard del companion Alexa (`feat/ux2-clients`); **in applicazione** su app e widget Android, con testata planner e indicatore di origine dati più in alto (`feat/ux2-android`).
- **Fase 2:** estrarre composable da `MainActivity.kt`, nuova navigation `Pianifica/Partenze/Per te`, card hero e dettaglio timeline. In applicazione su `feat/ux2-android`.
- **Fase 3:** adattamento tablet/foldable, polish widget e companion, test visual regression. Parte Android in applicazione su `feat/ux2-android`.
- **Fase 4:** Echo Show APL. **Implementata** nella skill (`feat/ux2-clients`), attiva solo quando il titolare abilita l’interfaccia APL in console (o distribuisce il manifest che la dichiara); verifica con simulatore e hardware ancora da fare. Solo successivamente valutare ulteriori form factor.
- **Dipendenza esterna:** live ferroviario solo da provider documentato/autorizzato.

Vedi [la specifica Echo Show](../alexa-companion/docs/ECHO_SHOW_APL_UI_SPEC.md) e il [prototipo web statico](./binario-echo-preview.html).
