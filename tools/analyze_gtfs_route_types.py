#!/usr/bin/env python3
"""Riepiloga route_type e nomi delle agenzie nei GTFS validati."""
from __future__ import annotations

import csv
import io
import json
import zipfile
from collections import Counter
from pathlib import Path

ROOTS = (Path('/home/ubuntu/binario_gtfs_imports'), Path('/home/ubuntu/binario_gtfs_imports_extended'))

for root in ROOTS:
    for path in sorted(root.glob('*.zip')):
        if not zipfile.is_zipfile(path):
            continue
        with zipfile.ZipFile(path) as archive:
            names = {Path(name).name for name in archive.namelist()}
            if 'routes.txt' not in names or 'agency.txt' not in names:
                continue
            def table(name: str):
                with archive.open(name) as part:
                    return list(csv.DictReader(io.TextIOWrapper(part, encoding='utf-8-sig', errors='replace')))
            routes = table('routes.txt')
            agencies = table('agency.txt')
            types = Counter(row.get('route_type', '').strip() for row in routes)
            print(json.dumps({
                'file': path.name,
                'bytes': path.stat().st_size,
                'agencies': [row.get('agency_name', '') for row in agencies][:5],
                'route_types': dict(sorted(types.items())),
                'rail_routes': sum(1 for row in routes if row.get('route_type', '').strip() in {'2', '100', '101', '102', '103', '104', '105', '106', '107', '108', '109'}),
            }, ensure_ascii=False))
