package com.bivouac.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Une photo associée à une sortie du Journal (RIC-43). Copie locale uniquement (voir
// LoggedTrackPhotoStore) : latitude/longitude/takenAtMillis viennent de l'EXIF au moment de
// l'ajout et sont dénormalisés ici pour ne jamais avoir à rouvrir le fichier ensuite, même
// raisonnement que les colonnes dénormalisées de LoggedTrackDayEntity.
//
// positionPointIndex indexe le HikeTrack concaténé (jours dans l'ordre, voir
// LoggedTrackRepository.open) — même convention que bivouacTrackPointIndices sur
// BankedTrackEntity/SavedTrackEntity pour "un marqueur accroché à un point de la trace". Null
// quand aucune corrélation (GPS ni horodatage) n'a été possible : la photo reste alors accessible
// seulement depuis la galerie plate, jamais un blocage.
//
// positionApproximate distingue une position déduite par horodatage d'une position certaine : GPS
// d'origine, ou repositionnement manuel par l'utilisateur, qui vaut confirmation explicite au même
// titre qu'un GPS exact. Attention, il ne suffit plus à lui seul pour décider d'afficher la
// pastille côté UI, voir positionUncertain plus bas.
@Entity(
    tableName = "logged_track_photo",
    foreignKeys = [
        ForeignKey(
            entity = LoggedTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId"])],
)
data class LoggedTrackPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    // Relatif à filesDir, jamais absolu — voir LoggedTrackPhotoStore pour la même raison que
    // LoggedTrackGpxStore.rawGpxFilePath.
    val filePath: String,
    val addedAtMillis: Long,
    val takenAtMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionPointIndex: Int? = null,
    val positionApproximate: Boolean = false,
    // SHA-256 des octets de la photo, même principe que LoggedTrackEntity.contentHash — sert à
    // refuser un doublon : la même photo cochée deux fois dans le même lot, ou ajoutée à nouveau
    // dans une session ultérieure, ou présente en deux exemplaires dans la pellicule sous deux
    // noms. Par le contenu et non par l'Uri : deux entrées MediaStore distinctes peuvent porter
    // exactement la même photo. Toujours renseigné : la table naît avec cette colonne (migration
    // 14->15), il n'existe donc aucune ligne d'avant elle.
    val contentHash: String = "",
    // Métadonnées d'origine relevées dans MediaStore au moment de l'ajout, jamais relues ensuite.
    // Elles ne servent à rien aujourd'hui : elles sont là pour la re-acquisition depuis la galerie
    // (RIC-151), qui aura besoin de retrouver la photo d'origine quand la copie locale a disparu —
    // d'où aussi le refus de supprimer automatiquement une ligne dont le fichier manque.
    //
    // Les trois sont nullables parce qu'aucune n'est garantie : le fournisseur ne remplit pas
    // forcément ces colonnes pour toute image indexée, et RELATIVE_PATH n'existe qu'à partir de
    // l'API 29. Une valeur absente n'est pas une anomalie.
    //
    // sourceDateTakenMillis est bien distinct de takenAtMillis ci-dessus : celui-ci vient de
    // l'EXIF de la photo (avec la reconstitution de fuseau de PhotoExifReader), celui-là est le
    // DATE_TAKEN de MediaStore, en UTC vrai — c'est celui-ci qui permettra de requêter MediaStore
    // pour retrouver le fichier d'origine.
    val sourceDisplayName: String? = null,
    val sourceRelativePath: String? = null,
    val sourceDateTakenMillis: Long? = null,
    // Le fuseau qui a servi à reconstituer takenAtMillis était-il porté par le fichier lui-même
    // (horodatage GPS en UTC, ou tag d'offset EXIF), ou seulement supposé être celui du téléphone
    // au moment de l'ajout ? Voir PhotoExifData.takenAtZoneCertain, dont c'est la persistance.
    //
    // Nullable, et null pour les lignes d'avant la migration 15->16 : l'information n'était pas
    // relevée à l'époque, et rouvrir les fichiers pour la rattraper coûterait une lecture EXIF par
    // photo sans rien garantir (le fichier a pu disparaître). « Inconnu » se comporte alors comme
    // « pas certain », c'est-à-dire exactement comme avant la migration côté affichage.
    val takenAtZoneCertain: Boolean? = null,
)

/**
 * RIC-43 : l'ordre dans lequel les photos d'une sortie se présentent partout — bandeau, galerie,
 * visionneuse, carrousel de la bulle.
 *
 * C'est, mot pour mot, le `ORDER BY takenAtMillis, addedAtMillis` de
 * [LoggedTrackDao.getPhotos] : chronologique par date de prise de vue, et à date égale (ou absente)
 * par ordre d'entrée dans le Journal. Les photos sans EXIF de date passent donc devant, exactement
 * comme SQLite range les NULL en tête d'un tri croissant — d'où le `nullsFirst`.
 *
 * Il existe en Kotlin parce que l'écran ne montre pas que la base : pendant une édition, les ajouts
 * en transit se superposent aux photos persistées (voir JournalViewModel.currentPhotos), et cette
 * liste combinée doit se ranger comme la requête l'aurait fait. Sans quoi une photo prise le matin
 * s'affiche en fin de bandeau tant qu'elle n'est pas enregistrée, puis saute à sa place
 * chronologique à la sauvegarde : deux ordres pour la même sortie, à quelques secondes d'écart.
 *
 * Signalé en recette. Le tri est stable côté Kotlin (`sortedWith`), donc deux photos strictement
 * ex æquo gardent l'ordre de la liste d'entrée : les persistées d'abord, puis les ajouts dans
 * l'ordre où ils ont été choisis.
 */
val PhotoDisplayOrder: Comparator<LoggedTrackPhotoEntity> =
    compareBy<LoggedTrackPhotoEntity, Long?>(nullsFirst()) { it.takenAtMillis }
        .thenBy { it.addedAtMillis }

/**
 * RIC-43 : la photo mérite-t-elle la pastille « positionnement approximatif » ?
 *
 * Un seul cas la mérite : une position déduite de la seule corrélation temporelle, alors que le
 * fuseau de l'horodatage n'était pas connu du fichier. C'est le seul où la position peut être
 * franchement fausse sans que rien ne le laisse voir, puisque le fuseau du téléphone au moment de
 * l'ajout tient alors lieu d'hypothèse (voir PhotoExifReader).
 *
 * Les trois autres chemins sont fiables et ne portent donc aucune pastille : le GPS de la photo
 * (désormais borné en distance, voir PhotoPositionCorrelator), la corrélation temporelle avec un
 * fuseau certain (à la dérive d'horloge près, qui se compte en minutes), et le placement ou le
 * repositionnement manuel, qui vaut confirmation explicite.
 *
 * Extension et non colonne : c'est une lecture des deux colonnes ci-dessus, pas un troisième état
 * à tenir synchronisé avec elles.
 */
val LoggedTrackPhotoEntity.positionUncertain: Boolean
    get() = positionApproximate && takenAtZoneCertain != true
