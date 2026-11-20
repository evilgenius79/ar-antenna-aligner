#!/usr/bin/env python3
"""Turn the Census ZCTA gazetteer into the compact ZIP -> centroid table bundled in the APK.

Usage: python build_zips.py <Gaz_zcta_national.txt> <app/src/main/assets/zips.csv>
Source: https://www.census.gov/geographies/reference-files/time-series/geo/gazetteer-files.html
"""
import sys

src, out = sys.argv[1], sys.argv[2]
n = 0
with open(src, encoding='utf-8-sig') as f, open(out, 'w', encoding='ascii', newline='\n') as o:
    header = [h.strip() for h in f.readline().replace('|', '\t').split('\t')]
    zi, la, lo = header.index('GEOID'), header.index('INTPTLAT'), header.index('INTPTLONG')
    for line in f:
        c = [x.strip() for x in line.replace('|', '\t').split('\t')]
        if len(c) <= lo:
            continue
        o.write(f'{c[zi]},{float(c[la]):.4f},{float(c[lo]):.4f}\n')
        n += 1
print(n, 'ZIP codes')
