package com.bivouac.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Les trois crans du tiroir de détail partagé entre Journal et Planification :
 * Synthèse (titre + stats), Profil (+ courbe de dénivelé), Détails (+ contenu propre à l'écran).
 */
internal enum class DrawerStop { SUMMARY, PROFILE, DETAIL }

/**
 * Cran sur lequel le tiroir doit se poser à la fin d'un geste : un fling franc (vitesse au-delà
 * du seuil) avance d'exactement un cran dans le sens du geste, un relâchement lent retombe sur
 * le cran le plus proche de la position courante.
 */
internal fun settleDrawerStop(
    currentOffset: Float,
    velocity: Float,
    anchors: Map<DrawerStop, Float>,
): DrawerStop {
    if (abs(velocity) >= 900f) {
        val ordered = anchors.entries.sortedBy { it.value }
        val nearestIndex = ordered.indices.minBy { abs(ordered[it].value - currentOffset) }
        return if (velocity < 0f) {
            ordered[(nearestIndex - 1).coerceAtLeast(0)].key
        } else {
            ordered[(nearestIndex + 1).coerceAtMost(ordered.lastIndex)].key
        }
    }
    return anchors.minBy { abs(it.value - currentOffset) }.key
}

/**
 * Plomberie commune du tiroir trois crans (RIC-95) : offset animé, geste de drag, relais
 * nested-scroll du contenu Détails vers la feuille, et cible d'animation par cran. Chaque écran
 * garde la partie qui lui est propre : le calcul de ses hauteurs/anchors (mesurées sur son propre
 * contenu) et le contenu de chaque cran. Obtenu via [rememberThreeStopDrawerState].
 */
internal class ThreeStopDrawerState(
    private val scope: CoroutineScope,
    initialAnchors: Map<DrawerStop, Float>,
) {
    /** Positions (offset Y du haut de la feuille) de chaque cran ; réécrites à chaque recalcul. */
    var anchors: Map<DrawerStop, Float> = initialAnchors

    /**
     * Cran Détails autorisé ou non (RIC-95 recette 2, décision produit) : Planification le
     * désactive quand la trace n'a aucun point de bivouac, puisqu'il n'y a alors rien à montrer
     * à ce cran ; Journal le laisse toujours actif. Piloté par [rememberThreeStopDrawerState].
     * Désactivé : le bouton de la rangée est grisé et inerte, la poignée saute le cran, et toute
     * cible DETAIL restante (geste qui se pose dessus) est redirigée sur PROFILE.
     */
    var detailEnabled: Boolean by mutableStateOf(true)

    var stop: DrawerStop by mutableStateOf(DrawerStop.PROFILE)
        private set

    val offset = Animatable(initialAnchors.getValue(DrawerStop.PROFILE))

    /**
     * Scroll du contenu du cran Détails : possédé ici pour que [nestedScrollConnection] sache
     * quand ce contenu est en butée (et doit donc rendre la main au déplacement de la feuille).
     */
    val detailScrollState = ScrollState(0)

    /**
     * Portion (en px) de la barre de statut réellement recouverte par la feuille : 0 tant que son
     * bord haut est en dessous, la hauteur complète quand elle touche le bord de l'écran. Sert à
     * insérer l'espace protégeant le contenu de la barre de statut. Basé sur la position absolue
     * et non sur la progression vers le cran Détails (RIC-95 recette) : côté Planification, le
     * cran Détails s'arrête souvent en milieu d'écran, et l'ancienne logique y insérait l'espace
     * complet dès ce cran, volant sa hauteur au contenu qui partait en scroll interne.
     */
    fun statusBarOverlapPx(statusBarHeightPx: Float): Float =
        (statusBarHeightPx - offset.value).coerceIn(0f, statusBarHeightPx)

    fun animateTo(target: DrawerStop, initialVelocity: Float = 0f) {
        val effectiveTarget = if (target == DrawerStop.DETAIL && !detailEnabled) DrawerStop.PROFILE else target
        stop = effectiveTarget
        scope.launch {
            offset.animateTo(
                targetValue = anchors.getValue(effectiveTarget),
                animationSpec = spring(),
                initialVelocity = initialVelocity,
            )
        }
    }

    private fun dragBy(delta: Float) {
        scope.launch {
            offset.snapTo(
                (offset.value + delta).coerceIn(
                    anchors.getValue(DrawerStop.DETAIL),
                    anchors.getValue(DrawerStop.SUMMARY),
                ),
            )
        }
    }

    val dragModifier: Modifier = Modifier.draggable(
        state = DraggableState(::dragBy),
        orientation = Orientation.Vertical,
        onDragStopped = { velocity ->
            animateTo(settleDrawerStop(offset.value, velocity, anchors), velocity)
        },
    )

    val nestedScrollConnection = object : NestedScrollConnection {
        private var handedToSheet = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val atBoundary = (available.y > 0f && detailScrollState.value == 0) ||
                (available.y < 0f && detailScrollState.value == detailScrollState.maxValue)
            if (source != NestedScrollSource.UserInput || !atBoundary) return Offset.Zero
            handedToSheet = true
            dragBy(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!handedToSheet) return Velocity.Zero
            handedToSheet = false
            animateTo(settleDrawerStop(offset.value, available.y, anchors), available.y)
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (handedToSheet) {
                handedToSheet = false
                animateTo(settleDrawerStop(offset.value, available.y, anchors), available.y)
            }
            return Velocity.Zero
        }
    }
}

/**
 * State holder du tiroir, recréé (donc reparti au cran Profil, décision RIC-95 : pas de mémoire
 * du cran d'une trace à l'autre) à chaque changement de [trackKey].
 */
@Composable
internal fun rememberThreeStopDrawerState(
    anchors: Map<DrawerStop, Float>,
    trackKey: Any?,
    detailEnabled: Boolean = true,
): ThreeStopDrawerState {
    val scope = rememberCoroutineScope()
    val state = remember(trackKey) { ThreeStopDrawerState(scope, anchors) }
    state.anchors = anchors
    state.detailEnabled = detailEnabled
    // Cran Détails désactivé pendant qu'on y est (dernier bivouac supprimé depuis la table) :
    // le tiroir vient déjà de se rétracter à la hauteur Profil (les deux anchors coïncident),
    // le cran affiché suit pour ne pas laisser un surlignage sur un cran devenu inerte.
    LaunchedEffect(state, detailEnabled) {
        if (!detailEnabled && state.stop == DrawerStop.DETAIL) state.animateTo(DrawerStop.PROFILE)
    }
    // Les hauteurs mesurées arrivent après la première composition (0 ou un fallback d'abord),
    // donc les tout premiers anchors sous-estiment les vrais crans ; à chaque recalcul, l'offset
    // est réaligné sur la position recalculée du cran courant. Le retour au cran Profil au
    // changement de trace, lui, passe par le remember(trackKey) ci-dessus : pas besoin de forcer
    // Profil ici, ce qui évite de rappeler brutalement le tiroir à Profil quand c'est le contenu
    // qui fait bouger les anchors en cours d'usage (apparition du libellé « Total » côté
    // Planification, par exemple).
    LaunchedEffect(state, anchors) {
        state.offset.snapTo(anchors.getValue(state.stop))
    }
    return state
}

/** Poignée du tiroir : un tap avance d'un cran (Synthèse → Profil → Détails → Synthèse). */
@Composable
internal fun ThreeStopDrawerHandle(state: ThreeStopDrawerState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .size(width = 44.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant)
            .graphicsLayer { alpha = 0.45f }
            .semantics { contentDescription = "Poignée du tiroir" }
            .clickable {
                state.animateTo(
                    when (state.stop) {
                        DrawerStop.SUMMARY -> DrawerStop.PROFILE
                        DrawerStop.PROFILE -> if (state.detailEnabled) DrawerStop.DETAIL else DrawerStop.SUMMARY
                        DrawerStop.DETAIL -> DrawerStop.SUMMARY
                    },
                )
            },
    )
}

/**
 * Rangée Synthèse / Profil / Détails : le cran courant en couleur primaire, un tap y anime.
 * « Détails » est grisé et inerte quand [ThreeStopDrawerState.detailEnabled] est faux.
 */
@Composable
internal fun ThreeStopDrawerStopRow(state: ThreeStopDrawerState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        DrawerStop.entries.forEach { candidate ->
            val enabled = candidate != DrawerStop.DETAIL || state.detailEnabled
            TextButton(onClick = { state.animateTo(candidate) }, enabled = enabled) {
                Text(
                    when (candidate) {
                        DrawerStop.SUMMARY -> "Synthèse"
                        DrawerStop.PROFILE -> "Profil"
                        DrawerStop.DETAIL -> "Détails"
                    },
                    // La couleur est posée explicitement sur le Text, donc l'état désactivé du
                    // TextButton ne suffit pas à griser : alpha 38 % à la main (convention M3).
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        candidate == state.stop -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
