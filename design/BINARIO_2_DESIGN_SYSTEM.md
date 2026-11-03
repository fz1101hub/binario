# Binario 2.0 — sistema UI/UX multipiattaforma

**Stato:** proposta di prodotto del 25 ottobre 2026, applicata al codice il 31 ottobre 2026. **Non è una release pubblicata** e non è ancora stata verificata su dispositivi reali.

> **Stato al 31 ottobre 2026.** Token applicati a Wear OS, Apple Watch, iPhone e dashboard del companion Alexa; schermate Echo Show (APL) implementate nella skill ma attive solo dopo che il titolare abilita l’interfaccia APL ([azione del titolare](../alexa-companion/docs/ALEXA_SETUP.md#schermate-echo-show-apl--azione-del-titolare)). Le fasi Android 1–3 (app e widget) sono **applicate**: palette e testata del planner, navigazione `Pianifica / Partenze / Per te`, card della prossima partenza, dettaglio a timeline, barra laterale da 600 dp ed elenco più dettaglio da 840 dp. Le verifiche Swift e il rendering APL sui dispositivi sono, per ora, solo statici; le schermate Android sono verificate da test JVM, test strumentati su emulatore e lint in CI, non su telefoni, tablet o pieghevoli reali.

## Visione e rispetto del prodotto attuale

Binario resta un assistente personale di consultazione ferroviaria, senza biglietteria, pubblicità, tracciamento continuo o account obbligatorio nell'app Android. La home resta **planner-first**: «Dove andiamo?»; le partenze abituali sono a un solo passaggio. Non replicare layout, icone, loghi, componenti o testo dell'app usata come riferimento cromatico.

> **Riferimento: il render approvato.** Dal 2 novembre 2026 il riferimento visivo di Binario 2.0 è il **render approvato dal product owner** (tavola «BINARIO 2.0 — Una stessa esperienza, su tutti i tuoi dispositivi»: Pianificazione, Risultati, Partenze, Dettaglio corsa, Le tue tratte, Impostazioni, tablet e pieghevole). Sostituisce il campionamento iniziale (`#388E3C` / `#2E7D32`) e la tabella dei token precedente. Le schermate Android si confrontano con il render tramite `DesignScreenshotTest` (workflow Android CI con `capture_design_screens=true`). Il render resta un riferimento di stile: dove mostra dati non disponibili (cambi, binari, foto di treni) l'app mostra ciò che i dati supportano (vedi «Differenze volute»).

## Token condivisi

Colori misurati sul render (colore dominante per regione) e ruoli dello schema scuro derivati dalle stesse tinte:

| Ruolo | Chiaro | Scuro | Uso e contrasto |
|---|---|---|---|
| Testata e barra laterale | `#003A28` | `#0A1F17` | Testata verde che continua sotto la status bar e sotto la prima card; testo bianco 12,8:1 |
| Sottotitoli e overline sulla testata | `#8FE3C0` | `#8FE3C0` | «Il tuo prossimo viaggio parte da qui.», «TRATTA ATTIVA»: 8,5:1 su `#003A28` |
| Segmento / chip selezionato | `#004A33` | `#7FDBB5` | Testo bianco 10,4:1 (scuro: testo `#0A1F17`, 10,4:1) |
| Voce selezionata della barra laterale | `#00523A` | `#1E4A3A` | Testo bianco 9,3:1 |
| Tab o segmento selezionato sulla testata | `#FCFCFC` | `#C9F2E1` | «Percorso», «Treni»: testo `#003A28` 12,5:1 (scuro `#0A1F17`, 14,1:1); le altre tab `#1E5A44` con testo bianco 8,1:1 |
| Primary (pulsanti pieni) | `#00703E` | `#7FDBB5` | «Cerca soluzioni», «Segui questo viaggio»: bianco 6,2:1 (scuro: `#003826`, 8,0:1) |
| Tonale (pulsanti e pill di stato) | `#D8F4EA` | `#1E4A3A` | «Dettagli», «Nuova tratta», stato: testo `#00513A` 8,1:1 (scuro `#C9F2E1`, 8,2:1) |
| Chip e segmenti non selezionati | `#E2EEEA` | `#233630` | Testo `#1A2E26` 12,1:1 |
| Card e campi | `#FCFCFC` | `#14221C` | Testo `#17241F` 15,6:1, secondario `#3F5249` 8,1:1 |
| Sfondo pagina | `#F4F7F6` | `#0B1511` | Lettura continua |
| Timeline (linea e fermate) | `#12785A` | `#6FD3A8` | 5,3:1 sulle card |
| Attenzione | `#99571B` | `#FFC489` | Solo variazioni corroborate da fonte non-demo, sempre con testo |
| Errore e cambi | `#B3261E` | `#FFB4AB` | Errori, soppressioni e «N cambi» solo se la fonte li pubblica |

**Regola accessibilità:** ogni coppia testo/sfondo elencata raggiunge almeno 4,5:1 (WCAG AA); target minimo 48 dp su Android, font 200% senza altezze fisse per il testo, TalkBack/VoiceOver, stati sempre con testo oltre al colore.

**Nota:** la tabella vale per l'app Android e i suoi widget. Wear OS, Apple Watch, iPhone e il companion Alexa usano ancora la palette precedente (`#2E7D32` / `#9BDD9E`) finché non vengono allineati al render in un intervento dedicato.

I token sono definiti **una volta per superficie** e i componenti li leggono da lì, senza colori RGB nelle viste:

| Superficie | Definizione dei token | Stato |
|---|---|---|
| Android app e widget | `app/src/main/java/it/binario/app/Theme.kt` (`ColorScheme` chiaro e scuro più `BinarioColors` per testata, segmenti, barra laterale e timeline), `app/src/main/res/values/colors.xml` (widget: testata verde, righe chiare) | Applicata secondo il render approvato |
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

**Nota di stato:** la barra Android `Cerca / Consulta / Agenda / Impostazioni` è stata sostituita da `Pianifica / Partenze / Per te` (l’Agenda è una sezione di Per te). La testata «Dove andiamo?» e la qualità del dato sopra la lista di partenze sono applicate.

## Layout per superficie

| Superficie | Layout | Informazione dominante | Implementazione |
|---|---|---|---|
| Smartphone Android | Una colonna, bottom navigation | Ricerca → soluzioni → dettaglio | Fasi 1–2 applicate |
| Tablet portrait | Planner con scorciatoie laterali / rail | Confronto percorsi e preferiti | Fase 3 applicata (larghezza ≥ 600 dp: barra laterale; ≥ 840 dp: elenco e dettaglio) |
| Tablet landscape | Due o tre pannelli, elenco+detail | Più corse e timeline | Fase 3 applicata (larghezza ≥ 600 dp: barra laterale; ≥ 840 dp: elenco e dettaglio) |
| Foldable chiuso | Phone responsive | Pianifica e corsa | Fase 3 applicata (larghezza ≥ 600 dp: barra laterale; ≥ 840 dp: elenco e dettaglio) |
| Foldable aperto | Doppio pannello | Elenco e dettaglio persistenti | Fase 3 applicata (larghezza ≥ 600 dp: barra laterale; ≥ 840 dp: elenco e dettaglio) |
| Widget Android compatto | Due righe complete | Ora e destinazione | Implementato; palette applicata con contrasto AA |
| Widget Android standard/espanso | Fino a cinque righe, paginazione | Tabellone tratta/stazione/treno | Implementato; palette applicata con contrasto AA |
| Wear OS | Prossimo treno, 2 righe, refresh al telefono | Ora, tratta, qualità dato | Tema verde Material 3 for Wear, Tile e anteprime con gli stessi token; funzionalità esistenti |
| Apple Watch | Tratta, 2 corse, dettaglio, complication | Ora, destinazione, stato cache | Tinta globale e stati dal catalogo asset; funzionalità esistenti; la complication mantiene lo stile del quadrante |
| iPhone | Planner, tratte salvate, impostazioni | Ricerca e risultati programmati | Tinta globale verde chiaro/scuro, pulsante principale e messaggi di errore AA; funzionalità esistenti |
| Dashboard companion Alexa | Web, una o due colonne | Tratte personali e collegamento Alexa | Palette applicata |
| Web / PWA | Layout multipannello | Planner, confronto, timeline | Solo concept, non esiste client ferroviario PWA dedicato |
| Android Auto | Solo attività consentite e template ufficiali | Sicurezza alla guida | Esplorazione futura, nessuna UI Auto implementata |
| Alexa speaker | Conversazione sintetica | Richieste vocali | Companion predisposto ma dipendente dalla configurazione; risposta invariata |
| Alexa Echo Show | APL responsive, touch+voice | Tratte, partenze, dettagli | APL implementata (Home, Partenze, Stato treno, Le mie tratte); attiva solo dopo l’abilitazione dell’interfaccia APL da parte del titolare; rendering su dispositivo da verificare |

## Differenze volute rispetto al render (Android)

- **Cambi:** i risultati uniscono per orario di partenza i treni diretti della ricerca (live o catalogo) e i viaggi con cambi calcolati offline sugli orari GTFS inclusi, ciascuno con la propria fonte; i viaggi con cambi esistono solo dove le reti incluse coprono entrambe le stazioni e sono sempre orari programmati. I filtri sono quelli del render: «Tutti», «Diretti», «Meno cambi», «Più veloce», più «Meno a piedi» e, con le statistiche di rete incluse, «Più affidabili»; il pulsante tonale «Filtri» sotto i filtri apre Impostazioni › Preferenze di viaggio, e «Preferisci» sceglie il filtro con cui si aprono i risultati.
- **Segni dell’orario offline:** «Ultimo di oggi» è una `Pill` `tertiaryContainer` con l’icona della luna (`LastOfDayChip`), sotto treno e destinazione, letta anche da TalkBack; «Non ci sono più treni oggi: primo domani alle HH:mm» è una `InfoNoticeCard`. Il simbolo della sedia a rotelle (`WheelchairMark`: `Icons.AutoMirrored.Filled.Accessible`, 18 dp, colore primario, con `contentDescription` propria) compare solo dove gli orari indicano l’accessibilità, mai per un dato mancante. «Resta a bordo» è una riga piccola con l’icona del sedile nella timeline della tratta, fra partenza e arrivo, mai un blocco di cambio.
- **Affidabilità della rete:** una riga piccola `labelMedium` `onSurfaceVariant` con l’icona `QueryStats` sotto i treni della scheda («In orario 27 volte su 30 · ritardo medio 3 min», per i viaggi anche «Coincidenza a … riuscita N volte su M»), mai un colore che dica da solo se un treno è affidabile; TalkBack la legge nella descrizione della scheda con «(statistiche non ufficiali)». Sotto i filtri, quando almeno una scheda ha la riga, la nota «Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al …»; nel dettaglio la scheda «Affidabilità ultimi N giorni» con la stessa nota. Mai su dati dimostrativi.
- **Preferenze con tre stati:** un `FilterChip` per tipo di servizio passa a ogni tocco da indifferente a preferito (spunta) a evitato (divieto) e ritorno; lo stato è letto come `stateDescription`, non solo dall’icona.
- **Binari, ritardi, orari previsti:** con il catalogo dimostrativo restano «Binario —» e l'etichetta «Catalogo dimostrativo locale — non live».
- **Foto del treno:** dove il render mostra una fotografia, l'app mostra la mappa del percorso (MapLibre / OpenFreeMap); nessuna immagine di terzi.
- **Titoli:** Risultati e dettagli usano la forma breve «Roma → Torino» (città del catalogo o rimozione prudente della designazione della stazione principale); restano i nomi completi quando le due stazioni sono nella stessa città, e TalkBack legge sempre i nomi completi.
- **Suggerisci una stazione:** apre nel browser il modulo pubblico delle segnalazioni del progetto su GitHub (nessun indirizzo e-mail di contatto); l'icona «apri all'esterno» sostituisce la freccia.
- **Partenze:** l'interruttore «Treni | Stazioni» resta nella testata perché il tabellone di stazione fa parte della stessa destinazione.

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

- **Fase 1:** verde condiviso accessibile. **Applicata** su Wear OS, Apple Watch, iPhone e dashboard del companion Alexa (`feat/ux2-clients`); **applicata** su app e widget Android, con testata planner e indicatore di origine dati più in alto.
- **Fase 2:** estrarre composable da `MainActivity.kt`, nuova navigation `Pianifica/Partenze/Per te`, card hero e dettaglio timeline. **Applicata** su Android.
- **Fase 3:** adattamento tablet/foldable, polish widget e companion, test visual regression. Parte Android **applicata** secondo il render approvato (barra laterale verde, doppio pannello con mappa del percorso, widget con testata verde); le schermate si catturano con `DesignScreenshotTest` su richiesta (`capture_design_screens`), il confronto con il render resta visivo.
- **Fase 4:** Echo Show APL. **Implementata** nella skill (`feat/ux2-clients`), attiva solo quando il titolare abilita l’interfaccia APL in console (o distribuisce il manifest che la dichiara); verifica con simulatore e hardware ancora da fare. Solo successivamente valutare ulteriori form factor.
- **Dipendenza esterna:** live ferroviario solo da provider documentato/autorizzato.

Vedi [la specifica Echo Show](../alexa-companion/docs/ECHO_SHOW_APL_UI_SPEC.md) e il [prototipo web statico](./binario-echo-preview.html).
