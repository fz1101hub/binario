# Security policy — Binario

## Stato del canale di segnalazione

Un recapito di sicurezza pubblico non è ancora stato definito dal titolare del progetto. Per questo motivo **non viene pubblicato alcun file `security.txt` con indirizzo o URL fittizio**. Prima dell’esposizione pubblica del servizio deve essere configurato un canale realmente presidiato (ad esempio una casella dedicata o un sistema di vulnerability disclosure) e deve essere pubblicato in `/.well-known/security.txt`.

## Gestione delle segnalazioni

Fino alla definizione del recapito, le vulnerabilità individuate nello sviluppo vengono tracciate nel repository e risolte con patch, test, build e checkpoint. Quando il contatto sarà disponibile, la policy dovrà indicare almeno: canale, lingue accettate, informazioni utili alla riproduzione, finestra di risposta, criterio di severità, finestra di patch e coordinamento della disclosure.

## Materiali tecnici pronti

| Materiale | Stato | Uso |
|---|---|---|
| SBOM CycloneDX | Generabile con `pnpm sbom` | Inventario dipendenze produzione per ogni release |
| Audit dipendenze | `pnpm audit --prod` | Verifica advisory note prima del checkpoint |
| Validazione audio | Implementata e testata | Rifiuta input base64 non valido o oltre soglia prima dello storage |
| Procedure protette | Implementate e testate | Isolamento delle tratte e preferenze per utente |
| Contratto snapshot | Implementato e testato | Sopprime dati/avvisi companion non freschi, in conflitto o non disponibili |

La presente policy è un artefatto tecnico di progetto e non sostituisce gli obblighi contrattuali o organizzativi del soggetto responsabile della pubblicazione.
