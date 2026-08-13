# Binario v0.5 — Piano di implementazione

## Obiettivo

La release v0.5 introduce la prima esperienza di **giornata ferroviaria personale**, ispirata ai benchmark consolidati: una sintesi immediata delle prossime partenze, tratte fissate dall’utente e comunicazione inequivocabile della qualità del dato. Non aggiunge fonti live non autorizzate, account, permessi o raccolta dati.

## Incrementi inclusi

| Incremento | Decisione |
|---|---|
| Partenze rapide | Nell’app, sotto la tratta attiva, una card mostra al massimo due corse ordinate per orario previsto con la stessa logica neutrale della lista completa. Ogni riga espone operatore, categoria, numero, destinazione, orario previsto, ritardo/stato, binario e fonte. |
| Tratte fissate | Ogni tratta salvata può essere fissata/rimossa dai preferiti. Le tratte fissate precedono le altre nella gestione tratte e lo stato rimane locale e persistente. |
| Integrità del dato demo | Quando la confidence è `DEMONSTRATION`, la UI non usa “dati aggiornati” come etichetta principale: annuncia “catalogo dimostrativo locale, non live”. |
| Ordinamento testabile | L’ordinamento neutrale per orario previsto e numero treno è estratto in una funzione di dominio e coperto da test unitari. |

## Vincoli mantenuti

Il widget conserva cinque corse per pagina, cambio di direzione e refresh. La persistenza dei dati esistenti resta retrocompatibile: le tratte salvate con il formato precedente continuano a essere leggibili e assumono `fissata = false`.

Room è previsto per la prossima estensione che introdurrà entità relazionate — monitoraggi di tipo treno/stazione, regole alert e tabelloni — per evitare una migrazione prematura durante questo piccolo incremento retrocompatibile.

## Criteri di accettazione

| Criterio | Evidenza richiesta |
|---|---|
| Card rapida | Nessuna più di due corse; testo e semantica completi a font 200%. |
| Ordinamento neutrale | Le corse sono ordinate per orario previsto e, a parità, per numero treno senza preferenza regionale. |
| Trasparenza demo | Nessuna superficie UI definisce live il catalogo dimostrativo. |
| Retrocompatibilità | Decoder rotte accetta sia formato storico a quattro campi sia formato nuovo a cinque campi. |
| Qualità build | Test unitari, `lintDebug` e `assembleDebug` devono superare. |
