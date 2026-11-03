# Validazione UX/UI sul dispositivo — Binario v0.7.0

La build v0.7.0 supera test unitari, lint e compilazione. Questa checklist serve a verificare sul dispositivo reale i comportamenti che non possono essere simulati affidabilmente nel sandbox: densità percepita, font scalabile, TalkBack e gesti di sistema.

| Scenario | Risultato atteso |
|---|---|
| Prima apertura, schermata Oggi | Tratta attiva, prossima corsa e almeno una corsa successiva sono visibili senza una sequenza di card introduttive. |
| Cambio direzione | Il comando aggiorna origine/destinazione e corse mantenendo la schermata Oggi. |
| Aggiornamento | Il comando è raggiungibile dalla barra superiore e dalla hero; lo stato non-live resta esplicito. |
| Navigazione | Oggi, Cerca e Tratte sono riconoscibili e la destinazione selezionata è evidente. |
| Ricerca | Numero treno e filtro operatore non cambiano le corse mostrate nella schermata Oggi. |
| Tratte | Aggiunta tramite sheet, fissaggio, selezione e eliminazione non richiedono scrolling attraverso un form permanente. |
| Dettaglio | Orario previsto, stato, binario e prime fermate sono comprensibili immediatamente. |
| Font 200% | Nessun pulsante essenziale, orario, destinazione, binario o stato sparisce o si sovrappone. |
| TalkBack | Ogni corsa viene annunciata nell’ordine: destinazione, orario, stato, binario, operatore. |
| Dark mode | Gli stati regolare, attenzione, soppresso e non-live restano leggibili anche senza affidarsi al solo colore. |

> La validazione sul telefono non deve giudicare la qualità dei dati demo come se fosse un feed live. Per questa build il comportamento corretto è dichiarare chiaramente **“Catalogo dimostrativo locale — non live”**.
