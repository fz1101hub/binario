#!/usr/bin/env python3
from __future__ import annotations
import csv
import io
import zipfile
from pathlib import Path

path = Path('/home/ubuntu/binario_gtfs_imports_extended/toscana-tft-gtfs.zip')
with zipfile.ZipFile(path) as zf:
    for filename in ('routes.txt', 'stops.txt'):
        print(f'--- {filename} ---')
        with zf.open(filename) as part:
            rows = list(csv.DictReader(io.TextIOWrapper(part, encoding='utf-8-sig', errors='replace')))
        for row in rows[:30]:
            print(row)
