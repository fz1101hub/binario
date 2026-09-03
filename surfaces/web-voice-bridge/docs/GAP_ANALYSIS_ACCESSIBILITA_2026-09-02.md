# Gap analysis accessibilità — Binario

**Stato:** valutazione tecnica preliminare, non certificazione né parere legale.  
**Data:** 2 settembre 2026.  
**Perimetro esaminato:** web Binario, companion Expo Android/iOS, sorgenti Wear OS e handoff Apple Watch.  

> **Verdetto:** Binario possiede una base accessibile superiore a un prodotto medio, ma non esistono evidenze sufficienti per dichiarare il livello massimo possibile, la conformità WCAG 2.2 AA end-to-end o la conformità al Decreto Legislativo 82/2022 / European Accessibility Act.

## Perimetro regolatorio

La Direttiva (UE) 2019/882 include, fra gli altri, servizi connessi al trasporto passeggeri ferroviario ed e-commerce. [1] In Italia è stata recepita dal Decreto Legislativo 27 maggio 2022, n. 82; le Linee guida AgID 2026 indicano espressamente servizi di trasporto passeggeri ferroviari, siti web e app mobili nel perimetro di analisi. [2] [3]

L’applicabilità concreta a Binario dipende dal servizio effettivamente messo sul mercato, dal modello economico e dallo status dell’operatore. Non è stata svolta una qualificazione legale dell’impresa, né verificata un’eventuale eccezione applicabile alle microimprese. Il progetto deve quindi adottare **EN 301 549 v3.2.1 e WCAG 2.2 AA** come baseline tecnica prudenziale, senza dedurre da ciò una presunzione di conformità legale. La Commissione sottolinea che la conformità reale richiede il coinvolgimento di persone con disabilità e che gli overlay non sostituiscono la correzione delle barriere nel prodotto. [4]

## Evidenze positive nel codice

| Area | Evidenza osservata | Valutazione |
| --- | --- | --- |
| Semantica web | Label, ruoli, `aria-live`, `aria-expanded`, `listbox` e opzioni dell’autocomplete sono presenti nei flussi principali. | Buona base, da provare con screen reader e tastiera reale. |
| Focus e preferenze | Focus visibile, tema chiaro/scuro, contrasto elevato, testo maggiorato e riduzione movimento persistono nella web app. | Copertura progettuale buona; contrasto e focus non sono misurati su tutti gli stati. |
| Errori e stato dati | Messaggi di stato, errori di permesso, fallback realtime e 404 italiana sono espliciti. | Coerente con criteri di messaggi di stato; da verificare ordine lettura e annunci. |
| Mobile | Il planner usa etichette/accessibility hint, `accessibilityLiveRegion`, picker di sistema e autocomplete con opzioni leggibili. | Base positiva, ma nessuna prova TalkBack/VoiceOver, font scale o tablet. |
| Wear | Esistono stati di attesa, annullamento, retry e TTS configurabile; i dati sono minimizzati e fail-closed. | Non dimostra ergonomia, contrasto, target touch o TTS su hardware. |

## Gap che impediscono una dichiarazione di conformità

| Priorità | Gap | Criteri/obblighi potenzialmente coinvolti | Chiusura richiesta |
| --- | --- | --- | --- |
| **P0** | Nessun audit manuale completo con tastiera, NVDA/Firefox, VoiceOver/Safari, TalkBack/Android, font scale e utenti con disabilità. | WCAG 2.2, EN 301 549; test assistivo richiesto per evidenza credibile. | Matrice di test firmata con esiti, difetti e retest su web/mobile/watch. |
| **P0** | Nessuna dichiarazione accessibilità, canale di feedback/reclamo né processo di gestione delle segnalazioni per un’eventuale offerta nel perimetro. | Informazioni accessibili sul servizio e rimedio/assistenza secondo la disciplina applicabile. | Pagina accessibilità, contatto reale gestito, SLA di triage e registro delle correzioni. |
| **P1** | Il gate `axe` copre shell pubblica e Home, ma non tutte le route, dialoghi, menu, contrasto, nome/ruolo/valore e focus order in stato reale. | WCAG 1.4.3/1.4.11, 2.4.x, 4.1.2/4.1.3. | Estendere la rete automatica e svolgere audit manuale obbligatorio. |
| **P1** | Lo skip link globale è presente, ma non è attestato il comportamento della navigazione laterale compressa con tastiera e screen reader. | WCAG 2.4.1, 2.4.3, 2.4.7, 2.4.11. | Prove Tab/Shift+Tab/Escape su tutte le route e lettori di schermo. |
| **P1** | Il layout mobile non blocca artificialmente la scala carattere e il planner rifluisce, ma non esiste una prova a font scale 100–200%, Dynamic Type, rotazione o tablet. | WCAG2Mobile, EN 301 549 software/mobile. | Test device e correzioni overflow/troncamenti. |
| **P1** | L’attività vocale Wear include annunci e target minimi a livello di codice, ma non sono misurati o testati con TalkBack su un watch reale. | EN 301 549 software, messaggi di stato e operabilità touch. | Test TalkBack/watch di dimensioni rappresentative, timeout e TTS. |
| **P1** | Tile e richiesta vocale Wear non sono testati su schermi 192/227 dp, con TalkBack, font size e diversi input. | EN 301 549 / linee guida piattaforma Wear. | Test device/emulatore con target touch, contrasto, back navigation, TTS e stati timeout. |
| **P1** | WidgetKit/watchOS restano sorgenti di handoff non compilati né verificati in Xcode. | Accessibilità Apple e conformità del binario finale. | Target reali, test VoiceOver/Dynamic Type/Reduce Motion e TestFlight. |
| **P2** | Alcune aree dense della Home possono aumentare il carico cognitivo; l’attuale verifica visiva non misura comprensione o completamento del task. | WCAG 3.2/3.3 e requisiti funzionali di usabilità accessibile. | Test moderati su pianificazione, modifica, privacy, cancellazione e migrazione locale. |
| **P2** | Dittatura e TTS non sono collaudati con lingue, output assistivo e alternative complete nei browser/device supportati. | Alternative di input, messaggi di stato e controllo utente. | Script di test: consenso, rifiuto, annullamento, errore, testo modificabile e fallback manuale. |

### Remediation già applicata dopo la gap analysis

Il 2 settembre 2026 la shell web ha ricevuto un link **«Vai al contenuto principale»**, disponibile al focus, e landmark `navigation`/`main` nominati. Il target del bypass usa `tabIndex={-1}` per ricevere il focus senza entrare nell’ordine tab ordinario. La suite renderizzata verifica il link, l’ancora e i landmark. Questa correzione riduce una barriera concreta ma non sostituisce le prove Tab/Shift+Tab/Escape né l’audit assistivo completo.

È stato inoltre aggiunto `axe-core` come dipendenza **solo di sviluppo** e lo script `pnpm test:a11y`: sulla shell pubblica blocca violazioni automatiche con impatto `critical` o `serious`. Il controllo esclude il contrasto, perché jsdom non calcola in modo affidabile il layout e i colori composti; contrasto, zoom e reflow restano perciò verifiche manuali obbligatorie.

Lo stesso gate WCAG automatizzabile viene eseguito sulla Home ospite in tema chiaro; nell’ultima esecuzione non ha rilevato violazioni `critical` o `serious`. Il risultato indica esclusivamente l’assenza di tali rilievi nel DOM renderizzato e non estende il claim a tutti gli stati, browser, tecnologie assistive o alle verifiche non automatizzabili.

La revisione visiva a **375 × 812 px** mostra che il percorso ospite principale rifluisce senza sovrapposizioni osservabili e conserva testo, campi e azioni leggibili. La pagina resta intenzionalmente lunga; la densità informativa è quindi un tema di usabilità da validare con utenti, non una prova automatica di non conformità. La cattura non equivale a zoom browser, font scale o Dynamic Type.

## Valutazione per superficie

| Superficie | Stato tecnico | Giudizio attuale |
| --- | --- | --- |
| Web | Bypass tastiera, landmark, form/lista semantici, preferenze e gate axe; 114 test funzionali/UI. | **Parzialmente verificato, non certificabile.** |
| Android/iOS Expo | Controlli etichettati, picker, live region, feedback errori, reflow e modalità semplificata; nessuna prova TalkBack/VoiceOver reale. | **Predisposto, non verificato su assistive tech.** |
| Wear OS | Codice compilato con testo, annuncio stati, pulsanti Annulla/Ascolta/Riprova, TTS opzionale e target minimi. | **Predisposto tecnicamente, non conforme dimostrato.** |
| Apple Watch/Widget | Solo handoff di sorgenti. | **Non verificato e non distribuibile.** |
| Alexa | Inattiva; non può essere considerata una superficie accessibile disponibile. | **Fuori da qualsiasi claim di disponibilità.** |

## Protocollo minimo prima di qualunque claim pubblico

1. Definire il perimetro legale del servizio con consulenza qualificata e verificare obblighi di informazioni, feedback e vigilanza applicabili in Italia.
2. Eseguire test WCAG 2.2 AA manuali su tutte le route, includendo tastiera, zoom 200/400%, reflow, contrasto, screen reader e riduzione movimento.
3. Eseguire test mobile Android/iOS con TalkBack e VoiceOver, font scale/Dynamic Type, contrasto, rotazione, switch access e tastiera esterna.
4. Eseguire test Wear su dispositivi/schermi rappresentativi; per Apple solo dopo completamento Xcode/TestFlight.
5. Coinvolgere persone con disabilità in test di task reali e pubblicare una dichiarazione accessibilità veritiera con canale di feedback reale.

## Ordine di remediation proposto

| Fase | Intervento | Evidenza di chiusura |
| --- | --- | --- |
| **A — codice web** | Skip link, landmark `main`, focus order testabile, correzioni semantiche emerse da axe. | Test UI+tastiera e scansione automatica senza violazioni critiche note. |
| **B — robustezza responsiva** | Zoom/reflow, target, contrasto e stato disabilitato su chiaro/scuro/alto contrasto. | Screenshot e checklist a 200/400% su browser rappresentativi. |
| **C — mobile/watch** | Font scale, TalkBack/VoiceOver, annuncio stati Wear, touch target, timeout e TTS. | Matrice device compilata con esiti e regressioni risolte. |
| **D — valutazione umana** | Sessioni di test sui task: pianificare, dettare, modificare, cancellare, importare e leggere stato dati. | Report dei partecipanti, priorità difetti, retest e decisioni di prodotto. |
| **E — governance** | Perimetro legale, dichiarazione, feedback, formazione e ciclo di rilascio. | Informazioni pubbliche accurate e owner responsabile delle segnalazioni. |

## Riferimenti

[1]: https://eur-lex.europa.eu/eli/dir/2019/882/oj "Direttiva (UE) 2019/882 — requisiti di accessibilità per prodotti e servizi"

[2]: https://accessible-eu-centre.ec.europa.eu/content-corner/digital-library/legislative-decree-accessibility-requirements-products-and-services-decreto-legislativo-sui_en?prefLang=it "AccessibleEU — Decreto Legislativo 27 maggio 2022, n. 82"

[3]: https://trasparenza.agid.gov.it/download/10081.html "AgID — Linee guida sull’accessibilità dei servizi, versione 1.0, 2026"

[4]: https://digital-strategy.ec.europa.eu/en/policies/web-accessibility "Commissione europea — Web accessibility"

[5]: https://www.w3.org/TR/WCAG22/ "W3C — WCAG 2.2"
