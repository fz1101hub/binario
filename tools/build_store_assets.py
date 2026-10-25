#!/usr/bin/env python3
"""Generate the Google Play listing assets that derive from the brand icon.

Pure Python and offline: decodes the 1024x1024 brand PNG, drops its (fully
opaque) alpha channel, box-filters it to the 512x512 app icon Play requires
and writes store/play/README.md with the assets that still need a designer.
"""
from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ICON = ROOT / "companion-expo" / "assets" / "brand" / "binario-companion-icon.png"
OUTPUT_DIR = ROOT / "store" / "play"
ICON_SIZE = 512
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
CHANNELS_BY_COLOR_TYPE = {2: 3, 6: 4}
COLOR_TYPE_BY_CHANNELS = {3: 2, 4: 6}
README = """# Asset del listing Google Play

Cartella generata da `python3 tools/build_store_assets.py`: non modificare a mano i file generati, rigenerali e committa il risultato. `tests/test_build_store_assets.py` fallisce se i file committati divergono dal generatore.

| Asset | Requisito Play | Stato |
|---|---|---|
| `icon-512.png` | Icona app: PNG 512×512 a 32 bit, max 1024 KB, quadrato pieno senza angoli arrotondati (Play applica la maschera) | Generata dal marchio `companion-expo/assets/brand/binario-companion-icon.png` (1024 px, lo stesso segno di `ic_binario`) con box filter 2×2, RGB opaco |
| Grafica in evidenza | 1024×500 px, PNG o JPEG, max 15 MB; niente testo essenziale ai bordi, nessuna cornice di dispositivo | Da progettare dal titolare |
| Screenshot telefono | Da 2 a 8, PNG o JPEG, rapporto 16:9 o 9:16, lato minore ≥ 320 px, lato maggiore ≤ 3840 px | Da catturare: pianificatore, Consulta, dettaglio corsa, widget, impostazioni privacy |
| Screenshot tablet 7" e 10" | Facoltativi finché il listing non dichiara il supporto tablet; stessi vincoli di formato | Da decidere |
| Screenshot Wear OS | Da 1 a 8, rapporto 1:1, da 384×384 a 3840×3840 px, senza cornice dell'orologio | Da catturare: tratta attiva, riquadro (Tile), complicazione |
| Video promozionale | Facoltativo, URL YouTube pubblico | Non previsto |

I testi del listing nelle cinque lingue, le risposte del modulo Sicurezza dei dati e la procedura di invio sono in `docs/store/PLAY_SUBMISSION.md` e `docs/store/PLAY_DATA_SAFETY.md`.
"""


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def unfilter_row(filter_type: int, line: bytearray, previous: bytes, bpp: int) -> None:
    """Reverse one PNG scanline filter in place (filter types 0-4)."""
    if filter_type == 0:
        return
    if filter_type == 1:
        for index in range(bpp, len(line)):
            line[index] = (line[index] + line[index - bpp]) & 0xFF
    elif filter_type == 2:
        for index in range(len(line)):
            line[index] = (line[index] + previous[index]) & 0xFF
    elif filter_type == 3:
        for index in range(len(line)):
            left = line[index - bpp] if index >= bpp else 0
            line[index] = (line[index] + ((left + previous[index]) >> 1)) & 0xFF
    elif filter_type == 4:
        for index in range(len(line)):
            left = line[index - bpp] if index >= bpp else 0
            up = previous[index]
            up_left = previous[index - bpp] if index >= bpp else 0
            estimate = left + up - up_left
            distance_left = abs(estimate - left)
            distance_up = abs(estimate - up)
            distance_up_left = abs(estimate - up_left)
            if distance_left <= distance_up and distance_left <= distance_up_left:
                predicted = left
            elif distance_up <= distance_up_left:
                predicted = up
            else:
                predicted = up_left
            line[index] = (line[index] + predicted) & 0xFF
    else:
        raise ValueError(f"filtro PNG non valido: {filter_type}")


def read_png(data: bytes) -> tuple[int, int, int, bytes]:
    """Decode an 8-bit non-interlaced RGB/RGBA PNG into (width, height, channels, pixels)."""
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("firma PNG assente")
    offset = len(PNG_SIGNATURE)
    width = height = channels = None
    idat: list[bytes] = []
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        payload = data[offset + 8 : offset + 8 + length]
        if len(payload) != length:
            raise ValueError("chunk PNG troncato")
        (crc,) = struct.unpack_from(">I", data, offset + 8 + length)
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise ValueError(f"CRC non valido nel chunk {kind!r}")
        offset += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, compression, filter_method, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if bit_depth != 8 or color_type not in CHANNELS_BY_COLOR_TYPE or compression or filter_method or interlace:
                raise ValueError("supportati solo PNG RGB/RGBA a 8 bit non interlacciati")
            channels = CHANNELS_BY_COLOR_TYPE[color_type]
        elif kind == b"IDAT":
            idat.append(payload)
        elif kind == b"IEND":
            break
    if width is None or channels is None or not idat:
        raise ValueError("IHDR o IDAT assenti")
    raw = zlib.decompress(b"".join(idat))
    stride = width * channels
    if len(raw) != (stride + 1) * height:
        raise ValueError("dati immagine PNG incoerenti con le dimensioni")
    pixels = bytearray(stride * height)
    previous = bytes(stride)
    for row in range(height):
        start = row * (stride + 1)
        line = bytearray(raw[start + 1 : start + 1 + stride])
        unfilter_row(raw[start], line, previous, channels)
        pixels[row * stride : (row + 1) * stride] = line
        previous = bytes(line)
    return width, height, channels, bytes(pixels)


def write_png(width: int, height: int, channels: int, pixels: bytes) -> bytes:
    """Encode 8-bit RGB/RGBA pixels as a deterministic unfiltered PNG."""
    stride = width * channels
    raw = b"".join(b"\x00" + pixels[row * stride : (row + 1) * stride] for row in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, COLOR_TYPE_BY_CHANNELS[channels], 0, 0, 0)
    return PNG_SIGNATURE + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b"")


def opaque_rgb(channels: int, pixels: bytes) -> bytes:
    if channels == 3:
        return pixels
    if any(pixels[index] != 255 for index in range(3, len(pixels), 4)):
        raise ValueError("l'icona sorgente contiene trasparenza: Play richiede un quadrato pieno senza alfa")
    return bytes(value for index, value in enumerate(pixels) if index % 4 != 3)


def box_downscale(width: int, height: int, pixels: bytes, factor: int) -> bytes:
    """Average every factor x factor block of an RGB image, rounding half up."""
    if factor < 1 or width % factor or height % factor:
        raise ValueError(f"dimensioni {width}x{height} non divisibili per {factor}")
    stride = width * 3
    output_width = width // factor
    output = bytearray(output_width * (height // factor) * 3)
    block = factor * factor
    for row in range(height // factor):
        for column in range(output_width):
            for channel in range(3):
                total = 0
                for dy in range(factor):
                    base = (row * factor + dy) * stride + column * factor * 3 + channel
                    for dx in range(factor):
                        total += pixels[base + dx * 3]
                output[(row * output_width + column) * 3 + channel] = (total + block // 2) // block
    return bytes(output)


def build_play_icon(source: bytes) -> bytes:
    width, height, channels, pixels = read_png(source)
    if width != height or width % ICON_SIZE:
        raise ValueError(f"l'icona sorgente deve essere quadrata e multipla di {ICON_SIZE} px, non {width}x{height}")
    scaled = box_downscale(width, height, opaque_rgb(channels, pixels), width // ICON_SIZE)
    return write_png(ICON_SIZE, ICON_SIZE, 3, scaled)


def build_assets(source: Path = SOURCE_ICON, output_dir: Path = OUTPUT_DIR) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    icon = output_dir / "icon-512.png"
    icon.write_bytes(build_play_icon(source.read_bytes()))
    readme = output_dir / "README.md"
    readme.write_text(README, encoding="utf-8")
    return [icon, readme]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", type=Path, default=SOURCE_ICON)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    args = parser.parse_args(argv)
    try:
        written = build_assets(args.source, args.output_dir)
    except (OSError, ValueError, zlib.error) as error:
        print(f"Store assets: FAIL: {error}", file=sys.stderr)
        return 1
    for path in written:
        print(f"Store assets: scritto {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
