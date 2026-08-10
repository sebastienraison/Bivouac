package com.bivouac.app.gpximport

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bivouac.app.data.gpx.GpxParser
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.gpx.TrackStatsCalculator
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GpxImportUiState {
    data object Idle : GpxImportUiState
    data object Loading : GpxImportUiState
    data class Loaded(val track: HikeTrack, val stats: TrackStats) : GpxImportUiState
    data class Error(val message: String) : GpxImportUiState
}

class GpxImportViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<GpxImportUiState>(GpxImportUiState.Idle)
    val uiState: StateFlow<GpxImportUiState> = _uiState.asStateFlow()

    private val _bivouacPoints = MutableStateFlow<List<BivouacPoint>>(emptyList())
    val bivouacPoints: StateFlow<List<BivouacPoint>> = _bivouacPoints.asStateFlow()

    // Live position of a point currently being dragged on the map, kept separate from
    // [bivouacPoints] so the segments table can reflect it in real time without feeding back into
    // HikeMapView: that would tear down and recreate the marker mid-gesture and break the drag.
    private val _dragPreview = MutableStateFlow<Pair<String, Int>?>(null)

    // Bivouac points with the currently dragged one (if any) at its live preview position —
    // shared by the segments table and the elevation profile marker, both of which should track
    // the gesture in real time rather than only jump on release.
    val effectiveBivouacPoints: StateFlow<List<BivouacPoint>> = combine(_bivouacPoints, _dragPreview) { points, preview ->
        applyDragPreview(points, preview)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val segments: StateFlow<List<Segment>> = combine(_uiState, effectiveBivouacPoints) { state, points ->
        val track = (state as? GpxImportUiState.Loaded)?.track
        if (track == null || points.isEmpty()) return@combine emptyList()
        computeSegments(track.points, points)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun importGpx(resolver: ContentResolver, uri: Uri) {
        _uiState.value = GpxImportUiState.Loading
        _bivouacPoints.value = emptyList()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val track = resolver.openInputStream(uri)?.use { GpxParser.parse(it) }
                        ?: throw IOException("Impossible d'ouvrir le fichier sélectionné")
                    track to TrackStatsCalculator.compute(track.points)
                }
            }
            _uiState.value = result.fold(
                onSuccess = { (track, stats) -> GpxImportUiState.Loaded(track, stats) },
                onFailure = { GpxImportUiState.Error("Trace incorrecte ou fichier illisible.") },
            )
        }
    }

    fun clear() {
        _uiState.value = GpxImportUiState.Idle
        _bivouacPoints.value = emptyList()
    }

    fun addBivouacPoint(trackPointIndex: Int) {
        val current = _bivouacPoints.value
        if (current.any { it.trackPointIndex == trackPointIndex }) return
        _bivouacPoints.value = (current + BivouacPoint(UUID.randomUUID().toString(), trackPointIndex))
            .sortedBy { it.trackPointIndex }
    }

    fun removeBivouacPoint(id: String) {
        _bivouacPoints.value = _bivouacPoints.value.filterNot { it.id == id }
    }

    fun moveBivouacPoint(id: String, newTrackPointIndex: Int) {
        _dragPreview.value = null
        _bivouacPoints.value = _bivouacPoints.value
            .map { if (it.id == id) it.copy(trackPointIndex = newTrackPointIndex) else it }
            .sortedBy { it.trackPointIndex }
    }

    fun previewBivouacDrag(id: String, trackPointIndex: Int) {
        _dragPreview.value = id to trackPointIndex
    }

    private fun applyDragPreview(points: List<BivouacPoint>, preview: Pair<String, Int>?): List<BivouacPoint> {
        if (preview == null) return points
        return points.map { if (it.id == preview.first) it.copy(trackPointIndex = preview.second) else it }
            .sortedBy { it.trackPointIndex }
    }

    private fun computeSegments(points: List<TrackPoint>, bivouacs: List<BivouacPoint>): List<Segment> {
        val boundaries = listOf(0) + bivouacs.map { it.trackPointIndex } + listOf(points.lastIndex)
        return boundaries.zipWithNext { start, end ->
            val segmentPoints = points.subList(start, end + 1)
            Segment(segmentPoints, TrackStatsCalculator.compute(segmentPoints))
        }
    }
}
