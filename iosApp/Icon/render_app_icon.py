#!/usr/bin/env python3
"""Renders the iOS app icon from the Android launcher icon's layers, so the icon is drawn once.

A vector drawable's path data is already SVG, so each <path> is carried across as it is: fills,
strokes and their caps and joins, alpha, and the linear gradients aapt inlines as
<aapt:attr name="android:fillColor">. The background and foreground layers are stacked, cropped to
the middle of the 108dp adaptive-icon canvas and rasterised by headless Chrome at 1024x1024, the one
size an iOS asset catalog needs. The alpha channel is dropped on the way out: an app icon that has
one is refused at upload, even when every pixel is opaque.

    python3 iosApp/Icon/render_app_icon.py

Needs Google Chrome and Pillow. Run it again whenever the launcher icon changes.
"""

from __future__ import annotations

import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
LAYERS = [
    ROOT / "androidApp/src/main/res/drawable/ic_launcher_background.xml",
    ROOT / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml",
]
OUT = ROOT / "iosApp/Sources/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ANDROID = "{http://schemas.android.com/apk/res/android}"

# The launcher icon's content sits in the middle of a 108dp canvas that a mask crops down to about
# 72dp. An iOS icon shows the whole square, so the crop keeps a margin rather than the mask's circle.
VIEW_BOX = "12 12 84 84"
SIZE = 1024


def paint(value: str) -> tuple[str, str]:
    """#AARRGGBB (or #RRGGBB) as an SVG colour and opacity."""
    digits = value.lstrip("#")
    if len(digits) == 6:
        return f"#{digits}", "1"
    alpha, rgb = int(digits[:2], 16), digits[2:]
    return (f"#{rgb}", f"{alpha / 255:.3f}") if alpha else ("none", "0")


def gradient(node: ET.Element, gid: str) -> str | None:
    """The <linearGradient> for an aapt-inlined android:fillColor on [node], if it has one."""
    for attr in node:
        if not attr.tag.endswith("attr") or attr.get("name") != "android:fillColor":
            continue
        g = attr.find("gradient")
        if g is None or g.get(f"{ANDROID}type", "linear") != "linear":
            raise SystemExit(f"only linear gradients are supported, found {ET.tostring(attr)[:80]!r}")
        stops = []
        for item in g.findall("item"):
            color, opacity = paint(item.get(f"{ANDROID}color"))
            stops.append(f'<stop offset="{item.get(f"{ANDROID}offset")}" stop-color="{color}" stop-opacity="{opacity}"/>')
        coords = {k: g.get(f"{ANDROID}{k}") for k in ("startX", "startY", "endX", "endY")}
        return (
            f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{coords["startX"]}" '
            f'y1="{coords["startY"]}" x2="{coords["endX"]}" y2="{coords["endY"]}">{"".join(stops)}</linearGradient>'
        )
    return None


def paths(layer: pathlib.Path, prefix: str) -> tuple[list[str], list[str]]:
    defs, out = [], []
    for i, node in enumerate(ET.parse(layer).getroot().iter("path")):
        attrs = {"d": node.get(f"{ANDROID}pathData")}
        if (g := gradient(node, f"{prefix}{i}")) is not None:
            defs.append(g)
            attrs["fill"] = f"url(#{prefix}{i})"
        else:
            attrs["fill"], attrs["fill-opacity"] = paint(node.get(f"{ANDROID}fillColor", "#00000000"))
        if alpha := node.get(f"{ANDROID}fillAlpha"):
            attrs["fill-opacity"] = alpha
        if stroke := node.get(f"{ANDROID}strokeColor"):
            attrs["stroke"], attrs["stroke-opacity"] = paint(stroke)
            attrs["stroke-width"] = node.get(f"{ANDROID}strokeWidth", "1")
            attrs["stroke-linecap"] = node.get(f"{ANDROID}strokeLineCap", "butt")
            attrs["stroke-linejoin"] = node.get(f"{ANDROID}strokeLineJoin", "miter")
            if alpha := node.get(f"{ANDROID}strokeAlpha"):
                attrs["stroke-opacity"] = alpha
        out.append("<path " + " ".join(f'{k}="{v}"' for k, v in attrs.items()) + "/>")
    return defs, out


def svg() -> str:
    defs, body = [], []
    for n, layer in enumerate(LAYERS):
        d, p = paths(layer, f"g{n}_")
        defs += d
        body += p
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{VIEW_BOX}" width="{SIZE}" height="{SIZE}">'
        f'<defs>{"".join(defs)}</defs>{"".join(body)}</svg>'
    )


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp, "icon.html")
        page.write_text(f'<html><body style="margin:0">{svg()}</body></html>')
        shot = pathlib.Path(tmp, "icon.png")
        subprocess.run(
            [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
             f"--window-size={SIZE},{SIZE}", f"--screenshot={shot}", page.as_uri()],
            check=True, capture_output=True,
        )
        Image.open(shot).convert("RGB").save(OUT, optimize=True)
    print(OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
