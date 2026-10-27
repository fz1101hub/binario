# Binario per pendolari — implementazione del 26 ottobre 2026

Il lavoro parte dal checkout esistente, commit `72f90cc`, che contiene già le integrazioni e le correzioni dei branch di stabilizzazione. Sono stati esaminati i riferimenti Git disponibili. Non sono stati importati interi branch o creati un secondo frontend web, un secondo catalogo stazioni o un nuovo backend ferroviario.

## Esperienza e codice

- **Android:** home Oggi con tratta attiva, inversione, due prossime corse, accesso al tabellone, tratte salvate e agenda. Il pianificatore resta in Cerca. Una ricerca pianificata mantiene data e ora; il comando “Segui le partenze da adesso” passa esplicitamente alla consultazione corrente.
- **iPhone:** home SwiftUI, selezione della tratta abituale fra quelle salvate, ritorno, aggiornamento manuale e automatico e preferenze locali. Eliminare la tratta elimina anche la selezione abituale. Il repository iOS resta GTFS programmato.
- **Smartwatch:** riuso dei moduli Wear OS e Apple Watch e del contratto snapshot v1 esistente, con massimo due corse e scadenza. Non è stato aggiunto un secondo motore dati agli orologi.
- **Echo:** un interceptor APL produce una schermata scorrevole dai contenuti della scheda vocale esistente. La risposta vocale rimane disponibile sugli Echo senza schermo. Niente risorse remote, markup utente eseguibile o riepilogo personale prima dell’account linking. Alexa continua a dichiarare l’assenza di dati realtime finché manca una sorgente remota autorizzata.

Componenti Android e traduzioni sono stati estratti dalla Activity per riusarli nella home e nel tabellone. L’elenco delle corse si adatta alla larghezza e ai caratteri ingranditi. La pulizia riguarda il codice modificato: non costituisce una certificazione di assenza di codice morto nell’intero repository.

## Aggiornamenti e realtime

Il ciclo delle schermate è legato alla visibilità e al lifecycle: controllo ogni 15 secondi, richieste non più frequenti di 60 secondi per dati operativi, 300 per orari statici e 120 dopo un errore. Le ricerche Android pianificate non sono aggiornate come partenze correnti. I job sostituiti vengono cancellati e i token di richiesta impediscono alle risposte precedenti di sovrascrivere lo stato più recente. I worker preesistenti dei widget mantengono il proprio ciclo.

`GtfsRealtimeRouteDataSource` collega gli adapter STA/SNCF e il resolver compatto già presenti al graph Android. Interroga soltanto il feed che ha prodotto corse nella tratta. Il raccordo richiede trip ID, giorno di servizio esplicito, un evento di partenza, fermata e orario programmato della stessa fonte. Osservazioni scadute, future o ambigue lasciano intatto l’orario statico. Una previsione non viene trasformata in un passaggio realmente rilevato; non vengono dedotti binari o soppressioni.

La scadenza operativa non supera la scadenza della fonte. Il tabellone distingue dati operativi, orario programmato, ultimo dato e indisponibilità. Un caricamento riuscito di un orario statico non viene più etichettato “live”.

Questa integrazione **non offre copertura live nazionale**: dipende dalla presenza di identificativi compatibili nei feed STA/SNCF. I tabelloni best-effort restano un opt-in delle build personali. Trenord E015 e Svizzera richiedono ancora autorizzazioni e gateway. iPhone e Alexa non hanno acquisito un provider live attraverso questa modifica.

## Accessibilità e privacy

La UI usa componenti nativi, etichette testuali degli stati, navigazione semantica, dimensioni del testo di sistema, layout senza righe a altezza fissa, azioni Android di almeno 48 dp e azioni iPhone di almeno 44 pt. Non viene richiesto un nuovo permesso di posizione, microfono o notifiche. Le nuove stringhe sono presenti in italiano, inglese, tedesco, francese e spagnolo.

I criteri di riferimento sono WCAG 2.2 AA ed EN 301 549. L’applicabilità delle norme italiane ed europee, inclusi legge 4/2004, D.lgs. 106/2018, D.lgs. 82/2022 e GDPR, dipende da titolare, servizio e distribuzione. Questo intervento tecnico non equivale a una dichiarazione di conformità legale. Prima del rilascio servono verifiche TalkBack/VoiceOver, ingrandimento, contrasto e navigazione su dispositivi, una valutazione di accessibilità documentata e le informazioni del titolare previste da PRIVACY.md.

## Verifica e limiti di consegna

- Risorse Android compilate e collegate con AAPT2; sorgenti Java e tutte le sorgenti Kotlin/Compose dell’app compilate con gli strumenti e le dipendenze già presenti.
- Suite JVM dell’app: 383 test passati, eseguiti direttamente con JUnit usando i sorgenti correnti e le fixture del repository.
- Suite Python: 117 test passati; verifica statica privacy e freschezza dei 10 pack GTFS superate al 26 ottobre 2026 (almeno 30 giorni di validità residua).
- Companion Alexa: controllo TypeScript, 38 test mirati della skill/voce e build production con verifica di pulizia del bundle. La suite HTTP completa richiede i socket locali.
- Gradle completo e test HTTP che aprono un listener locale bloccati dalle restrizioni della sandbox. Le richieste di permesso non hanno prodotto un’esecuzione. La compilazione diretta non sostituisce lint, packaging e firma Gradle.
- Build e test Swift/Xcode, prova dei feed esterni e test end-to-end su telefono, orologio ed Echo non eseguiti in questo ambiente. I test Swift aggiunti sono pronti per il job Apple.
- Nessun nuovo APK/IPA firmato e nessuna pubblicazione sugli store o della skill Alexa. Gli artefatti nativi già presenti nel checkout precedono queste modifiche.

Per il gate Android standard, in un ambiente che consenta i socket locali:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict \
  :app:testDebugUnitTest :wear:testDebugUnitTest \
  :app:lintDebug :wear:lintDebug :app:assembleDebug :wear:assembleDebug
```

I progetti Apple restano generabili da `ios/project.yml` con la pipeline Xcode esistente. Non sono state aggiunte dipendenze di produzione.
