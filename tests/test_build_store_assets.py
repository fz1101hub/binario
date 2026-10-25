#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "build_store_assets",
    ROOT / "tools" / "build_store_assets.py",
)
assert SPEC and SPEC.loader
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def filtered_png(width: int, height: int, channels: int, pixels: bytes, filter_types: list[int]) -> bytes:
    """Encode pixels applying one PNG filter type per row, to exercise the decoder."""
    stride = width * channels
    raw = bytearray()
    previous = bytes(stride)
    for row, filter_type in enumerate(filter_types):
        line = pixels[row * stride : (row + 1) * stride]
        raw.append(filter_type)
        for index in range(stride):
            left = line[index - channels] if index >= channels else 0
            up = previous[index]
            up_left = previous[index - channels] if index >= channels else 0
            if filter_type == 0:
                predicted = 0
            elif filter_type == 1:
                predicted = left
            elif filter_type == 2:
                predicted = up
            elif filter_type == 3:
                predicted = (left + up) >> 1
            else:
                estimate = left + up - up_left
                distances = (abs(estimate - left), abs(estimate - up), abs(estimate - up_left))
                if distances[0] <= distances[1] and distances[0] <= distances[2]:
                    predicted = left
                elif distances[1] <= distances[2]:
                    predicted = up
                else:
                    predicted = up_left
            raw.append((line[index] - predicted) & 0xFF)
        previous = line
    header = struct.pack(">IIBBBBB", width, height, 8, BUILDER.COLOR_TYPE_BY_CHANNELS[channels], 0, 0, 0)
    return (
        BUILDER.PNG_SIGNATURE
        + BUILDER.png_chunk(b"IHDR", header)
        + BUILDER.png_chunk(b"IDAT", zlib.compress(bytes(raw)))
        + BUILDER.png_chunk(b"IEND", b"")
    )


class BuildStoreAssetsTest(unittest.TestCase):
    def test_decoder_round_trips_every_png_filter_type(self) -> None:
        width, height, channels = 4, 5, 4
        pixels = bytes((index * 37 + 11) & 0xFF for index in range(width * height * channels))
        encoded = filtered_png(width, height, channels, pixels, [0, 1, 2, 3, 4])
        self.assertEqual((width, height, channels, pixels), BUILDER.read_png(encoded))
        rgb = bytes(value for index, value in enumerate(pixels) if index % 4 != 3)
        self.assertEqual((width, height, 3, rgb), BUILDER.read_png(BUILDER.write_png(width, height, 3, rgb)))

    def test_decoder_rejects_corrupt_or_unsupported_png(self) -> None:
        pixels = bytes(16 * 3)
        valid = filtered_png(4, 4, 3, pixels, [0, 0, 0, 0])
        with self.assertRaisesRegex(ValueError, "firma PNG"):
            BUILDER.read_png(b"GIF89a" + valid[6:])
        corrupt = bytearray(valid)
        corrupt[-5] ^= 0xFF
        with self.assertRaisesRegex(ValueError, "CRC"):
            BUILDER.read_png(bytes(corrupt))
        sixteen_bit = struct.pack(">IIBBBBB", 4, 4, 16, 2, 0, 0, 0)
        with self.assertRaisesRegex(ValueError, "8 bit"):
            BUILDER.read_png(BUILDER.PNG_SIGNATURE + BUILDER.png_chunk(b"IHDR", sixteen_bit) + BUILDER.png_chunk(b"IEND", b""))

    def test_box_filter_averages_blocks_and_drops_only_an_opaque_alpha(self) -> None:
        rgb = bytes(
            [
                10, 20, 30, 12, 22, 32, 100, 0, 0, 100, 0, 0,
                14, 24, 34, 16, 26, 36, 100, 0, 0, 100, 0, 0,
                0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 2,
                0, 0, 0, 0, 0, 0, 3, 3, 3, 4, 4, 4,
            ]
        )
        self.assertEqual(bytes([13, 23, 33, 100, 0, 0, 0, 0, 0, 3, 3, 3]), BUILDER.box_downscale(4, 4, rgb, 2))
        self.assertEqual(bytes([29, 7, 9]), BUILDER.box_downscale(2, 2, BUILDER.box_downscale(4, 4, rgb, 2), 2))
        with self.assertRaisesRegex(ValueError, "divisibili"):
            BUILDER.box_downscale(4, 4, rgb, 3)

        rgba = b"".join(bytes([1, 2, 3, 255]) for _ in range(4))
        self.assertEqual(bytes([1, 2, 3]) * 4, BUILDER.opaque_rgb(4, rgba))
        self.assertEqual(rgba, BUILDER.with_opaque_alpha(bytes([1, 2, 3]) * 4))
        translucent = rgba[:-1] + bytes([128])
        with self.assertRaisesRegex(ValueError, "trasparenza"):
            BUILDER.opaque_rgb(4, translucent)

    def test_play_icon_requires_a_square_multiple_of_512(self) -> None:
        with self.assertRaisesRegex(ValueError, "quadrata"):
            BUILDER.build_play_icon(filtered_png(4, 8, 3, bytes(4 * 8 * 3), [0] * 8))

    def test_committed_play_icon_and_readme_match_the_generator(self) -> None:
        with tempfile.TemporaryDirectory(prefix="store-play-") as temporary:
            output_dir = Path(temporary)
            written = BUILDER.build_assets(output_dir=output_dir)
            self.assertEqual([output_dir / "icon-512.png", output_dir / "README.md"], written)
            icon = written[0].read_bytes()
            self.assertEqual((ROOT / "store" / "play" / "README.md").read_text(encoding="utf-8"), written[1].read_text(encoding="utf-8"))

        # Compare decoded pixels, not the compressed stream: zlib output differs between implementations.
        committed = (ROOT / "store" / "play" / "icon-512.png").read_bytes()
        self.assertEqual(BUILDER.read_png(committed), BUILDER.read_png(icon))
        width, height, channels, pixels = BUILDER.read_png(committed)
        # Play documents the icon as a 32-bit PNG: RGBA with every alpha byte opaque.
        self.assertEqual((512, 512, 4), (width, height, channels))
        self.assertLess(len(committed), 1024 * 1024)
        self.assertEqual({255}, set(pixels[3::4]))
        self.assertEqual(bytes([0, 90, 82, 255]), pixels[:4])
        center = (256 * 512 + 256) * 4
        self.assertEqual(bytes([255, 255, 255, 255]), pixels[center : center + 4])


if __name__ == "__main__":
    unittest.main()
