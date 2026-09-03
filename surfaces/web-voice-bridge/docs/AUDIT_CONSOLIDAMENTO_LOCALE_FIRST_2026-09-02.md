# Audit di consolidamento locale-first — Binario

**Data:** 2 settembre 2026  
**Ambito:** web Binario, companion Expo Android/Wear, sorgenti di handoff iOS/WidgetKit/watchOS, contratti dati, dettatura e rinvii a venditori.

> Questo è un audit tecnico-documentale basato sul codice e sulle verifiche automatiche disponibili nell’ambiente. Non è una certificazione legale, un’attestazione di conformità né un collaudo su dispositivi fisici.

## Esito sintetico

Binario applica ora un modello **locale-first** verificabile: l’utente può pianificare tratte locali senza account; stato programmato e indisponibilità realtime restano separati; widget e watch ricevono un payload ridotto e con scadenza; la dettatura richiede azione e consenso; non è attivo alcun provider ferroviario, venditore o checkout.

| Area | Evidenza realizzata | Stato | Limite o azione residua |
| --- | --- | --- | --- |
| Accesso | Home web ospite con tratte in `localStorage`; planning mobile in `AsyncStorage`. L’accesso è un CTA facoltativo. | Implementato | Definire soltanto in futuro la migrazione opt-in verso un account Binario. |
| Realtime e avvisi | Governance con provider `unconfigured`, kill switch attivo e fallback `unavailable`. | Implementato, fail-closed | Servono autorizzazione, licenza, adapter, test e monitoraggio prima di ogni fonte reale. |
| Widget e watch | Snapshot minimo, TTL/freschezza e soppressione. Wear usa Data Layer, Tile interattivo e microfono di sistema; iOS ha sorgenti e handoff. | Wear compilato; iOS preparato | Test reali Wear e setup/build/firma Apple restano obbligatori. |
| Biglietti | Registro venditori vuoto; gate HTTPS, allowlist host, licenza e link ufficiale verificati. | Implementato, inattivo | Nessun pulsante finché non esiste autorizzazione reale del venditore. |
| Dittatura web | Consenso esplicito, limite 16 MB, validazione, rate limit volatile, revisione del testo; l’audio non è associato al profilo. | Implementato | La ritenzione dell’archivio tecnico sottostante va attestata contrattualmente o eliminata lato storage. |
| Dittatura mobile | Bozza interpretata da testo della tastiera di sistema; nessuna registrazione, upload o account. | Implementato | Validare accessibilità e comportamento della tastiera/dettatura su dispositivi reali. |
| Accessibilità | Focus, controlli nominati, annunci di stato, tema, contrasto elevato e riduzione movimento nel codice. | Parziale | Audit manuale WCAG 2.2 AA con tastiera, screen reader e device reali. |
| Supply chain | SBOM e policy di sicurezza esistenti; test automatici e controlli TypeScript. | Parziale | Designare un contatto di sicurezza realmente gestito, support period, processo patch e disclosure. |

## Evidenze tecniche e remediation applicate

La modalità ospite non tenta di usare la sessione o l’archivio personale: conserva al massimo quaranta tratte nel browser, scarta payload corrotti e non abilita il tabellone senza un codice di stazione selezionato. Le pagine pubbliche di guida, trasparenza e tabellone rimangono consultabili; preferiti e recenti sono invece disabilitati senza sessione per evitare chiamate o persistenza personale nascosta.

Il contratto `officialSellerHandoff` non contiene venditori preconfigurati. Una futura configurazione richiede governance attiva, kill switch disattivato, provider, licenza e link ufficiale già verificati, URL HTTPS senza credenziali, porta, query o frammento e hostname nell’allowlist. Il contratto restituisce solo l’etichetta «Continua sul sito ufficiale di …»; non modella carrello, pagamento, redirect dinamici, login o credenziali del venditore.

La dettatura web è stata resa compatibile con l’uso ospite per non trasformare l’account in un requisito funzionale. Prima di `getUserMedia`, l’interfaccia presenta un dialogo con finalità, revisione umana e limite di retention attestabile. Il server limita il payload, valida formato e MIME, applica una soglia volatile per client con finestra breve, usa un prefisso storage `dictation/ephemeral/` privo di user ID e azzera il buffer in memoria al termine. Questo non prova la cancellazione nell’archivio di oggetti e non deve essere descritto come tale.

Nel companion non è stato aggiunto un registratore camuffato da dettatura. La documentazione Expo definisce `expo-audio` come API di riproduzione e registrazione, non come motore di trascrizione; il flusso nativo riusa quindi la dettatura volontaria della tastiera di sistema e mantiene solo il testo modificabile prima del salvataggio. [1]

Il Tile Wear OS include ora un’azione **Parla a Binario** che apre l’attività di riconoscimento vocale del sistema sul watch; non richiede che l’utente estragga o apra il telefono. Il testo resta in memoria soltanto per la durata della richiesta. Una domanda sulla prossima tratta o su una tratta che coincide con il payload locale può ricevere il riepilogo programmato e ancora fresco, con etichetta non realtime. Per ritardi, binari, cancellazioni e avvisi, il watch inoltra soltanto il testo al telefono attraverso il Data Layer e attende una risposta: nello stato attuale, il telefono restituisce obbligatoriamente il fallback realtime, perché nessun provider è autorizzato. Il timeout o l’assenza di collegamento hanno lo stesso esito fail-closed.

Il contratto degli avvisi delle tratte salvate separa i promemoria locali scelti dall’utente dagli eventi ferroviari. Il promemoria pianificato può essere consegnato come notifica di sistema con la dicitura non realtime; un evento di ritardo, cancellazione, binario o servizio è ammesso soltanto da snapshot realtime con provider, confidence, timestamp e TTL strettamente validi. Un dato che scade esattamente all’istante di verifica è soppresso, allineando web, app e watch.

## Valutazione regolatoria e operativa

| Tema | Rilevanza prudenziale | Stato di Binario | Criterio per un rilascio pubblico |
| --- | --- | --- | --- |
| GDPR | Tratte, note e audio possono costituire dati personali; la protezione dati è un diritto fondamentale nell’UE. [2] | Minimizzazione locale, no account obbligatorio, no audio nel profilo. | Informativa, registro trattamenti, base giuridica, retention attuabile, DPA/sub-responsabili e trasferimenti documentati dal titolare. |
| AI Act | La trascrizione automatica richiede trasparenza sul suo uso; Binario non prende decisioni automatizzate sul viaggio. [3] | Consenso, messaggio di trascrizione, revisione/editabilità e nessuna previsione ferroviaria fabbricata. | Valutazione della catena fornitore/modello, policy di AI literacy, informativa aggiornata e verifica legale della qualificazione d’uso. |
| WCAG 2.2 AA | WCAG 2.2 fornisce criteri testabili e raccomanda l’uso della versione più aggiornata. [4] | Semantica e controlli accessibili inclusi nei test UI; tema, contrasto e motion preference presenti. | Verifica manuale completa: tastiera, focus non oscurato, target touch, contrasto, zoom/testo, TalkBack e VoiceOver. |
| Cyber Resilience Act | Il CRA disciplina requisiti orizzontali di cybersecurity per prodotti con elementi digitali. [5] | Fail-closed, SBOM, test di input, no segreti client, policy senza contatto fittizio. | Valutazione di applicabilità con consulenza competente, gestione vulnerabilità, contatto disclosure reale, support period, evidenze secure development e procedure incidenti. |
| Store e piattaforme | Android/iOS richiedono asset, signing, dichiarazioni privacy e test sulle piattaforme effettive. | Android buildabile localmente; iOS non firmato/non compilato. | Test dispositivo, firma e metadati store reali; nessuna dichiarazione non dimostrabile nelle privacy label. |

## Blocchi di rilascio non sostituibili con codice

L’attivazione di fonti ferroviarie, tracking o acquisto richiede verifiche contrattuali e di licenza non inferibili da URL pubblici. L’integrazione di account degli operatori, se mai valutata, deve essere un consenso distinto dall’account Binario, con autorizzazione del provider, valutazione privacy e assenza di raccolta di password/OTP nel prodotto.

Nessun test automatico può sostituire l’accesso a macOS/Xcode e a un Apple Developer Team per target, App Group, firma, WidgetKit e Apple Watch. Analogamente, l’APK necessita ancora installazione e test su emulatore/telefono e Wear OS reali; l’audit non attesta l’esperienza su hardware né la pubblicazione negli store.

## Residuo dependency audit del companion

Il controllo `pnpm audit --prod` del 2 settembre 2026 segnala due advisory ad alta gravità per `image-size` `<= 2.0.2`: **GHSA-w3rx-r6r6-pgpr** e **GHSA-5p2g-fcmc-qvqq**. L’albero effettivo mostra un’unica versione transitiva tramite `expo` → `@expo/cli` → Metro; l’audit non indica una versione corretta disponibile. La web app, nello stesso controllo, non segnala vulnerabilità note in produzione.

Binario non carica immagini fornite dagli utenti nel companion e `image-size` non è una dipendenza applicativa richiamata dal codice del prodotto. Questo riduce il percorso di esposizione, ma non elimina il rischio di supply chain nella toolchain. Non è stata applicata una resolution arbitraria né `audit --fix`, perché potrebbe rompere l’allineamento Expo/Metro. Prima di una release pubblica va rieseguito l’audit dopo ogni aggiornamento Expo e va adottato il primo upgrade supportato che risolva gli advisory.

## Riferimenti

[1]: https://docs.expo.dev/versions/latest/sdk/audio/ "Expo Audio — registrazione e riproduzione"
[2]: https://commission.europa.eu/law/law-topic/data-protection/data-protection-eu_en "Commissione europea — quadro di protezione dati UE"
[3]: https://digital-strategy.ec.europa.eu/en/policies/regulatory-framework-ai "Commissione europea — AI Act"
[4]: https://www.w3.org/TR/WCAG22/ "W3C — Web Content Accessibility Guidelines 2.2"
[5]: https://eur-lex.europa.eu/eli/reg/2024/2847/oj/eng "EUR-Lex — Regolamento (UE) 2024/2847, Cyber Resilience Act"
