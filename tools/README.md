# Data tools

Python 3.9+, standard library only.

## Antenna sites (`build_tiles.py`)

Builds the per-degree tiles on the `data` branch and the list bundled in the APK.

1. Download the FCC weekly bulk files into one folder (about 760 MB):

   ```
   for f in r_tower l_LMpriv l_LMcomm l_LMbcast l_paging l_micro; do
     curl -LO https://data.fcc.gov/download/pub/uls/complete/$f.zip
   done
   ```

2. Run it (about two minutes, needs a few GB of RAM):

   ```
   python tools/build_tiles.py <fcc_folder> <out_folder> app/src/main/assets/targets.json 2026-11
   ```

   The last argument is the date stamped into each site's note.

3. Publish: copy `<out_folder>/tiles` and `<out_folder>/index.json` onto the `data` branch and push.

4. If the bundled list changed, copy the previous `app/src/main/assets/targets.json` to
   `targets_seed_v<N>.json`, add it to the old-seed list in `TargetRepository.load()`, and bump
   `TargetRepository.SEED_VERSION`. Existing installs then pick up the new list without losing
   entries the user added or edited.

### Tile format

`tiles/<lat>_<lon>.json.gz`, named for the south-west corner of a 1° cell (`39_-86` covers
39–40° N, 86–85° W):

```json
{"v": 1, "built": "2026-11",
 "fields": ["id", "name", "lat", "lon", "altMeters", "type", "tags", "note", "cats"],
 "sites": [["fcc:1028174", "Indiana SAFE-T (IPSC) tower", 39.620083, -85.406889, 396.8, "REPEATER", "PT", "...", "PC"]]}
```

- `id` is stable between builds: the tower registration number, or call sign plus location number.
- `altMeters` is ground elevation plus structure or antenna height, above mean sea level. May be null.
- `tags`: `T` registered tower, `P` public safety, `B` other land mobile, `M` microwave only.
  These drive the tick boxes when adding an area.
- `cats`: who uses the site, for hiding and showing groups in the app. `P` police/fire/EMS,
  `G` government, `R` railroad, `U` utilities, `S` schools, `H` hospitals, `F` farm,
  `C` cell carriers and registered towers, `B` broadcast, `N` paging, `M` microwave links,
  `O` other business. See `rec_cats()` for the rules.
- New fields are only ever added at the end; the app reads by position, so older versions keep working.

### How sites are built

- Only active licences with a fixed location, and registered structures marked as constructed.
- Records within 80 m are merged into one site. A licence that cites a tower registration number
  joins that tower if it is filed within 1 km of it.
- `local_fixes()` holds hand corrections for licences filed with wrong coordinates.

## ZIP centroids (`build_zips.py`)

Converts the Census ZCTA gazetteer into `app/src/main/assets/zips.csv` (`zip,lat,lon`).

```
python tools/build_zips.py 2025_Gaz_zcta_national.txt app/src/main/assets/zips.csv
```

Source: https://www.census.gov/geographies/reference-files/time-series/geo/gazetteer-files.html
