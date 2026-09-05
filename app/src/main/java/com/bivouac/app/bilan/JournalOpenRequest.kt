package com.bivouac.app.bilan

/**
 * RIC-19 §6 : ce que le Bilan doit transmettre pour ouvrir la sortie réelle derrière un record.
 * Même patron que [com.bivouac.app.journal.DuplicatePlanRequest] (défini côté Journal, qui
 * l'émet pour la Planification) mais dans l'autre sens : c'est ici, côté producteur (Bilan), que
 * le type vit : MainActivity le fait transiter vers JournalScreen (seul point commun aux deux
 * écrans, chacun son ViewModel), qui l'expose à JournalViewModel.openTrackById.
 *
 * [dayIndex] reste nul pour les records mono-jour (RIC-19 §6) : la trace s'ouvre alors sans
 * curseur particulier, exactement comme un clic normal dans la liste du Journal. Non nul
 * seulement pour les deux records multi-jours (plus gros trek, bivouac le plus haut), qui
 * réutilisent le curseur déjà affiché sur l'ElevationProfile (BIV-52) pour se positionner sur le
 * bon jour plutôt que de construire une nouvelle navigation day-level.
 */
data class JournalOpenRequest(val trackId: String, val dayIndex: Int? = null)
