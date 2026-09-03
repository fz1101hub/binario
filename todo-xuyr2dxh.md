# Project TODO — miglioramenti benchmark locali

- [x] Recuperare in una worktree isolata la base Git del companion e verificare stato e diff puliti.
- [ ] Ripristinare le risorse Wear assenti ma referenziate dal manifest, quindi riportare verde la baseline Android/Wear prima degli incrementi funzionali.
- [x] Inventariare le capacità effettive del ramo v0.20.3 rispetto al benchmark, includendo persistenza, widget, Wear e componenti Apple.
- [x] Confrontare i flussi utili sperimentati nella precedente versione Binario su Git con il benchmark esterno, ricavandone requisiti senza importare o copiare codice.
- [x] Separare e documentare le proposte già presenti, implementabili solo in locale, subordinate alla scelta UI e bloccate da provider, hardware o governance.
- [x] Verificare i percorsi di rete esistenti e mantenere esclusi provider non autorizzati, scraping, endpoint privati e stati realtime non dimostrabili.
- [x] Rimuovere dall’esecuzione della base Git storica gli adapter ViaggiaTreno/RFI/Italo non autorizzati, lasciando esclusivamente il fallback locale programmato e l’indisponibilità esplicita.
- [x] Implementare soltanto i miglioramenti locali realmente assenti e non dipendenti dal redesign: priorità personale della tratta, ordinamento esplicito e denominazione ferroviaria limitata con migrazione retrocompatibile.
- [x] Estendere test unitari e di persistenza per ogni metadato personale, migrazione, limite e comportamento fail-closed introdotto.
- [ ] Eseguire test Android/Wear, build di debug e verifica delle modifiche prima del commit isolato.
- [ ] Aggiornare la documentazione del benchmark e creare un commit Git ristretto alle funzionalità effettivamente completate.
- [ ] Applicare densità visiva, preferenza del formato orario e microcopy soltanto dopo la scelta esplicita del mockup da parte dell’utente.
