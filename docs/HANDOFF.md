# HANDOFF — AR Antenna Aligner (Android)

**For:** a fresh Claude Code session tasked with building the Android app.
**From:** a session working on the owner's police-scanner / SDR hobby rig (`F:\police scanner`, public at rushcounty.online).
**Date:** 2026-11-04.

---

## 1. The vision (read this first)

The owner runs a radio/SDR setup and aims **directional antennas** (yagis, beams) at specific
real-world RF targets: transmitter towers, repeater sites, an airport ~45 mi away, etc. Aiming them by
hand/compass is fiddly.

**The app:** hold up the phone, look through the camera, and see **world-locked virtual markers**
floating over the real landscape showing the **compass bearing (and elevation) to each RF target** — a
"virtual tower / pin" sitting exactly where that tower actually is on the horizon. Walk the antenna
around until it points at the marker. That's the whole product.

Think: **Star Walk / Sky Guide, but pointed at the horizon for antennas.** This is
**location-based augmented reality** (aka geo-AR / geospatial AR).

---

## 2. Doability + recommended approach

**Verdict: clearly doable.** The MVP is a well-trodden path and does NOT require ARCore.

Two implementation paths — **start with Path A:**

### Path A — Sensor-based overlay (RECOMMENDED MVP)
Camera preview as the background, a transparent overlay on top, markers positioned by math from:
- **GPS** (device lat/lon/alt) — `FusedLocationProviderClient`.
- **Orientation** (where the phone is pointing) — `SensorManager`, `TYPE_ROTATION_VECTOR`
  (fused gyro+accel+mag; smoother & more robust than raw magnetometer).
- **Target list** (lat/lon/alt of each RF site).
Compute bearing + elevation + distance to each target, project onto the screen, draw a marker.

Pros: works anywhere, offline, no API keys, no cloud coverage dependency. Cons: accuracy is bounded by
the phone's compass (see §5 — the big gotcha).

### Path B — ARCore Geospatial API (ADVANCED, optional later)
Google's ARCore **Geospatial API** places anchors at real lat/lon/altitude using VPS (Visual Positioning
Service) + device motion, giving drift-free, far more accurate world-locked anchors. Downsides: needs
Google Play Services for AR, a Google Cloud **API key**, ARCore-supported device, and VPS/terrain
coverage (rural areas may fall back to GPS anyway). **Do not start here.** Consider it only if Path A's
compass accuracy proves insufficient after the mitigations in §5.

---

## 3. Recommended stack

- **Language:** Kotlin. **UI:** Jetpack Compose (overlay can be a `Canvas`/custom composable over the
  camera), or classic Views if simpler for the builder.
- **Camera:** CameraX (`PreviewView`). Read the real camera **field of view** from Camera2
  characteristics (`LENS_INFO`, focal length + `SENSOR_INFO_PHYSICAL_SIZE`) — needed for accurate
  projection; don't hardcode 60°.
- **Orientation:** `SensorManager` → `TYPE_ROTATION_VECTOR` → `getRotationMatrix` /
  `remapCoordinateSystem` for camera orientation → derive azimuth/pitch/roll. Low-pass filter to reduce
  jitter.
- **Location:** `FusedLocationProviderClient` (play-services-location). Request fine location at startup.
- **Magnetic→true north:** `android.hardware.GeomagneticField(lat, lon, alt, timeMillis)` → returns
  **declination**. ALWAYS convert magnetic heading to true north with it. (Central Indiana declination is
  small — a few degrees west — but it must be applied; do NOT hardcode, compute it live.)
- **Min SDK:** 26+ is fine.

---

## 4. The core math

Given device `(latD, lonD, altD)` and target `(latT, lonT, altT)`:

- **Distance (haversine)** → meters.
- **Initial bearing (great-circle), degrees true:**
  `θ = atan2( sin(Δlon)·cos(latT),  cos(latD)·sin(latT) − sin(latD)·cos(latT)·cos(Δlon) )`, normalized 0–360.
- **Elevation angle:** `atan2(Δalt, groundDistance)` (≈0° for distant targets; nonzero for nearby/high
  sites). Optionally correct for earth curvature at long range.

**Projection to screen (two options):**
- *Quick MVP:* `Δaz = normalize180(targetBearing − deviceHeadingTrue)`. If `|Δaz| < horizFOV/2`, then
  `screenX = cx + (Δaz / (horizFOV/2)) · (width/2)`; similarly `screenY` from `(targetElev − devicePitch)`
  and vertical FOV. Ignores roll — acceptable to prove the concept.
- *Accurate (do this for v1):* build the **ENU unit vector** (East, North, Up) to the target from bearing
  & elevation, rotate it into the **device/camera frame** using the sensor rotation matrix, then project
  with the real camera intrinsics. Handles roll/tilt correctly and is the difference between "roughly
  there" and "sits on the tower."

---

## 5. Compass accuracy — normal good practice (not a crisis)

Heading comes from the phone's compass, so standard compass hygiene applies — but the app is used
**out in the open, away from the antenna masts** (the user confirmed: non-metal roof, and it's used
standing clear of poles/metal), so this is an ordinary magnetometer environment, **not** a worst-case
one. Don't over-engineer it. Do the following and move on:

1. **Prefer `TYPE_ROTATION_VECTOR`** (sensor-fused gyro+accel+mag) over the raw magnetometer — smoother
   and rides through brief disturbances.
2. **Apply magnetic declination** via `GeomagneticField` so headings are true-north (easy to forget;
   a few degrees off otherwise).
3. **Show a small compass-accuracy indicator** from `onAccuracyChanged`, and prompt a **figure-8
   calibration** if it reports low — the standard Android pattern.
4. **Optional nice-to-have — "sight-in" calibration:** let the user aim at a known landmark (or map pin)
   and tap to zero out any residual heading offset. Cheap to add, handy if they ever *do* align right next
   to a big metal mast, but not required for normal use.

That's it — with a non-metal roof and open-air use, the compass will behave. The real accuracy limits are
GPS position and camera-FOV calibration (§4), which matter more than magnetic interference here.

---

## 6. Target data model

```
Target {
  name: String        // "KIND Airport", "SAFE-T Tower", "School DMR 461.450"
  lat: Double
  lon: Double
  altMeters: Double    // AMSL; 0/estimate ok for distant ground sites
  type: Enum           // AIRPORT | TOWER | REPEATER | CUSTOM
  note: String?        // freq, callsign, etc.
}
```
- Load from a bundled/editable **JSON** file; let the user **add points** (enter lat/lon, or drop a pin on
  a map, or "use my current location" to save a spot).
- **Seed example (known good):** `KIND Indianapolis Intl ≈ 39.7173, -86.2944, alt ~241 m` — from the
  owner's location near **Rushville, IN** this bears roughly **280° (WNW), ~45 mi**. Use it to sanity-check
  the whole pipeline (if the KIND marker lands ~WNW, the math/compass are right).

### Where to get real tower coordinates (tie-in to the owner's existing data)
The owner has an FCC license report at `F:\police scanner\reference\FCC_License_Report_Rush_County_IN_deduped.csv`
(Entity, Callsign, Frequency, Code, Svc, City). It has **callsigns but not coordinates**. The **FCC ULS
license search** (wireless2.fcc.gov) returns **transmitter lat/lon by callsign** (e.g. `WZX813` Rush County,
`WRXX236` Rush County Schools, `KVC540` Raleigh Fire). RadioReference also lists many site coords. The app
should ultimately let the user paste/import these. For the first build, hardcoding KIND + 1–2 known sites is
enough to validate.

---

## 7. Suggested milestones

1. **M1 – Camera + sensors:** CameraX preview; overlay that prints live true-north heading, pitch, GPS,
   and compass-accuracy status. (Proves sensors before any AR.)
2. **M2 – One marker:** hardcode KIND; draw a pin at its bearing/elevation; verify it sits WNW when you
   turn toward Indianapolis.
3. **M3 – Accurate projection:** full rotation-matrix/ENU projection; marker stays glued to the spot as
   you pan/tilt/roll. Add distance + label.
4. **M4 – Target list + add/edit:** JSON-backed targets, user-added points, map-pin picker.
5. **M5 – Sight-in calibration** (§5.3) + accuracy UX. This is what makes it trustworthy on a roof.
6. **M6 – Polish:** multiple markers, distance rings, a nicer "virtual tower" glyph, night mode, haptic
   "on target" when the antenna heading matches a marker.

## 8. Nice-to-haves (later)
- 3D tower model instead of a flat pin (Filament / SceneView) — cosmetic; the 2D pin is functionally fine.
- "Antenna mode": enter the antenna's beamwidth; show a cone and a clear on/off-target indicator.
- Elevation/tilt guidance for the physical downtilt.
- Import directly from the scanner project (shared targets JSON served by rushcounty.online).
- Share/save a bearing; offline map tiles.

## 9. How to test accuracy
Stand at a known point (in the open, away from masts — the normal use case), aim the phone at a landmark
whose true bearing you can get from a map (Google Earth "ruler" gives true bearing between two points) and
compare the app's heading. Confirm the KIND marker lands ~280° when you turn toward Indianapolis. Focus
testing on projection correctness and GPS, not magnetic edge cases.

## 10. References
- ARCore Geospatial API: https://developers.google.com/ar/develop/geospatial (Path B only)
- Android sensors / rotation vector: https://developer.android.com/develop/sensors-and-location/sensors
- `GeomagneticField`: https://developer.android.com/reference/android/hardware/GeomagneticField
- CameraX: https://developer.android.com/media/camera/camerax
- Bearing/distance formulas: https://www.movable-type.co.uk/scripts/latlong.html
- FCC ULS license search (tower coords by callsign): https://wireless2.fcc.gov/UlsApp/UlsSearch/searchLicense.jsp

---

**Start at M1 and build incrementally.** The real work is getting the **projection math right** (§4 —
targets stay glued to the correct spot as you pan/tilt) and clean GPS/FOV handling. Compass is ordinary
good-practice (§5), not a crisis — the app is used in the open away from metal. Good luck.
