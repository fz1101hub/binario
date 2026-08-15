# Catalogo geografico nazionale delle stazioni ferroviarie

Questo asset viene usato **solo** per suggerire nomi di stazioni nell’autocompletamento. Non certifica che una stazione abbia un servizio ferroviario attivo, né fornisce orari, binari, ritardi, soppressioni o posizione dei treni.

Contiene dati © OpenStreetMap contributors, disponibili secondo la [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/). L’attribuzione deve restare disponibile nella documentazione dell’app e nelle informative sulle fonti dati.

- Sorgente: https://www.openstreetmap.org/copyright
- Estrazione: `railway=station` o `railway=halt` in Italia; esclusi elementi marcati dismessi, abbandonati o dedicati alle merci.
- Snapshot interrogato: 15 agosto 2026.
- Rigenerazione: `tools/build_osm_station_catalog.py` da una risposta Overpass read-only salvata e revisionata.
