#!/usr/bin/env python3
"""d3-celestial stars.6.json (Hipparcos, J2000) -> src/main/assets/star_catalog_j2000.bin

Format (big endian): int32 count, then count * (float32 ra_deg, float32 dec_deg, float32 mag),
sorted by magnitude (brightest first).
Kaynak: https://github.com/ofrohn/d3-celestial (BSD-3-Clause, Olaf Frohn) -> NOTICE_STAR_CATALOG.txt
Kullanim: python3 tools/build_star_catalog.py <stars.6.json> [max_mag=5.5]
"""
import json, struct, sys

src = sys.argv[1]
max_mag = float(sys.argv[2]) if len(sys.argv) > 2 else 5.5
with open(src, encoding="utf-8") as fh:
    feats = json.load(fh)["features"]
stars = []
for f in feats:
    mag = float(f["properties"]["mag"])
    lon, lat = f["geometry"]["coordinates"]
    if mag <= max_mag:
        stars.append((mag, lon % 360.0, lat))
stars.sort()
out = "src/main/assets/star_catalog_j2000.bin"
with open(out, "wb") as fh:
    fh.write(struct.pack(">i", len(stars)))
    for mag, ra, dec in stars:
        fh.write(struct.pack(">fff", ra, dec, mag))
print(len(stars), "yildiz ->", out)
