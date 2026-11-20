package online.rushcounty.aligner.ui

import android.content.res.Configuration
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import online.rushcounty.aligner.ar.CameraIntrinsics
import online.rushcounty.aligner.ar.Projector
import online.rushcounty.aligner.data.Category
import online.rushcounty.aligner.data.Settings
import online.rushcounty.aligner.data.Target
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.sat.Astro
import online.rushcounty.aligner.sat.OrbitMath
import kotlinx.coroutines.delay
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.LookAngles
import online.rushcounty.aligner.sensors.Fix
import online.rushcounty.aligner.sensors.OrientationState

const val SUN_ID = "sun"
const val MOON_ID = "moon"

/** Within this many degrees of the selected target counts as "on target" (green + haptic). */
private const val ON_TARGET_DEG = 1.5

/** A target with its look angles from the current fix. */
data class Placed(val target: Target, val look: LookAngles)

/** The selected satellite's next-24h path in the sky and where to aim a fixed antenna. */
data class SatTrack(val points: List<LookAngles>, val centerAz: Double, val centerEl: Double) {
    val elSwing: Double get() = points.maxOf { it.elevationDeg } - points.minOf { it.elevationDeg }
    val azSwing: Double get() {
        val d = points.map { GeoMath.normalize180(it.bearingDeg - centerAz) }
        return d.max() - d.min()
    }
    /** Worth aiming at the center rather than the current position. */
    val wobbles: Boolean get() = elSwing > 2.0 || azSwing > 2.0
}

@Composable
fun ArScreen(vm: AppViewModel, cameraGranted: Boolean, locationGranted: Boolean) {
    val orient by vm.orientation.state.collectAsStateWithLifecycle()
    val fix by vm.location.fix.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val intrinsics by vm.intrinsics.collectAsStateWithLifecycle()
    val satellites by vm.satellites.collectAsStateWithLifecycle()
    val shownSats by vm.shownSats.collectAsStateWithLifecycle()

    // Satellites move; re-evaluate their positions every couple of seconds.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
        }
    }

    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }
    var sightInMode by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var filtering by remember { mutableStateOf(false) }

    val observer = fix?.let { GeoPoint(it.lat, it.lon, it.altMeters) }
    val groundPlaced = remember(fix, targets, settings.maxTowerRangeKm, settings.hiddenCats, settings.selectedTargetId) {
        val f = fix ?: return@remember emptyList()
        val obs = GeoPoint(f.lat, f.lon, f.altMeters)
        val limitM = settings.maxTowerRangeKm * 1000
        targets.filter { it.visible && (Category.shown(it, settings.hiddenCats) || it.id == settings.selectedTargetId) }.map { t -> Placed(t, GeoMath.lookAngles(obs, t.toGeoPoint(f.altMeters))) }
            .filter { pl ->
                val ranged = pl.target.type == TargetType.TOWER || pl.target.type == TargetType.REPEATER
                !ranged || limitM <= 0 || pl.look.distanceMeters <= limitM || pl.target.id == settings.selectedTargetId
            }
    }
    val satPlaced = remember(fix, satellites, shownSats, now) {
        val obs = observer ?: return@remember emptyList()
        satellites.filter { it.noradId in shownSats }.mapNotNull { el ->
            val pos = OrbitMath.positionEcef(el, now)
            val look = OrbitMath.lookAngles(obs, pos)
            if (look.elevationDeg < -1.0) return@mapNotNull null // behind the Earth
            Placed(
                Target(
                    id = satTargetId(el.noradId), name = el.name, lat = pos.latitudeDeg, lon = pos.longitudeDeg,
                    type = TargetType.SATELLITE,
                    note = "over ${Format.longitude(pos.longitudeDeg)} · incl ${Format.deg(el.inclinationDeg)}",
                ),
                look,
            )
        }
    }
    val skyPlaced = remember(fix, now, settings.showSunMoon) {
        val obs = observer ?: return@remember emptyList()
        if (!settings.showSunMoon) return@remember emptyList()
        val sun = Astro.apparent(obs, Astro.sunEcef(now))
        val moon = Astro.apparent(obs, Astro.moonEcef(now))
        val illum = (Astro.moonIllumination(now) * 100).toInt()
        listOfNotNull(
            Placed(Target(id = SUN_ID, name = "Sun", lat = 0.0, lon = 0.0, type = TargetType.CELESTIAL), sun)
                .takeIf { sun.elevationDeg > -1.0 },
            Placed(Target(id = MOON_ID, name = "Moon", lat = 0.0, lon = 0.0, type = TargetType.CELESTIAL, note = "$illum% lit"), moon)
                .takeIf { moon.elevationDeg > -1.0 },
        )
    }
    val placed = groundPlaced + satPlaced + skyPlaced
    val selected = placed.firstOrNull { it.target.id == settings.selectedTargetId }

    // Daily path of the selected satellite (recomputed every 10 minutes).
    val selectedSat = satellites.firstOrNull { satTargetId(it.noradId) == settings.selectedTargetId }
    val track = remember(selectedSat, fix, now / 600_000) {
        val obs = observer ?: return@remember null
        val el = selectedSat ?: return@remember null
        val pts = OrbitMath.track(el, obs, now)
        OrbitMath.trackCenter(pts)?.let { (az, elv) -> SatTrack(pts, az, elv) }
    }
    // Clarke belt as seen from here: one point per degree of longitude.
    val belt = remember(fix?.let { (it.lat * 100).toInt() to (it.lon * 100).toInt() }) {
        val obs = observer ?: return@remember emptyList()
        (-180..180).map { OrbitMath.lookAngles(obs, OrbitMath.geoSlot(it.toDouble())) }
    }
    // Where a fixed antenna should point: the daily center for a wobbling satellite, else the target itself.
    val aim: Pair<Double, Double>? = when {
        selected == null -> null
        track != null && track.wobbles -> track.centerAz to track.centerEl
        else -> selected.look.bearingDeg to selected.look.elevationDeg
    }

    val declination = fix?.declinationDeg ?: 0.0
    val projector = orient.rotation?.takeIf { viewW > 0 && viewH > 0 }?.let { r ->
        Projector(r, declination + settings.headingOffsetDeg, viewW, viewH, focalPx(intrinsics, viewW, viewH, settings))
    }
    // Browsing: after a drag the view is a free-look (true heading, pitch) instead of where the phone points.
    val look = browse
    val viewProjector = if (look != null && projector != null) {
        Projector(
            Projector.rotationFor(look.first - projector.headingCorrectionDeg, look.second),
            projector.headingCorrectionDeg, viewW, viewH, projector.focalPx,
        )
    } else projector

    // Haptic tick on entering the on-target cone of the selected target.
    val onTarget = projector != null && aim != null &&
        projector.offBoresightDeg(aim.first, aim.second) <= ON_TARGET_DEG
    val satsInBeam = projector?.let { p ->
        satPlaced.filter {
            it.look.elevationDeg > 0 &&
                p.offBoresightDeg(it.look.bearingDeg, it.look.elevationDeg) <= settings.beamwidthDeg / 2
        }
    }.orEmpty()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(onTarget) {
        if (onTarget && settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) {
            CameraPreview(onIntrinsics = vm::setIntrinsics)
        } else {
            Text(
                "Camera permission needed for the live view.\nMarkers still work over black.",
                color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        if (settings.nightMode) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        // The camera no longer matches the view while browsing, so cover it.
        if (browse != null) Box(Modifier.fillMaxSize().background(Color(0xFF0B1118)))

        ArOverlay(
            projector = viewProjector,
            placed = placed,
            selectedId = settings.selectedTargetId,
            settings = settings,
            onTarget = onTarget && browse == null,
            belt = if (settings.showClarkeBelt && satellites.isNotEmpty()) belt else emptyList(),
            track = track,
            onSize = { w, h -> viewW = w; viewH = h },
            onTapTarget = { vm.select(it) },
            onPan = { dx, dy ->
                val p = viewProjector
                if (p != null && !sightInMode) {
                    val cur = browse ?: (p.cameraHeadingTrueDeg() to p.cameraPitchDeg())
                    // The sky follows the finger: drag right looks left, drag down looks up.
                    val pitch = (cur.second + Math.toDegrees((dy / p.focalPx).toDouble())).coerceIn(-89.0, 89.0)
                    val perPx = Math.toDegrees(1.0 / p.focalPx) / kotlin.math.cos(Math.toRadians(pitch)).coerceAtLeast(0.2)
                    browse = GeoMath.normalize360(cur.first - dx * perPx) to pitch
                }
            },
        )

        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panel: @Composable (Modifier) -> Unit = { mod ->
            if (sightInMode) {
                SightInPanel(
                    modifier = mod,
                    projector = projector,
                    selected = selected,
                    sunUp = skyPlaced.any { it.target.id == SUN_ID && it.look.elevationDeg > 1.0 },
                    onPickSun = { vm.select(SUN_ID) },
                    settings = settings,
                    onSet = { known ->
                        projector?.let { p ->
                            val raw = p.cameraHeadingTrueDeg() - settings.headingOffsetDeg
                            vm.sightIn(known, raw)
                        }
                        sightInMode = false
                    },
                    onClear = { vm.clearSightIn(); sightInMode = false },
                    onCancel = { sightInMode = false },
                )
            } else {
                BottomPanel(
                    modifier = mod,
                    projector = projector,
                    selected = selected,
                    aim = aim,
                    track = track,
                    satsInBeam = satsInBeam,
                    anySats = satPlaced.isNotEmpty(),
                    settings = settings,
                    onTarget = onTarget,
                    onTargets = { vm.show(Screen.TARGETS) },
                    onFilter = { filtering = true },
                    onSightIn = { browse = null; sightInMode = true },
                    onSettings = { vm.show(Screen.SETTINGS) },
                    onNight = { vm.updateSettings { it.copy(nightMode = !it.nightMode) } },
                )
            }
        }
        if (!landscape) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Hud(viewProjector, orient, fix, settings, locationGranted, compact = false)
                CalibrationBanner(orient)
                if (browse != null) RecenterButton(Modifier.align(Alignment.CenterHorizontally)) { browse = null }
                Spacer(Modifier.weight(1f))
                panel(Modifier)
            }
        } else {
            // Landscape: info top-left, controls bottom-right, so the crosshair and horizon stay clear.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.42f)) {
                    Hud(viewProjector, orient, fix, settings, locationGranted, compact = true)
                    CalibrationBanner(orient)
                }
                if (browse != null) RecenterButton(Modifier.align(Alignment.TopEnd)) { browse = null }
                panel(Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.46f))
            }
        }
    }
    if (filtering) {
        CategoryDialog(
            counts = remember(targets) { Category.counts(targets) },
            hidden = settings.hiddenCats,
            onToggle = vm::setCategoryShown,
            onAll = vm::setCategoriesShown,
            onDismiss = { filtering = false },
        )
    }
}

/** Shown while browsing by drag; returns to the live camera view. */
@Composable
private fun RecenterButton(modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.padding(8.dp)) {
        Text("◎ Recenter · back to live view")
    }
}

private fun focalPx(intr: CameraIntrinsics?, w: Float, h: Float, s: Settings): Float {
    val base = if (intr != null) {
        Projector.focalPxForFillCenter(intr.focalMm, intr.activeWidthMm, intr.activeHeightMm, w, h)
    } else {
        // No metadata: assume a typical phone main camera (4.25 mm lens on a 5.6 x 4.2 mm 4:3 sensor, ~67° long side).
        Projector.focalPxForFillCenter(4.25, 5.6, 4.2, w, h)
    }
    return (base * s.fovScale).toFloat()
}

@Composable
private fun CameraPreview(onIntrinsics: (CameraIntrinsics?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var boundPreview by remember { mutableStateOf<Preview?>(null) }
    DisposableEffect(previewView) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val d = previewView.display ?: return
                if (d.displayId == displayId) boundPreview?.targetRotation = d.rotation
            }
        }
        dm.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val pre = runCatching { CameraIntrinsics.from(provider.getCameraInfo(selector)) }.getOrNull()
            // Match the stream aspect to the sensor so the preview is the full, uncropped sensor
            // (otherwise the FOV math would be off by the crop).
            val ratio = if (pre != null && pre.aspect > 1.6) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .build()
                )
                .build()
            preview.surfaceProvider = previewView.surfaceProvider
            previewView.display?.let { preview.targetRotation = it.rotation }
            boundPreview = preview
            runCatching {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(owner, selector, preview)
                onIntrinsics(CameraIntrinsics.from(cam.cameraInfo) ?: pre)
            }.onFailure { onIntrinsics(pre) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// ---------------------------------------------------------------- HUD

private val Panel = Color(0xB0000000)

@Composable
private fun Hud(p: Projector?, orient: OrientationState, fix: Fix?, s: Settings, locationGranted: Boolean, compact: Boolean) {
    val fg = if (s.nightMode) NightRed else Color.White
    Column(
        Modifier.fillMaxWidth().padding(if (compact) 4.dp else 8.dp)
            .background(Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (p != null) {
                val hdg = p.cameraHeadingTrueDeg()
                Text(Format.deg(hdg), color = fg, fontSize = if (compact) 24.sp else 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(" ${GeoMath.compassPoint(hdg)} true", color = fg, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Tilt ${Format.signedDeg(p.cameraPitchDeg())}", color = fg, fontFamily = FontFamily.Monospace)
                    Text("Roll ${Format.signedDeg(p.rollDeg())}", color = fg.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            } else {
                Text(if (orient.available) "Waiting for sensors…" else "No rotation-vector sensor on this device", color = fg)
            }
        }
        val gps = when {
            !locationGranted -> "GPS: permission needed"
            fix == null -> "GPS: acquiring…"
            else -> buildString {
                append("GPS ±${fix.horizontalAccuracyM?.let { "%.0f".format(it) } ?: "?"} m")
                append("  ·  alt ${Format.height(fix.altMeters, s.imperial)}${if (fix.altIsMsl) "" else " (ellipsoid)"}")
                append("  ·  decl ${Format.signedDeg(fix.declinationDeg)}")
            }
        }
        Text(gps, color = fg.copy(alpha = 0.85f), fontSize = 12.sp)
        Row {
            val (label, color) = compassStatus(orient.magAccuracy)
            Text("Compass: $label", color = if (s.nightMode) NightRed else color, fontSize = 12.sp)
            orient.headingAccuracyDeg?.let {
                Text("  (±${"%.0f".format(it)}°)", color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            if (s.headingOffsetDeg != 0.0) {
                Text("  ·  sight-in ${Format.signedDeg(s.headingOffsetDeg)}", color = fg.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            if (p != null) {
                Spacer(Modifier.weight(1f))
                Text("FOV ${"%.0f".format(p.horizontalFovDeg)}°×${"%.0f".format(p.verticalFovDeg)}°", color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}

private fun compassStatus(acc: Int): Pair<String, Color> = when (acc) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high" to Color(0xFF66FF88)
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium" to Color(0xFFFFD54F)
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low" to Color(0xFFFF8A65)
    else -> "unreliable" to Color(0xFFFF5252)
}

@Composable
private fun CalibrationBanner(orient: OrientationState) {
    if (orient.rotation == null) return
    if (orient.magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return
    Text(
        "Compass needs calibration — wave the phone in a figure-8 a few times, away from metal.",
        color = Color.Black, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .background(Color(0xFFFFD54F), RoundedCornerShape(8.dp)).padding(10.dp),
    )
}

@Composable
private fun BottomPanel(
    modifier: Modifier,
    projector: Projector?,
    selected: Placed?,
    aim: Pair<Double, Double>?,
    track: SatTrack?,
    satsInBeam: List<Placed>,
    anySats: Boolean,
    settings: Settings,
    onTarget: Boolean,
    onTargets: () -> Unit,
    onFilter: () -> Unit,
    onSightIn: () -> Unit,
    onSettings: () -> Unit,
    onNight: () -> Unit,
) {
    val fg = if (settings.nightMode) NightRed else Color.White
    Column(
        modifier.fillMaxWidth().padding(8.dp)
            .background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        if (selected != null) {
            val l = selected.look
            Text(selected.target.name, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "${Format.deg(l.bearingDeg)} ${GeoMath.compassPoint(l.bearingDeg)}  ·  elev ${Format.signedDeg(l.elevationDeg, 2)}  ·  ${Format.distance(l.distanceMeters, settings.imperial)}",
                color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            if (selected.target.type == TargetType.SATELLITE) {
                val t = track
                Text(
                    if (t != null && t.wobbles)
                        "Daily wobble ±${"%.1f".format(t.elSwing / 2)}° el, ±${"%.1f".format(t.azSwing / 2)}° az → aim at center " +
                            "${Format.deg(t.centerAz)} / ${Format.signedDeg(t.centerEl)} (the + mark)"
                    else "Holds station — aim straight at it",
                    color = fg.copy(alpha = 0.85f), fontSize = 12.sp,
                )
                if (l.elevationDeg < 0) Text("Below your horizon right now", color = Color(0xFFFF8A65), fontSize = 12.sp)
            }
            if (projector != null && aim != null) {
                val dAz = GeoMath.normalize180(aim.first - projector.cameraHeadingTrueDeg())
                val dEl = aim.second - projector.cameraPitchDeg()
                Text(
                    if (onTarget) "● ON TARGET" else Format.guidance(dAz, dEl),
                    color = if (onTarget) (if (settings.nightMode) NightRed else OnTargetGreen) else fg,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Text("Tap a marker (or pick from Targets) to aim at it.", color = fg.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        if (anySats) {
            Text(
                if (satsInBeam.isEmpty()) "No satellites inside the ${settings.beamwidthDeg.toInt()}° beam"
                else "${satsInBeam.size} sat${if (satsInBeam.size > 1) "s" else ""} in beam: " +
                    satsInBeam.joinToString(", ") { it.target.name.substringBefore(" (") },
                color = fg.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton("Targets", Modifier.weight(1f), onTargets)
            // A dot marks that some categories are switched off.
            PanelButton(if (settings.hiddenCats.isEmpty()) "Filter" else "Filter •", Modifier.weight(0.9f), onFilter)
            PanelButton("Sight in", Modifier.weight(1f), onSightIn)
            PanelButton(if (settings.nightMode) "Day" else "Night", Modifier.weight(0.75f), onNight)
            PanelButton("Settings", Modifier.weight(1f), onSettings)
        }
    }
}

@Composable
private fun SightInPanel(
    modifier: Modifier,
    projector: Projector?,
    selected: Placed?,
    sunUp: Boolean,
    onPickSun: () -> Unit,
    settings: Settings,
    onSet: (knownTrueBearing: Double) -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
) {
    val fg = if (settings.nightMode) NightRed else Color.White
    var bearingText by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxWidth().padding(8.dp)
            .background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        Text("Sight-in calibration", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (sunUp && selected?.target?.id != SUN_ID) {
            TextButton(onClick = onPickSun, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("☀ Use the Sun (most accurate reference)", color = SunYellow)
            }
        }
        if (selected?.target?.id == SUN_ID) {
            Text(
                "Don't look at the Sun directly — center the bright blob on screen. Shield the screen with your hand if it washes out.",
                color = SunYellow, fontSize = 12.sp,
            )
        }
        if (selected?.target?.id == MOON_ID) {
            Text("The Moon's position is good to about 0.3° — prefer the Sun or a visible tower.", color = fg.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Text(
            if (selected != null)
                "Put the crosshair exactly on the real ${selected.target.name}, hold steady, then tap Set. " +
                    "This zeroes any leftover compass error."
            else
                "Select a target first (tap its marker), or type the true bearing of a landmark you can see " +
                    "and put the crosshair on it.",
            color = fg.copy(alpha = 0.85f), fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected != null) {
                Button(
                    onClick = {
                        // Boresight sits on the target, so its azimuth should equal the target bearing exactly.
                        onSet(selected.look.bearingDeg)
                    },
                    enabled = projector != null,
                    colors = ButtonDefaults.buttonColors(containerColor = OnTargetGreen, contentColor = Color.Black),
                ) { Text("Set on target") }
            }
            androidx.compose.material3.OutlinedTextField(
                value = bearingText,
                onValueChange = { bearingText = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                label = { Text("or bearing °T") },
                singleLine = true,
                modifier = Modifier.width(130.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = fg),
            )
            val known = bearingText.toDoubleOrNull()?.takeIf { it in 0.0..360.0 }
            OutlinedButton(onClick = { known?.let(onSet) }, enabled = known != null && projector != null) { Text("Set") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            PanelButton("Clear offset (${Format.signedDeg(settings.headingOffsetDeg)})", Modifier.weight(1.4f), onClear)
            PanelButton("Cancel", Modifier.weight(1f), onCancel)
        }
    }
}

@Composable
private fun PanelButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false) }
}
