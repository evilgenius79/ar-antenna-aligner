package online.rushcounty.aligner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import online.rushcounty.aligner.ar.CameraIntrinsics
import online.rushcounty.aligner.data.AreaRepository
import online.rushcounty.aligner.data.Category
import online.rushcounty.aligner.data.Settings
import online.rushcounty.aligner.data.SettingsStore
import online.rushcounty.aligner.data.SiteKind
import online.rushcounty.aligner.data.Target
import online.rushcounty.aligner.data.TargetRepository
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.sat.OrbitalElements
import online.rushcounty.aligner.sat.SatelliteRepository
import online.rushcounty.aligner.sensors.LocationProvider
import online.rushcounty.aligner.sensors.OrientationProvider

enum class Screen { AR, TARGETS, SETTINGS }

/** Most sites one "add area" brings in; keeps the list and the AR view responsive. */
private const val MAX_AREA_SITES = 10_000

private const val DOWNLOADING = "Downloading from CelesTrak…"

/** Selection ids for satellites share the target-id namespace. */
fun satTargetId(noradId: Int) = "sat:$noradId"

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TargetRepository(app)
    private val settingsStore = SettingsStore(app)
    private val satRepo = SatelliteRepository(app)
    private val areaRepo = AreaRepository(app)

    private val _areaStatus = MutableStateFlow<String?>(null)
    val areaStatus: StateFlow<String?> = _areaStatus.asStateFlow()

    private val _areaBusy = MutableStateFlow(false)
    val areaBusy: StateFlow<Boolean> = _areaBusy.asStateFlow()

    val orientation = OrientationProvider(app)
    val location = LocationProvider(app)

    private val _targets = MutableStateFlow(repo.load())
    val targets: StateFlow<List<Target>> = _targets.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow(Screen.AR)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _intrinsics = MutableStateFlow<CameraIntrinsics?>(null)
    val intrinsics: StateFlow<CameraIntrinsics?> = _intrinsics.asStateFlow()

    private val _satellites = MutableStateFlow(satRepo.load())
    val satellites: StateFlow<List<OrbitalElements>> = _satellites.asStateFlow()

    private val _shownSats = MutableStateFlow(satRepo.loadShown(_satellites.value))
    val shownSats: StateFlow<Set<Int>> = _shownSats.asStateFlow()

    private val _satStatus = MutableStateFlow<String?>(null)
    val satStatus: StateFlow<String?> = _satStatus.asStateFlow()

    init {
        orientation.smoothing = _settings.value.smoothing
    }

    fun show(s: Screen) { _screen.value = s }

    fun setIntrinsics(i: CameraIntrinsics?) { _intrinsics.value = i }

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        orientation.smoothing = _settings.value.smoothing
        settingsStore.save(_settings.value)
    }

    fun select(id: String?) = updateSettings { it.copy(selectedTargetId = id) }

    // --- targets ---

    private fun commit(list: List<Target>) {
        _targets.value = list
        repo.save(list)
    }

    fun upsert(t: Target) {
        val cur = _targets.value
        commit(if (cur.any { it.id == t.id }) cur.map { if (it.id == t.id) t else it } else cur + t)
    }

    fun delete(id: String) {
        commit(_targets.value.filterNot { it.id == id })
        if (_settings.value.selectedTargetId == id) select(null)
    }

    fun toggleVisible(id: String) =
        commit(_targets.value.map { if (it.id == id) it.copy(visible = !it.visible) else it })

    /** @return (imported, skipped) */
    fun import(text: String): Pair<Int, Int> {
        val (list, skipped) = TargetRepository.parseImport(text)
        if (list.isNotEmpty()) commit(_targets.value + list)
        return list.size to skipped
    }

    fun setCategoryShown(c: Category, shown: Boolean) = updateSettings { s ->
        s.copy(hiddenCats = s.hiddenCats.filter { it != c.code } + if (shown) "" else c.code.toString())
    }

    /** Show every category in [cats], or hide them all. */
    fun setCategoriesShown(cats: List<Category>, shown: Boolean) = updateSettings { s ->
        val codes = cats.map { it.code }
        s.copy(hiddenCats = s.hiddenCats.filter { it !in codes } + if (shown) "" else codes.joinToString(""))
    }

    fun exportJson(): String = TargetRepository.toJson(_targets.value)

    fun resetTargets() {
        _targets.value = repo.resetToSeed()
    }

    fun clearTargets() {
        commit(emptyList())
        select(null)
    }

    /**
     * Add every antenna site of the chosen [kinds] within [radiusMiles] of a ZIP code
     * (or of the current GPS position when [zip] is null). Sites already in the list are left alone.
     */
    fun addArea(zip: String?, radiusMiles: Int, kinds: Set<SiteKind>) {
        if (_areaBusy.value) return
        _areaBusy.value = true
        _areaStatus.value = "Looking up location…"
        val here = location.fix.value?.let { GeoPoint(it.lat, it.lon) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val center = if (zip != null) areaRepo.zipCenter(zip) ?: error("ZIP code $zip not found")
                    else here ?: error("No GPS position yet")
                    areaRepo.fetch(center, radiusMiles * 1.609344, kinds) { done, total ->
                        _areaStatus.value = "Downloading map square ${done + 1} of $total…"
                    }
                }
            }.onSuccess { found ->
                val have = _targets.value.map { it.id }.toSet()
                // Nearest first, so a cap keeps the closest ones.
                val fresh = found.filterNot { it.id in have }.take(MAX_AREA_SITES)
                // Sites added by an older version have no categories yet; pick them up from this download.
                val current = TargetRepository.withCats(_targets.value, found)
                if (fresh.isNotEmpty() || current != _targets.value) commit(current + fresh)
                val where = zip ?: "your location"
                _areaStatus.value = "Added ${fresh.size} sites within $radiusMiles mi of $where" +
                    if (found.size > fresh.size) " (${found.size - fresh.size} were already in your list)." else "."
            }.onFailure {
                _areaStatus.value = "Couldn't add the area: ${it.message ?: it.javaClass.simpleName}"
            }
            _areaBusy.value = false
        }
    }

    fun clearAreaStatus() { _areaStatus.value = null }

    // --- satellites ---

    fun setSatShown(noradId: Int, shown: Boolean) {
        _shownSats.update { if (shown) it + noradId else it - noradId }
        satRepo.saveShown(_shownSats.value)
    }

    fun aimAtSatellite(noradId: Int) {
        setSatShown(noradId, true)
        select(satTargetId(noradId))
        show(Screen.AR)
    }

    fun refreshSatellites() {
        if (_satStatus.value == DOWNLOADING) return
        _satStatus.value = DOWNLOADING
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { satRepo.download() } }
                .onSuccess { _satellites.value = it; _satStatus.value = "Updated ${it.size} satellites" }
                .onFailure { _satStatus.value = "Update failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    // --- sight-in ---

    /**
     * Zero the residual heading error: [rawTrueHeadingDeg] is the camera heading the app computes
     * WITHOUT any user offset; [knownTrueBearingDeg] is where the crosshair is really pointing.
     */
    fun sightIn(knownTrueBearingDeg: Double, rawTrueHeadingDeg: Double) =
        updateSettings { it.copy(headingOffsetDeg = GeoMath.normalize180(knownTrueBearingDeg - rawTrueHeadingDeg)) }

    fun clearSightIn() = updateSettings { it.copy(headingOffsetDeg = 0.0) }
}
