# Verifica — Binario v0.19.0

## Obiettivo

Ampliare l’autocompletamento oltre i soli data pack GTFS regionali senza estrarre cataloghi da APK di terzi e senza confondere una stazione suggerita con una corsa ferroviaria verificata.

## Fonte e delimitazione

| Aspetto | Esito verificato |
|---|---|
| Catalogo | 3.749 nomi univoci italiani derivati da elementi OpenStreetMap `railway=station` e `railway=halt`. |
| Filtri | Esclusi elementi con tag dismesso, abbandonato o merci. |
| Licenza | ODbL 1.0; attribuzione `© OpenStreetMap contributors` inclusa nell’asset, nelle Impostazioni e nel manifest. [1] |
| Integrità | Asset gzip `stations/osm_it_rail_stations.json.gz`, SHA-256 `42e680201b4e6b8b937bd872276743a49935ea106c0363cf3fc10370db0e845a`. |
| Uso | Solo scoperta/autocompletamento; nessun orario, binario, ritardo, soppressione, posizione o stato operativo proviene da questo catalogo. |

## Test funzionali

Il test unitario verifica un catalogo sintetico non operativo, provenienza e prefisso degli identificativi. Un secondo test legge l’asset effettivamente incluso e trova **Roma Termini** e **Viterbo Porta Fiorentina**. Il controllo sull’asset ha inoltre confermato Roma Tiburtina, Roma Trastevere, Fiumicino Aeroporto, Cassino e Latina.

## Validazione tecnica

| Controllo | Esito |
|---|---|
| `:app:compileDebugKotlin` | Superato. |
| `:app:testDebugUnitTest` | Superato. |
| Catena operativa | Inalterata: realtime qualificato → GTFS programmato → indisponibilità esplicita. |
| Attribuzione UI | Visibile in **Impostazioni → Privacy e dati**. |

## Riferimenti

[1] [OpenStreetMap — copyright, attribuzione e ODbL](https://www.openstreetmap.org/copyright)
