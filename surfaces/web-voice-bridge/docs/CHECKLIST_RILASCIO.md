# Checklist di rilascio Binario

## Blocco tecnico

- Eseguire `pnpm check`, `pnpm test`, `pnpm build`, `pnpm audit --prod` e `pnpm sbom` sul core web.
- Rieseguire `pnpm check`, `pnpm test`, `npx expo export --platform web`, `pnpm audit --prod`, `npx expo prebuild --clean --no-install` e le build debug Android/Wear OS sul companion.
- Rigenerare e riesaminare lo SBOM CycloneDX a ogni modifica delle dipendenze.
- Verificare che nessun file `.env`, `local.properties`, token, bundle build o `node_modules` sia incluso nella revisione o nell’archivio sorgente.

## Blocco prodotto e dati

- Verificare i quattro stati dati: programmato, realtime verificato, scaduto/conflitto e non disponibile.
- Confermare che nessuna cache, notifica, widget, watch o Alexa presenti realtime senza snapshot `verified` entro TTL.
- Eseguire import/export con un file JSON valido e uno malformato; verificare limite record, assenza di token e mancata sovrascrittura.
- Provare archivio/ripristino, preferenze, tag, ordine, duplicazione, inversione e cancellazione dati in un account di prova.

## Blocco accessibilità e privacy

- Provare tastiera, focus visibile, zoom browser 200–400%, contrasto elevato, riduzione movimento e schermate small-width.
- Provare screen reader su browser e, per il companion, TalkBack/VoiceOver su dispositivo reale.
- Leggere in anteprima il file di export e il report diagnostico: non devono contenere token, audio, coordinate, note non selezionate o snapshot ferroviari.
- Riesaminare retention, DPA, informativa e DPIA prima di nuovi fornitori o di trattamenti che aumentino il rischio.

## Blocco esterno

- Prima di una release Apple, creare/firma i target Xcode, WidgetKit, watchOS e App Group da macOS.
- Prima di attivare realtime, approvare licenza, fonte, adapter, freshness, kill switch e osservabilità del provider.
- Pubblicare `security.txt` soltanto quando sarà disponibile un canale di sicurezza realmente presidiato.
