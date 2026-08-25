package com.bivouac.app.ui.gpximport

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bivouac.app.R
import com.bivouac.app.data.gpx.TrackStats
import com.bivouac.app.data.model.BivouacPoint
import com.bivouac.app.data.model.HikeTrack
import com.bivouac.app.data.model.Segment
import com.bivouac.app.data.model.TrackPoint
import com.bivouac.app.ui.components.DrawerStop
import com.bivouac.app.ui.components.ElevationProfile
import com.bivouac.app.ui.components.GainIconColor
import com.bivouac.app.ui.components.InfoText
import com.bivouac.app.ui.components.StatsRows
import com.bivouac.app.ui.components.ThreeStopDrawerHandle
import com.bivouac.app.ui.components.ThreeStopDrawerStopRow
import com.bivouac.app.ui.components.rememberThreeStopDrawerState
import kotlin.math.roundToInt

// ElevationProfile's own Canvas is a fixed 72dp + its 14dp bottom axis regardless of content
// (see BOTTOM_AXIS_HEIGHT in ElevationProfile.kt), plus the 10dp/2dp top/bottom padding this
// composable wraps it in below (98dp), plus a small margin against label-descender/antialiasing
// overhang right at the block's own bottom edge — see the comment on profileHeightPx below for
// why this is a constant rather than an onGloballyPositioned measurement.
private val PROFILE_BLOCK_HEIGHT_DP = 106.dp

/**
 * Planification's take on the three-stop drawer (BIV-54): all the drag/anchor/nested-scroll
 * plumbing, the handle and the stop row come from the shared [ThreeStopDrawerState] machinery
 * (RIC-95, also used by Journal's ThreeStopJournalDetail); what stays here is what genuinely
 * differs per screen: the content of each stop (segments + bivouac points, where Journal has
 * tags/notes) and the height/anchor computation. Unlike Journal's, which always reaches
 * fullHeightPx, Planification stays an active context (the map behind the sheet must stay usable
 * for placing/dragging bivouacs), so DETAIL only grows to fullHeightPx when the segments table
 * actually needs it: every stop's height is the real measured size of its own content, capped
 * (never forced) at fullHeightPx; see segmentsMaxHeightPx below for how the segments list itself
 * gets bounded and made to scroll once it would otherwise exceed that cap.
 */
@Composable
internal fun ThreeStopPlanificationDetail(
    track: HikeTrack,
    stats: TrackStats,
    bivouacPoints: List<BivouacPoint>,
    elevationMarkerPoints: List<BivouacPoint>,
    segments: List<Segment>,
    dirty: Boolean,
    isBanked: Boolean,
    onCloseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDuplicateClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRemovePoint: (String) -> Unit,
    onExportSegment: (index: Int, segment: Segment) -> Unit,
    // RIC-125 : export de la trace entière, indépendant des segments/bivouacs — seul moyen de
    // sortir une trace mono-jour sans aucun point de bivouac posé (SegmentsList ne s'affiche pas
    // dans ce cas, voir bivouacPoints.isNotEmpty() plus bas).
    onExportTrack: () -> Unit,
    onWeatherClick: (TrackPoint) -> Unit,
    nonFreeFeaturesDisabled: Boolean = false,
    onSheetTopMeasured: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current

        val fullHeightPx = with(density) { maxHeight.toPx() }
        var measuredSummaryHeightPx by remember { mutableIntStateOf(0) }
        // Rekeyé quand le bloc segments se démonte (dernier bivouac supprimé) : une mesure
        // figée d'un bloc qui n'existe plus gardait sinon le cran Détails à sa vieille hauteur,
        // avec une grande zone vide à la place de la table (RIC-95 recette, 2e passe).
        var measuredSegmentsAdditionPx by remember(bivouacPoints.isEmpty()) { mutableIntStateOf(0) }
        val fallbackSummaryHeightPx = with(density) { 150.dp.toPx() }
        val navigationBarHeightPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()
        // Every stop below is sized off what its own content actually measures, capped only by
        // fullHeightPx as an absolute safety net — never by an arbitrary fraction, which is what
        // was clipping the profile curve and the segments table on some real devices (BIV-57
        // phone recette): a taller-than-expected header (larger system font, different insets)
        // left less room than the fixed 55%/60% caps assumed, cutting off content below them.
        val summaryHeightPx = (if (measuredSummaryHeightPx > 0) {
            measuredSummaryHeightPx.toFloat() + navigationBarHeightPx + with(density) { 8.dp.toPx() }
        } else {
            fallbackSummaryHeightPx + navigationBarHeightPx
        }).coerceAtMost(fullHeightPx)
        // PROFILE_BLOCK_HEIGHT_DP, not a live onGloballyPositioned measurement: ElevationProfile's
        // Canvas is a fixed dp height regardless of content, so measuring it dynamically only added
        // a multi-frame convergence lag (summary → profile → detail, each depending on the previous
        // frame's measurement) — harmless most of the time, but a still-converging PROFILE anchor
        // one frame short of the real value clipped the curve's X-axis labels right at the screen
        // edge (BIV-57 phone recette: zero margin for error since PROFILE's anchor puts the block's
        // bottom edge exactly at the screen's bottom). A known constant sidesteps the lag entirely.
        // Le bord bas du bloc profil coïncidait exactement avec le bord bas de l'écran au cran
        // Profil, et les libellés de l'axe X passaient sous la barre système (RIC-95 recette) :
        // un séparateur + la hauteur de la barre de navigation sont réservés sous la courbe et
        // comptés dans l'anchor Profil pour remonter la courbe d'autant. Inconditionnel, segments
        // ou pas (2e passe de recette) : la version réservée au cas « aucun bivouac » faisait
        // sauter le tiroir et retomber les libellés sous la barre système dès l'ajout du premier
        // point. Avec des segments, cette réserve est remplie par le haut de la table (son
        // padding), et elle couvre aussi le padding barre-de-navigation du bloc segments dans le
        // calcul de detailHeightPx plus bas.
        val profileBottomReservePx = navigationBarHeightPx + with(density) { 1.dp.toPx() }
        val profileHeightPx = (summaryHeightPx + with(density) { PROFILE_BLOCK_HEIGHT_DP.toPx() } + profileBottomReservePx)
            .coerceIn(summaryHeightPx, fullHeightPx)
        // How much room is left for the segments list before DETAIL would have to exceed the
        // screen — the segments Column below is capped to exactly this via heightIn(max), so it
        // naturally scrolls instead of pushing DETAIL past what fits on long, multi-day traces.
        // Stable from the first frame now that profileHeightPx no longer depends on a measurement
        // of its own, so the segments Column's own measurement converges in a single pass too.
        // The status bar's height is reserved out of the cap (RIC-95 recette): DETAIL therefore
        // tops out just under the status bar instead of at true fullscreen, which keeps the
        // status-bar protection spacer (statusBarOverlapPx) at zero on this screen and the whole
        // measured content visible without internal scroll until it genuinely runs out of room.
        val segmentsMaxHeightPx = (fullHeightPx - profileHeightPx - statusBarHeightPx).coerceAtLeast(0f)
        // Pas de terme séparé pour le padding barre-de-navigation du bloc segments (que sa mesure
        // interne n'inclut pas) : profileBottomReservePx, inconditionnel et déjà dans
        // profileHeightPx, réserve exactement cette hauteur-là.
        val detailHeightPx = (profileHeightPx + measuredSegmentsAdditionPx)
            .coerceIn(profileHeightPx, fullHeightPx)
        val anchors = remember(fullHeightPx, summaryHeightPx, profileHeightPx, detailHeightPx) {
            mapOf(
                DrawerStop.DETAIL to fullHeightPx - detailHeightPx,
                DrawerStop.PROFILE to fullHeightPx - profileHeightPx,
                DrawerStop.SUMMARY to fullHeightPx - summaryHeightPx,
            )
        }
        // Keyed on the track instance: opening another trace (or duplicating the current one)
        // recreates the drawer state, which lands it back on the PROFILE stop (RIC-95 decision,
        // aligned with Journal; the previous "keep the current stop across traces" behavior of
        // this screen is gone on purpose).
        val drawer = rememberThreeStopDrawerState(
            anchors = anchors,
            trackKey = track,
            // Sans bivouac, le cran Détails n'a aucun contenu propre : bouton grisé et inerte
            // plutôt qu'une navigation vers un cran vide (RIC-95 recette 2, décision produit).
            detailEnabled = bivouacPoints.isNotEmpty(),
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { detailHeightPx.toDp() })
                .offset { IntOffset(0, drawer.offset.value.roundToInt()) }
                .onGloballyPositioned { onSheetTopMeasured(it.positionInRoot().y.toInt()) },
            shape = if (drawer.offset.value <= 1f) RectangleShape else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.height(with(density) { drawer.statusBarOverlapPx(statusBarHeightPx).toDp() }),
                )
                Column(
                    modifier = drawer.dragModifier
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            bottom = with(density) { navigationBarHeightPx.toDp() } + 8.dp,
                        )
                        .onGloballyPositioned { measuredSummaryHeightPx = it.size.height },
                ) {
                    ThreeStopDrawerHandle(drawer, Modifier.align(Alignment.CenterHorizontally))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = track.name ?: "Trace sans nom",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        TrackActionsRow(
                            dirty = dirty,
                            isBanked = isBanked,
                            onSaveClick = onSaveClick,
                            onRenameClick = onRenameClick,
                            onDuplicateClick = onDuplicateClick,
                            onDeleteClick = onDeleteClick,
                            onExportClick = onExportTrack,
                            onCloseClick = onCloseClick,
                        )
                    }
                    if (bivouacPoints.isNotEmpty()) {
                        Text(
                            text = "Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatsRows(stats, muted = bivouacPoints.isNotEmpty())
                    ThreeStopDrawerStopRow(drawer)
                }

                ElevationProfile(
                    points = track.points,
                    bivouacPoints = elevationMarkerPoints,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 2.dp),
                )

                // Clôture permanente sous la courbe (pendant de profileBottomReservePx) : avec des
                // segments, elle fait office de premier séparateur de la table (qui saute donc le
                // sien) ; sans segments, l'espace barre-de-navigation en dessous garde les
                // libellés de l'axe X au-dessus de la barre système.
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                if (bivouacPoints.isEmpty()) {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }

                if (bivouacPoints.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = with(density) { segmentsMaxHeightPx.toDp() })
                            .nestedScroll(drawer.nestedScrollConnection)
                            .verticalScroll(drawer.detailScrollState)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                            .onGloballyPositioned { measuredSegmentsAdditionPx = it.size.height },
                    ) {
                        SegmentsList(
                            track = track,
                            segments = segments,
                            bivouacPoints = bivouacPoints,
                            onRemovePoint = onRemovePoint,
                            onExportSegment = onExportSegment,
                            onWeatherClick = onWeatherClick,
                            nonFreeFeaturesDisabled = nonFreeFeaturesDisabled,
                        )
                    }
                }
            }
        }
    }
}

// Trailing icons on the title row of an open trace. Order matters and is deliberate: save, then
// the overflow menu (duplicate/delete), then close last — see CONCEPTION notes. The overflow menu
// never holds save or close, both stay standalone; reused identically on each home screen list row
// (minus save, nothing to save from there) to keep the convention consistent across the app.
@Composable
private fun TrackActionsRow(
    dirty: Boolean,
    isBanked: Boolean,
    onSaveClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDuplicateClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onExportClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row {
        IconButton(onClick = onSaveClick) {
            Icon(
                Icons.Default.Save,
                contentDescription = if (dirty) "Enregistrer (modifications non sauvegardées)" else "Enregistrer",
                // Orange (GainIconColor) rather than the error/red role: an unsaved change isn't
                // a critical error, just a state — red is reserved for destructive actions
                // (delete), matching Material 3's role guidance.
                tint = if (dirty) GainIconColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Renommer") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    enabled = isBanked,
                    onClick = { menuExpanded = false; onRenameClick() },
                )
                DropdownMenuItem(
                    text = { Text("Dupliquer") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { menuExpanded = false; onDuplicateClick() },
                )
                DropdownMenuItem(
                    text = { Text("Exporter") },
                    leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                    onClick = { menuExpanded = false; onExportClick() },
                )
                DropdownMenuItem(
                    text = { Text("Supprimer") },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    enabled = isBanked,
                    onClick = { menuExpanded = false; onDeleteClick() },
                )
            }
        }
        IconButton(onClick = onCloseClick) {
            Icon(Icons.Default.Close, contentDescription = "Fermer la trace")
        }
    }
}

@Composable
private fun SegmentsList(
    track: HikeTrack,
    segments: List<Segment>,
    bivouacPoints: List<BivouacPoint>,
    onRemovePoint: (String) -> Unit,
    onExportSegment: (index: Int, segment: Segment) -> Unit,
    onWeatherClick: (TrackPoint) -> Unit,
    nonFreeFeaturesDisabled: Boolean,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        segments.forEachIndexed { index, segment ->
            // Pas de séparateur de tête : la clôture permanente sous la courbe le fournit déjà.
            if (index > 0) HorizontalDivider()
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Jour ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { onExportSegment(index, segment) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Télécharger ce segment en GPX",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                StatsRows(segment.stats)
            }

            if (index < bivouacPoints.size) {
                HorizontalDivider()
                val bivouac = bivouacPoints[index]
                val trackPoint = track.points[bivouac.trackPointIndex]
                BivouacRow(
                    trackPoint = trackPoint,
                    onWeatherClick = { onWeatherClick(trackPoint) },
                    onRemove = { onRemovePoint(bivouac.id) },
                    showWeather = !nonFreeFeaturesDisabled,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BivouacRow(trackPoint: TrackPoint, onWeatherClick: () -> Unit, onRemove: () -> Unit, showWeather: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bivouac_badge),
            contentDescription = "Point de bivouac",
            modifier = Modifier.size(24.dp),
        )
        val elevation = trackPoint.elevationMeters
        if (elevation != null) {
            InfoText(
                text = "${elevation.roundToInt()} m",
                icon = Icons.Default.Terrain,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showWeather) ComposedWeatherIconButton(onClick = onWeatherClick)
        IconButton(onClick = onRemove, modifier = Modifier.padding(start = 6.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Supprimer ce point de bivouac",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// No native "partly cloudy" glyph in the Material icon set used, so the classic sun-behind-cloud
// pictogram is composed from the two separate icons instead.
@Composable
private fun ComposedWeatherIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = "Météo au point de bivouac",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.BottomStart),
            )
            Icon(
                Icons.Default.WbSunny,
                contentDescription = null,
                tint = GainIconColor,
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.TopEnd),
            )
        }
    }
}
