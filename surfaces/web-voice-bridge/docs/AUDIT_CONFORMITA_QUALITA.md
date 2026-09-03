# Audit tecnico di conformità, accessibilità e qualità — Binario

**Data:** 1 settembre 2026. Questo documento è un audit tecnico-operativo, non un parere legale. La conformità effettiva richiede verifica del soggetto che immette il prodotto sul mercato, dei contratti dei fornitori, delle fonti ferroviarie e del modello di business.

## Sintesi esecutiva

La codebase ha recuperato le principali funzioni web reimportabili: dark mode persistente, catalogo stazioni canonico, stazioni vicine calcolate localmente, preferiti/recenti isolati per utente, dettatura nativa per campo e per tratta, revisione obbligatoria, contesto pianificato data/ora e tabellone senza dati inventati. L’audit del 1 settembre ha rilevato dipendenze vulnerabili; dopo remediation puntuale e ricostruzione compatibile del lockfile, `pnpm audit --prod` non riporta vulnerabilità note e la suite conta 76 test superati.

Restano necessari prima di una pubblicazione commerciale: una fonte ferroviaria autorizzata, un processo di retention contrattuale dell’audio, governance privacy, canale di vulnerability disclosure e test manuali di accessibilità con persone e tecnologie assistive.

## Matrice di conformità

| Ambito | Evidenze implementate | Gap / rischio residuo | Remediation e priorità |
|---|---|---|---|
| Accessibilità — WCAG 2.2 AA / EAA | Tema chiaro/scuro, focus visibile, testo e icone nei badge, etichette accessibili, stati non comunicati solo dal colore, test UI su theme/autocomplete/dettatura | Mancano audit con tastiera, screen reader, zoom 200–400%, contrasto misurato e meccanismo di feedback/formalità EAA | P0 prima dell’offerta al pubblico; integrare test manuale e dichiarazione di accessibilità |
| Accessibility Days | Usato come riferimento di buone pratiche e cultura inclusiva | Non è uno standard giuridico né una certificazione | Allineare la checklist a WCAG/EAA; pianificare review con esperti e utenti con disabilità |
| GDPR | Dati stazione personali isolati per utente, recenti con cap, dettatura avviata solo con gesto esplicito, testo rivedibile, nessuna persistenza nel profilo dell’audio/testo | Mancano informativa completa, registro trattamenti, DPA/ruoli del trascrittore, retention verificata e valutazione DPIA basata sul rischio | P0 prima di trattamento reale su scala; definire base giuridica, retention e processo diritti interessati |
| AI Act | Copy esplicito: trascrizione automatica, revisione umana obbligatoria, nessuna decisione automatizzata sul viaggio | Serve confermare classificazione della funzione/fornitore e conservare evidenza dell’informativa accessibile | P1; mantenere disclosure al primo uso e valutare obblighi specifici del provider |
| Cyber Resilience Act | Audit dipendenze di produzione a zero advisory note; aggiornamento Drizzle ORM; input audio base64 validato prima di upload; procedure protette e test no-auth | Mancano SBOM pubblicabile, security policy, processo CVE/disclosure, gestione patch e verifica applicabilità/ruolo CRA | P0 organizzativo; istituire inventario SBOM, owner vulnerabilità e canale `security.txt` |
| Sicurezza applicativa | tRPC protetto, validazione Zod, isolamento per utente, base64 e size limit, fail-closed su Alexa/dati realtime, test no-auth | Mancano rate-limit distribuito, CSP/security headers riesaminati, threat model e pen test prima del go-live | P1 tecnico; introdurre rate-limit nel layer condiviso e verifica OWASP ASVS mirata |
| Qualità e manutenibilità | TypeScript senza errori, 25 file test/76 casi, migrazioni non distruttive, documenti di contratto e fonti, pannello prossimità estratto dalla Home, plugin con peer Vite incompatibile e componenti template non referenziati rimossi | Restano dipendenze dev transitive deprecate di Drizzle Kit e un refactor incrementale della Home | P1; aggiornare Drizzle Kit quando disponibile e continuare a estrarre pannelli Home solo quando aumenta la complessità |
| Prestazioni | Catalogo lazy/cache, recenti limitati, tabellone in chunk lazy (12,06 kB), calcolo prossimità locale, bundle applicativo principale 129,57 kB / 25,19 kB gzip e vendor separati cacheabili | Manca RUM e un budget prestazionale formalizzato | P1; introdurre budget bundle e metriche di campo prima del go-live |
| Qualità del dato ferroviario | Catalogo reale, codici persistiti, stati espliciti di dati non disponibili | Nessun provider live autorizzato; risultati viaggio/tabelloni/avvisi non operativi | P0 di prodotto/legale; usare il Contratto Fonti prima dell’attivazione |

## Evidenze di build e sicurezza

| Controllo | Esito |
|---|---|
| TypeScript | `pnpm check` superato |
| Test | 25 file, 76 test superati |
| Build produzione | Superata; tabellone lazy da 12,06 kB, shell applicativa 129,57 kB minificati / 25,19 kB gzip e vendor cacheabili separati |
| Audit dipendenze produzione | 0 critiche, 0 alte, 0 moderate, 0 basse nella verifica del 1 settembre 2026 |
| Migrazioni | Codici stazione e registro preferiti/recenti applicati senza operazioni distruttive |

## Raccomandazioni tecniche non bloccate

1. Estrarre dalla Home i pannelli Viaggi, Stazioni, Avvisi e Dittatura guidata in componenti dedicati, conservando la logica tRPC centrale.
2. Creare un budget di bundle e RUM; il tabellone è già lazy, la shell applicativa è separata dai vendor e i componenti template non referenziati sono stati rimossi.
3. Aggiungere security contact, SBOM e processo di aggiornamento dipendenze con test e checkpoint, evitando `audit --fix` indiscriminato: l’upgrade automatico a Express 5 ha dimostrato incompatibilità con routing infrastrutturale Express 4. La configurazione pnpm è ora nel workspace supportato; restano da monitorare le dipendenze dev transitive di Drizzle Kit.
4. Creare una checklist accessibilità di release: tastiera, focus, zoom, screen reader, contrasto, errori di form e riduzione movimento.
5. Prima di attivare realtime, implementare adapter server-side, provenance, timestamp, source labels e fallback `unavailable` definiti in `CONTRATTO_FONTI_FERROVIARIE.md`.

## Riferimenti verificati

[1] [Commissione europea — European Accessibility Act](https://commission.europa.eu/strategy-and-policy/policies/justice-and-fundamental-rights/disability/european-accessibility-act-eaa_en)  
[2] [W3C — WCAG 2 Overview](https://www.w3.org/WAI/standards-guidelines/wcag/)  
[3] [AgID — Accessibilità e usabilità](https://www.agid.gov.it/it/ambiti-intervento/accessibilita-usabilita)  
[4] [Commissione europea — FAQ Articolo 50 AI Act](https://digital-strategy.ec.europa.eu/en/faqs/transparency-obligations-under-article-50-ai-act)  
[5] [EUR-Lex — Regolamento (UE) 2024/1689](https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng)  
[6] [Commissione europea — Data protection by design and by default](https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/obligations/what-does-data-protection-design-and-default-mean_en)  
[7] [EDPB — Guidelines 02/2021 on virtual voice assistants](https://www.edpb.europa.eu/documents/guideline/guidelines-022021-on-virtual-voice-assistants_en)  
[8] [EUR-Lex — Regolamento (UE) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng)  
[9] [Commissione europea — Cyber Resilience Act](https://digital-strategy.ec.europa.eu/en/policies/cyber-resilience-act)  
[10] [ENISA — CRA Single Reporting Platform](https://www.enisa.europa.eu/topics/product-security/single-reporting-platform-srp)
