# Checklist canonica — Companion Expo

Questa è la checklist operativa di riferimento per il Companion Expo. Sostituisce i TODO ad hoc nella radice di `companion-expo/`. Le caselle rappresentano **condizioni da verificare per la modifica o la release considerata**: non attestano attività svolte in una sessione precedente e vanno marcate solo con evidenza riproducibile nella pull request o nella documentazione di release.

## Controlli riproducibili

- [ ] Installare le dipendenze con `pnpm install --frozen-lockfile`.
- [ ] Eseguire `pnpm run check`, `pnpm test`, `pnpm run verify:branding`, `pnpm run verify:managed-config` e `pnpm run verify:release-inputs`.
- [ ] Rigenerare Android con `pnpm exec expo prebuild --platform android --clean --no-install` e confermare che una seconda rigenerazione non modifichi i sorgenti gestiti.
- [ ] Eseguire lint e build debug dei moduli `:app` e `:wear` con il wrapper Gradle, quando Android SDK e JDK richiesti sono disponibili.
- [ ] Verificare che configurazioni locali, chiavi, artefatti di build, `node_modules` e file `.env` non siano tracciati.

## Dati, privacy e sicurezza

- [ ] Mantenere il comportamento local-first: nessun account, sincronizzazione o richiesta di rete è attivo senza una funzione, un consenso e una configurazione esplicitamente documentati.
- [ ] Verificare che export e import siano avviati dall'utente, minimizzati e privi di token, coordinate, audio e dati realtime non necessari.
- [ ] Confermare che il payload Wear resti minimo, che non contenga il testo delle checklist e che il modulo Wear non dichiari `INTERNET` finché non effettua richieste proprie.
- [ ] Riesaminare `PRIVACY.md` e i contratti di superficie quando cambiano permessi, persistenza, notifiche, condivisione o integrazioni.

## Collaudi esterni richiesti

- [ ] Su un dispositivo Android reale, verificare i flussi di condivisione `text/plain`, notifiche, widget, Tile Wear e i casi offline o di revoca.
- [ ] Eseguire il protocollo TalkBack su planner, autocomplete, picker, promemoria, tema e scala caratteri, registrando esiti e difetti.
- [ ] Su macOS/Xcode, generare, firmare e provare WidgetKit e watchOS con App Group e identificativi Apple reali prima di una distribuzione Apple.
- [ ] Valutare gli advisory transitivi Expo/Metro e aggiornare soltanto a una versione compatibile, con controlli completi ripetuti.

## Evidenze e criteri di chiusura

- [ ] Collegare ogni casella chiusa a comando, log CI, test automatizzato o verbale di collaudo datato e ripetibile.
- [ ] Tenere separati controlli di codice, build e test su hardware; una build o un test unitario non sostituisce un collaudo del dispositivo.
- [ ] Per una release, riportare le evidenze nella documentazione di release senza dichiarare completati i requisiti esterni privi di prova.

I requisiti Android e Wear di più alto livello sono descritti anche in [RELEASE_BUILD.md](RELEASE_BUILD.md) e nel [protocollo di collaudo Android/Wear](PROTOCOLLO_COLLAUDO_ANDROID_WEAR_2026-09-03.md).
