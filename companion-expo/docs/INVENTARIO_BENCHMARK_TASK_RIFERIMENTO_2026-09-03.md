# Inventario benchmark del task di riferimento

> **Fonte interna.** Task di riferimento `LntDGCMb6rdvvCgyX6NJaw`, indicato dall’utente. L’inventario separa artefatti pertinenti da sorgenti, APK e file tecnici non necessari al confronto di prodotto.

## Artefatti prioritari da leggere

| Gruppo | File prioritari | Uso previsto |
| --- | --- | --- |
| Benchmark e roadmap | `benchmark_completo_app_ferroviarie_per_binario.md`, `matrice_funzioni_app_ferroviarie_v2.csv`, `BENCHMARK_APP_FERROVIARIA_E_ROADMAP.md`, `BENCHMARK_GAP_V14.md` | Distinguere funzioni già presenti, miglioramenti locali e prerequisiti esterni. |
| UX/UI e accessibilità | `DESIGN_SYSTEM_FERROVIARIO.md`, `BENCHMARK_UX_UI_LAYOUT.md`, `evidenze_visive_prioritarie.md`, `matrice_ux_ui_app.csv`, `MOOVIT_XAPK_ANALYSIS_5.197.1.1801.md` | Definire gerarchia, densità, microcopy, navigazione e principi accessibili. |
| Riferimenti visivi | `01_home_ricerca_riferimento.png`, `02_tabellone_stazione.png`, `03_dettaglio_treno_tema_scuro.png`, `layout_schermate_core.png`, `palette_prioritarie_estratte.png`, screenshot Binario e OnTheRails | Ispirazione di pattern, non copia di asset, marchi o composizioni. |
| Qualità piattaforma | `VERIFICA-v0.20.3.md`, `BENCHMARK-Wear-OS-IDFM.md`, `ROADMAP-SMARTWATCH.md`, `matrice_conformita_pubblicazione_e_roadmap_android_ios_binario.md` | Separare incrementi software dai collaudi Android/iOS/Wear reali. |
| Dati e sicurezza | `CATALOGO_FONTI_DATI_FERROVIARI.md`, `verifica_licenze_fonti_live.md`, `verification_matrix.md`, `SOURCE_POLICY.md` | Mantenere il blocco su endpoint non autorizzati e i fallback espliciti. |

## Principi già confermati

Il materiale conferma come pattern trasferibili la ricerca immediata, la memoria personale di tratte/stazioni/corse, una schermata tabellone separata, una timeline di dettaglio e widget orientati alla routine. Conferma anche che ritardi, binari, soppressioni, timeline live, allarmi di servizio e monitoraggio di corse richiedono una fonte autorizzata, timestamp, TTL e una resa esplicita dell’indisponibilità.

I successivi incrementi locali devono privilegiare: tratta principale fissabile, riordino esplicito, preferenze di densità/formato, microcopy progressivo, semantica stabile tra Piano viaggio/Tabellone/Dettaglio e accessibilità con icona più testo. Non devono introdurre tracking continuo, WebView di acquisto, mappe invasive, stati live fittizi o accesso diretto a provider non autorizzati.

## Confronto con le matrici storiche

Le matrici confermano come ancora trasferibili l’ordinamento manuale delle tratte personali, il fissaggio di una tratta principale, la ricerca diretta per treno e stazione, la cronologia locale cancellabile, etichette delle tratte frequenti, controlli data/ora e preferenze di densità. Nel progetto attuale sono già presenti planner locale, inversione, ricorrenze esplicite, checklist, import/export, preferiti/recenti stazioni, tabellone offline, snapshot widget, posizione consensuale e stati non realtime; tali elementi non vanno reimplementati.

Restano vincolati a fonti autorizzate: arrivi/partenze live, ritardi, binari, soppressioni, avvisi ferroviari, timeline reale e allarmi derivati. Restano vincolati a test su dispositivo reale: TalkBack, font scale/reflow fisico, Wear, consumo energetico e qualsiasi uso di posizione in background. La documentazione storica sulla conformità e sulle piattaforme è utile per il backlog, ma non è prova di conformità o di build iOS/Store della versione corrente.

## Evidenze dalle analisi di app ferroviarie

I dieci confronti di Orario Treni, OnTheRails, Trenitalia, Italo, Trenord, TrenScan, Omio, Moovit, Île-de-France Mobilités e Trainline convergono su una Home focalizzata sulla tratta quotidiana, tabellone stazione distinto, ricerca rapida, preferiti/recents locali, planner origine-destinazione con inversione, timeline di dettaglio e widget snello. Gli elementi aggiuntivi utili senza provider esterni sono: etichette personali per tratte frequenti, una singola tratta fissata, riordino esplicito, stato UI coerente (`scheduled`, `unavailable`, `stale` solo se esiste un dato cache), preferenze locali di densità/formato orario, righe compatte e microcopy con icona più testo.

Non sono trasferibili senza fonti autorizzate e timestamp: ritardi, anticipo, binari, soppressioni, arrivi/partenze, confidenza, affollamento, alternative reali, scioperi e avvisi del gestore. Non sono nel perimetro local-first: ticketing, prezzi, account operatore, NFC, SDK analytics, pubblicità, geofencing, tracking in background e notifiche basate su un monitoraggio live inesistente. I riferimenti storici a Room, Compose e KMP descrivono implementazioni precedenti e non sostituiscono l’architettura Expo locale corrente.
