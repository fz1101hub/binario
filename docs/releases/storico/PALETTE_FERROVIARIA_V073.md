# Palette ferroviaria — Binario v0.7.3

La palette v0.7.3 prende ispirazione dal contrasto sobrio e dalla leggibilità dei classici tabelloni ferroviari e delle app di consultazione rapide, senza riprodurre colori, asset o interfacce proprietarie di Orario Treni o On The Rails.

| Ruolo | Chiaro | Scuro | Uso |
|---|---:|---:|---|
| Petrolio | `#1A4861` | `#A7D2E5` | Azioni principali, navigazione, tratta selezionata. |
| Avorio carta | `#FBF7EF` | `#121713` | Sfondo della lettura continua e superfici. |
| Verde segnale | `#4B6246` | `#C0D8B4` | Conferme e stati regolari, mai come unico indicatore. |
| Rame | `#A5501B` | `#FFB786` | Attenzione, ritardi e metadati salienti. |
| Rosso segnale | `#AF2E20` | `#FFB4A8` | Soppressioni ed errori con testo esplicito. |

Il widget usa un fondale petrolio più scuro e testi avorio per restare leggibile sulla home del dispositivo. Dark mode conserva gli stessi ruoli semantici: non ribalta semplicemente i colori, evitando stati indistinguibili o contrasti insufficienti.
