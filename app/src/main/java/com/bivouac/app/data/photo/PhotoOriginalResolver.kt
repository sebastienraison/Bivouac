package com.bivouac.app.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.bivouac.app.data.db.LoggedTrackPhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RIC-157 : ce que rend une tentative de retrouver la photo d'origine d'une ligne du Journal.
 *
 * Trois issues et pas deux : « pas trouvée » et « impossible de chercher » n'appellent pas le même
 * discours côté écran. La première est un constat définitif (la photo n'est plus dans la galerie),
 * la seconde est une situation réversible (la permission galerie n'est pas accordée).
 */
sealed interface PhotoOriginalResolution {

    /**
     * L'original est retrouvé et CONFIRMÉ par son empreinte : ce n'est jamais une supposition.
     *
     * [uriRefreshed] dit si l'URI diffère de celui qui était en base, donc s'il vaut la peine
     * d'être réécrit (voir LoggedTrackRepository.resolvePhotoOriginal, qui s'en charge).
     */
    data class Found(val uri: Uri, val uriRefreshed: Boolean) : PhotoOriginalResolution

    /** Cherchée, pas retrouvée : l'original n'est plus dans la galerie, ou plus reconnaissable. */
    data object NotFound : PhotoOriginalResolution

    /**
     * Pas cherchée : la permission galerie n'est pas accordée.
     *
     * Aucune demande n'est déclenchée d'ici, et c'est délibéré : la permission photos est opt-in
     * (voir PhotoLibraryPermission et le flux d'ajout du Journal), une résolution qui la
     * réclamerait d'elle-même la transformerait en permission subie.
     */
    data object Unavailable : PhotoOriginalResolution
}

/**
 * RIC-157 : une requête MediaStore, réduite à ce qui la caractérise.
 *
 * Type à part et non deux paramètres, pour que la construction de la requête soit une fonction
 * pure ([PhotoOriginalResolver.lookupsFor]) exerçable sans appareil : c'est là que se joue la
 * garantie « jamais de scan complet de la galerie ».
 */
data class MediaStoreLookup(val selection: String, val args: List<String>)

/**
 * RIC-157 : retrouver, dans la galerie, l'original d'une photo du Journal.
 *
 * Sert la copie réduite : le Journal ne garde qu'une version allégée, l'original reste chez
 * l'utilisateur, et ce composant est ce qui permettra d'y revenir (montée en qualité dans la
 * visionneuse, lot B). Il sert tout autant les copies intégrales dont le fichier local a disparu
 * (RIC-151), ce qui est la raison pour laquelle une ligne dont le fichier manque n'est jamais
 * supprimée automatiquement.
 *
 * **Deux temps, du moins cher au plus cher.**
 * 1. `lastResolvedUri` : une seule ouverture et un seul hachage. Couvre l'écrasante majorité des
 *    cas, un URI MediaStore restant valide tant que la photo n'a pas été déplacée ni réindexée.
 * 2. Recherche profonde : une requête MediaStore CIBLÉE par les métadonnées déjà en base, puis le
 *    hachage des seuls candidats qu'elle rend.
 *
 * **L'empreinte est le seul juge, jamais l'URI ni les métadonnées.** Un URI peut, après
 * réindexation, désigner une autre photo ; deux photos peuvent partager un nom et une date. Rien
 * n'est rendu qui n'ait été confirmé par [LoggedTrackPhotoEntity.contentHash], calculé à l'import
 * sur les octets d'ORIGINE, avant toute réduction : c'est tout l'intérêt de cet invariant.
 *
 * **Jamais de scan complet de la galerie.** Sans métadonnée exploitable, [lookupsFor] rend une
 * liste vide et la résolution s'arrête là. Parcourir et hacher une pellicule entière coûterait des
 * minutes et des gigaoctets de lecture pour une seule photo : ce n'est pas une résolution plus
 * lente, c'est une opération d'une autre nature, qui n'a pas sa place derrière un geste
 * d'utilisateur.
 */
object PhotoOriginalResolver {

    private const val TAG = "PhotoOriginalResolver"

    /**
     * Plafond de candidats effectivement hachés, toutes requêtes confondues.
     *
     * Les deux critères sont très sélectifs (un nom de fichier, une date de prise de vue à la
     * milliseconde) : dépasser ce plafond signifie qu'on est tombé sur un cas dégénéré (des
     * milliers d'images à la même date parce que le fournisseur remplit DATE_TAKEN avec zéro, par
     * exemple). Le plafond transforme ce cas en « pas trouvée » plutôt qu'en attente sans fin.
     */
    private const val MAX_CANDIDATES_HASHED = 50

    /**
     * Les requêtes de la recherche profonde, dans l'ordre où elles sont tentées, à partir des
     * métadonnées d'origine relevées à l'import.
     *
     * Deux requêtes successives et non une seule combinée : une conjonction
     * `nom ET date` raterait une photo renommée aussi bien qu'une photo dont le fournisseur a
     * réécrit la date à la réindexation, alors que chaque critère pris seul en retrouve une bonne
     * part. Le nom d'abord : c'est le plus stable des deux, et c'est celui qui rend le moins de
     * candidats sur une pellicule ordinaire.
     *
     * `sourceRelativePath` n'est volontairement PAS utilisé comme filtre : une photo rangée
     * ailleurs depuis l'import est exactement le cas que la recherche profonde doit rattraper,
     * l'ajouter à la sélection l'exclurait.
     *
     * La taille du fichier, que la spec envisageait comme troisième critère, n'est pas disponible :
     * elle n'a jamais été relevée à l'import et ne figure sur aucune colonne de
     * [LoggedTrackPhotoEntity]. L'ajouter demanderait une migration et ne servirait qu'à réduire un
     * nombre de candidats déjà petit : à reprendre s'il s'avère insuffisant, pas avant.
     *
     * Liste vide quand aucune métadonnée n'est exploitable : c'est la garantie « pas de scan
     * complet », et elle est écrite ici plutôt que testée à l'appel pour être vérifiable sans
     * appareil.
     */
    fun lookupsFor(displayName: String?, dateTakenMillis: Long?): List<MediaStoreLookup> = buildList {
        displayName?.takeIf { it.isNotBlank() }?.let {
            add(MediaStoreLookup("${MediaStore.MediaColumns.DISPLAY_NAME} = ?", listOf(it)))
        }
        // 0 exclu : plusieurs fournisseurs remplissent DATE_TAKEN avec zéro plutôt que de le
        // laisser vide, et cette « date » désignerait alors toute la pellicule d'un coup.
        dateTakenMillis?.takeIf { it > 0L }?.let {
            add(MediaStoreLookup("${MediaStore.Images.Media.DATE_TAKEN} = ?", listOf(it.toString())))
        }
    }

    /**
     * Résout l'original de [photo], et rien d'autre : cette fonction ne touche pas la base. C'est
     * LoggedTrackRepository.resolvePhotoOriginal qui persiste l'URI rafraîchi, pour que le
     * composant reste utilisable là où il n'y a pas de repository sous la main.
     *
     * Tout se passe sur [Dispatchers.IO] : chaque étape lit des fichiers de plusieurs Mo, et rien
     * de tout ça n'a le droit de s'exécuter sur le thread principal.
     */
    suspend fun resolve(context: Context, photo: LoggedTrackPhotoEntity): PhotoOriginalResolution =
        withContext(Dispatchers.IO) {
            if (!PhotoLibraryPermission.isGranted(context)) return@withContext PhotoOriginalResolution.Unavailable
            // Une ligne sans empreinte ne peut rien confirmer, et confirmer est tout le contrat de
            // ce composant. N'existe pas en pratique (la table naît avec contentHash, migration
            // 14 -> 15), mais s'en remettre à ça pour rendre un original faux serait absurde.
            val expected = photo.contentHash
            if (expected.isBlank()) return@withContext PhotoOriginalResolution.NotFound

            val resolver = context.contentResolver
            val known = photo.lastResolvedUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (known != null && hashOf(resolver, known) == expected) {
                return@withContext PhotoOriginalResolution.Found(known, uriRefreshed = false)
            }

            var hashed = 0
            for (lookup in lookupsFor(photo.sourceDisplayName, photo.sourceDateTakenMillis)) {
                for (candidate in query(resolver, lookup)) {
                    // Déjà éliminé à l'étape 1, inutile de relire ses octets.
                    if (candidate == known) continue
                    if (hashed >= MAX_CANDIDATES_HASHED) {
                        Log.w(TAG, "Trop de candidats pour la photo ${photo.id}, recherche abandonnée")
                        return@withContext PhotoOriginalResolution.NotFound
                    }
                    hashed++
                    if (hashOf(resolver, candidate) == expected) {
                        return@withContext PhotoOriginalResolution.Found(candidate, uriRefreshed = true)
                    }
                }
            }
            PhotoOriginalResolution.NotFound
        }

    // Null (et non une exception) sur tout échec : un URI mort, une permission retirée entre la
    // vérification et la lecture, un fournisseur qui ne répond plus. Aucun de ces cas n'est une
    // anomalie ici, ce sont les cas que la recherche profonde existe pour rattraper.
    private fun hashOf(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { PhotoContentHash.of(it) }
    }.getOrNull()

    private fun query(resolver: ContentResolver, lookup: MediaStoreLookup): List<Uri> = runCatching {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val uris = mutableListOf<Uri>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            lookup.selection,
            lookup.args.toTypedArray(),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                uris += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn),
                )
            }
        }
        uris
    }.onFailure { Log.w(TAG, "Recherche MediaStore impossible", it) }.getOrDefault(emptyList())
}
