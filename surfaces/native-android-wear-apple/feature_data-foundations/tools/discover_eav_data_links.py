#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path
from urllib.request import Request, urlopen

url = 'https://www.eavsrl.it/open-data/'
out = Path('/home/ubuntu/eav_probe/open-data.html')
out.parent.mkdir(parents=True, exist_ok=True)
request = Request(url, headers={'User-Agent': 'Binario-source-discovery/0.1 (personal project; read-only)'})
with urlopen(request, timeout=45) as response:
    body = response.read()
out.write_bytes(body)
text = body.decode('utf-8', errors='replace')
for link in sorted(set(re.findall(r'''(?:https?://[^\s"'<>]+\.(?:zip|csv|txt|xlsx?|json)(?:\?[^\s"'<>]*)?|href=["'][^"']+\.(?:zip|csv|txt|xlsx?|json)[^"']*)''', text, re.I))):
    print(link)
print(f'bytes={len(body)} saved={out}')
