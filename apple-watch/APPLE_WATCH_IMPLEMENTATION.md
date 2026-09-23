# Implementazione Apple Watch Binario

Il companion watchOS è stato ricostruito come progetto nativo watchOS 10 con SwiftUI, WidgetKit, App Intents, cache locale e App Group. Mostra al massimo due corse, provider, qualità, freshness e l’avvertenza non-live; non mostra biglietti, posizione, token o storico.

> Apple Watch si abbina a iPhone, non a uno smartphone Android. Per questo l’app Android non può inviare uno snapshot direttamente tramite Watch Connectivity. L’architettura Binario prevede una futura app iOS Binario che usa Watch Connectivity oppure un servizio di sincronizzazione companion dedicato e derivato dallo stesso contratto dell’app principale. Il companion Alexa non è un canale dati per watchOS.

Finché il repository non contiene un target iOS companion, il progetto generato dichiara `WKWatchOnly = YES` per consentire build e test indipendenti. Quando verrà introdotta l’app iOS, questa chiave dovrà essere sostituita dal relativo `WKCompanionAppBundleIdentifier` e il trasporto `WCSession` dovrà superare una prova end-to-end.

## Generazione e verifica su macOS

1. Installare Xcode e XcodeGen.
2. Dalla directory `apple-watch`, eseguire `xcodegen generate`.
3. Nel portale Apple Developer registrare `group.it.binario.watch` e associarlo ai due target.
4. Aprire `BinarioWatch.xcodeproj`, configurare il team e verificare `BinarioWatchTests` su un simulatore Apple Watch con watchOS 10 o successivo.

L’App Intent rilegge soltanto la cache: non promette un refresh live. Il Widget aggiorna la timeline ogni quindici minuti come risveglio massimo; il contenuto cambia realmente soltanto quando arriva un nuovo snapshot compatibile tramite un canale autorizzato.
