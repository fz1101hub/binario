# Implementazione Apple Watch Binario

Il companion watchOS è stato ricostruito come progetto nativo watchOS 10 con SwiftUI, WidgetKit, App Intents, cache locale e App Group. Mostra al massimo due corse, provider, qualità, freshness e l’avvertenza non-live; non mostra biglietti, posizione, token o storico.

> Apple Watch si abbina a iPhone, non a uno smartphone Android. Per questo l’app Android non può inviare uno snapshot direttamente tramite Watch Connectivity. Il produttore iOS è ora implementato nel progetto integrato `../ios/project.yml`; il companion Alexa non è un canale dati per watchOS.

`apple-watch/project.yml` resta intenzionalmente un progetto watch-only indipendente e dichiara `WKApplication = YES` e `WKWatchOnly = YES`. È soltanto un **harness di CI e test** (`BinarioWatchTests` sul job `watchos`): non ha un companion iPhone, quindi `PhoneConnectivity` non riceve mai un application context e l’app può mostrare solo lo snapshot non disponibile. **Non va mai inviato ad App Store Connect** e non va creato un record per `it.binario.watch`. Il prodotto da pubblicare è il target `BinarioWatch` incorporato da `../ios/project.yml` nell’app `it.binario.ios` (`it.binario.ios.watchkitapp`), che imposta `WKCompanionAppBundleIdentifier` e disabilita lo stato watch-only; entrambi i progetti compilano le stesse sorgenti in Swift language mode 6 e il job `ios` esegue `BinarioWatchTests` anche sul progetto integrato. Questa associazione statica non costituisce prova di build, firma o consegna `WCSession`: servono Xcode su macOS e una successiva prova end-to-end su iPhone e Apple Watch abbinati.

## Generazione e verifica su macOS

1. Installare Xcode e XcodeGen.
2. Dalla directory `apple-watch`, eseguire `xcodegen generate`.
3. Nel portale Apple Developer registrare `group.it.binario.watch` e associarlo ai due target.
4. Aprire `BinarioWatch.xcodeproj`, configurare il team e verificare `BinarioWatchTests` su un simulatore Apple Watch con watchOS 10 o successivo.

L’App Intent rilegge soltanto la cache e richiede subito il reload della timeline del widget: l’effetto è osservabile anche se il contenuto non è cambiato, ma non promette un refresh live. Il Widget aggiorna inoltre la timeline ogni quindici minuti come risveglio massimo; il contenuto cambia realmente soltanto quando arriva un nuovo snapshot compatibile tramite un canale autorizzato.
