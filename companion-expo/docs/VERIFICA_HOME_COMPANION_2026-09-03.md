# Verifica Home companion — 3 settembre 2026

## Stato della verifica visiva

La preview Expo web della Home companion è stata aperta il 3 settembre 2026 dopo l’allineamento funzionale con la Homepage web. Al primo caricamento il browser ha mostrato una schermata vuota, senza elementi interattivi rilevabili. Dopo il completamento del bundle web e un ricaricamento, la viewport è rimasta bianca e la console browser non ha restituito messaggi. L’osservazione non costituisce una prova di malfunzionamento Android: la pipeline nativa separata ha completato typecheck, suite, export Android e build telefono/Wear. La resa Expo web rimane quindi non verificata e va diagnosticata a parte, senza bloccare o qualificare impropriamente il build Android.

## Perimetro dell’incremento

La Home companion ora espone il planner locale, il riepilogo delle tratte imminenti, ritorno/ripianificazione con nuova data e ora obbligatorie e accessi ad Agenda, importazione guidata e portabilità/privacy. Non presenta dati ferroviari realtime come disponibili e non sostituisce il collaudo su dispositivo Android reale.
