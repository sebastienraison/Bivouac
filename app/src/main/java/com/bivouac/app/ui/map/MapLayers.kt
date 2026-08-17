package com.bivouac.app.ui.map

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector
import com.bivouac.app.BuildConfig
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

// Esri's tile REST endpoint expects z/y/x, unlike the z/x/y convention osmdroid's built-in
// XYTileSource always builds — confirmed against both sources rather than assumed, since the two
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
    "Powered by Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val url = "$baseUrl$zoom/$y/$x"
        // Optional local key (BIV-56, see app/build.gradle.kts) lifts Esri's anonymous-access
        // volume limits. Absent by default — falls back to the public endpoint, unauthenticated,
        // exactly as before.
        return if (BuildConfig.ESRI_API_KEY.isNotBlank()) "$url?token=${BuildConfig.ESRI_API_KEY}" else url
    }
}

enum class MapLayer(val label: String, val tileSource: ITileSource, val icon: ImageVector) {
    STANDARD("Standard", TileSourceFactory.MAPNIK, Icons.Default.Map),
    HIKING("Randonnée", TileSourceFactory.OpenTopo, Icons.Default.Terrain),
    SATELLITE("Satellite", EsriWorldImagery, Icons.Default.Satellite),
}
