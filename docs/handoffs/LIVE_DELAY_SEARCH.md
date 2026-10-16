# Realtime personale e ricerca Appiano–Ostiense

L’APK debug personale abilita `PERSONAL_REALTIME_SOURCES` per impostazione predefinita. La precedente build di test ereditava il valore `false` e non soddisfaceva il caso d’uso dei ritardi. La build release conserva l’attivazione personale esplicita; `-PBINARIO_PERSONAL_REALTIME_SOURCES=false` resta disponibile. Le fonti che richiedono credenziali non sono abilitate né contengono segreti nel client.

Per oggi il graph consulta prima ViaggiaTreno: partenze per codice stazione, quindi dettagli per origine della corsa, numero e giorno. Appiano S08328 → Ostiense S08406 viene riconosciuta fra le fermate anche quando il capolinea è Tiburtina. Il risultato contiene soltanto gli orari pubblicati delle fermate e le previsioni calcolate dal ritardo pubblicato. Un ritardo è operativo solo con rilevazione recente (massimo cinque minuti, tolleranza futura un minuto) e treno partito, oppure cancellazione esplicita. Mancanze e dati vecchi non diventano zero minuti live. Il fallback programmato resta disponibile; le date future non interrogano il realtime diretto.

Il client usa solo HTTPS, timeout, risposta limitata a 2 MiB, cache/coalescing di trenta secondi, cooldown sugli errori e massimo tre richieste simultanee. Numero massimo di dettagli per ricerca: dodici. I risultati, il dettaglio e la tratta nel pannello operativo vengono aggiornati ogni trenta secondi mentre la relativa schermata è visibile e l’Activity è STARTED. Un refresh automatico non scavalca uno già in corso né cambia data/ora della ricerca.

## Verifiche

I test sintetici dell’adapter coprono destinazione intermedia, identità e direzione sbagliate, rilevazione assente/vecchia, cancellazione, partenza ritardata ancora prendibile, date future, coalescing e cooldown. I test del wrapper coprono precedenza del live sul programmato, fallback dopo errore e assenza di chiamate live per date future. Queste fixture non provano la disponibilità attuale del servizio esterno.

`RealtimeBuildConfigurationTest` controlla sull’APK installato che il flag e i tre provider operativi siano abilitati. Appartiene alla suite Android deterministica. `ViaggiaTrenoJourneyNetworkTest` è invece un test esterno esplicito per Appiano → Ostiense, attivato da `workflow_dispatch verify_live_route=true`. Di notte consulta le prime corse dalle 05:00: un risultato programmato letto dalla fonte non viene descritto come ritardo operativo verificato. Il test RFI resta separato e interroga direttamente l’adapter programmato, per non passare incidentalmente grazie a ViaggiaTreno.

Da una shell attivata con `/workspace/.binario-tools/activate.sh`:

```bash
./gradlew --no-daemon --max-workers=2 --dependency-verification=strict -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :wear:testDebugUnitTest :app:lintDebug :wear:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/verify_privacy_policy.py
python3 tools/verify_ci_pinning.py
```

La macchina cloud non ha KVM e il proxy corrente nega `www.viaggiatreno.it` e `prm.rfi.it`. Non tentare di aggirarlo, declassare HTTPS o dichiarare superati i test esterni senza evidenza. I domini richiesti sono stati aggiunti alla bozza di configurazione; il salvataggio non cambia automaticamente il runtime. Il test funzionale Android esterno si esegue sul runner CI con KVM; non esporta risposte del servizio come proxy di rete. Consultare gli esiti CI dello SHA e distinguere suite deterministica e verifica esterna.

## Riferimenti di schema

Gli endpoint ViaggiaTreno sono interfacce pubbliche best-effort, non API open con SLA: `partenze/{codice}/{dataJavascriptCodificata}` e `andamentoTreno/{origine}/{numero}/{giornoMillis}`. Per il contratto dei campi sono stati consultati i client pubblici `matteoredaelli/pyviaggiatreno` e `marcocot/trenitalia-api`; non sono documentazione ufficiale né prova di una risposta attuale. Non usare chiavi di applicazioni terze o dati dimostrativi per superare un errore del servizio.
