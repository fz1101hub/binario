# Verifica Binario v0.17.0

## Esito

| Controllo | Risultato |
|---|---|
| Commit di base | `0e0867ad33c3dd94fbadf7afa1ffc0240a6498f9` verificato prima della riconciliazione |
| Test unitari + APK debug | Superati dopo l’esposizione dei metadata nel widget |
| Lint Android | Superato |
| Integrità GTFS | 12/12 SHA-256 valide |
| Copertura GTFS | 12/12 data pack con schema richiesto |
| Guardrail widget | Demo/statico non evidenziato come variazione; provider/freshness/qualità/warning visibili |

## Collaudo su dispositivo consigliato

Verificare un widget in formato compatto, standard ed espanso con uno snapshot realtime disponibile e uno statico. Controllare che provider, freshness, score e warning restino leggibili e che una corsa GTFS programmata non usi il giallo di variazione.
