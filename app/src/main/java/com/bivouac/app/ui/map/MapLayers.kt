package com.bivouac.app.ui.map

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector
import com.bivouac.app.BuildConfig
import com.bivouac.app.R
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

// RIC-193 : copie de TileSourceFactory.OpenTopo (osmdroid 6.1.20, vérifié via javap sur le jar
// runtime du build : mêmes URLs, même plage de zoom 0-17, même taille de tuile 256, même extension
// .png), avec un seul champ changé : copyrightNotice, codé en ALLEMAND en dur dans osmdroid
// ("Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap (CC-BY-SA)").
// i18n-ok : texte d'attribution identique en français et en anglais par exigence des fournisseurs
// (même traitement que EsriWorldImagery ci-dessus pour la couche Satellite).
private val OpenTopoEnglish: ITileSource = XYTileSource(
    "OpenTopoMap",
    0,
    17,
    256,
    ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/",
    ),
    "Map data: © OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)",
)

// Esri's tile REST endpoint expects z/y/x, unlike the z/x/y convention osmdroid's built-in
// XYTileSource always builds: confirmed against both sources rather than assumed, since the two
// conventions are easy to mix up and silently fetch mismatched tiles.
private val EsriWorldImagery: ITileSource = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    // "Powered by Esri" prefix required by Esri's attribution guidelines (BIV-63), not just the
    // provider list itself.
    //
    // RIC-188 (lot 1 i18n) : ce littéral est la métadonnée osmdroid de la source de tuiles, pas le
    // texte affiché. Ce que l'utilisateur lit vient de R.string.map_layer_satellite_attribution_text
    // (même valeur dans les deux langues, Esri l'exige telle quelle), posé par EsriAttributionLink
    // dans HikeMapView : la couche Satellite est la seule dont WrappingCopyrightOverlay se retire.
    // Il reste ici parce que le constructeur d'OnlineTileSourceBase s'exécute à l'initialisation de
    // la classe, sans Context : le vider pour ne pas dupliquer la chaîne ferait dire à la source de
    // tuiles qu'elle n'a aucune attribution, exactement ce que BIV-63 interdit.
    "Powered by Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val url = "$baseUrl$zoom/$y/$x"
        // Optional local key (BIV-56, see app/build.gradle.kts) lifts Esri's anonymous-access
        // volume limits. Absent by default: falls back to the public endpoint, unauthenticated,
        // exactly as before.
        return if (BuildConfig.ESRI_API_KEY.isNotBlank()) "$url?token=${BuildConfig.ESRI_API_KEY}" else url
    }
}

// RIC-188 (lot 1 i18n) : le libellé est un id de ressource et non une chaîne, parce qu'un enum se
// construit à l'initialisation de la classe, bien avant qu'un Context existe. Le résoudre au point
// d'affichage (MapControls) est aussi ce qui garde le menu déroulant et la contentDescription du
// bouton sur UNE seule ressource par fond de carte.
enum class MapLayer(@StringRes val labelRes: Int, val tileSource: ITileSource, val icon: ImageVector) {
    STANDARD(R.string.map_layer_standard_label, TileSourceFactory.MAPNIK, Icons.Default.Map),
    HIKING(R.string.map_layer_hiking_label, OpenTopoEnglish, Icons.Default.Terrain),
    SATELLITE(R.string.map_layer_satellite_label, EsriWorldImagery, Icons.Default.Satellite),
}
