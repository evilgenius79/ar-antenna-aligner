# Antenna site data

Data branch for [AR Antenna Aligner](https://github.com/evilgenius79/ar-antenna-aligner). The app
downloads these files when you add antennas near a ZIP code.

- `tiles/<lat>_<lon>.json.gz`: antenna sites in one 1° cell, named for its south-west corner.
- `index.json`: build date and the number of sites in each tile.

Built from FCC Universal Licensing System and Antenna Structure Registration bulk files (US public
domain) by `tools/build_tiles.py` on the `main` branch. The format is described in
`tools/README.md` there.
