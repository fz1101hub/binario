# Binario — App Actions Android

## Perimetro

Le App Actions collegano Google Assistant alle attività dell’app Android Binario su telefono e, quando compatibile, Wear OS. Non costituiscono una Custom Skill conversazionale per Google Home: il portale Google Home consultato riguarda integrazioni di dispositivi smart-home, mentre le App Actions aprono o controllano funzioni dell’app Android.[1] [2]

## Deep link predisposti dal bridge

| Funzione | URL HTTPS bridge | URI Android Binario |
|---|---|---|
| Tratta attiva | `/app/active-route` | `binario://route/active` |
| Prossime corse | `/app/next-departures?route={nome}` | `binario://departures/next?route={nome}` |
| Ricerca treno | `/app/train-search?number={numero}` | `binario://train/search?number={numero}` |

Gli endpoint HTTP non ricevono né restituiscono dati personali; applicano `Cache-Control: no-store` e inoltrano solo parametri normalizzati e limitati. L’app Android deve validare nuovamente ogni parametro prima di visualizzarlo.

## `shortcuts.xml` da aggiungere all’app Android

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <capability android:name="actions.intent.OPEN_APP_FEATURE">
        <intent android:targetPackage="it.binario.app"
                android:targetClass="it.binario.app.MainActivity">
            <parameter android:name="feature" android:key="feature" />
        </intent>
    </capability>

    <shortcut android:shortcutId="active-route" android:shortLabel="Tratta attiva">
        <intent android:action="android.intent.action.VIEW"
                android:data="binario://route/active" />
    </shortcut>
    <shortcut android:shortcutId="next-departures" android:shortLabel="Prossime corse">
        <intent android:action="android.intent.action.VIEW"
                android:data="binario://departures/next" />
    </shortcut>
    <shortcut android:shortcutId="train-search" android:shortLabel="Ricerca treno">
        <intent android:action="android.intent.action.VIEW"
                android:data="binario://train/search" />
    </shortcut>
</shortcuts>
```

Il frammento è una base da validare con il Built-in Intent più specifico disponibile al momento della pubblicazione. L’app deve dichiarare anche un `intent-filter` `VIEW` con schema `binario`; per Android App Links HTTPS, il dominio definitivo deve pubblicare un file `assetlinks.json` verificato da Android.

## Verifica

1. Aggiungere le capability in `res/xml/shortcuts.xml` e il meta-data corrispondente nel manifest Android.
2. Collegare ogni URI a una destinazione interna che non richiede geolocalizzazione, account o raccolta di diagnostica.
3. Provare i deep link su dispositivo Android e con lo strumento App Actions di Android Studio.
4. Mostrare sempre lo stato del provider nel dettaglio aperto; se il provider live non è disponibile, usare la stessa formulazione: **“dati non disponibili in tempo reale”**.

## Riferimenti

[1]: https://developer.android.com/develop/devices/assistant/overview "Android Developers — Google Assistant and App Actions"
[2]: https://developers.home.google.com/ "Google Home Developers"
