#!/usr/bin/env python3
"""Verifica che l'asset del catalogo curato sia realmente presente nell'APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile


ASSET_PATH = "assets/stations/curated_it_rail_stations.json"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    args = parser.parse_args()

    with zipfile.ZipFile(args.apk) as archive:
        try:
            payload = archive.read(ASSET_PATH)
        except KeyError:
            print(f"ERRORE: asset mancante nell'APK: {ASSET_PATH}", file=sys.stderr)
            return 1

    catalog = json.loads(payload.decode("utf-8"))
    stations = catalog.get("stations", [])
    by_name = {station.get("name"): station for station in stations}
    appiano = by_name.get("Appiano Proba Petronia")
    ostiense = by_name.get("Roma Ostiense")
    if appiano is None or ostiense is None:
        print("ERRORE: Appiano Proba Petronia o Roma Ostiense assente dall'asset APK", file=sys.stderr)
        return 1
    if "APPIANO" not in appiano.get("aliases", []):
        print("ERRORE: alias APPIANO assente dall'asset APK", file=sys.stderr)
        return 1

    print(
        json.dumps(
            {
                "asset": ASSET_PATH,
                "asset_sha256": hashlib.sha256(payload).hexdigest(),
                "stations": len(stations),
                "appiano_id": appiano.get("id"),
                "ostiense_id": ostiense.get("id"),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
