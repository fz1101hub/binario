#!/usr/bin/env python3
"""Generate companion Expo raster assets from Binario's tracked Android vector.

This is intentionally deterministic and uses no external or generative service. The
source-of-truth mark is app/src/main/res/drawable/ic_binario.xml: its teal field and
white train glyph are reproduced at launcher, adaptive-icon, and splash safe areas.
"""
from pathlib import Path
from PIL import Image, ImageDraw

REPOSITORY = Path(__file__).resolve().parents[2]
SOURCE_MARK = REPOSITORY / "app/src/main/res/drawable/ic_binario.xml"
OUTPUT = REPOSITORY / "companion-expo/assets/brand"
TEAL = "#005A52"
WHITE = "#FFFFFF"


def require_primary_mark() -> None:
    source = SOURCE_MARK.read_text(encoding="utf-8")
    expected = (
        'android:fillColor="#005A52"',
        'android:fillColor="#FFFFFF"',
        'android:viewportWidth="48"',
        'android:viewportHeight="48"',
        'M12,8c-2.2,0 -4,1.8 -4,4v20',
    )
    if not all(marker in source for marker in expected):
        raise RuntimeError(f"{SOURCE_MARK} is no longer the expected Binario primary mark")


def scaled(value: float, origin: int, unit: float) -> int:
    return round(origin + value * unit)


def draw_train(image: Image.Image, *, origin_x: int, origin_y: int, unit: float) -> None:
    """Rasterise the geometric train glyph in the tracked Binario vector at 48dp."""
    draw = ImageDraw.Draw(image)
    # Main body: M12,8... and the two wheel/rail details from ic_binario.xml.
    draw.rounded_rectangle(
        (scaled(8, origin_x, unit), scaled(8, origin_y, unit), scaled(40, origin_x, unit), scaled(36, origin_y, unit)),
        radius=scaled(4, 0, unit),
        fill=WHITE,
    )
    for left in (16, 25):
        draw.rectangle(
            (scaled(left, origin_x, unit), scaled(13, origin_y, unit), scaled(left + 7, origin_x, unit), scaled(20, origin_y, unit)),
            fill=TEAL,
        )
    draw.rectangle(
        (scaled(14, origin_x, unit), scaled(25, origin_y, unit), scaled(34, origin_x, unit), scaled(29, origin_y, unit)),
        fill=TEAL,
    )
    for center_x in (16, 32):
        draw.ellipse(
            (scaled(center_x - 3, origin_x, unit), scaled(26, origin_y, unit), scaled(center_x + 3, origin_x, unit), scaled(32, origin_y, unit)),
            fill=TEAL,
        )
    # The vector's undercarriage: M16,32 ... M13,42h5l3,-4h6l3,4h5v-2.
    draw.polygon(
        [
            (scaled(16, origin_x, unit), scaled(35, origin_y, unit)),
            (scaled(13, origin_x, unit), scaled(40, origin_y, unit)),
            (scaled(18, origin_x, unit), scaled(40, origin_y, unit)),
            (scaled(21, origin_x, unit), scaled(36, origin_y, unit)),
            (scaled(27, origin_x, unit), scaled(36, origin_y, unit)),
            (scaled(30, origin_x, unit), scaled(40, origin_y, unit)),
            (scaled(35, origin_x, unit), scaled(40, origin_y, unit)),
            (scaled(32, origin_x, unit), scaled(35, origin_y, unit)),
        ],
        fill=WHITE,
    )


def train_layer(size: int, glyph_size: int) -> Image.Image:
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    unit = glyph_size / 48
    offset = (size - glyph_size) // 2
    draw_train(layer, origin_x=offset, origin_y=offset, unit=unit)
    return layer


def save_icon() -> None:
    image = Image.new("RGBA", (1024, 1024), TEAL)
    image.alpha_composite(train_layer(1024, 640))
    image.save(OUTPUT / "binario-companion-icon.png", format="PNG", optimize=False)


def save_adaptive_foreground() -> None:
    # Android's adaptive safe zone is centered; retain generous transparent padding.
    train_layer(1024, 432).save(OUTPUT / "binario-companion-adaptive-foreground.png", format="PNG", optimize=False)


def save_splash() -> None:
    # expo-splash-screen applies the teal background; this is the foreground mark only.
    train_layer(1024, 416).save(OUTPUT / "binario-companion-splash.png", format="PNG", optimize=False)


def main() -> None:
    require_primary_mark()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    save_icon()
    save_adaptive_foreground()
    save_splash()
    print(f"Generated deterministic Binario companion assets in {OUTPUT}")


if __name__ == "__main__":
    main()
