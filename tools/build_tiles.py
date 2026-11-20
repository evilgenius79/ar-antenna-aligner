#!/usr/bin/env python3
"""Build the antenna-site data the app uses from the FCC's public bulk files.

Inputs (download into one folder, see tools/README.md):
    r_tower.zip   Antenna Structure Registration (registered towers)
    l_LMpriv.zip  Land mobile, private (public safety, business, utilities ...)
    l_LMcomm.zip  Land mobile, commercial
    l_LMbcast.zip Broadcast auxiliary
    l_paging.zip  Paging
    l_micro.zip   Microwave

Outputs:
    <out>/tiles/<lat>_<lon>.json.gz   one file per 1-degree cell (south-west corner in the name)
    <out>/index.json                  build date, counts, list of tiles
    <seed>                            the list bundled in the APK (Rush County, Indiana)

Usage:  python build_tiles.py <fcc_dir> <out_dir> <seed_json>

Each physical site becomes one entry: licences and registrations within 80 m of each other
(or sharing a tower registration number) are merged.
"""
import collections
import datetime
import gzip
import io
import json
import math
import os
import re
import statistics
import sys
import zipfile

MERGE_M = 80.0
# A licence citing a tower registration number joins that tower only if it is filed this close to it.
ASR_MERGE_M = 1000.0
PUBLIC_SAFETY = {'PW', 'YE', 'YF', 'GE', 'GF', 'GP', 'SG', 'SY', 'PA', 'YW', 'SP', 'SL'}
SERVICES = ['l_LMpriv', 'l_LMcomm', 'l_LMbcast', 'l_paging', 'l_micro']


def rows(z, name):
    with z.open(name) as f:
        for line in io.TextIOWrapper(f, encoding='latin-1', newline=''):
            yield line.rstrip('\r\n').split('|')


def num(x):
    try:
        return float(x)
    except (ValueError, TypeError):
        return None


def dms(d, m, s, h):
    try:
        v = float(d) + float(m or 0) / 60 + float(s or 0) / 3600
    except ValueError:
        return None
    return -v if h in ('S', 'W') else v


def dist(a, b):
    k = 111320.0
    return math.hypot((a[0] - b[0]) * k, (a[1] - b[1]) * k * math.cos(math.radians(a[0])))


def in_scope(lat, lon):
    """50 states + Puerto Rico / USVI; drops zeros and obvious junk."""
    return lat is not None and lon is not None and 17.0 <= lat <= 72.0 and -180.0 <= lon <= -64.0


# ---------------------------------------------------------------- read

def read_uls(fcc_dir, svc):
    z = zipfile.ZipFile(os.path.join(fcc_dir, svc + '.zip'))
    micro = svc == 'l_micro'
    active = {r[1]: r[6] for r in rows(z, 'HD.dat') if len(r) > 6 and r[5] == 'A'}
    locs = {}
    for r in rows(z, 'LO.dat'):
        if len(r) < 41 or r[1] not in active:
            continue
        if not micro and r[6] != 'F':
            continue
        lat = dms(r[19], r[20], r[21], r[22])
        lon = dms(r[23], r[24], r[25], r[26])
        if not in_scope(lat, lon):
            continue
        locs[(r[1], r[8])] = dict(
            src=svc[2:], call=r[4], loc=r[8], svc=active[r[1]], who='?', addr=r[11].strip(), city=r[12].strip(),
            state=r[14].strip(), ground=num(r[18]), lat=lat, lon=lon,
            asr=r[37].strip() if r[37].strip() not in ('', 'N/A') else None,
            struct=num(r[39]) or num(r[38]), stype=r[40].strip(), ant=None, freqs=set(), rep=False)
    need = {k[0] for k in locs}
    names = {r[1]: r[7] for r in rows(z, 'EN.dat') if len(r) > 7 and r[5] == 'L' and r[1] in need}
    for r in rows(z, 'FR.dat'):
        if len(r) < 11:
            continue
        rec = locs.get((r[1], r[6]))
        if rec is None or not r[10]:
            continue
        if len(rec['freqs']) < 16:
            rec['freqs'].add(round(float(r[10]), 5))
        if re.match(r'FB[2468]', r[8]):
            rec['rep'] = True
    for r in rows(z, 'AN.dat'):
        if len(r) < 12:
            continue
        rec = locs.get((r[1], r[7]))
        if rec is None:
            continue
        for i in (10, 11):
            v = num(r[i])
            if v and v < 700 and (rec['ant'] is None or v > rec['ant']):
                rec['ant'] = v
    out = []
    for (usi, _), rec in locs.items():
        if not rec['freqs'] and not micro:
            continue
        rec['who'] = names.get(usi, '?')
        rec['freqs'] = sorted(rec['freqs'])
        out.append(rec)
    print(f'{svc}: {len(out)} licensed locations', flush=True)
    return out


def read_asr(fcc_dir):
    z = zipfile.ZipFile(os.path.join(fcc_dir, 'r_tower.zip'))
    co = {}
    for r in rows(z, 'CO.dat'):
        if len(r) < 16:
            continue
        lat = dms(r[6], r[7], r[8], r[9])
        lon = dms(r[11], r[12], r[13], r[14])
        if in_scope(lat, lon):
            co[r[4]] = (lat, lon)
    own = {r[4]: r[9] for r in rows(z, 'EN.dat') if len(r) > 9 and r[5] == 'O'}
    out = []
    for r in rows(z, 'RA.dat'):
        if len(r) < 33 or r[8] != 'C' or r[4] not in co:
            continue
        out.append(dict(
            src='asr', call='ASR ' + r[3], loc='', svc='ASR', who=own.get(r[4], '?'), addr=r[23].strip(),
            city=r[24].strip(), state=r[25].strip(), ground=num(r[29]), lat=co[r[4]][0], lon=co[r[4]][1], asr=r[3],
            struct=num(r[30]) or num(r[28]), stype=r[32].strip(), ant=None, freqs=[], rep=False))
    print(f'r_tower: {len(out)} constructed registered structures', flush=True)
    return out


# ---------------------------------------------------------------- group

def cluster(recs):
    """Greedy grouping, registered towers first; a spatial hash keeps it linear."""
    cell = 0.001  # ~111 m of latitude
    grid = collections.defaultdict(list)
    by_asr = {}
    sites = []
    recs.sort(key=lambda r: (r['src'] != 'asr', r['lat'], r['lon'], r['call'], r['loc']))
    for r in recs:
        pos = (r['lat'], r['lon'])
        best = by_asr.get(r['asr']) if r['asr'] else None
        if best is not None and dist(pos, best['pos']) > ASR_MERGE_M:
            best = None  # wrong registration number on the licence
        if best is None:
            ci, cj = int(math.floor(pos[0] / cell)), int(math.floor(pos[1] / cell))
            span = 1 + int(1 / max(math.cos(math.radians(pos[0])), 0.2))
            bd = MERGE_M
            for i in (ci - 1, ci, ci + 1):
                for j in range(cj - span, cj + span + 1):
                    for s in grid.get((i, j), ()):
                        d = dist(pos, s['pos'])
                        if d < bd:
                            best, bd = s, d
            if best is None:
                best = dict(pos=pos, asrs=[], recs=[])
                sites.append(best)
                grid[(ci, cj)].append(best)
        best['recs'].append(r)
        if r['src'] == 'asr':
            best['asrs'].append(r['asr'])
            by_asr.setdefault(r['asr'], best)
    return sites


# ---------------------------------------------------------------- local fixes
# Licences filed with wrong coordinates, corrected by hand. Rush County, Indiana.

def local_fixes(sites):
    def find(call, loc=None):
        return next((s for s in sites if any(r['call'] == call and loc in (None, r['loc']) for r in s['recs'])), None)

    def merge(a, b):
        if a is None or b is None or a is b:
            return
        a['recs'] += b['recs']
        a['asrs'] = sorted(set(a['asrs']) | set(b['asrs']))
        b['recs'] = []

    pd = find('KSB300')
    if pd is not None:
        # 270 W 15th St, Rushville: police/fire licences are filed up to 700 m west of the building.
        merge(pd, find('KJD846'))
        for s in sites:
            if s is not pd and any(r['call'] == 'WRXX236' and '15TH' in r['addr'].upper() for r in s['recs']):
                merge(pd, s)
        pd['pos'] = (39.62486, -85.44689)
        pd['ground'] = 296.0
    # WRCR-FM tower, 2601 W 650 N: a paging licence is filed 170 m east of the registered tower.
    merge(find('ASR 1029906'), find('KNKO346', '72'))
    # WRCR studio at 102 N Perkins is filed with the height of the tower it links to.
    for s in sites:
        for r in s['recs']:
            if r['call'] == 'WHE41' and 'PERKINS' in r['addr'].upper():
                r['struct'] = r['ant'] = None
    sites[:] = [s for s in sites if s['recs']]


OVERRIDE = {
    'KSB300': 'Rushville Police & Fire tower', 'WZX813': 'Rush Co Sheriff & EMA', 'ASR 1029906': 'WRCR-FM tower',
    'WQTJ485': 'Rush Memorial Hospital', 'ASR 1028174': 'Indiana SAFE-T (IPSC) tower', 'KDJ565': 'Rush Co Highway garage',
}

# ---------------------------------------------------------------- naming

WHO = [(re.compile(p, re.I), n) for p, n in [
    (r'Cellco|GTE Mobilnet|Verizon', 'Verizon'), (r'T-Mobile|Sprint', 'T-Mobile'),
    (r'AT&T|CINGULAR|BellSouth Mobility', 'AT&T'), (r'DISH Wireless', 'DISH'), (r'United States Cellular|USCOC', 'UScellular'),
    (r'^SBA ', 'SBA'), (r'American Tower', 'American Tower'), (r'Crown Castle|Pinnacle Towers|Global Signal', 'Crown Castle'),
    (r'Vertical Bridge|^The Towers, LLC', 'Vertical Bridge'), (r'CSX', 'CSX railroad'),
    (r'Norfolk Southern', 'Norfolk Southern railroad'), (r'Union Pacific', 'Union Pacific railroad'), (r'BNSF', 'BNSF railroad'),
    (r'Duke Energy', 'Duke Energy'),
    # Rush County, Indiana
    (r'^RUSH, COUNTY OF', 'Rush County'), (r'RUSH COUNTY EMERGENCY', 'Rush County EMA'),
    (r'^RUSHVILLE, CITY OF', 'City of Rushville'), (r'^RUSHVILLE, TOWNSHIP OF', 'Rushville Twp Fire'),
    (r'W\.A\.T\.C\.H', 'Watch Communications'), (r'State of Indiana - IPSC', 'Indiana SAFE-T (IPSC)'),
    (r'INDIANA GAS', 'CenterPoint Energy'), (r'PHI Health', 'PHI Air Medical'), (r'AMS Spectrum', 'American Messaging'),
    (r'HARVEST LAND', 'Harvest Land Co-op'),
]]
CARRIERS = ['Verizon', 'T-Mobile', 'AT&T', 'DISH', 'UScellular']
SMALL = {'of', 'and', 'the', 'dba', 'for', 'in'}
SUFFIX = re.compile(r',? *\b(INC|LLC|L\.L\.C|CORP|CORPORATION|L\.?P|LTD|COMPANY)\b\.?', re.I)
GOV = re.compile(r'^(.+), (COUNTY|CITY|TOWN|TOWNSHIP|VILLAGE|STATE|BOROUGH|PARISH) OF\b', re.I)
_who_cache = {}


def who(n):
    v = _who_cache.get(n)
    if v is None:
        v = _who_cache[n] = _who(n)
    return v


def _who(n):
    for pat, rep in WHO:
        if pat.search(n):
            return rep
    m = GOV.match(n)
    if m:
        place, kind = m.group(1).title(), m.group(2).title()
        return f'{place} {kind}' if kind in ('County', 'Township', 'Parish') else f'{kind} of {place}'
    n = re.split(r':| DBA | D/B/A ', n, flags=re.I)[0]
    n = SUFFIX.sub('', n).strip(' ,.')
    if ',' in n:
        return n.split(',')[0].title() + ' (private)'
    if not n.isupper():
        return n
    words = []
    for w in n.split():
        lw = w.lower()
        if lw in SMALL:
            words.append(lw)
        elif len(w) <= 4 and not re.search(r'[AEIOUY]', w):
            words.append(w)  # initialism
        else:
            words.append(w.capitalize())
    return ' '.join(words) or '?'


# ---------------------------------------------------------------- categories
# One letter per category; the app shows or hides markers by these. The FCC only separates public
# safety from everything else, so the finer groups come from the licensee's name and, for
# railroads, from the band (160.215-161.565 MHz is reserved for them).

CAT_ORDER = 'PGRUSHFCBNMO'
RX_PS = re.compile(r'FIRE|POLICE|SHERIFF|\bEMS\b|AMBULANCE|RESCUE|EMERGENCY|\b911\b|LAW ENFORCEMENT|PUBLIC SAFETY', re.I)
RX_RAIL = re.compile(
    r'RAILROAD|RAILWAY|RAIL ROAD|\bRAIL\b|\bRR\b|\bCSX|NORFOLK SOUTHERN|UNION PACIFIC|\bBNSF\b|AMTRAK|CONRAIL|'
    r'KANSAS CITY SOUTHERN|CANADIAN NATIONAL|CANADIAN PACIFIC|\bCPKC\b|GRAND TRUNK|ILLINOIS CENTRAL|SOO LINE|'
    r'WISCONSIN CENTRAL|PTC-220', re.I)
RX_SCHOOL = re.compile(r'SCHOOL|\bMSD\b|UNIVERSITY|COLLEGE|ACADEMY|EDUCATION|\bISD\b|\bUSD\b', re.I)
RX_HOSP = re.compile(r'HOSPITAL|MEDICAL|HEALTH|CLINIC|NURSING', re.I)
RX_UTIL = re.compile(
    r'ELECTRIC|\bPOWER\b|ENERGY|\bREMC\b|\bEMC\b|\bGAS\b|\bWATER|UTILIT|PIPELINE|PIPE LINE|SEWER|EDISON|'
    r'\bLIGHT\b|SANITARY|IRRIGATION', re.I)
RX_FARM = re.compile(
    r'\bFARM|GRAIN|\bAG\b|AGRI|CO-OP|COOPERATIVE|\bCOOP\b|\bSEED|DAIRY|RANCH|HARVEST|FERTILIZER|\bFEED\b|ORCHARD|CATTLE', re.I)
# Only applied to licences in the public-safety pool, where the holder is already a government body.
RX_GOVSVC = re.compile(
    r'HIGHWAY|STREET|PUBLIC WORKS|\bROAD|TRANSPORTATION|\bDOT\b|\bPARK|SANITATION|TRANSIT|AIRPORT|SOLID WASTE|'
    r'DRAINAGE|CONSERVATION|FOREST|NATURAL RESOURCES|HOUSING|LIBRARY|ANIMAL', re.I)
RX_GOV = re.compile(
    r', (COUNTY|CITY|TOWN|TOWNSHIP|VILLAGE|STATE|BOROUGH|PARISH) OF\b|^(COUNTY|CITY|TOWN|TOWNSHIP|VILLAGE|STATE) OF\b|'
    r'MUNICIPAL|DEPARTMENT OF|DEPT OF|PUBLIC TRANSIT|\bAUTHORITY\b|COMMISSION', re.I)
TOWER_COS = {'SBA', 'American Tower', 'Crown Castle', 'Vertical Bridge'}
RAIL_BAND = (160.215, 161.565)


def name_cat(n, default):
    """Category a licensee name points to, or [default] when it says nothing."""
    for rx, c in ((RX_PS, 'P'), (RX_RAIL, 'R'), (RX_SCHOOL, 'S'), (RX_HOSP, 'H'), (RX_UTIL, 'U'), (RX_FARM, 'F')):
        if rx.search(n):
            return c
    if RX_GOV.search(n):
        return 'G'
    return default


def rec_cats(r):
    n = r['who']
    w = who(n)
    cell = w in CARRIERS or w in TOWER_COS
    if r['src'] == 'asr':
        extra = None if cell else name_cat(n, None)
        return {'C'} | ({extra} if extra else set())
    if r['src'] == 'LMbcast':
        return {'B'}
    if r['src'] == 'paging':
        return {'N'}
    if r['src'] == 'micro':
        return {'C'} if cell else {name_cat(n, 'M')}
    if r['svc'] in PUBLIC_SAFETY:
        # Counties and cities usually hold police and fire on a licence in their own name.
        for rx, c in ((RX_PS, 'P'), (RX_RAIL, 'R'), (RX_SCHOOL, 'S'), (RX_HOSP, 'H'), (RX_UTIL, 'U'), (RX_GOVSVC, 'G')):
            if rx.search(n):
                return {c}
        return {'P'}
    if cell:
        return {'C'}
    if any(RAIL_BAND[0] <= f <= RAIL_BAND[1] for f in r['freqs']) and not RX_PS.search(n):
        return {'R'}
    return {name_cat(n, 'O')}


def site_cats(recs):
    cs = set()
    for r in recs:
        cs |= rec_cats(r)
    if len(cs) > 1:
        cs.discard('M')  # a microwave dish on a site that is known for something else
    return ''.join(c for c in CAT_ORDER if c in cs)


PRI = collections.defaultdict(lambda: 3)
PRI.update({s: 0 for s in PUBLIC_SAFETY})
PRI.update({'GO': 1, 'IG': 1, 'YG': 1, 'YO': 1, 'GB': 1, 'YB': 1, 'CD': 2, 'GS': 2, 'ASR': 5})


def fmt_freq(x):
    return f'{x:.4f}'.rstrip('0').rstrip('.')


def describe(s, build):
    recs = s['recs']
    recs.sort(key=lambda r: (4 if r['src'] == 'micro' else PRI[r['svc']], r['call'], r['loc']))
    names = []
    for r in recs:
        w = who(r['who'])
        if w not in names:
            names.append(w)
    carriers = [c for c in CARRIERS if c in names]
    asr_owner = next((who(r['who']) for r in recs if r['src'] == 'asr'), None)
    others = [n for n in names if n not in CARRIERS and n != asr_owner]
    lm = [r for r in recs if r['src'] not in ('asr', 'micro')]
    ov = next((v for k, v in OVERRIDE.items() if any(r['call'] == k for r in recs)), None)
    city = next((r['city'].title() for r in recs if r['city']), '')
    state = next((r['state'] for r in recs if r['state']), '')

    if ov:
        name = ov
    elif lm:
        first = who(lm[0]['who'])
        name = first + (f' +{len(names) - 1}' if len(names) > 1 else '')
    elif carriers:
        name = 'Cell tower: ' + ', '.join(carriers)
    elif others:
        name = others[0] + ' link'
    else:
        name = f'Tower: {asr_owner}'
    if not ov and city and city.lower() not in name.lower():
        name += f' ({city})'

    tags = ''
    if any(r['svc'] in PUBLIC_SAFETY for r in recs):
        tags += 'P'
    if s['asrs']:
        tags += 'T'
    if any(r['svc'] not in PUBLIC_SAFETY for r in lm):
        tags += 'B'
    if not tags:
        tags = 'M'

    grounds = [r['ground'] for r in recs if r['ground'] and -100 < r['ground'] < 4500]
    ground = s.get('ground') or (statistics.median(grounds) if grounds else None)
    hs = [h for r in recs for h in (r['struct'], r['ant']) if h and h < 700]
    h = max(hs) if hs else None
    addr = next((r['addr'] for r in recs if r['addr']), '')
    calls = sorted({r['call'] for r in recs if r['src'] != 'asr'})
    fr = sorted({x for r in recs for x in r['freqs'] if x < 3000})
    mw = any(r['src'] == 'micro' for r in recs)
    stype = next((r['stype'] for r in recs if r['stype']), '')
    parts = []
    if addr:
        parts.append(', '.join(x for x in (addr.title(), city, state) if x) + '.')
    if len(names) > 1 or ov:
        parts.append('Users: ' + ', '.join(names[:6]) + ('…' if len(names) > 6 else '') + '.')
    if fr:
        parts.append(' / '.join(fmt_freq(x) for x in fr[:10]) + (' …' if len(fr) > 10 else '') + ' MHz.')
    if mw:
        parts.append('Microwave links.')
    if h and ground is not None:
        parts.append(f'Ground {ground:.0f} m + {h:.0f} m{" " + stype.lower() if stype else ""}.')
    if calls:
        parts.append(', '.join(calls[:6]) + ('…' if len(calls) > 6 else '') + '.')
    if s['asrs']:
        parts.append('ASR ' + ', '.join(sorted(s['asrs'])[:3]) + '.')
    parts.append(f'src: FCC ULS/ASR {build}')
    key = sorted(s['asrs'])[0] if s['asrs'] else f"{calls[0]}-{min(r['loc'] for r in recs if r['call'] == calls[0])}"
    return dict(
        id='fcc:' + key, name=name, lat=round(s['pos'][0], 6), lon=round(s['pos'][1], 6),
        alt=round(ground + (h or 0), 1) if ground is not None else None,
        type='REPEATER' if any(r['rep'] for r in recs) else 'TOWER', tags=tags, cats=site_cats(recs),
        note=' '.join(parts))


# ---------------------------------------------------------------- write

def write_tiles(entries, out_dir, build):
    tiles = collections.defaultdict(list)
    for e in entries:
        tiles[(math.floor(e['lat']), math.floor(e['lon']))].append(e)
    tdir = os.path.join(out_dir, 'tiles')
    os.makedirs(tdir, exist_ok=True)
    for old in os.listdir(tdir):
        os.remove(os.path.join(tdir, old))
    index = {}
    total = 0
    for (la, lo), es in sorted(tiles.items()):
        es.sort(key=lambda e: e['id'])
        # New fields go on the end: older app versions read by position and ignore the rest.
        body = {'v': 1, 'built': build,
                'fields': ['id', 'name', 'lat', 'lon', 'altMeters', 'type', 'tags', 'note', 'cats'],
                'sites': [[e['id'], e['name'], e['lat'], e['lon'], e['alt'], e['type'], e['tags'], e['note'], e['cats']]
                          for e in es]}
        raw = json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        name = f'{la}_{lo}.json.gz'
        with open(os.path.join(tdir, name), 'wb') as f:
            # mtime=0 keeps the output byte-identical between runs when the data has not changed
            with gzip.GzipFile(fileobj=f, mode='wb', mtime=0) as g:
                g.write(raw)
        index[f'{la}_{lo}'] = len(es)
        total += os.path.getsize(os.path.join(tdir, name))
    with open(os.path.join(out_dir, 'index.json'), 'w', encoding='utf-8') as f:
        json.dump({'v': 1, 'built': build, 'sites': len(entries), 'tiles': index}, f, separators=(',', ':'))
    print(f'{len(entries)} sites in {len(tiles)} tiles, {total / 1e6:.1f} MB gzipped')


# Towers mapped in OpenStreetMap near Rushville with no FCC record (c) OpenStreetMap contributors.
SEED_OSM = [(39.75025, -85.55464), (39.78726, -85.27068), (39.7928, -85.27102), (39.52422, -85.46084),
            (39.52727, -85.37638), (39.61202, -85.29059), (39.74373, -85.36136), (39.63748, -85.44603)]
SEED_BOX = (39.43, 39.80, -85.66, -85.27)


def write_seed(entries, path):
    la0, la1, lo0, lo1 = SEED_BOX
    out = [dict(e) for e in entries if la0 <= e['lat'] <= la1 and lo0 <= e['lon'] <= lo1]
    for p in SEED_OSM:
        if min(dist(p, (t['lat'], t['lon'])) for t in out) > 150:
            out.append(dict(id=f'osm:{p[0]},{p[1]}', name='Tower (not in FCC data)', lat=p[0], lon=p[1], alt=None,
                            type='TOWER', tags='T', cats='C',
                            note='Mapped in OpenStreetMap; no FCC record found, height unknown. (c) OpenStreetMap contributors'))
    cnt = collections.Counter(t['name'] for t in out)
    seen = collections.Counter()
    for t in sorted(out, key=lambda t: (t['name'], -t['lat'])):
        if cnt[t['name']] > 1:
            seen[t['name']] += 1
            t['name'] += f" #{seen[t['name']]}"
    out.sort(key=lambda t: t['name'])
    final = [dict(id='seed:kind', name='KIND Indianapolis Intl', lat=39.7173, lon=-86.2944, altMeters=241.0, type='AIRPORT', cats='A',
                  note='Sanity check: from Rushville this bears ~280 true (W), ~45 mi')]
    for t in out:
        d = dict(id=t['id'], name=t['name'], lat=t['lat'], lon=t['lon'])
        if t['alt'] is not None:
            d['altMeters'] = t['alt']
        d['type'] = t['type']
        d['cats'] = t['cats']
        d['note'] = t['note']
        final.append(d)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('[\n' + ',\n'.join('  ' + json.dumps(t, ensure_ascii=False) for t in final) + '\n]\n')
    print(f'seed: {len(final)} entries -> {path}')


def main():
    fcc_dir, out_dir, seed = sys.argv[1], sys.argv[2], sys.argv[3]
    build = sys.argv[4] if len(sys.argv) > 4 else datetime.date.today().strftime('%Y-%m')
    recs = read_asr(fcc_dir)
    for svc in SERVICES:
        recs += read_uls(fcc_dir, svc)
    sites = cluster(recs)
    print(f'{len(recs)} records -> {len(sites)} sites', flush=True)
    local_fixes(sites)
    entries = [describe(s, build) for s in sites]
    # ids must be unique; a call sign location can only be the key of one site, but be safe
    seen = set()
    uniq = []
    for e in entries:
        if e['id'] in seen:
            e['id'] += f"@{e['lat']},{e['lon']}"
        seen.add(e['id'])
        uniq.append(e)
    print('categories:', dict(collections.Counter(c for e in uniq for c in e['cats']).most_common()))
    write_tiles(uniq, out_dir, build)
    write_seed(uniq, seed)


if __name__ == '__main__':
    main()
