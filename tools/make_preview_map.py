#!/usr/bin/env python3
"""Make app/src/main/assets/preview_map.jpg: the piece of map behind the thermal-colour previews.

The Thermal colours page draws the thermal map as it was 1060 s into the demo flight
(app/src/main/assets/demo_flight.igc) on a 48 x 48 grid of 30 m cells centred on the glider.
This fetches the OpenTopoMap tiles under exactly that window, stitches and crops them, and saves
one small image, so the previews show real map colours without needing the network.

Re-run if the demo flight, PaletteSample.AT_S, SIZE or the cell size changes. Needs Pillow.
    python3 tools/make_preview_map.py
Map data (c) OpenStreetMap contributors, SRTM; map style (c) OpenTopoMap (CC-BY-SA).
"""
import io, math, os, urllib.request
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
IGC = os.path.join(HERE, "../app/src/main/assets/demo_flight.igc")
OUT = os.path.join(HERE, "../app/src/main/assets/preview_map.jpg")
AT_S, SIZE, CELL_M, ZOOM, PIXELS = 1060, 48, 30.0, 16, 512
EARTH_R = 6_371_008.8  # as core/Geo.kt, so the window matches LocalProjection


def fix_at(seconds):
    rows = [l for l in open(IGC, errors="replace") if l.startswith("B")]
    tod = lambda l: int(l[1:3]) * 3600 + int(l[3:5]) * 60 + int(l[5:7])
    last = [l for l in rows if tod(l) - tod(rows[0]) <= seconds][-1]
    lat = (int(last[7:9]) + int(last[9:14]) / 60000) * (-1 if last[14] == "S" else 1)
    lon = (int(last[15:18]) + int(last[18:23]) / 60000) * (-1 if last[23] == "W" else 1)
    return lat, lon


def world_px(lat, lon):
    n = 256 * 2 ** ZOOM
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n


def main():
    lat0, lon0 = fix_at(AT_S)
    m_lat = EARTH_R * math.pi / 180
    m_lon = m_lat * math.cos(math.radians(lat0))
    # Grid column i covers east (i - SIZE/2 - 0.5 .. i - SIZE/2 + 0.5) cells; row j covers north (SIZE/2 - j + 0.5 .. - 0.5).
    west, east = (-SIZE / 2 - 0.5) * CELL_M, (SIZE / 2 - 0.5) * CELL_M
    north, south = (SIZE / 2 + 0.5) * CELL_M, (-SIZE / 2 + 0.5) * CELL_M
    x0, y0 = world_px(lat0 + north / m_lat, lon0 + west / m_lon)
    x1, y1 = world_px(lat0 + south / m_lat, lon0 + east / m_lon)
    tx0, ty0, tx1, ty1 = int(x0 // 256), int(y0 // 256), int(x1 // 256), int(y1 // 256)
    sheet = Image.new("RGB", ((tx1 - tx0 + 1) * 256, (ty1 - ty0 + 1) * 256))
    for tx in range(tx0, tx1 + 1):
        for ty in range(ty0, ty1 + 1):
            url = f"https://tile.opentopomap.org/{ZOOM}/{tx}/{ty}.png"
            req = urllib.request.Request(url, headers={"User-Agent": "Cloudbase/0.1 (preview map generator)"})
            sheet.paste(Image.open(io.BytesIO(urllib.request.urlopen(req, timeout=30).read())).convert("RGB"), ((tx - tx0) * 256, (ty - ty0) * 256))
    crop = sheet.crop((round(x0 - tx0 * 256), round(y0 - ty0 * 256), round(x1 - tx0 * 256), round(y1 - ty0 * 256)))
    crop.resize((PIXELS, PIXELS), Image.LANCZOS).save(OUT, quality=82)
    print(f"centre {lat0:.5f},{lon0:.5f}; {sheet.size[0] // 256}x{sheet.size[1] // 256} tiles; wrote {OUT} ({os.path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    main()
