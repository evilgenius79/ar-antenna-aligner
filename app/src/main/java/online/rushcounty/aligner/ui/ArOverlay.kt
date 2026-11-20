package online.rushcounty.aligner.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import online.rushcounty.aligner.ar.Projector
import online.rushcounty.aligner.ar.ScreenPoint
import online.rushcounty.aligner.data.Settings
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.LookAngles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

val NightRed = Color(0xFFFF3B30)
val OnTargetGreen = Color(0xFF4CFF7A)
private val SelectedAmber = Color(0xFFFFC107)

private fun typeColor(t: TargetType): Color = when (t) {
    TargetType.AIRPORT -> Color(0xFF64B5F6)
    TargetType.TOWER -> Color(0xFF4DD0E1)
    TargetType.REPEATER -> Color(0xFFBA68C8)
    TargetType.CUSTOM -> Color(0xFFE0E0E0)
    TargetType.SATELLITE -> Color(0xFF81C784)
    TargetType.CELESTIAL -> SunYellow
}

val SunYellow = Color(0xFFFFE082)
private val MoonGrey = Color(0xFFE0E0E0)

private val BeltColor = Color(0xFF81C784)

/** Hit-test record written during draw, read on tap. */
private class Hit(val id: String, val x: Float, val y: Float)

@Composable
fun ArOverlay(
    projector: Projector?,
    placed: List<Placed>,
    selectedId: String?,
    settings: Settings,
    onTarget: Boolean,
    belt: List<LookAngles>,
    track: SatTrack?,
    onSize: (Float, Float) -> Unit,
    onTapTarget: (String) -> Unit,
    onPan: (dxPx: Float, dyPx: Float) -> Unit,
) {
    val currentOnPan by rememberUpdatedState(onPan)
    val hits = remember { ArrayList<Hit>() }
    val paints = remember { OverlayPaints() }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { onSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val best = hits.minByOrNull { hypot(it.x - pos.x, it.y - pos.y) }
                    if (best != null && hypot(best.x - pos.x, best.y - pos.y) < 48.dp.toPx()) onTapTarget(best.id)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    currentOnPan(drag.x, drag.y)
                }
            }
    ) {
        hits.clear()
        val p = projector ?: return@Canvas
        paints.night = settings.nightMode
        paints.density = density

        if (settings.showHorizon) drawHorizon(p, paints)
        if (belt.isNotEmpty()) drawSkyPath(p, belt, BeltColor.copy(alpha = 0.5f), dashed = true, paints)
        if (track != null) drawTrack(p, track, paints)

        val labels = ArrayList<LabelReq>()
        val onScreen = ArrayList<LabelReq>()
        for (pl in placed) {
            val sp = p.project(pl.look.bearingDeg, pl.look.elevationDeg)
            val sel = pl.target.id == selectedId
            if (p.isOnScreen(sp, marginPx = 0f)) {
                onScreen += LabelReq(pl, sp.x, sp.y, sel)
            } else if (sel || (pl.target.type != TargetType.TOWER && pl.target.type != TargetType.REPEATER)) {
                // Off-screen towers get no arrow unless selected: there are too many of them.
                val edge = drawEdgeArrow(p, sp, pl, sel, paints)
                if (edge != null) hits += Hit(pl.target.id, edge.x, edge.y)
            }
        }
        // Nearest ones win when the view is crowded; draw far first so near/selected end up on top.
        // Satellites, Sun and Moon are never the ones dropped.
        onScreen.sortWith(
            compareBy<LabelReq> { !it.selected }.thenBy { !it.pl.target.type.generated }.thenBy { it.pl.look.distanceMeters }
        )
        val shown = if (onScreen.size > MAX_MARKERS) onScreen.subList(0, MAX_MARKERS) else onScreen
        for (req in shown.asReversed()) {
            drawMarker(ScreenPoint(req.x, req.y, true), req.pl, req.selected, paints)
            labels += req
            hits += Hit(req.pl.target.id, req.x, req.y + 10 * density)
        }

        drawLabels(labels, settings, paints).forEach { hits += it }
        drawCrosshair(p, settings, onTarget, paints)
    }
}

/** Most markers drawn at once; beyond this the nearest are kept. */
private const val MAX_MARKERS = 250

private class LabelReq(val pl: Placed, val x: Float, val y: Float, val selected: Boolean)

/** Tower glyph footprint below the aim point (kept in sync with [drawMarker]). */
private fun glyphRect(x: Float, y: Float, selected: Boolean, d: Float, satellite: Boolean = false, celestial: Boolean = false): RectF {
    if (celestial) return RectF(x - 15f * d, y - 15f * d, x + 15f * d, y + 15f * d)
    if (satellite) {
        val k = if (selected) 1.3f else 1f
        return RectF(x - 30f * d * k, y - 6f * d * k, x + 30f * d * k, y + 6f * d * k)
    }
    val h = (if (selected) 46f else 34f) * d
    val w = h * 0.32f
    return RectF(x - w / 2, y - 6f * d, x + w / 2, y + h)
}

/**
 * Two-line labels above each marker, decluttered: the selected target is placed first, then
 * nearer targets. A label that would overlap is lifted row by row (with a leader line back to
 * its tower); if there is no room within a few rows, a non-selected label is dropped.
 * Returns hit points at each placed label so tapping a label selects its target.
 */
private fun DrawScope.drawLabels(reqs: List<LabelReq>, s: Settings, pt: OverlayPaints): List<Hit> {
    val out = ArrayList<Hit>()
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        val taken = ArrayList<RectF>()
        reqs.forEach {
            taken += glyphRect(it.x, it.y, it.selected, d, it.pl.target.type == TargetType.SATELLITE, it.pl.target.type == TargetType.CELESTIAL)
        }

        val order = reqs.sortedWith(compareByDescending<LabelReq> { it.selected }.thenBy { it.pl.look.distanceMeters })
        for (r in order) {
            val l = r.pl.look
            val name = r.pl.target.name
            val detail = if (r.pl.target.type == TargetType.CELESTIAL)
                "az ${Format.deg(l.bearingDeg)}  el ${Format.signedDeg(l.elevationDeg)}" + (r.pl.target.note?.let { "  $it" } ?: "")
            else if (r.pl.target.type == TargetType.SATELLITE)
                "az ${Format.deg(l.bearingDeg)}  el ${Format.signedDeg(l.elevationDeg)}  ${Format.longitude(r.pl.target.lon)}"
            else "${Format.deg(l.bearingDeg)}  ${Format.distance(l.distanceMeters, s.imperial)}  ${Format.signedDeg(l.elevationDeg, 2)}"
            val nameSize = if (r.selected) 15f else 13f
            pt.text.textSize = nameSize * d
            val wName = pt.text.measureText(name)
            pt.text.textSize = 11f * d
            val wDetail = pt.text.measureText(detail)
            val w = maxOf(wName, wDetail) + 8f * d
            val h = 34f * d
            // Keep the label on screen horizontally.
            val left = (r.x - w / 2).coerceIn(4f * d, (size.width - w - 4f * d).coerceAtLeast(4f * d))

            // Gap between the aim point and the label (satellites have a ring/panels around the point).
            val gap = when {
                r.pl.target.type == TargetType.CELESTIAL -> if (r.selected) 22f * d else 16f * d
                r.pl.target.type != TargetType.SATELLITE -> 8f * d
                r.selected -> 22f * d
                else -> 9f * d
            }
            var rect: RectF? = null
            for (row in 0..4) {
                val bottom = r.y - gap - row * (h + 2f * d)
                val cand = RectF(left, bottom - h, left + w, bottom)
                // The glyph rect of this same marker sits below the label, so it never conflicts.
                if (taken.none { RectF.intersects(it, cand) }) { rect = cand; break }
            }
            if (rect == null) {
                if (!r.selected) continue
                rect = RectF(left, r.y - gap - h, left + w, r.y - gap)
            }
            taken += rect

            val color = if (r.selected) SelectedAmber else typeColor(r.pl.target.type)
            if (rect.bottom < r.y - gap - 1f * d) {
                pt.line.color = pt.c(color.copy(alpha = 0.6f))
                pt.line.strokeWidth = 1f * d
                canvas.drawLine(r.x, rect.bottom, r.x, r.y - 6f * d, pt.line)
            }
            val cx = rect.centerX()
            pt.outlinedText(canvas, name, cx, rect.bottom - 4f * d, nameSize, color, Paint.Align.CENTER)
            pt.outlinedText(canvas, detail, cx, rect.bottom - 21f * d, 11f, Color.White.copy(alpha = 0.9f), Paint.Align.CENTER)
            out += Hit(r.pl.target.id, cx, rect.centerY())
        }
    }
    return out
}

private class OverlayPaints {
    var night = false
    var density = 1f

    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val textShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        color = android.graphics.Color.BLACK
        strokeJoin = Paint.Join.ROUND
    }
    val path = Path()

    fun c(color: Color): Int = (if (night) NightRed.copy(alpha = color.alpha) else color).toArgb()

    fun outlinedText(canvas: android.graphics.Canvas, s: String, x: Float, y: Float, sizeDp: Float, color: Color, align: Paint.Align) {
        text.textSize = sizeDp * density
        text.textAlign = align
        text.color = c(color)
        textShadow.textSize = text.textSize
        textShadow.textAlign = align
        textShadow.strokeWidth = 3f * density
        canvas.drawText(s, x, y, textShadow)
        canvas.drawText(s, x, y, text)
    }
}

/** Level line (0° elevation) with compass ticks every 5°, labels every 30°. */
private fun DrawScope.drawHorizon(p: Projector, pt: OverlayPaints) = drawIntoCanvas { cv ->
    val canvas = cv.nativeCanvas
    val d = pt.density
    pt.line.color = pt.c(Color.White.copy(alpha = 0.55f))
    pt.line.strokeWidth = 1.5f * d

    var prev: ScreenPoint? = null
    for (az in 0..360) {
        val sp = p.project(az.toDouble(), 0.0)
        val pv = prev
        if (pv != null && pv.inFront && sp.inFront &&
            (p.isOnScreen(pv, 2000f) || p.isOnScreen(sp, 2000f))
        ) canvas.drawLine(pv.x, pv.y, sp.x, sp.y, pt.line)
        prev = sp
    }
    for (az in 0 until 360 step 5) {
        val sp = p.project(az.toDouble(), 0.0)
        if (!p.isOnScreen(sp, 20f)) continue
        val up = p.project(az.toDouble(), 0.6) // direction of "up" on screen at this azimuth
        val ux = up.x - sp.x
        val uy = up.y - sp.y
        val len = hypot(ux, uy).coerceAtLeast(1e-3f)
        val major = az % 30 == 0
        val tick = (if (major) 14f else if (az % 10 == 0) 9f else 5f) * d
        canvas.drawLine(sp.x, sp.y, sp.x + ux / len * tick, sp.y + uy / len * tick, pt.line)
        if (major) {
            val label = when (az) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> az.toString() }
            pt.outlinedText(
                canvas, label, sp.x + ux / len * (tick + 14f * d), sp.y + uy / len * (tick + 14f * d) + 5f * d,
                if (az % 90 == 0) 15f else 12f, Color.White.copy(alpha = 0.85f), Paint.Align.CENTER,
            )
        }
    }
}

/** Polyline through sky directions (az/el), skipping segments below the horizon or behind the camera. */
private fun DrawScope.drawSkyPath(p: Projector, pts: List<LookAngles>, color: Color, dashed: Boolean, pt: OverlayPaints) =
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        pt.line.color = pt.c(color)
        pt.line.strokeWidth = 1.5f * d
        pt.line.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f) else null
        var prev: ScreenPoint? = null
        var prevUp = false
        for (la in pts) {
            val sp = p.project(la.bearingDeg, la.elevationDeg)
            val up = la.elevationDeg > -0.5
            val pv = prev
            if (pv != null && prevUp && up && pv.inFront && sp.inFront &&
                (p.isOnScreen(pv, 2000f) || p.isOnScreen(sp, 2000f))
            ) canvas.drawLine(pv.x, pv.y, sp.x, sp.y, pt.line)
            prev = sp
            prevUp = up
        }
        pt.line.pathEffect = null
    }

/** The selected satellite's daily figure-8, with a + at its center (where a fixed antenna should point). */
private fun DrawScope.drawTrack(p: Projector, t: SatTrack, pt: OverlayPaints) {
    drawSkyPath(p, t.points, SelectedAmber.copy(alpha = 0.7f), dashed = false, pt)
    if (!t.wobbles) return
    val c = p.project(t.centerAz, t.centerEl)
    if (!p.isOnScreen(c)) return
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        val s = 9f * d
        pt.line.color = pt.c(SelectedAmber)
        pt.line.strokeWidth = 2.5f * d
        canvas.drawLine(c.x - s, c.y, c.x + s, c.y, pt.line)
        canvas.drawLine(c.x, c.y - s, c.x, c.y + s, pt.line)
        pt.outlinedText(canvas, "aim (daily center)", c.x + s + 4f * d, c.y + 4f * d, 11f, SelectedAmber, Paint.Align.LEFT)
    }
}

/** Satellite: body with two solar panels; the body center is the exact aim point. */
private fun DrawScope.drawSatellite(sp: ScreenPoint, selected: Boolean, pt: OverlayPaints) = drawIntoCanvas { cv ->
    val canvas = cv.nativeCanvas
    val d = pt.density
    val color = if (selected) SelectedAmber else typeColor(TargetType.SATELLITE)
    val k = if (selected) 1.3f else 1f
    val b = 4.5f * d * k // half body
    val pw = 11f * d * k // panel length
    val ph = 3.5f * d * k // half panel height
    pt.fill.color = pt.c(color)
    canvas.drawRect(sp.x - b, sp.y - b, sp.x + b, sp.y + b, pt.fill)
    pt.line.color = pt.c(color)
    pt.line.strokeWidth = 1.5f * d
    canvas.drawRect(sp.x - b - 2f * d - pw, sp.y - ph, sp.x - b - 2f * d, sp.y + ph, pt.line)
    canvas.drawRect(sp.x + b + 2f * d, sp.y - ph, sp.x + b + 2f * d + pw, sp.y + ph, pt.line)
    canvas.drawLine(sp.x - b - 2f * d, sp.y, sp.x - b, sp.y, pt.line)
    canvas.drawLine(sp.x + b, sp.y, sp.x + b + 2f * d, sp.y, pt.line)
    if (selected) canvas.drawCircle(sp.x, sp.y, 20f * d, pt.line)
}

/** Sun: disc with rays. Moon: outlined disc. The center is the exact aim point. */
private fun DrawScope.drawCelestial(sp: ScreenPoint, sun: Boolean, selected: Boolean, pt: OverlayPaints) =
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        val color = if (selected) SelectedAmber else if (sun) SunYellow else MoonGrey
        val r = 7f * d
        pt.line.color = pt.c(color)
        pt.line.strokeWidth = 2f * d
        if (sun) {
            pt.fill.color = pt.c(color.copy(alpha = 0.35f))
            canvas.drawCircle(sp.x, sp.y, r, pt.fill)
            canvas.drawCircle(sp.x, sp.y, r, pt.line)
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                canvas.drawLine(sp.x + c * (r + 3f * d), sp.y + s * (r + 3f * d), sp.x + c * (r + 8f * d), sp.y + s * (r + 8f * d), pt.line)
            }
        } else {
            canvas.drawCircle(sp.x, sp.y, r, pt.line)
        }
        // Fine center dot so the exact aim point is clear on a bright disc.
        pt.fill.color = pt.c(color)
        canvas.drawCircle(sp.x, sp.y, 1.6f * d, pt.fill)
        if (selected) canvas.drawCircle(sp.x, sp.y, 20f * d, pt.line)
    }

/** "Virtual tower": a small lattice mast whose tip is the exact aim point. Labels are drawn by [drawLabels]. */
private fun DrawScope.drawMarker(sp: ScreenPoint, pl: Placed, selected: Boolean, pt: OverlayPaints) {
    if (pl.target.type == TargetType.SATELLITE) return drawSatellite(sp, selected, pt)
    if (pl.target.type == TargetType.CELESTIAL) return drawCelestial(sp, pl.target.id == SUN_ID, selected, pt)
    drawTower(sp, pl, selected, pt)
}

private fun DrawScope.drawTower(sp: ScreenPoint, pl: Placed, selected: Boolean, pt: OverlayPaints) =
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        val color = if (selected) SelectedAmber else typeColor(pl.target.type)
        val h = (if (selected) 46f else 34f) * d
        val w = h * 0.32f
        val x = sp.x
        val y = sp.y

        pt.line.color = pt.c(color)
        pt.line.strokeWidth = (if (selected) 2.5f else 2f) * d
        // Mast: apex at the aim point, legs below.
        pt.path.reset()
        pt.path.moveTo(x, y)
        pt.path.lineTo(x - w / 2, y + h)
        pt.path.moveTo(x, y)
        pt.path.lineTo(x + w / 2, y + h)
        canvas.drawPath(pt.path, pt.line)
        for (i in 1..3) { // cross-bracing
            val f = i / 4f
            canvas.drawLine(x - w / 2 * f, y + h * f, x + w / 2 * f, y + h * f, pt.line)
        }
        // Aim point.
        pt.fill.color = pt.c(color)
        canvas.drawCircle(x, y, (if (selected) 5f else 4f) * d, pt.fill)
        if (selected) {
            pt.line.strokeWidth = 1.5f * d
            canvas.drawCircle(x, y, 11f * d, pt.line)
        }
    }

/** Arrow pinned to the screen edge pointing toward an off-screen target. Returns its position. */
private fun DrawScope.drawEdgeArrow(
    p: Projector, sp: ScreenPoint, pl: Placed, selected: Boolean, pt: OverlayPaints,
): Offset? {
    val d = pt.density
    val cx = size.width / 2
    val cy = size.height / 2
    // For targets behind the camera, project() already returns a far point in the turn direction.
    val dx = sp.x - cx
    val dy = sp.y - cy
    if (abs(dx) < 1e-3f && abs(dy) < 1e-3f) return null
    // Inset rectangle (leave room for HUD top and panel bottom).
    val left = 28f * d
    val right = size.width - 28f * d
    val top = size.height * 0.22f
    val bottom = size.height * 0.70f
    val tx = if (dx > 0) (right - cx) / dx else if (dx < 0) (left - cx) / dx else Float.MAX_VALUE
    val ty = if (dy > 0) (bottom - cy) / dy else if (dy < 0) (top - cy) / dy else Float.MAX_VALUE
    val t = minOf(abs(tx), abs(ty))
    val ex = cx + dx * t
    val ey = cy + dy * t
    val ang = atan2(dy, dx)
    val color = if (selected) SelectedAmber else typeColor(pl.target.type)
    val r = (if (selected) 16f else 10f) * d

    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        pt.fill.color = pt.c(color.copy(alpha = if (selected) 1f else 0.75f))
        pt.path.reset()
        pt.path.moveTo(ex + cos(ang) * r, ey + sin(ang) * r)
        pt.path.lineTo(ex + cos(ang + 2.5f) * r, ey + sin(ang + 2.5f) * r)
        pt.path.lineTo(ex + cos(ang - 2.5f) * r, ey + sin(ang - 2.5f) * r)
        pt.path.close()
        canvas.drawPath(pt.path, pt.fill)
        if (selected) {
            val dAz = GeoMath.normalize180(pl.look.bearingDeg - p.cameraHeadingTrueDeg())
            val align = when {
                ex > size.width * 0.75f -> Paint.Align.RIGHT
                ex < size.width * 0.25f -> Paint.Align.LEFT
                else -> Paint.Align.CENTER
            }
            val ly = if (ey > cy) ey - r - 6f * d else ey + r + 16f * d
            pt.outlinedText(canvas, "${pl.target.name}  ${Format.signedDeg(dAz, 0)}", ex, ly, 13f, color, align)
        }
    }
    return Offset(ex, ey)
}

/** Center crosshair + antenna beamwidth circle. */
private fun DrawScope.drawCrosshair(p: Projector, s: Settings, onTarget: Boolean, pt: OverlayPaints) =
    drawIntoCanvas { cv ->
        val canvas = cv.nativeCanvas
        val d = pt.density
        val cx = size.width / 2
        val cy = size.height / 2
        val color = if (onTarget) OnTargetGreen else Color.White
        pt.line.color = pt.c(color)
        pt.line.strokeWidth = 2f * d
        val g = 6f * d
        val l = 22f * d
        canvas.drawLine(cx - l, cy, cx - g, cy, pt.line)
        canvas.drawLine(cx + g, cy, cx + l, cy, pt.line)
        canvas.drawLine(cx, cy - l, cx, cy - g, pt.line)
        canvas.drawLine(cx, cy + g, cx, cy + l, pt.line)

        val beamR = p.coneRadiusPx(s.beamwidthDeg / 2)
        if (beamR in 30f..(size.width * 2)) {
            pt.line.color = pt.c(color.copy(alpha = 0.45f))
            pt.line.strokeWidth = 1.5f * d
            pt.line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f)
            canvas.drawCircle(cx, cy, beamR, pt.line)
            pt.line.pathEffect = null
            pt.outlinedText(canvas, "${s.beamwidthDeg.toInt()}° beam", cx, cy - beamR - 6f * d, 11f, color.copy(alpha = 0.7f), Paint.Align.CENTER)
        }
    }
