# Binario Companion Expo — baseline recuperata

Questa directory conserva il lavoro companion recuperato dalla cartella di sessione priva di metadati Git. È stata importata senza unire, sostituire o cancellare la base Android/Wear nativa storica collocata alla radice del repository.

| Proprietà | Valore |
| --- | --- |
| Origine congelata | `/home/ubuntu/binario-companion-native` |
| Ramo di recupero | `recovery/expo-companion-session-20260903` |
| File sorgente/documentazione inventariati | 43 nella copia di sessione; 42 importati dopo l’esclusione dell’APK Android rigenerabile. |
| Archivio di sicurezza esterno | `binario-companion-session-20260903.tar.gz` |
| SHA-256 archivio | `31c02d6f89fb7dc75db4b450bfd9a0257bf4c990d6e070624bc37c3c46d1f95b` |
| Esclusioni deliberate | `node_modules`, cache Expo, build Android/iOS e APK, perché rigenerabili e non sorgenti. |

## Contenuto preservato

La baseline include le superfici Expo di Home, Agenda, Companion e Impostazioni; planner e tabellone locale; itinerari, importazione testuale, condivisione Android `text/plain`, persistenza locale, stazioni offline e test contrattuali. Include inoltre le matrici di parità, la roadmap benchmark e il protocollo TalkBack prodotti nelle sessioni precedenti.

## Stato di ripristino

Questa è una **snapshot sorgente da consolidare**, non una dichiarazione di compilazione riuscita. La cartella originale non contiene `.git`, dipendenze o tutti i moduli referenziati dagli import; prima di modificare le funzioni occorre ricostruire i moduli assenti confrontandoli con i contratti e con il Git storico, senza sostituire i flussi Expo già preservati.

> Regola di recupero: Git storico è una fonte di confronto. La baseline Expo è il lavoro di sessione da conservare e da estendere; nessun file viene sostituito automaticamente.
