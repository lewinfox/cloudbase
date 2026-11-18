#!/usr/bin/env python3
"""Generate core/src/main/resources/app/cloudbase/core/egm96_2deg.s8 from the real EGM96 15' grid.

Source: PROJ's vdatum copy of NGA's EGM96 15' grid (egm96_15.gtx, 721 x 1440 big-endian float32,
origin -90,-180, 0.25 deg). Download it first:
    curl -LO https://download.osgeo.org/proj/vdatum/egm96_15/egm96_15.gtx
Usage (needs numpy):
    python3 -I make_geoid.py egm96_15.gtx [out.s8] [--report] [--points]

Output format: signed bytes, whole metres, row-major, 91 latitude rows from -90 (south) to +90, each
with 180 longitude columns from -180 (inclusive) to +178 (the 180 column wraps to -180). 2 deg
spacing, 16380 bytes. Nodes are exact 15' samples (2 deg is a multiple of 0.25 deg), rounded.
--report prints the interpolation error of the coarse grid against the 15' grid.
--points prints reference values (15' bilinear) used by GeoidTest.
"""
import hashlib, struct, sys
import numpy as np

STEP = 2
SHA256 = "c02a6eb70a7a78efebe5adf3ade626eb75390e170bb8b3f36136a2c28f5326a0"


def load(path):
    raw = open(path, "rb").read()
    if hashlib.sha256(raw).hexdigest() != SHA256:
        print("warning: input checksum differs from the one this script was written against", file=sys.stderr)
    lat0, lon0, dlat, dlon, nr, nc = struct.unpack(">4d2i", raw[:40])
    assert (lat0, lon0, dlat, dlon, nr, nc) == (-90.0, -180.0, 0.25, 0.25, 721, 1440), "unexpected grid"
    return np.frombuffer(raw[40:], dtype=">f4").reshape(nr, nc).astype(np.float64)


def full_res(g, lat, lon):
    """Bilinear interpolation in the 15' grid (the 'truth' the coarse grid is compared against)."""
    x = ((lon + 180.0) % 360.0) / 0.25
    y = (lat + 90.0) / 0.25
    i = min(int(y), 719)
    j = int(x) % 1440
    fy, fx = y - i, x - int(x)
    j1 = (j + 1) % 1440
    return (g[i, j] * (1 - fx) * (1 - fy) + g[i, j1] * fx * (1 - fy)
            + g[i + 1, j] * (1 - fx) * fy + g[i + 1, j1] * fx * fy)


def build(g):
    k = STEP * 4
    coarse = np.rint(g[::k, ::k]).astype(np.int64)  # 91 x 180 (drops the duplicate +180 column)
    assert coarse.shape == (91, 180) and coarse.min() >= -128 and coarse.max() <= 127
    return coarse


def coarse_at(c, lat, lon):
    """Same interpolation the Kotlin code does (for error reporting)."""
    x = ((lon + 180.0) % 360.0) / STEP
    y = (lat + 90.0) / STEP
    i = min(int(y), 89)
    j = int(x) % 180
    fy, fx = y - i, x - int(x)
    j1 = (j + 1) % 180
    return (c[i, j] * (1 - fx) * (1 - fy) + c[i, j1] * fx * (1 - fy)
            + c[i + 1, j] * (1 - fx) * fy + c[i + 1, j1] * fx * fy)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    g = load(args[0])
    c = build(g)
    out = args[1] if len(args) > 1 else "../core/src/main/resources/app/cloudbase/core/egm96_2deg.s8"
    open(out, "wb").write(c.astype(np.int8).tobytes())
    print("wrote", out, c.size, "bytes")
    if "--report" in sys.argv:
        rng = np.random.default_rng(1)
        lats = np.degrees(np.arcsin(rng.uniform(-1, 1, 200000)))  # area-uniform
        lons = rng.uniform(-180, 180, 200000)
        err = np.array([coarse_at(c, a, b) - full_res(g, a, b) for a, b in zip(lats, lons)])
        ae = np.abs(err)
        print("random global points: max %.2f  p99 %.2f  p95 %.2f  median %.2f  rms %.2f"
              % (ae.max(), np.percentile(ae, 99), np.percentile(ae, 95), np.median(ae), np.sqrt((err ** 2).mean())))
        w = np.argmax(ae)
        print("worst at", lats[w], lons[w])
    if "--points" in sys.argv:
        pts = [(46.0, 7.75), (-43.5, 172.6), (51.5, -0.1), (5.0, 79.0), (0.0, 0.0), (90.0, 0.0), (-90.0, 0.0),
               (64.0, -20.0), (35.7, 139.7), (-33.9, 151.2), (40.7, -74.0), (-12.0, -77.0), (28.0, 86.9),
               (-0.5, 113.0), (61.0, -150.0), (-22.0, -45.0), (37.0, -119.0), (47.3, 12.0), (-41.3, 174.8),
               (8.0, 115.0), (-1.0, -179.9), (10.0, 179.9), (-60.0, -60.0), (78.0, 15.0), (19.5, -155.5),
               (27.0, 0.5), (-89.9, 100.0), (89.9, -100.0)]
        for a, b in pts:
            print("    doubleArrayOf(%s, %s, %.3f)," % (a, b, full_res(g, a, b)))


if __name__ == "__main__":
    main()
