import type { Express, Response } from "express";

/**
 * The privacy policy and terms pages the skill manifest links (privacyPolicyUrl, termsOfUseUrl).
 *
 * The privacy text restates the «Alexa Companion» section of the repository's PRIVACY.md, with
 * the values this service now uses (refresh-token and session lifetimes). It claims nothing that
 * section does not; what the owner still has to supply is marked as such on the page. The terms
 * are a draft for the owner's legal review and say so. Both are static, so they work without the
 * database, the dashboard bundle or JavaScript.
 */

const STYLE = `<style>
body{font-family:system-ui,sans-serif;max-width:46rem;margin:2.5rem auto;padding:0 1rem;line-height:1.6;color:#1b1b1b;background:#fff}
h1{line-height:1.25}h2{margin-top:2rem}
.notice{border:2px solid #8a5a00;background:#fff7e6;padding:.75rem 1rem;border-radius:.5rem}
table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:.5rem;text-align:start;vertical-align:top}
code{font-size:.95em}
</style>`;

function page(title: string, body: string) {
  return `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
${STYLE}
</head>
<body>
${body}
</body>
</html>
`;
}

export const PRIVACY_PAGE = page(
  "Informativa privacy — Binario Alexa Companion",
  `<h1>Informativa privacy — Binario Alexa Companion</h1>
<p>Questa pagina riprende la sezione «Alexa Companion» dell'informativa privacy di Binario e descrive il servizio che collega la skill Alexa «Binario» alle tratte salvate nella dashboard.</p>
<p class="notice"><strong>Da completare dal titolare prima della pubblicazione:</strong> identità e recapito del titolare del trattamento per le richieste di accesso, rettifica o cancellazione; periodo di conservazione, ciclo di espurgo e gestore dei log e delle copie di sicurezza dell'infrastruttura.</p>

<h2>Quando viene usato</h2>
<p>Alexa Companion è opzionale e non è necessario per Android, Wear o il Companion Expo. Rimane inattivo finché non sono configurati database, variabili OAuth e skill Alexa e finché l'utente non completa un consenso autenticato per il collegamento. L'app Android attuale non invia ancora il proprio profilo a questo backend; le tratte personali Alexa sono quelle create nella dashboard del companion o tramite la relativa API autenticata.</p>
<p>L'accesso alla dashboard crea o aggiorna un record utente con identificativo del provider di login (<code>openId</code>), nome, email, metodo di accesso, ruolo e date di accesso. Il backend conserva per la dashboard le tratte personali — origine, destinazione, nome, fissaggio e date — e un profilo minimo con identificativo della tratta attiva, direzione, numeri treno monitorati, revisione e date. Il modello esclude espressamente cronologia dei viaggi, posizione, biglietti, snapshot live, audio e credenziali dell'app dal profilo remoto. Un database adottato dalla dashboard WebDev storica (migrazioni 0000–0011) può contenere ancora campi che il codice attuale non scrive né legge più: per le tratte note personali, note di accessibilità, data, ora e profilo di viaggio, promemoria, etichette e checklist; le tabelle delle stazioni preferite e aperte di recente (con la data dell'ultima apertura) e dei dispositivi companion abbinati (nome e ultimo uso). Il backend non li invia al browser né alla skill e non li cancella da sé: restano finché l'utente non elimina la tratta o l'account, che li rimuove insieme al resto.</p>
<p>Nel collegamento Alexa, il backend conserva l'identificativo Alexa solo come digest HMAC, con date di collegamento, verifica e revoca. I codici OAuth e i refresh token sono salvati soltanto come hash; il database conserva anche client ID, redirect URI, scope, famiglia di rotazione e date di creazione, consumo, scadenza o revoca. Il codice di autorizzazione scade dopo cinque minuti; il refresh token ruota a ogni uso e scade un anno dopo l'ultima rotazione; il token di accesso vocale ha durata dichiarata di un'ora. Il riuso di un refresh token già ruotato, oltre una finestra di 60 secondi che copre il nuovo tentativo di una richiesta la cui risposta è andata persa, revoca i discendenti ancora attivi della stessa famiglia e invalida la generazione delle credenziali vocali. Scadenza o revoca impediscono l'uso dell'artefatto, ma il codice attuale non implementa una cancellazione automatica delle righe scadute.</p>

<h2>Voce, dati inviati da Alexa e risposte</h2>
<p>Amazon Alexa tratta l'audio per riconoscere il comando secondo i propri termini. Alla skill il backend riceve la richiesta firmata da Alexa, il token di account linking, l'identificativo Alexa e gli slot testuali pertinenti, per esempio nome della tratta o numero treno. Il backend non salva il contenuto audio e il codice non registra gli slot di intent come cronologia vocale. Per verificare il collegamento salva soltanto l'hash dell'identificativo Alexa; per rispondere può leggere dal database le tratte personali e pronunciare i loro nomi tramite la piattaforma Alexa. Quando una richiesta fallisce, il log del server riporta soltanto tipo di richiesta, intent, lingua e classe dell'errore, senza slot, identificativi o valori del database.</p>
<p>La skill corrente non recupera dati ferroviari live: per richieste di prossimi treni o stato treno risponde che i dati in tempo reale non sono disponibili. Alle domande sulla puntualità di un treno o di una tratta salvata, e sul treno più puntuale di una tratta salvata fra quelli che di solito partono nelle tre ore successive alla richiesta, risponde con le statistiche non ufficiali di Binario su dati ViaggiaTreno e con il catalogo delle stazioni dell'app, file inclusi nel servizio al momento del deploy: il numero treno detto e, per una tratta, origine e destinazione salvate sono confrontati soltanto con quei file sul server, senza essere salvati né registrati nei log, e la risposta nomina solo la tratta (per il treno più puntuale anche numero, orario abituale e quota in orario dei treni), mai le sue stazioni. La domanda sulla puntualità di un treno non richiede il collegamento dell'account e non legge il database. Non invia quindi nome della stazione, posizione o numero treno a un feed ferroviario come parte della risposta Alexa attuale.</p>

<h2>Revoca, cancellazione e conservazione</h2>
<p>Nella dashboard la funzione <strong>Scollega account Alexa</strong> imposta la revoca sul collegamento Alexa e sui refresh token attivi. Ogni richiesta della skill verifica lo stato del collegamento e la generazione delle credenziali nel database, perciò un access token già emesso non continua a leggere le tratte dopo la revoca. La revoca non cancella il record di collegamento, il relativo hash, i token revocati, l'utente, le tratte o il profilo. Un nuovo scambio di autorizzazione con consenso ripristina il collegamento.</p>
<p>L'utente può eliminare le singole tratte oppure usare <strong>Elimina account e dati</strong> nella dashboard, confermando con la parola <code>ELIMINA</code>. La cancellazione revoca prima gli artefatti OAuth nella stessa transazione, elimina il record utente e, tramite chiavi esterne <code>ON DELETE CASCADE</code>, rimuove tratte, preferenze stazione, profilo, dispositivi e codici di pairing, collegamento Alexa, authorization code e refresh token associati. Il cookie di sessione viene cancellato; un cookie residuo emesso prima della cancellazione non ricrea automaticamente l'utente. Un successivo nuovo login, se il servizio resta disponibile, può creare un nuovo account vuoto.</p>
<p>La sessione della dashboard è un cookie HTTP-only che dura 24 ore dall'ultimo rinnovo e non oltre sette giorni dal login. Il logout cancella il cookie nel browser, ma non elimina account o dati nel database. La cancellazione account riguarda le righe del database operativo controllate dal codice; i log e le copie di sicurezza dell'infrastruttura del database o dell'hosting non sono definiti da questo servizio e il titolare ne indica periodo, ciclo di espurgo e gestore.</p>

<h2>Scelte dell'utente</h2>
<table>
<tr><th>Esigenza</th><th>Controllo disponibile</th></tr>
<tr><td>Scollegare Alexa</td><td><strong>Scollega account Alexa</strong> nella dashboard: revoca il collegamento e i refresh token, senza cancellare i dati descritti sopra.</td></tr>
<tr><td>Eliminare l'account Alexa Companion</td><td><strong>Elimina account e dati</strong> nella dashboard, con conferma <code>ELIMINA</code>: il database operativo elimina l'utente e i record collegati.</td></tr>
<tr><td>Richiedere accesso, correzione o cancellazione</td><td>Al recapito del titolare del trattamento, da completare (vedi riquadro in alto).</td></tr>
</table>

<h2 lang="en">English summary</h2>
<div lang="en">
<p>This page restates, in Italian above, the «Alexa Companion» section of Binario's privacy policy. In short: the dashboard stores your login identifier, name, email and the routes you save (origin, destination, name). A database adopted from the earlier WebDev dashboard may still hold fields this service no longer reads or writes (route notes, accessibility notes, travel dates, tags, recently opened stations, paired devices): they are never sent to the browser or the skill and are removed when you delete the route or your account. Linking Alexa stores the Alexa user identifier only as an HMAC digest; OAuth codes and refresh tokens are stored only as hashes. Authorization codes expire after five minutes, access tokens after one hour, refresh tokens one year after their last rotation. Audio is processed by Amazon under its terms; this service does not store it and does not keep spoken requests as a history. The skill reads your saved route names and says that real-time data is not available; it sends nothing to a rail data feed. It can also say how punctual a train or one of your saved routes is, and which train usually leaving one of your routes in the three hours after the request has been the most punctual, from unofficial Binario statistics on ViaggiaTreno data and the app's station catalogue bundled with the service: the train number, and a route's origin and destination, are only compared with those files on the server, never stored or logged, and the answer names only the route (for the most punctual train also the trains' numbers, usual times and on-time shares), never its stations. Asking about a train needs no linked account. You can unlink Alexa or delete your account and data from the dashboard at any time. The controller's identity and contact, and the retention of logs and backups, are still to be supplied by the owner.</p>
</div>`
);

export const TERMS_PAGE = page(
  "Termini d'uso — Binario Alexa Companion (bozza)",
  `<h1>Termini d'uso — Binario Alexa Companion</h1>
<p class="notice"><strong>Bozza da far rivedere al titolare.</strong> Questo testo non è ancora stato approvato e non costituisce termini d'uso vincolanti: le parti fra parentesi quadre restano da definire dal titolare.</p>

<h2>Il servizio</h2>
<p>Binario Alexa Companion collega la skill Alexa «Binario» alle tratte che l'utente salva nella dashboard. La skill legge i nomi delle tratte salvate e indica quando i dati in tempo reale non sono disponibili: non fornisce orari, ritardi, binari o soppressioni in tempo reale e non li inventa. Può indicare la puntualità media di un treno o di una tratta secondo statistiche non ufficiali di Binario su dati ViaggiaTreno, che non sono dati di Trenitalia o RFI né dati in tempo reale.</p>

<h2>Account e collegamento</h2>
<p>Per usare le risposte personali servono un account della dashboard, creato con l'accesso tramite il portale di login, e il collegamento dell'account dall'app Alexa con un consenso esplicito. L'utente può scollegare Alexa o eliminare l'account e i dati in qualsiasi momento dalla dashboard.</p>

<h2>Dati personali</h2>
<p>Il trattamento dei dati è descritto nell'<a href="/privacy">informativa privacy</a>.</p>

<h2>Da definire dal titolare</h2>
<p>[Identità e recapito del titolare del servizio; condizioni d'uso consentite; limitazioni di responsabilità; disponibilità e modifiche del servizio; modalità di modifica di questi termini; legge applicabile e foro competente.]</p>`
);

function sendStatic(res: Response, html: string) {
  res.setHeader("Cache-Control", "public, max-age=3600");
  return res.status(200).type("html").send(html);
}

export function registerLegalPages(app: Express) {
  app.get("/privacy", (_req, res) => sendStatic(res, PRIVACY_PAGE));
  app.get("/terms", (_req, res) => sendStatic(res, TERMS_PAGE));
}
